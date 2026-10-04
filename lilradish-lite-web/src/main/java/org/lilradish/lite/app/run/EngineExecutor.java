package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.codestep.CodeStepDeployment;
import org.lilradish.lite.app.inference.LongestSend;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunStepFailureId;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * A task that fails, or is handed over once shut down, is logged and never thrown into the commit that landed it.
 * Not itself an executor bean: one would stand in for the application's own, which the framework then leaves out.
 * Once the application is closing it says it is stopping, and nothing new is to be sent to a model or run as code.
 * Code runs on threads of its own, so code that never returns holds none of the threads a model is called on.
 */
@Component
@DependsOn(OneProcess.BEAN)
final class EngineExecutor implements DisposableBean, ApplicationListener<ContextClosedEvent>, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(EngineExecutor.class);

    private static final int THREADS = 2;

    private static final int CODE_THREADS = 2;

    /* What a task waits on once its call or its code is back: writing where it ended, and going on from there. */
    private static final Duration END_ALONE = Duration.ofSeconds(30);

    private final ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();

    private final ThreadPoolTaskExecutor codePool = new ThreadPoolTaskExecutor();

    private final AtomicInteger threadsMade = new AtomicInteger();

    private final AtomicInteger codeThreadsMade = new AtomicInteger();

    private final ConcurrentHashMap<Object, Object> queued = new ConcurrentHashMap<>();

    private final Duration drain;

    private volatile boolean stopping;

    private volatile @Nullable ApplicationContext context;

    /*
     * Taken so the pools are destroyed before the store's connections: a draining task still writes. Nothing here
     * may take the model calls or their shutdown signal, which would order this pool's stop before that signal.
     */
    EngineExecutor(DataSource store, ObjectProvider<LongestSend> longestSend, CodeStepDeployment codeSteps) {
        requireNonNull(store, "EngineExecutor store must not be null");
        drain = drain(longestSend, codeSteps);
        started(pool, THREADS, task -> new EngineThread(task, threadsMade.incrementAndGet()));
        started(codePool, CODE_THREADS, task -> new CodeThread(task, codeThreadsMade.incrementAndGet()));
    }

    /**
     * How long a shutdown in order waits for what is under way: the longest one send or one code run may take,
     * whichever is longer, and then as long as writing where it ended takes alone.
     */
    static Duration drain(ObjectProvider<LongestSend> longestSend, CodeStepDeployment codeSteps) {
        LongestSend send = longestSend.getIfAvailable();
        Duration longest = send == null ? Duration.ZERO : send.duration();
        if (codeSteps.longestRun().compareTo(longest) > 0) {
            longest = codeSteps.longestRun();
        }
        return longest.plus(END_ALONE);
    }

    private static void started(ThreadPoolTaskExecutor made, int threads, ThreadFactory factory) {
        made.setCorePoolSize(threads);
        made.setMaxPoolSize(threads);
        made.setThreadFactory(factory);
        made.setWaitForTasksToCompleteOnShutdown(true);
        made.initialize();
    }

    /** Whether the application is closing: a try already out is waited for, and nothing new goes out. */
    boolean stopping() {
        return stopping;
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    /*
     * Published before any lifecycle stops and before this pool drains, so nothing is planned out meanwhile. A
     * context beneath this one publishes its own close here as well, and is not this application closing.
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent closed) {
        if (closed.getApplicationContext() == context) {
            stopping = true;
        }
    }

    void execute(Runnable task) {
        handOver(pool, task, null);
    }

    void execute(EntryId entry, Runnable task) {
        requireNonNull(entry, "EngineExecutor entry must not be null");
        handOver(pool, task, new EntryIntent(entry));
    }

    void execute(PendingSend pending, Runnable task) {
        requireNonNull(pending, "EngineExecutor pending send must not be null");
        Object intent = pending.presser() == null
                ? new RunIntent(pending.group(), pending.root())
                : new PressedSendIntent(pending.purpose(), pending.aTry(), pending.attemptOn(), pending.failedOn());
        handOver(pool, task, intent);
    }

    /** As {@link #execute} does, on the threads code is run on and nothing else is. */
    void executeCode(Runnable task) {
        handOver(codePool, task, null);
    }

    void executeCode(PendingCode pending, Runnable task) {
        requireNonNull(pending, "EngineExecutor pending code must not be null");
        handOver(codePool, task, new CodeIntent(pending.group(), pending.root(), pending.step()));
    }

    private void handOver(ThreadPoolTaskExecutor to, Runnable task, @Nullable Object intent) {
        requireNonNull(task, "EngineExecutor task must not be null");
        Object ticket = intent == null ? task : new Object();
        if (intent != null && queued.putIfAbsent(intent, ticket) != null) {
            return;
        }
        try {
            to.execute(() -> {
                if (intent != null) {
                    queued.remove(intent, ticket);
                }
                try {
                    task.run();
                } catch (Exception failed) {
                    logger.error("A run handed over could not go on by itself", failed);
                } catch (Error fatal) {
                    // What it says may be what code said, so only its kind is logged, and it ends this thread still.
                    logger.error(
                            "A run handed over stopped its thread with {}",
                            fatal.getClass().getName());
                    throw fatal;
                } finally {
                    if (intent != null) {
                        queued.remove(intent, ticket);
                    }
                }
            });
        } catch (TaskRejectedException refused) {
            if (intent != null) {
                queued.remove(intent, ticket);
            }
            logger.warn("A run was handed over once runs had stopped going on by themselves", refused);
        }
    }

    private record EntryIntent(EntryId entry) {}

    private record RunIntent(GroupId group, RunId root) {}

    private record PressedSendIntent(
            ModelCallPurpose purpose,
            ProductionId production,
            @Nullable RunStepSendAttemptId attempt,
            @Nullable RunStepFailureId failure) {}

    private record CodeIntent(GroupId group, RunId root, WorkflowStepId step) {}

    @Override
    public void destroy() {
        stopping = true;
        codePool.initiateShutdown();
        pool.initiateShutdown();
        // One deadline for both: they drain side by side, so waiting on one never adds to the other's bound.
        long deadline = System.nanoTime() + drain.toNanos();
        boolean codeDrained = drained(codePool, deadline);
        boolean callsDrained = drained(pool, deadline);
        if (!codeDrained || !callsDrained) {
            logger.warn(
                    "Runs under way were not all written down within {} of stopping; what is left is settled when"
                            + " this system starts again",
                    drain);
        }
    }

    private static boolean drained(ThreadPoolTaskExecutor draining, long deadline) {
        try {
            return draining.getThreadPoolExecutor()
                    .awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
