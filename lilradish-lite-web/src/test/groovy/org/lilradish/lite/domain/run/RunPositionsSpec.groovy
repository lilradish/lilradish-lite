package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.RunState.DONE
import static org.lilradish.lite.domain.run.RunState.FAILED
import static org.lilradish.lite.domain.run.RunState.RUNNING
import static org.lilradish.lite.domain.run.RunState.STOPPED
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.position
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.run
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted

import spock.lang.Specification

class RunPositionsSpec extends Specification {

    def "a run is done once every step is, stopped or not, and otherwise stopped under a stop, failed where a step failed, and running"() {
        expect:
        RunPositions.state(runOf(steps.size(), stopped), steps.collect { position(it) }) == state

        where:
        steps                                         | stopped || state
        ["done", "done"]                              | false   || DONE
        ["done", "done"]                              | true    || DONE
        ["done", "not started"]                       | false   || RUNNING
        ["done", "not started"]                       | true    || STOPPED
        ["done", "failed with its tries spent"]       | false   || FAILED
        ["failed as written down", "not started"]     | false   || FAILED
        ["done", "failed with its tries spent"]       | true    || STOPPED
        ["owed", "not started"]                       | false   || RUNNING
        ["awaiting a person's review", "not started"] | false   || RUNNING
        ["held back on a stop", "not started"]        | false   || RUNNING
        ["running a call", "not started"]             | false   || RUNNING
    }

    def "a running run is at its first step not done, and one not running is at none"() {
        given:
        def running = runOf(steps.size(), stopped)

        expect:
        RunPositions.at(running, steps.collect { position(it) }) ==
                (at == null ? Optional.empty() : Optional.of(running.steps()[at - 1]))

        where:
        steps                                  | stopped || at
        ["done", "owed", "not started"]        | false   || 2
        ["not started", "not started"]         | false   || 1
        ["done", "owed"]                       | true    || null
        ["done", "failed with its tries spent"] | false  || null
        ["done", "done"]                       | false   || null
    }

    def "how many steps are done counts those done and no other"() {
        expect:
        RunPositions.done(steps.collect { position(it) }) == done

        where:
        steps                                                   || done
        []                                                      || 0
        ["failed with its tries spent", "owed"]                 || 0
        ["done", "not started", "done"]                         || 2
        ["done", "done", "done"]                                || 3
    }

    def "where a run is cannot be worked out from other than one position per step"() {
        when:
        RunPositions."$asked"(runOf(2, false), [position("done")])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "RunPositions was handed other than one position per step"

        where:
        asked << ["state", "at"]
    }

    private static RunSnapshot runOf(int steps, boolean stopped) {
        run((1..steps).collect { unstarted(question(it, person(), 1)) }, stopped)
    }
}
