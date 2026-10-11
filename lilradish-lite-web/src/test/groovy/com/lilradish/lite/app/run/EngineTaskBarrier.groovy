package com.lilradish.lite.app.run

import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/** Counts test engine tasks from submission until their bodies finish, including tasks rejected before running. */
final class EngineTaskBarrier {

    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(20)
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(20)

    private final Object monitor = new Object()
    private long submitted
    private long pending

    static EngineTaskBarrier attach(EngineExecutor executor) {
        def barrier = new EngineTaskBarrier()
        barrier.watch(executor.@pool)
        barrier.watch(executor.@codePool)
        barrier
    }

    private void watch(ThreadPoolTaskExecutor pool) {
        assert pool.@taskDecorator == null: 'the test barrier must not replace an engine task decorator'
        ThreadPoolExecutor raw = pool.threadPoolExecutor
        RejectedExecutionHandler original = raw.rejectedExecutionHandler
        pool.setTaskDecorator({ Runnable command -> register(command) } as TaskDecorator)
        raw.rejectedExecutionHandler = { Runnable rejected, ThreadPoolExecutor executor ->
            try {
                original.rejectedExecution(rejected, executor)
            } finally {
                if (rejected instanceof Ticket) {
                    rejected.finish()
                }
            }
        } as RejectedExecutionHandler
    }

    private Ticket register(Runnable command) {
        synchronized (monitor) {
            submitted++
            pending++
            monitor.notifyAll()
        }
        new Ticket(this, command)
    }

    private void finished() {
        synchronized (monitor) {
            pending--
            assert pending >= 0
            monitor.notifyAll()
        }
    }

    long submittedCount() {
        synchronized (monitor) {
            submitted
        }
    }

    /** A bounded wait for every submitted task, including tasks handed from one engine pool to the other. */
    void awaitDrained() {
        long deadline = System.nanoTime() + DEADLINE_NANOS
        synchronized (monitor) {
            while (true) {
                long remaining = deadline - System.nanoTime()
                assert remaining > 0: "the engine's tasks did not finish (${pending} pending)"
                if (pending == 0) {
                    long seen = submitted
                    TimeUnit.NANOSECONDS.timedWait(monitor, Math.min(remaining, QUIET_NANOS))
                    if (pending == 0 && submitted == seen) {
                        return
                    }
                } else {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
                }
            }
        }
    }

    private static final class Ticket implements Runnable {
        private final EngineTaskBarrier owner
        private final Runnable command
        private final AtomicBoolean finished = new AtomicBoolean()

        Ticket(EngineTaskBarrier owner, Runnable command) {
            this.owner = owner
            this.command = command
        }

        @Override
        void run() {
            try {
                command.run()
            } finally {
                finish()
            }
        }

        void finish() {
            if (finished.compareAndSet(false, true)) {
                owner.finished()
            }
        }
    }
}
