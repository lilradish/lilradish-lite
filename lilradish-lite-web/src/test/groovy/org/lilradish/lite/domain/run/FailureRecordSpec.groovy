package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.tryId

import org.lilradish.lite.domain.inference.ModelCallPurpose
import spock.lang.Specification

class FailureRecordSpec extends Specification {

    static final RunStepFailureId FAILURE = new RunStepFailureId(key(1101))

    def "a failure on a try is taken saying what it was sending that try for, and one on none saying nothing of it"() {
        when:
        def failure = new FailureRecord(FAILURE, RunStepFailureReason.MODEL_NOT_DEPLOYED, onTry, purpose, minutes(10),
                false)

        then:
        failure.id() == FAILURE
        failure.onTry() == onTry
        failure.purpose() == purpose

        where:
        onTry     | purpose
        tryId(1)  | ModelCallPurpose.PRODUCE
        tryId(1)  | ModelCallPurpose.REVIEW
        null      | null
    }

    def "a failure with no id is refused, and nothing is built"() {
        given:
        FailureRecord failure = null

        when:
        failure = new FailureRecord(null, RunStepFailureReason.MODEL_NOT_DEPLOYED, tryId(1), ModelCallPurpose.PRODUCE,
                minutes(10), false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "FailureRecord id must not be null"
        failure == null
    }

    def "a failure naming what it was sending for without its try, or its try without it, or sending to help, is refused"() {
        given:
        FailureRecord failure = null

        when:
        failure = new FailureRecord(FAILURE, RunStepFailureReason.MODEL_NOT_DEPLOYED, onTry, purpose, minutes(10), false)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message
        failure == null

        where:
        onTry    | purpose                  || message
        tryId(1) | null                     || "FailureRecord names what it was sending for exactly where it was on a try"
        null     | ModelCallPurpose.PRODUCE || "FailureRecord names what it was sending for exactly where it was on a try"
        tryId(1) | ModelCallPurpose.HELP    || "FailureRecord was sending a try to be produced or to be reviewed"
    }
}
