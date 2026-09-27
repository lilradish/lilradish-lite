package org.lilradish.lite.app.run

import javax.sql.DataSource
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.SmartLifecycle
import spock.lang.Specification

/**
 * Where a start settles what was out and where runs go on again, as the context orders them: settling as every
 * singleton is made, before the web server takes a request; going on only once it serves, and never before settling.
 */
class EngineRestartSpec extends Specification {

    /**
     * Phases start in ascending order, so a start failing on its port never hands a model call to a closing context;
     * a runner would settle only after the web server serves, when a request may already meet what is out.
     */
    def "what was out is settled as every singleton is made, and runs go on only once the web server serves"() {
        given:
        def restart = new EngineRestart(null, null, null, null, null, null, null)

        expect:
        restart.phase > WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE
        restart.phase == SmartLifecycle.DEFAULT_PHASE
        restart.autoStartup

        and: "settled as a singleton made, never by a runner"
        restart instanceof SmartInitializingSingleton
        !(restart instanceof ApplicationRunner)
        !(restart instanceof CommandLineRunner)
    }

    def "runs are not let go on before what was out is settled, and nothing is handed over"() {
        given:
        def executor = EngineExecutors.of(Stub(DataSource))
        def restart = new EngineRestart(null, null, null, null, null, null, executor)

        when:
        restart.start()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Runs were to go on by themselves before what was out when this system stopped was settled"

        and:
        !restart.running
        executor.@pool.threadPoolExecutor.taskCount == 0

        cleanup:
        executor.destroy()
    }
}
