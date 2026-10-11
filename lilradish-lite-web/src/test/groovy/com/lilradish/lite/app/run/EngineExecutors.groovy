package com.lilradish.lite.app.run

import com.lilradish.lite.app.codestep.CodeStepDeployment
import com.lilradish.lite.app.inference.LongestSend
import java.time.Duration
import javax.sql.DataSource
import org.springframework.beans.factory.support.StaticListableBeanFactory

/**
 * The run engine's own pool as a spec makes it outside any context: no model endpoint, and code let run a minute.
 * Here beside the pool because only its own package may name it.
 */
public final class EngineExecutors {

    static final CodeStepDeployment A_MINUTE = new CodeStepDeployment(Duration.ofMinutes(1))

    private EngineExecutors() {}

    /** Only this worker JVM's real engine thread types are inspected; no thread is interrupted. */
    static synchronized void requireTerminated() {
        if (engineThreadsRemain()) {
            throw new IllegalStateException("The previous test scope's engine threads have not terminated")
        }
    }

    private static boolean engineThreadsRemain() {
        Thread.getAllStackTraces().keySet().any { thread ->
            thread.alive && (thread instanceof EngineThread || thread instanceof CodeThread)
        }
    }

    static synchronized EngineExecutor of(DataSource store) {
        // Capacity belongs to real physical datasource acquisitions, not to declaration-only fixtures.
        new EngineExecutor(store, new StaticListableBeanFactory().getBeanProvider(LongestSend), A_MINUTE)
    }
}
