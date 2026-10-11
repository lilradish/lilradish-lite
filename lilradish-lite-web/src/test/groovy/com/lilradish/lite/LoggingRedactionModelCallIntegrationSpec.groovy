package com.lilradish.lite

import static com.lilradish.lite.testutil.RedactionSamples.A_VALUE
import static com.lilradish.lite.testutil.RedactionSamples.DECIDED_AT

import com.lilradish.lite.app.inference.ConvertedCallAnswer
import com.lilradish.lite.app.inference.SearchObservation
import com.lilradish.lite.app.inference.ToolCallOutcome
import com.lilradish.lite.app.run.EngineWrites
import com.lilradish.lite.app.run.PendingCall
import com.lilradish.lite.app.run.PendingSend
import com.lilradish.lite.app.run.RunTryDetails
import com.lilradish.lite.app.run.StepAnswers
import com.lilradish.lite.app.run.StepTries
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.Confidence
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.ForeignProse
import com.lilradish.lite.domain.inference.KeptAnswer
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.Payload
import com.lilradish.lite.domain.inference.ProductionAnswer
import com.lilradish.lite.domain.inference.ReviewAnswer
import com.lilradish.lite.domain.inference.TurnAway
import com.lilradish.lite.domain.inference.UnreadableResponseRecord
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelMode
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.domain.run.ModelCallId
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunStepFailureId
import com.lilradish.lite.domain.run.RunStepSendAttemptId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification

/** What a model call carries, from what is sent to what comes back, renders without the words it holds. */
class LoggingRedactionModelCallIntegrationSpec extends Specification {

    def "a converted answer drops both the answer and observations while keeping model counts and tool reports"() {
        given:
        def tool = new ModelToolName("web_search")
        def observation = new SearchObservation(tool, "weather in Rome", "Sunny.")

        when:
        def rendered = new ConvertedCallAnswer('{"summary":"private result"}', 120L, 8L, true,
                [(tool): 2L], [observation], reason,
                reason == DidNotFitReason.MODEL_DECLINED ? 'private refusal' : null, 3L).toString()

        then:
        rendered == "ConvertedCallAnswer(sentCount=120, cameBackCount=8, cutOff=true, " +
                "reportedUses={ModelToolName(value=web_search)=2}, didNotFitReason=${reason}, " +
                "generatedToolCallCharacters=3)" as String

        where:
        reason << [null, DidNotFitReason.MODEL_DECLINED]
    }

    def "a tool call outcome drops the model answer and observations and keeps its safe ending and usage"() {
        given:
        def tool = new ModelToolName("web_search")
        def outcome = new CallOutcome.CameBack(answer, 120L, 8L, true, false, reason,
                reason == DidNotFitReason.MODEL_DECLINED ? 'private refusal' : null)

        when:
        def rendered = new ToolCallOutcome(outcome, [(tool): 2L],
                [new SearchObservation(tool, "weather in Rome", "Sunny.")]).toString()

        then:
        rendered == "ToolCallOutcome(outcome=CallOutcome\$CameBack(sentCount=120, cameBackCount=8, " +
                "countedByModel=true, cutOff=false, didNotFitReason=${reason}), " +
                "reportedUses={ModelToolName(value=web_search)=2})" as String

        where:
        answer                                     | reason
        '{"summary":"private provider response"}' | null
        '{"summary":"private second response"}'   | null
        '{"summary":"private provider response"}' | DidNotFitReason.MODEL_DECLINED
    }

    def "a search observation drops absent and present text while retaining which tool observed it"() {
        when:
        def rendered = new SearchObservation(new ModelToolName("web_search"), searchedFor, found).toString()

        then:
        rendered == 'SearchObservation(tool=ModelToolName(value=web_search))'

        where:
        searchedFor       | found
        "weather in Rome" | "Sunny."
        null              | "Sunny."
        "weather in Rome" | null
        null              | null
    }

    def "how sure a model was renders as the figure rather than an identity hash"() {
        expect:
        new Confidence(90).toString() == 'Confidence(percent=90)'
    }

    def "what a call sends renders how long it is and neither of its texts"() {
        when:
        def rendered = SentText.measure("answer as the envelope says", '{"instruction":"total the invoice"}').toString()

        then:
        !rendered.contains("envelope says")
        !rendered.contains("total the invoice")

        and: "under a head only the rewrite emits, the slots that held them gone rather than emptied"
        rendered.startsWith('SentText(')
        rendered.contains('characters=62')
        !rendered.contains('system=')
        !rendered.contains('user=')
    }

