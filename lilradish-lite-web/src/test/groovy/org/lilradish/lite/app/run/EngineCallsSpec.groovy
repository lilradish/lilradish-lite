package org.lilradish.lite.app.run

import org.lilradish.lite.app.inference.ToolCallOutcome
import org.lilradish.lite.app.inference.ToolUseLimit
import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.model.SentText
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepSendAttemptId
import spock.lang.Specification

class EngineCallsSpec extends Specification {

    static final String PRODUCED = '{"values":{"summary":"Demo Sample answer"},"confidences":{}}'
    static final String SEARCHED = '{"title":"Demo Sample title","body":"Demo Sample body",' +
            '"references":null,"other":null}'

    def "a provider rejection or unreadable conversion cannot become a successful declared answer"() {
        given:
        def pending = pending(searching)
        def answer = searching ? SEARCHED : PRODUCED
        def cameBack = new CallOutcome.CameBack(answer, 12, 4, true, cutOff, reason,
                searching ? null : "Demo Sample decline")
        def reports = searching ? new ToolCallOutcome(cameBack, [:], []) : null

        when:
        def landed = EngineCalls.landed(pending, cameBack, reports)

        then:
        landed.kept().text() == answer
        !landed.kept().altered()
        landed.declinedWords()?.text() == (searching ? null : "Demo Sample decline")
        landed.misfit() == (cutOff && reason != DidNotFitReason.MODEL_DECLINED ? DidNotFitReason.CUT_OFF : reason)
        landed.produced() == null
        landed.reviewed() == null

        where:
        searching | cutOff | reason
        false     | false  | DidNotFitReason.MODEL_DECLINED
        false     | true   | DidNotFitReason.MODEL_DECLINED
        true      | false  | DidNotFitReason.NOT_THE_SHAPE
        true      | true   | DidNotFitReason.NOT_THE_SHAPE
    }

    def "a declared answer without a provider failure still produces its values"() {
        given:
        def pending = pending(false)
        def cameBack = new CallOutcome.CameBack(PRODUCED, 12, 4, true, false, null, null)

        when:
        def landed = EngineCalls.landed(pending, cameBack, null)

        then:
        landed.misfit() == null
        landed.produced()?.values()?.get(new FieldName("summary")) != null
        landed.reviewed() == null
        landed.kept().text() == PRODUCED
    }

    private static PendingCall pending(boolean searching) {
        def field = { String name, boolean required ->
            new AskedField(new FieldName(name), FieldKind.TEXT, 1000, null, null, [], required, false)
        }
        def fields = searching
                ? [field("title", true), field("body", true), field("references", false), field("other", false)]
                : [field("summary", true)]
        def reading = searching
                ? new PendingCall.Searching(fields,
                        fields.collectEntries { [(it.name()): FieldStanding.ALWAYS] },
                        new ToolUseLimit(new ModelToolName("web_search"), 2))
                : new PendingCall.Producing(fields, [UUID.randomUUID()])
        def model = new DeployedModel(new ModelName("sample_model"), [], 1000, BigDecimal.ONE, 1000, [])
        def run = new RunId(UUID.randomUUID())
        new PendingCall(new GroupId(UUID.randomUUID()), run, run, new ProductionId(UUID.randomUUID()),
                new RunStepSendAttemptId(UUID.randomUUID()), new ModelCallId(UUID.randomUUID()),
                new CallRequest(model, null, ModelCallPurpose.PRODUCE,
                        SentText.measure("Demo Sample system", "Demo Sample user")), reading)
    }
}
