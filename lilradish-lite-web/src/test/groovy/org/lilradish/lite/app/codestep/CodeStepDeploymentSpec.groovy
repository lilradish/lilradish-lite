package org.lilradish.lite.app.codestep

import java.time.Duration
import spock.lang.Specification

class CodeStepDeploymentSpec extends Specification {

    def "keeps the longest a code step runs for, however short"() {
        expect:
        new CodeStepDeployment(longestRun).longestRun() == longestRun

        where:
        longestRun << [Duration.ofNanos(1), Duration.ofMinutes(1), Duration.ofHours(24)]
    }

    /** A wait the executor counts in seconds or millis must hold it, so a day is as long as it may be. */
    def "refuses a longest run that is missing, could not be waited out, or runs past a day, naming the key"() {
        when:
        new CodeStepDeployment(longestRun)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        longestRun                         || expected                 | message
        null                               || NullPointerException     | "lilradish.code-steps.longest-run must be set"
        Duration.ZERO                      || IllegalArgumentException | "lilradish.code-steps.longest-run must be positive: PT0S"
        Duration.ofMillis(-1)              || IllegalArgumentException | "lilradish.code-steps.longest-run must be positive: PT-0.001S"
        Duration.ofHours(24).plusNanos(1)  || IllegalArgumentException | "lilradish.code-steps.longest-run must be at most PT24H: PT24H0.000000001S"
        Duration.ofSeconds(Long.MAX_VALUE) || IllegalArgumentException | "lilradish.code-steps.longest-run must be at most PT24H: PT2562047788015215H30M7S"
    }
}
