package org.lilradish.lite.app.inference

import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout

/** Timed: a doubling that ran on past the longest wait would take billions of steps, not fail. */
@Timeout(1)
class ResendPolicySpec extends Specification {

    static final Duration ONE_SECOND = Duration.ofSeconds(1)

    static final Duration LONGEST = Duration.ofSeconds(30)

    def "takes a policy of no resends whose longest wait is its first, and waits no longer than that"() {
        when:
        def policy = new ResendPolicy(0, ONE_SECOND, ONE_SECOND)

        then:
        policy.waitBefore(1, Duration.ofSeconds(5)) == ONE_SECOND
    }

    def "refuses a setting that is missing or cannot be waited out, naming the key"() {
        when:
        new ResendPolicy(times, firstWait, longestWait)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        times | firstWait               | longestWait             || expected                 | message
        null  | ONE_SECOND              | LONGEST                 || NullPointerException     | "lilradish.model-calls.resend.times must be set"
        -1    | ONE_SECOND              | LONGEST                 || IllegalArgumentException | "lilradish.model-calls.resend.times must not be negative: -1"
        3     | null                    | LONGEST                 || NullPointerException     | "lilradish.model-calls.resend.first-wait must be set"
        3     | Duration.ZERO           | LONGEST                 || IllegalArgumentException | "lilradish.model-calls.resend.first-wait must be positive: PT0S"
        3     | Duration.ofMillis(-1)   | LONGEST                 || IllegalArgumentException | "lilradish.model-calls.resend.first-wait must be positive: PT-0.001S"
        3     | ONE_SECOND              | null                    || NullPointerException     | "lilradish.model-calls.resend.longest-wait must be set"
        3     | ONE_SECOND              | Duration.ofMillis(999)  || IllegalArgumentException | "lilradish.model-calls.resend.longest-wait must not be shorter than first-wait: PT0.999S < PT1S"
    }

    def "doubles each wait from the first, never shorter than asked and never longer than the longest"() {
        given:
        def policy = new ResendPolicy(5, ONE_SECOND, LONGEST)

        expect:
        policy.waitBefore(resend, asked) == Duration.ofSeconds(seconds)

        where:
        resend            | asked                   || seconds
        1                 | null                    || 1
        2                 | null                    || 2
        3                 | null                    || 4
        5                 | null                    || 16
        6                 | null                    || 30
        100               | null                    || 30
        Integer.MAX_VALUE | null                    || 30
        1                 | Duration.ZERO           || 1
        1                 | Duration.ofSeconds(10)  || 10
        3                 | Duration.ofSeconds(3)   || 4
        3                 | Duration.ofSeconds(5)   || 5
        2                 | Duration.ofSeconds(31)  || 30
        2                 | Duration.ofNanos(Long.MAX_VALUE) || 30
    }

    def "saturates rather than overflows where the longest wait is the largest a duration holds"() {
        given:
        def largest = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)
        def policy = new ResendPolicy(Integer.MAX_VALUE, Duration.ofNanos(1), largest)

        expect:
        policy.waitBefore(resend, null) == expected

        where:
        resend            || expected
        1                 || Duration.ofNanos(1)
        31                || Duration.ofNanos(1L << 30)
        Integer.MAX_VALUE || Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)
    }

    def "counts resends from one"() {
        given:
        def policy = new ResendPolicy(5, ONE_SECOND, LONGEST)

        when:
        policy.waitBefore(resend, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "ResendPolicy counts resends from one: $resend"

        where:
        resend << [0, -1]
    }
}
