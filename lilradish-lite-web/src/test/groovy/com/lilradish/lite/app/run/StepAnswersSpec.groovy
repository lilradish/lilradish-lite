package com.lilradish.lite.app.run

import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class StepAnswersSpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    def "refusal facts follow their words while unreadable evidence also names whether downloading stopped"() {
        expect:
        JSON.readValue(JSON.writeValueAsString(answer), Map) == expected

        where:
        answer | expected
        new StepAnswers.DeclinedAnswer(new StepAnswers.DeclinedTextAnswer("**Imaginary refusal**", false, false), new StepAnswers.DeclinedTextAnswer("**Imaginary raw answer**", true, false), null) | [words: [text: "**Imaginary refusal**", altered: false, truncated: false], wordsLines: "several", content: [text: "**Imaginary raw answer**", altered: true, truncated: false]]
        new StepAnswers.DeclinedAnswer(null, new StepAnswers.DeclinedTextAnswer("Imaginary raw answer", false, false), null) | [content: [text: "Imaginary raw answer", altered: false, truncated: false]]
        new StepAnswers.DeclinedAnswer(null, null, true) | [withheld: true]
        new StepAnswers.DecisionAnswer("refused", "**Imaginary reason**", null, null, null, null) | [outcome: "refused", why: "**Imaginary reason**", whyLines: "several"]
        new StepAnswers.DecisionAnswer("refused", "Imaginary reason\nSecond line", null, null, null, null) | [outcome: "refused", why: "Imaginary reason\nSecond line", whyLines: "several"]
        new StepAnswers.DecisionAnswer("refused", null, true, null, null, null) | [outcome: "refused", withheld: true]
        new StepAnswers.DecisionAnswer("assured", null, null, null, null, null) | [outcome: "assured"]
        new RunActs.Decision("answer", "refused", "**Imaginary person reason**", null, null) | [field: "answer", outcome: "refused", why: "**Imaginary person reason**", whyLines: "several"]
        new RunActs.Decision("answer", "assured", null, null, null) | [field: "answer", outcome: "assured"]
        new StepAnswers.UnreadableResponseRecord("**Imaginary service evidence**", false, true, false) | [text: "**Imaginary service evidence**", altered: false, truncated: true, stopped: false]
        new StepAnswers.UnreadableResponseRecord("**Imaginary service evidence**", true, true, true) | [text: "**Imaginary service evidence**", altered: true, truncated: true, stopped: true]
    }

    def "search records and answering instructions keep their own readable facts"() {
        expect:
        JSON.readValue(JSON.writeValueAsString(answer), Map) == expected

        where:
        answer | expected
        new StepAnswers.SearchAnswer(null, [new StepAnswers.SearchedAnswer("Imaginary query", "Imaginary source")], null, null, null, null, null, null, null, null) | [searches: [[searchedFor: "Imaginary query", found: "Imaginary source"]]]
        new StepAnswers.SearchAnswer(true, null, null, null, null, null, null, null, null, null) | [withheld: true]
        new StepAnswers.AnsweringAnswer(1, false, "**Imaginary question**", [], null) | [number: 1, beyond: false, instruction: "**Imaginary question**", gives: []]
        new StepAnswers.AnsweringAnswer(1, false, null, [], null) | [number: 1, beyond: false, gives: []]
        new StepAnswers.AnsweringAnswer(1, false, null, [], null) | [number: 1, beyond: false, gives: []]
        new StepAnswers.WhereAnswer("held_back", "too_long", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null) | [kind: "held_back", reason: "too_long"]
        new StepAnswers.WhereAnswer("held_back", "turned_away", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null) | [kind: "held_back", reason: "turned_away"]
    }

    /** A reader refuses a failure whose reason and what it names disagree, so none is ever built to send. */
    def "whereabouts name a value no case claimed, or how deep runs may go, exactly where that is why the step failed"() {
        when:
        def whereabouts = new StepAnswers.WhereAnswer("failed", reason, null, null, null, null, null, null, null, null, null, null, null, null, null, null, unclaimed, tooDeep, null, null, null, null)

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
        new StepAnswers.WhereAnswer("failed", reason, null, null, null, null, null, null, null, null, null, null, null, null, null, null, unclaimed, tooDeep, null, null, null, null)

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
