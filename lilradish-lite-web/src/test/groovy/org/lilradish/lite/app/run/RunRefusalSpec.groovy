package org.lilradish.lite.app.run

import org.lilradish.lite.domain.failure.RefusalCode
import spock.lang.Specification

class RunRefusalSpec extends Specification {

    /** One sentence per code, the same wherever an act on a run raises it. */
    def "each refusal an act on a run raises carries its code and the one sentence written for it"() {
        when:
        def raised = refusal.raised()

        then:
        raised.errorCode() == code
        raised.message == sentence
        raised.cause == null

        where:
        refusal                                   || code                                       | sentence
        RunRefusal.CEILING_RAISE_ASKED_BY_ANOTHER || RefusalCode.CEILING_RAISE_ASKED_BY_ANOTHER | "A raise is withdrawn only by whoever asked for it."
        RunRefusal.CEILING_RAISE_ASKED_BY_CALLER  || RefusalCode.CEILING_RAISE_ASKED_BY_CALLER  | "Nobody approves or refuses a raise they asked for."
        RunRefusal.CEILING_RAISE_NOT_WAITING      || RefusalCode.CEILING_RAISE_NOT_WAITING      | "That raise is not waiting on anybody."
        RunRefusal.CEILING_UNUSABLE               || RefusalCode.CEILING_UNUSABLE               | "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all."
        RunRefusal.RUN_BENEATH_ANOTHER            || RefusalCode.RUN_BENEATH_ANOTHER            | "A run beneath another is changed only with the run at the top."
        RunRefusal.RUN_NAME_UNUSABLE              || RefusalCode.RUN_NAME_UNUSABLE              | "A run's name is one to 128 characters on one line, with something in it that shows."
        RunRefusal.RUN_NOT_IN_VIEW                || RefusalCode.RUN_NOT_IN_VIEW                | "That run is not in view."
        RunRefusal.TRY_SENDING_NOT_OFFERED        || RefusalCode.TRY_SENDING_NOT_OFFERED        | "Try sending is only for a call to a model that could not be sent: the model turned it away, it was too long for the model, or it is to a model, or a mode of one, that this deployment does not offer."
    }

    /** What a value's own rule said is kept beside the refusal for whoever raised it, and never put in its sentence. */
    def "a refusal raised over a rule refusing a value carries it as its cause, and not in its sentence"() {
        given:
        def cause = new IllegalArgumentException("RunName must not be empty")

        when:
        def raised = RunRefusal.RUN_NAME_UNUSABLE.raised(cause)

        then:
        raised.cause.is(cause)
        raised.message == "A run's name is one to 128 characters on one line, with something in it that shows."
    }

    /** Raised as every other refusal is, it would leave without what found the code step giving otherwise. */
    def "a code step giving otherwise is never raised as a refusal naming nothing beside it"() {
        when:
        RunRefusal.answering(RefusalCode.CODE_STEP_GIVES_OTHERWISE).raised(cause)

        then:
        def mistaken = thrown(IllegalStateException)
        mistaken.message == "A code step giving otherwise is refused only through givesOtherwise, naming what found it so"

        where:
        cause << [null, new IllegalArgumentException("RunName must not be empty")]
    }

    def "a code step giving otherwise is never refused naming no fault"() {
        when:
        RunRefusal.givesOtherwise(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "GivesOtherwiseRefusal fault must not be null"
    }

    def "a code a rule on runs answers with is found as the refusal for it"() {
        expect:
        RunRefusal.answering(refusal.code) == refusal

        where:
        refusal << RunRefusal.values()
    }

    def "a code no act on a run raises is refused rather than answered with some other sentence"() {
        when:
        RunRefusal.answering(code)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "No act on a run raises " + code

        where:
        code << [RefusalCode.GROUP_NOT_IN_VIEW, RefusalCode.ENTRY_NOT_IN_VIEW]
    }
}
