package org.lilradish.lite.domain.inference

import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import spock.lang.Specification

class CallRequestSpec extends Specification {

    private static final ModelMode RESEARCH = new ModelMode("research")

    private static final ModelMode DEEP_RESEARCH = new ModelMode("deep_research")

    /** Takes exactly ten characters: four units of two and a half. */
    private static final DeployedModel SAMPLE = model("sample_model", 4, "2.5", [RESEARCH])

    private static final DeployedModel GENEROUS = model("generous_model", 200000, "4", [RESEARCH])

    private static final DeployedModel BARE = model("bare_model", 200000, "4", [])

    def "keeps the model, mode or none, purpose and measured text it was made with"() {
        given:
        def sent = SentText.measure("answer in JSON", '{"a":1}')

        when:
        def request = new CallRequest(model, mode, purpose, sent)

        then:
        request.model() == model
        request.mode() == mode
        request.purpose() == purpose
        request.sent() == sent

        where:
        [pairing, purpose] << [
            [[GENEROUS, RESEARCH], [GENEROUS, null], [BARE, null]],
            ModelCallPurpose.values().toList(),
        ].combinations()
        model = pairing[0]
        mode = pairing[1]
    }

    /** Every row sits exactly at its model's limit. */
    def "takes as many characters as the model's limit times its characters per unit"() {
        given:
        def sent = SentText.measure(system, user)

        when:
        def request = new CallRequest(model, null, ModelCallPurpose.PRODUCE, sent)

        then:
        notThrown(IllegalArgumentException)
        request.sent().is(sent)

        where:
        model  | system | user
        SAMPLE | "sys"  | "1234567"
    }

    def "refuses one character more than the model takes, naming the model and the units it would send"() {
        when:
        new CallRequest(model, null, ModelCallPurpose.PRODUCE, SentText.measure(system, user))

        then:
        def error = thrown(IllegalArgumentException)
        error.message == expectedMessage

        where:
        model  | system | user       || expectedMessage
        SAMPLE | "sys"  | "12345678" || "CallRequest to sample_model would send 5 units, more than the 4 it takes"
    }

    def "judges one measured text against the request's own model, so what fits one model is refused by another"() {
        given:
        def sent = SentText.measure("sys", "12345678")

        when:
        def request = new CallRequest(GENEROUS, null, ModelCallPurpose.REVIEW, sent)

        then:
        request.sent() == sent

        when:
        new CallRequest(SAMPLE, null, ModelCallPurpose.REVIEW, sent)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "CallRequest to sample_model would send 5 units, more than the 4 it takes"
    }

    def "refuses a request the model could not take, naming the model wherever there is one"() {
        when:
        new CallRequest(model, mode, purpose, sent)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        model  | mode          | purpose                  | sent                       || expectedException        | expectedMessage
        null   | RESEARCH      | ModelCallPurpose.PRODUCE | SentText.measure("s", "u") || NullPointerException     | "CallRequest model must not be null"
        null   | null          | ModelCallPurpose.PRODUCE | SentText.measure("s", "u") || NullPointerException     | "CallRequest model must not be null"
        SAMPLE | DEEP_RESEARCH | ModelCallPurpose.PRODUCE | SentText.measure("s", "u") || IllegalArgumentException | "CallRequest to sample_model asks for mode deep_research, which it does not offer"
        BARE   | RESEARCH      | ModelCallPurpose.PRODUCE | SentText.measure("s", "u") || IllegalArgumentException | "CallRequest to bare_model asks for mode research, which it does not offer"
        SAMPLE | RESEARCH      | null                     | SentText.measure("s", "u") || NullPointerException     | "CallRequest to sample_model has no purpose"
        SAMPLE | null          | ModelCallPurpose.HELP    | null                       || NullPointerException     | "CallRequest to sample_model sends nothing"
        SAMPLE | null          | ModelCallPurpose.HELP    | SentText.measure("", "u")  || IllegalArgumentException | "CallRequest to sample_model sends no system text"
        SAMPLE | RESEARCH      | ModelCallPurpose.HELP    | SentText.measure("s", "")  || IllegalArgumentException | "CallRequest to sample_model sends no user text"
    }

    private static DeployedModel model(String name, long sentPerCallLimit, String charactersPerUnit,
            List<ModelMode> modes) {
        new DeployedModel(new ModelName(name), modes, sentPerCallLimit, new BigDecimal(charactersPerUnit), 8192, [])
    }
}