    /** The unmatched carrier stays safe because its nested value renders only the text length. */
    def "a request renders which model it asks and how much it sends, and never what"() {
        given:
        def model = new DeployedModel(new ModelName("sample_model"), [new ModelMode("research")], 200000,
                new BigDecimal("4"), 8192, [], [])
        def request = new CallRequest(model, new ModelMode("research"), ModelCallPurpose.PRODUCE,
                SentText.measure("the envelope", "the invoice"))

        when:
        def rendered = request.toString()

        then:
        !rendered.contains("the envelope")
        !rendered.contains("the invoice")

        and:
        rendered.contains("sample_model")
        rendered.contains("purpose=PRODUCE")
        rendered.contains("sent=SentText(characters=23)")
    }

    /** The unmatched request stays safe because its nested text renders only its length. */
    def "a call written down as sent renders which call it is and how much it sends, and never what"() {
        given:
        def model = new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal("4"), 8192, [], [])
        def call = new PendingCall(new GroupId(UUID.randomUUID()), new RunId(UUID.randomUUID()),
                new RunId(UUID.randomUUID()), new ProductionId(UUID.randomUUID()),
                new RunStepSendAttemptId(UUID.randomUUID()), new ModelCallId(UUID.randomUUID()),
                new CallRequest(model, null, ModelCallPurpose.PRODUCE,
                        SentText.measure("the envelope", "invoice 4471 totals 1234.50")),
                new PendingCall.Producing([], []))

        when:
        def rendered = call.toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("the envelope")

