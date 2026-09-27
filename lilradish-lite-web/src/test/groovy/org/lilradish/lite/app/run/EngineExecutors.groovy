package org.lilradish.lite.app.run

import java.time.Duration
import javax.sql.DataSource
import org.lilradish.lite.app.codestep.CodeStepDeployment
import org.lilradish.lite.app.inference.LongestSend
import org.springframework.beans.factory.support.StaticListableBeanFactory

/**
 * The run engine's own pool as a spec makes it outside any context: no model endpoint, and code let run a minute.
 * Here beside the pool because only its own package may name it.
 */
final class EngineExecutors {

    static final CodeStepDeployment A_MINUTE = new CodeStepDeployment(Duration.ofMinutes(1))

    private EngineExecutors() {}

    static EngineExecutor of(DataSource store) {
        new EngineExecutor(store, new StaticListableBeanFactory().getBeanProvider(LongestSend), A_MINUTE)
    }
}
