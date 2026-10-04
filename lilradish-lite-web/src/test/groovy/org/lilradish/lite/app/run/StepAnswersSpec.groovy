package org.lilradish.lite.app.run

import spock.lang.Specification

class StepAnswersSpec extends Specification {

    /** A reader refuses a failure whose reason and what it names disagree, so none is ever built to send. */
    def "whereabouts name a value no case claimed, or how deep runs may go, exactly where that is why the step failed"() {
        when:
        def whereabouts = new StepAnswers.WhereAnswer("failed", reason, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, unclaimed, tooDeep, null, null, null, null, null, null)

        then:
        whereabouts.failedUnclaimed() == unclaimed
        whereabouts.failedTooDeep() == tooDeep

        where:
        reason               | unclaimed                                       | tooDeep
        "unclaimed_value"    | new StepAnswers.UnclaimedAnswer.Named("wait")   | null
        "unclaimed_value"    | new StepAnswers.UnclaimedAnswer.Named(null)     | null
        "unclaimed_value"    | new StepAnswers.UnclaimedAnswer.Withheld(true)  | null
        "too_deep"           | null                                            | new StepAnswers.TooDeepAnswer("5")
        "model_not_deployed" | null                                            | null
    }

    def "whereabouts naming a value no case claimed, or how deep runs may go, other than exactly where that is why are refused"() {
        when:
        new StepAnswers.WhereAnswer("failed", reason, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, unclaimed, tooDeep, null, null, null, null, null, null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith("A step's whereabouts name the value no case claimed")

        where:
        reason               | unclaimed                                      | tooDeep
        "unclaimed_value"    | null                                           | null
        "too_deep"           | null                                           | null
        "model_not_deployed" | new StepAnswers.UnclaimedAnswer.Named("wait")  | null
        "model_not_deployed" | new StepAnswers.UnclaimedAnswer.Withheld(true) | null
        "model_not_deployed" | null                                           | new StepAnswers.TooDeepAnswer("5")
        "unclaimed_value"    | new StepAnswers.UnclaimedAnswer.Named("wait")  | new StepAnswers.TooDeepAnswer("5")
        "too_deep"           | new StepAnswers.UnclaimedAnswer.Named(null)    | new StepAnswers.TooDeepAnswer("5")
    }

    /** A reader takes a withheld that is false as a value it may read, which a withheld one never is. */
    def "a value no case claimed is withheld only as withheld, never as not"() {
        when:
        new StepAnswers.UnclaimedAnswer.Withheld(false)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A value no case claimed is withheld only as withheld"
    }
}