        and:
        rendered.startsWith("PendingCall[")
        rendered.contains("sent=SentText(characters=39)")
        rendered.contains("call=ModelCallId(value=${call.call().value()})")
    }

    def "a call to review renders which values it decides by field and key, and never what they hold"() {
        given:
        def model = new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal("4"), 8192, [], [])
        def call = new PendingCall(new GroupId(UUID.randomUUID()), new RunId(UUID.randomUUID()),
                new RunId(UUID.randomUUID()), new ProductionId(UUID.randomUUID()),
                new RunStepSendAttemptId(UUID.randomUUID()), new ModelCallId(UUID.randomUUID()),
                new CallRequest(model, null, ModelCallPurpose.REVIEW,
                        SentText.measure("the envelope", "invoice 4471 totals 1234.50")),
                new PendingCall.Reviewing([new FieldName("total")], [A_VALUE]))

        when:
        def rendered = call.toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("1234.50")

        and:
        rendered.contains("purpose=REVIEW")
        rendered.contains("total")
        rendered.contains("ProductionValueId(value=${A_VALUE.value()})")
    }

    def "a send handed over renders the keys it names, and nothing besides"() {
        given:
        def send = new PendingSend(new GroupId(UUID.randomUUID()), new RunId(UUID.randomUUID()),
                new RunId(UUID.randomUUID()), new WorkflowStepId(UUID.randomUUID()), new ProductionId(UUID.randomUUID()), purpose, presser, attemptOn,
                failedOn)

        when:
        def rendered = send.toString()

        then:
        rendered == ("PendingSend[group=GroupId(value=${send.group().value()}), " +
                "root=RunId(value=${send.root().value()}), run=RunId(value=${send.run().value()}), " +
                "step=WorkflowStepId(value=${send.step().value()}), aTry=ProductionId(value=${send.aTry().value()}), purpose=${purpose}, ${said}]") as String

        where:
        purpose                  | presser                            | attemptOn                                                                          | failedOn                                                                       || said
        ModelCallPurpose.PRODUCE | null                               | null                                                                               | null                                                                           || "presser=null, attemptOn=null, failedOn=null"
        ModelCallPurpose.PRODUCE | new UserId("overseer@example.com") | new RunStepSendAttemptId(UUID.fromString("00000011-0000-4000-8000-000000000001")) | null                                                                           || "presser=UserId(value=overseer@example.com), attemptOn=RunStepSendAttemptId(value=00000011-0000-4000-8000-000000000001), failedOn=null"
        ModelCallPurpose.PRODUCE | new UserId("overseer@example.com") | null                                                                               | new RunStepFailureId(UUID.fromString("00000012-0000-4000-8000-000000000001")) || "presser=UserId(value=overseer@example.com), attemptOn=null, failedOn=RunStepFailureId(value=00000012-0000-4000-8000-000000000001)"
        ModelCallPurpose.REVIEW  | null                               | null                                                                               | null                                                                           || "presser=null, attemptOn=null, failedOn=null"
        ModelCallPurpose.REVIEW  | new UserId("overseer@example.com") | new RunStepSendAttemptId(UUID.fromString("00000011-0000-4000-8000-000000000002")) | null                                                                           || "presser=UserId(value=overseer@example.com), attemptOn=RunStepSendAttemptId(value=00000011-0000-4000-8000-000000000002), failedOn=null"
        ModelCallPurpose.REVIEW  | new UserId("overseer@example.com") | null                                                                               | new RunStepFailureId(UUID.fromString("00000012-0000-4000-8000-000000000002")) || "presser=UserId(value=overseer@example.com), attemptOn=null, failedOn=RunStepFailureId(value=00000012-0000-4000-8000-000000000002)"
    }

    def "what was sent, read back to be sent again, renders the attempt holding it and its envelope, and drops what it holds"() {
        given:
        def attempt = new RunStepSendAttemptId(UUID.fromString("44440000-0000-4000-8000-000000000010"))

        when:
        def rendered = new EngineWrites.SentRecord(attempt, '{"takes":{"text":"invoice 4471 totals 1234.50"}}', 2)
                .toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("payload=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('EngineWrites$SentRecord(')
        rendered.contains("attempt=RunStepSendAttemptId(value=${attempt.value()})")
        rendered.contains("envelopeVersion=2")
        rendered == "EngineWrites\$SentRecord(attempt=RunStepSendAttemptId(value=${attempt.value()}), envelopeVersion=2)" as String
    }

    def "an answer that came back renders what was counted and drops the answer"() {
        when:
        def rendered = new CallOutcome.CameBack('{"total":"1234.50"}', 120, 8, true, false, reason,
                reason == DidNotFitReason.MODEL_DECLINED ? 'private refusal' : null).toString()

        then:
        !rendered.contains("1234.50")
        !rendered.contains("private refusal")
        !rendered.contains("answer=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('CallOutcome$CameBack(')
        rendered.contains("sentCount=120")
        rendered.contains("cameBackCount=8")
        rendered.contains("countedByModel=true")
        rendered.contains("cutOff=false")
        rendered == "CallOutcome\$CameBack(sentCount=120, cameBackCount=8, countedByModel=true, " +
                "cutOff=false, didNotFitReason=${reason})" as String

        where:
        reason << [null, DidNotFitReason.MODEL_DECLINED, DidNotFitReason.NOT_THE_SHAPE]
    }

    def "an error renders that it went wrong and drops what the other side said"() {
        expect:
        new CallOutcome.Errored("invoice 4471 was refused upstream",
                new UnreadableResponseRecord(KeptAnswer.of("private service response"), stopped))
                .toString() == 'CallOutcome$Errored()'

        where:
        stopped << [false, true]
    }

    def "a turnaway renders whether it was used up and drops what the model said"() {
        when:
        def rendered = new TurnAway("rate limited for account 88", true).toString()

        then:
        !rendered.contains("account 88")
        !rendered.contains("said=")

        and: "under a head only the rewrite emits"
        rendered.startsWith("TurnAway(")
        rendered.contains("spentUp=true")
        rendered == 'TurnAway(spentUp=true)'
    }

    def "a call turned away renders the turnaway that ended it without what the model said"() {
        when:
        def rendered = new CallOutcome.TurnedAway(new TurnAway("quota spent for account 88", true)).toString()

        then:
        !rendered.contains("account 88")
        !rendered.contains("said=")

        and:
        rendered.contains("last=TurnAway(")
        rendered.contains("spentUp=true")
        rendered == 'CallOutcome$TurnedAway(last=TurnAway(spentUp=true))'
    }

    def "tool call endings without answers keep each complete safe ending and no response or observations"() {
        expect:
        new ToolCallOutcome(outcome, [:], []).toString() ==
                "ToolCallOutcome(outcome=${ending}, reportedUses={})" as String

        where:
        outcome                                                      || ending
        new CallOutcome.TurnedAway(new TurnAway("private quota", true)) || 'CallOutcome$TurnedAway(last=TurnAway(spentUp=true))'
        new CallOutcome.NotResent()                                   || 'CallOutcome$NotResent()'
        new CallOutcome.Errored("private provider error", null)         || 'CallOutcome$Errored()'
    }

    def "why a model lost a try renders which way it did not fit and whether the words were cut, and drops the words"() {
        when:
        def rendered = new StepTries.LostRecord(DidNotFitReason.CUT_OFF, "invoice 4471 was refused upstream", true, false)
                .toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("wentWrong=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('StepTries$LostRecord(')
        rendered.contains("didNotFit=CUT_OFF")
        rendered.contains("wentWrongCut=true")
        rendered == 'StepTries$LostRecord(didNotFit=CUT_OFF, wentWrongCut=true, unreadableResponsePresent=false)'
    }

    def "unreadable response records drop their kept service text and every capture flag"() {
        given:
        def carrier = switch (kind) {
            case 'domain' -> new UnreadableResponseRecord(KeptAnswer.of('Demo Sample private envelope'), stopped)
            case 'store' -> new RunTryDetails.ModelRecord(false, false, null, false, false, null, false, false,
                    'Demo Sample private envelope', true, false, stopped)
            case 'page' -> new StepAnswers.UnreadableResponseRecord('Demo Sample private envelope', true, false, stopped)
        }

        when:
        def rendered = carrier.toString()

        then:
        !rendered.contains('Demo Sample private envelope')
        !rendered.contains('text=')
        !rendered.contains('altered=')
        !rendered.contains('truncated=')
        !rendered.contains('stopped=')
        !rendered.contains('kept=')
        rendered == expected

        where:
        kind     | stopped || expected
        'domain' | false   || 'UnreadableResponseRecord()'
        'domain' | true    || 'UnreadableResponseRecord()'
        'store'  | false   || 'RunTryDetails$ModelRecord(reviewing=false, declining=false)'
        'store'  | true    || 'RunTryDetails$ModelRecord(reviewing=false, declining=false)'
        'page'   | false   || 'StepAnswers$UnreadableResponseRecord()'
        'page'   | true    || 'StepAnswers$UnreadableResponseRecord()'
    }

    def "a turnaway a step's page reads renders which attempt it was of and what became of it, and drops what was said"() {
        given:
        def attempt = new RunStepSendAttemptId(UUID.fromString("44440000-0000-4000-8000-000000000009"))

        when:
        def rendered = new StepTries.TurnawayRecord(attempt, DECIDED_AT, "rate limited for account 88", true, false)
                .toString()

        then:
        !rendered.contains("account 88")
        !rendered.contains("said=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('StepTries$TurnawayRecord(')
        rendered.contains("attempt=RunStepSendAttemptId(value=${attempt.value()})")
        rendered.contains("saidCut=true")
        rendered.contains("sentAgain=false")
        rendered == "StepTries\$TurnawayRecord(attempt=RunStepSendAttemptId(value=${attempt.value()}), at=${DECIDED_AT}, saidCut=true, sentAgain=false)" as String
    }

    /** Cleaned to be shown, not to be logged: it can still repeat whatever was sent. */
    def "text from the model's side renders whether it was cut and drops the text"() {
        when:
        def rendered = ForeignProse.errorDetail("invoice 4471 was refused upstream " + "a" * 2048).toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("text=")

        and: "under a head only the rewrite emits"
        rendered.startsWith("ForeignProse(")
        rendered.contains("truncated=true")
        rendered == 'ForeignProse(truncated=true)'
    }

    def "what is kept of an answer renders whether it was altered and drops the answer"() {
        when:
        def rendered = KeptAnswer.of('{"total":"4471"}' + Character.toString(0x0)).toString()

        then:
        !rendered.contains("4471")
        !rendered.contains("text=")

        and: "under a head only the rewrite emits"
        rendered.startsWith("KeptAnswer(")
        rendered.contains("altered=true")
        rendered == 'KeptAnswer(altered=true, truncated=false)'
    }

    def "values a model produced render as produced and drop both the values and how sure it was"() {
        when:
        def rendered = new ProductionAnswer.Produced(
                [(new FieldName("summary")): new JsonValue.JsonString("invoice 4471 was refused")],
                [(new FieldName("summary")): new Confidence(87)]).toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("87")
        !rendered.contains("values=")
        !rendered.contains("confidences=")

        and: "under a head only the rewrite emits"
        rendered == 'ProductionAnswer$Produced()'
    }

    def "a refusal a model reviewing gave renders that it refused and drops why"() {
        when:
        def rendered = new ReviewAnswer.Refused("invoice 4471 does not add up").toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("words=")

        and: "under a head only the rewrite emits"
        rendered == 'ReviewAnswer$Refused()'
    }

    def "a production refused before, as the next asking is told it, drops what it gave and why it was refused"() {
        when:
        def rendered = new Payload.Refused(
                [(new FieldName("summary")): new JsonValue.JsonString("invoice 4471")],
                [(new FieldName("summary")): "the total does not add up"]).toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("does not add up")
        !rendered.contains("values=")
        !rendered.contains("words=")

        and: "under a head only the rewrite emits"
        rendered == 'Payload$Refused()'
    }
}
