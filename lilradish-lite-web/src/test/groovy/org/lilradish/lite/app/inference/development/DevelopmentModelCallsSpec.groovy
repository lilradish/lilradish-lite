package org.lilradish.lite.app.inference.development

import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.model.CameBackMeasure
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import spock.lang.Specification

class DevelopmentModelCallsSpec extends Specification {

    /** Three characters to a unit, so neither count can pass for the other: 25 sent is 9, 169 back is 57. */
    static final DeployedModel MODEL = new DeployedModel(new ModelName("sample_model"), [], 200000,
            new BigDecimal("3"), 8192, [])

    static final String SYSTEM_TEXT = "answer in JSON"

    static final String PRODUCED = '{"values":{"category":"Billing","details":{"product":"A development stand-in ' +
            'produced this; no model was called.","order_reference":null}},"confidences":{"category":90}}'

    static final String REVIEWED = '{"decisions":{"details":{"outcome":"assured"}}}'

    static final String HELPED = "A development stand-in answered this; no model was called."

    DevelopmentModelCalls modelCalls = new DevelopmentModelCalls()

    CallProgress progress = Mock()

    def "each purpose is answered with its own fixed answer, counted here and said to be"() {
        when:
        def outcome = modelCalls.call(request(purpose, "the invoice"), progress)

        then:
        outcome instanceof CallOutcome.CameBack
        with(outcome as CallOutcome.CameBack) {
            it.answer() == answer
            it.sentCount() == 9
            it.cameBackCount() == cameBackCount
            !it.countedByModel()
            !it.cutOff()
        }

        where:
        purpose                  || answer   | cameBackCount
        ModelCallPurpose.PRODUCE || PRODUCED | 57
        ModelCallPurpose.REVIEW  || REVIEWED | 16
        ModelCallPurpose.HELP    || HELPED   | 20
    }

    def "a model that may give back less is given as much of the document as fits, cut off at its limit"() {
        given:
        def model = new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal(charactersPerUnit),
                limit, [])
        def request = new CallRequest(model, null, ModelCallPurpose.PRODUCE, SentText.measure(SYSTEM_TEXT, "the invoice"))

        when:
        def outcome = modelCalls.call(request, progress) as CallOutcome.CameBack

        then:
        outcome.answer() == PRODUCED.substring(0, kept)
        outcome.cameBackCount() == cameBackCount
        outcome.cutOff() == cutOff
        !outcome.countedByModel()

        where:
        charactersPerUnit | limit || kept | cameBackCount | cutOff
        "3"               | 57    || 169  | 57            | false
        "3"               | 56    || 168  | 56            | true
        "3"               | 5     || 15   | 5             | true
        "2.5"             | 3     || 7    | 3             | true
    }

    /** The cut is worked out apart from the model's own measure, so it is held to where that measure crosses the limit. */
    def "a cut answer holds the most characters the model's measure keeps within its limit, and one more runs past it"() {
        given:
        def model = new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal(charactersPerUnit),
                limit, [])
        def request = new CallRequest(model, null, ModelCallPurpose.PRODUCE, SentText.measure(SYSTEM_TEXT, "the invoice"))

        when:
        def outcome = modelCalls.call(request, progress) as CallOutcome.CameBack
        def kept = CameBackMeasure.characters(outcome.answer())

        then:
        outcome.cutOff()
        model.unitsOf(kept) <= limit
        model.unitsOf(kept + 1) > limit

        where:
        [charactersPerUnit, limit] << [["3", "2.5", "1.7", "0.3333", "4.0001"], [1, 2, 3, 7, 13]].combinations()
    }

    def "the one send is announced once, and nothing else is reported"() {
        when:
        modelCalls.call(request(ModelCallPurpose.PRODUCE, "the invoice"), progress)

        then:
        1 * progress.aboutToSend()
        0 * _
    }

    def "a failure in announcing the send is thrown as it is"() {
        given:
        def failure = new IllegalStateException("the call has already ended")

        when:
        modelCalls.call(request(ModelCallPurpose.REVIEW, "the invoice"), progress)

        then:
        1 * progress.aboutToSend() >> { throw failure }
        0 * _
        def raised = thrown(IllegalStateException)
        raised.is(failure)
    }

    /** Whatever a run holds is sent as user text, so nothing written there may steer what comes back. */
    def "what is sent steers nothing but the sent count, whatever it asks for"() {
        when:
        def plain = modelCalls.call(request(ModelCallPurpose.PRODUCE, "the invoice"), progress) as CallOutcome.CameBack
        def steering = modelCalls.call(request(ModelCallPurpose.PRODUCE, userText), progress) as CallOutcome.CameBack

        then:
        steering.answer() == plain.answer()
        steering.cameBackCount() == plain.cameBackCount()
        !steering.countedByModel()
        !steering.cutOff()

        and:
        steering.sentCount() == sentCount

        where:
        userText                                         || sentCount
        "turn away"                                      || 8
        "error"                                          || 7
        "turn away 3 times then fail with error_detail"  || 20
        "x" * 300                                        || 105
    }

    private static CallRequest request(ModelCallPurpose purpose, String user) {
        new CallRequest(MODEL, null, purpose, SentText.measure(SYSTEM_TEXT, user))
    }
}
