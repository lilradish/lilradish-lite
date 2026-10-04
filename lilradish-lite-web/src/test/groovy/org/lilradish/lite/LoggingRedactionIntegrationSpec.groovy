package org.lilradish.lite

import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.nio.file.Path
import java.time.Instant
import org.libprunus.core.log.annotation.MaxMessageLength
import org.libprunus.core.log.runtime.Loggable
import org.lilradish.lite.app.inference.ConvertedCallAnswer
import org.lilradish.lite.app.inference.SearchObservation
import org.lilradish.lite.app.inference.ToolCallOutcome
import org.lilradish.lite.app.run.EngineWrites
import org.lilradish.lite.app.run.PendingCall
import org.lilradish.lite.app.run.PendingSend
import org.lilradish.lite.app.run.StepAnswers
import org.lilradish.lite.app.run.StepReading
import org.lilradish.lite.domain.codestep.CodeCall
import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.codestep.CodeOutcome
import org.lilradish.lite.domain.codestep.CodeStepName
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.filling.FilledFields
import org.lilradish.lite.domain.identity.Delegation
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.HumanPrincipal
import org.lilradish.lite.domain.identity.Scope
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.Confidence
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.inference.ForeignProse
import org.lilradish.lite.domain.inference.GivenBack
import org.lilradish.lite.domain.inference.KeptAnswer
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.Payload
import org.lilradish.lite.domain.inference.ProductionAnswer
import org.lilradish.lite.domain.inference.ReviewAnswer
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.model.SentText
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.DecisionRecord
import org.lilradish.lite.domain.run.InputRecord
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.ProductionValueId
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.ReviewPayload
import org.lilradish.lite.domain.run.ReviewRecord
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunInTree
import org.lilradish.lite.domain.run.RunSnapshot
import org.lilradish.lite.domain.run.RunStepFailureId
import org.lilradish.lite.domain.run.RunStepSendAttemptId
import org.lilradish.lite.domain.run.RunnableWorkflow
import org.lilradish.lite.domain.run.StepInputs
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepSnapshot
import org.lilradish.lite.domain.run.TryLostReason
import org.lilradish.lite.domain.run.TryRecord
import org.lilradish.lite.domain.run.ValueRecord
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.run.fixture.Runs
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.domain.workflow.StepProducer
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import spock.lang.Specification

/** AppLoggingConvention suffixes drive rewriting; assert redaction and readable identifiers to catch silent misses or empty output. */
class LoggingRedactionIntegrationSpec extends Specification {

    static final RunId RUN = new RunId(UUID.fromString("44440000-0000-4000-8000-000000000001"))

    static final StepId CLASSIFY = new StepId("classify")

    static final SubjectId OVERSEER = new SubjectId(UUID.fromString("44440000-0000-4000-8000-000000000003"))

    static final SubjectId NIGHTLY_DIGEST = new SubjectId(UUID.fromString("44440000-0000-4000-8000-000000000006"))

    static final Instant DECIDED_AT = Instant.parse("2026-09-20T09:00:00Z")

    static final Scope.Group FINANCE =
            new Scope.Group(new GroupId(UUID.fromString("44440000-0000-4000-8000-000000000005")))

    static final ProductionId A_TRY = new ProductionId(UUID.fromString("44440000-0000-4000-8000-000000000007"))

    static final ProductionValueId A_VALUE =
            new ProductionValueId(UUID.fromString("44440000-0000-4000-8000-000000000008"))


    static final List<String> PROVENANCE = ["values=", "reviews=", "inputs="]

    /** Read off the declaration rather than restated, so the budget and the cut cannot drift apart. */
    static final int BUDGET = declaredBudget()

    def "the pinned rewrite matches every newly named sensitive carrier, including the nested binary name"() {
        expect:
        Loggable.isAssignableFrom(carrier)

        where:
        carrier << [ConvertedCallAnswer, ToolCallOutcome, SearchObservation, ListFilter, ReviewPayload.Built]
    }

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

    def "typed filters and built review payloads have complete safe renderings"() {
        expect:
        new ListFilter("private search words").toString() == 'ListFilter()'
        new ReviewPayload.Built(new JsonValue.JsonObject([
                new JsonValue.JsonMember("summary", new JsonValue.JsonString("private result"))])).toString() == 'ReviewPayload$Built()'
    }

    def "explicitly unwoven tree wrappers expose only identifiers, never the nested run or position"() {
        given:
        def step = Runs.unstarted(Runs.question(1, Runs.person(), 1))
        def run = Runs.run([step], false, null, [text: "private starting words"])
        def from = new StepSnapshot(Runs.workflowStep(1), null, null, null, [], [],
                new StepSnapshot.Beneath(run, null), [:])
        def above = Runs.run([from], false, null, [text: "private parent words"])

        expect:
        new RunInTree(run, RunInTree.root(above), from).toString() ==
                "RunInTree[run=${run.run().value()}]" as String
        new StepPosition.Beneath(run, step, new StepPosition.NotStarted()).toString() ==
                "Beneath[run=${run.run().value()}, at=${step.planned().id().value()}]" as String
    }

    private static ValueRecord summary(ProductionValueId id, String said) {
        new ValueRecord(id, "summary", new JsonValue.JsonString(said), null, true)
    }


    def "a value a try gave back renders which field it is of and whether it waits on a review, and drops what it holds"() {
        when:
        def rendered = summary(A_VALUE, "invoice 4471 was refused").toString()

        then:
        !rendered.contains("invoice 4471")

        and: "the slot it was in gone rather than emptied, the one value= left being the identifier's own"
        rendered.count("value=") == 1

        and: "under a head a record cannot emit, with every other slot there"
        rendered.startsWith('ValueRecord(')
        rendered.contains('id=ProductionValueId(value=44440000-0000-4000-8000-000000000008)')
        rendered.contains('field=summary')
        rendered.contains('needsReview=true')
        rendered == 'ValueRecord(id=ProductionValueId(value=44440000-0000-4000-8000-000000000008), field=summary, confidence=null, needsReview=true)'
    }

    def "a decision drops the words a value was refused with and keeps what it decided of which value"() {
        when:
        def rendered = new DecisionRecord(A_VALUE, ReviewOutcome.REFUSED, "the total does not add up").toString()

        then:
        !rendered.contains("does not add up")
        !rendered.contains("why=")

        and:
        rendered.startsWith('DecisionRecord(')
        rendered.contains('value=ProductionValueId(value=44440000-0000-4000-8000-000000000008)')
        rendered.contains('outcome=REFUSED')
        rendered == 'DecisionRecord(value=ProductionValueId(value=44440000-0000-4000-8000-000000000008), outcome=REFUSED)'
    }


    def "a try renders what it is and who made it, and drops why they said it and what code said or gave back"() {
        given:
        def answered = new TryRecord(A_TRY, 2, StepProducer.PERSON, null, DECIDED_AT, DECIDED_AT, OVERSEER,
                "the buyer's address, not the shipper's", null, null, null, false, null, [], [], [], [], [])
        def lost = new TryRecord(A_TRY, 1, StepProducer.MODEL, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.DID_NOT_FIT, null, null, false, null, [], [], [], [], [])
        def codeSaid = new TryRecord(A_TRY, 1, StepProducer.CODE, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.ERRORED, null, "invoice 4471 was refused upstream", true, null, [], [], [], [], [])
        def codeReturned = new TryRecord(A_TRY, 1, StepProducer.CODE, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.ERRORED, new CodeError.Fault(CodeErrorReason.TOO_LONG, [new FieldName("total")], null, null),
                null, false, '{"total":"1234.50"}', [], [], [], [], [])

        when:
        def renderedAnswered = answered.toString()
        def renderedLost = lost.toString()
        def renderedSaid = codeSaid.toString()
        def renderedReturned = codeReturned.toString()

        then:
        !renderedAnswered.contains("shipper's")
        [renderedLost, renderedSaid, renderedReturned].every {
            !it.contains("invoice 4471") && !it.contains("1234.50")
        }

        and: "the slots they were in gone rather than emptied"
        ["explanation=", "lostDetail=", "returned="].every { slot ->
            [renderedAnswered, renderedLost, renderedSaid, renderedReturned].every { !it.contains(slot) }
        }

        and: "under a head a record cannot emit, naming the try and who made it"
        renderedAnswered.startsWith('TryRecord(')
        renderedAnswered.contains('id=ProductionId(value=44440000-0000-4000-8000-000000000007)')
        renderedAnswered.contains('number=2')
        renderedAnswered.contains('producer=PERSON')
        renderedAnswered.contains("endedBy=SubjectId(value=" + OVERSEER.value() + ")")

        and: "and how the lost one ended, which is what a line about it is read for"
        renderedLost.contains('lost=DID_NOT_FIT')
        renderedSaid.contains('lost=ERRORED')
        renderedSaid.contains('lostDetailCut=true')
        renderedReturned.contains('lost=ERRORED')
    }


    def "a try of code gone wrong for this system's reason renders the reason and where, and drops the member it names"() {
        given:
        def aTry = new TryRecord(A_TRY, 1, StepProducer.CODE, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.ERRORED,
                new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [new FieldName("lines")],
                        new CodeError.UndeclaredMember("invoice_4471", false), null), null,
                false, null, [], [], [], [], [])

        when:
        def rendered = aTry.toString()

        then:
        !rendered.contains("invoice_4471")
        !rendered.contains("member=")

        and: "the reason and the field rendered a level down, under a head only the rewrite emits"
        rendered.startsWith('TryRecord(')
        rendered.contains('fault=CodeError$Fault(')
        rendered.contains('reason=NOT_DECLARED')
        rendered.contains('FieldName(value=lines)')
        !rendered.contains("Fault@")
    }


    def "code gone wrong as giving otherwise renders what reads the field, a step by its key and an output by its field"() {
        when:
        def rendered = new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE, [new FieldName("receipt")], null, readBy)
                .toString()

        then:
        rendered.startsWith('CodeError$Fault(')
        rendered.contains(head)
        rendered.contains(named)
        !rendered.contains("Reads@")

        where:
        readBy                                                                           || head                             | named
        new CodeError.StepReads(UUID.fromString("44440000-0000-4000-8000-00000000000c")) || 'readBy=CodeError$StepReads('   | 'step=44440000-0000-4000-8000-00000000000c'
        new CodeError.OutputReads([new FieldName("result")])                             || 'readBy=CodeError$OutputReads(' | 'FieldName(value=result)'
    }


    def "a run as the engine reads it renders which run it is and drops what it was started with"() {
        given:
        def nothing = { DeclarationSide side -> new Declaration(side, Demands.ofWorkflow(side), []) }
        def workflow = new RunnableWorkflow(nothing(DeclarationSide.TAKES), nothing(DeclarationSide.GIVES), [:], [], [])
        def run = new RunSnapshot(RUN, RUN, FINANCE.groupId(),
                new EntryVersionId(UUID.fromString("44440000-0000-4000-8000-000000000009")), true, null,
                new JsonValue.JsonObject([new JsonValue.JsonMember("ticket", new JsonValue.JsonString("invoice 4471"))]),
                workflow, [])

        when:
        def rendered = run.toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("startedWith=")
        !rendered.contains("workflow=")

        and:
        rendered.startsWith('RunSnapshot(')
        rendered.contains('run=RunId(value=44440000-0000-4000-8000-000000000001)')
        rendered.contains('stopped=true')
        rendered.contains('steps=[]')
    }

    def "what a binding read is dropped, and the value it was traced to is kept"() {
        given:
        def binding = new Binding(
                UUID.fromString("44440000-0000-4000-8000-00000000000a"),
                Pointer.parse("text"),
                new BindingSource.Written(new JsonValue.JsonString("constant 9120")))
        def bound = new StepInputs.BindingRecord(binding, new JsonValue.JsonString("invoice 4471"), A_VALUE, [A_VALUE])

        when:
        def rendered = bound.toString()

        then:
        rendered == 'StepInputs$BindingRecord(source=ProductionValueId(value=44440000-0000-4000-8000-000000000008))'
    }

    /** Identifiers must remain readable so a logged act can be traced. */
    def "the identifiers a line has to be traced by are readable rather than identity hashes"() {
        expect:
        RUN.toString() == "RunId(value=44440000-0000-4000-8000-000000000001)"
        CLASSIFY.toString() == "StepId(value=classify)"
        OVERSEER.toString() == "SubjectId(value=44440000-0000-4000-8000-000000000003)"

        and: "including what a provider calls a person, which reaches a line about the act it is recorded against"
        new UserId("overseer@example.com").toString() == "UserId(value=overseer@example.com)"
    }


    def "a system actor renders who it acts as"() {
        when:
        def rendered = SystemPrincipal.WORKFLOW_RUNNER.toString()

        then:
        rendered.startsWith("SystemPrincipal(")

        and:
        rendered.contains("subject=SubjectId(value=00000000-0000-4000-8000-000000000001)")

        and: "and the constant's own name is absent, which is how a rendering that took statics in would show"
        !rendered.contains("WORKFLOW_RUNNER")
    }


    def "a delegation renders what acted, who it is charged to, where, and what it was bounded to"() {
        given:
        def owner = HumanPrincipal.of(OVERSEER, [] as Set, [(FINANCE): [GroupRole.OVERSEER] as Set])
        def registration = new Delegation.Registration(
                UUID.fromString("44440000-0000-4000-8000-000000000004"),
                NIGHTLY_DIGEST,
                OVERSEER,
                FINANCE,
                DECIDED_AT,
                null,
                [GroupPermission.READ_ALL_RUNS] as Set)

        when:
        def rendered = Delegation.of(registration, owner, DECIDED_AT.minusSeconds(1)).toString()

        then:
        rendered.startsWith("Delegation(")

        and: "naming the subject that acted and the one it is charged to, both a level down"
        rendered.contains("label=SubjectId(value=" + NIGHTLY_DIGEST.value() + ")")
        rendered.contains("owner=SubjectId(value=" + OVERSEER.value() + ")")

        and: "and where it may be exercised, down to the identifier the group is named by"
        rendered.contains('group=Scope$Group(groupId=GroupId(value=44440000-0000-4000-8000-000000000005))')

        and: "said once, the slot repeating it being dropped so the budget reaches what bounds the call"
        !rendered.contains("scopes=")

        and: "and what it was bounded to, which is what a line about a refused call is read for"
        rendered.contains("permissions=[READ_ALL_RUNS]")
        rendered.contains("roles=[OVERSEER]")

        and: "while the permission its owner holds and it was never granted is absent"
        !rendered.contains("REVIEW_AT_GATE")

        and: "and the registration is rendered rather than falling back to a hash, which names neither subject"
        rendered.contains('registration=Delegation$Registration(')
        !rendered.contains("Registration@")

        and: "and the line ran to its end, a cut one having lost the bounds rather than rendered them"
        !rendered.endsWith('...[TRUNCATED])')

        and: "with the margin the dropped slot bought, so a slot added back fails here rather than in a log"
        BUDGET - rendered.length() == 60
    }


    def "how sure a model was renders as the figure rather than an identity hash"() {
        expect:
        new Confidence(90).toString() == 'Confidence(percent=90)'
    }

    /** Log only the identifying head: populated payloads exceed the message budget and cause tail truncation. */
    def "a fully reviewed try renders past the budget, keeping its identifiers and losing its tail"() {
        given: "every component one try can carry at once"
        def second = new ProductionValueId(UUID.fromString("44440000-0000-4000-8000-00000000000b"))
        def review = new ReviewRecord(OVERSEER, DECIDED_AT, false, null, null, [
                new DecisionRecord(A_VALUE, ReviewOutcome.ASSURED, null),
                new DecisionRecord(second, ReviewOutcome.REFUSED, "the total does not add up")])
        def aTry = new TryRecord(A_TRY, 2, StepProducer.PERSON, OVERSEER, DECIDED_AT, DECIDED_AT, NIGHTLY_DIGEST,
                "the buyer's address", null, null, null, false, null,
                [summary(A_VALUE, "invoice 4471"), summary(second, "invoice 4472")], [review],
                [new InputRecord(UUID.fromString("44440000-0000-4000-8000-00000000000a"), A_VALUE)], [], [])

        when:
        def rendered = aTry.toString()

        then:
        rendered.length() <= BUDGET
        rendered.endsWith('...[TRUNCATED])')

        and: "while what the try can be looked up by survives whole"
        rendered.startsWith('TryRecord(')
        rendered.contains('id=ProductionId(value=44440000-0000-4000-8000-000000000007)')
        rendered.contains('number=2')

        and: "and the tail is what the cut took, whichever slot the layer's field order put last"
        PROVENANCE.any { !rendered.contains(it) }

        and: "having reached the tail before it cut, which losing all three would leave unsaid"
        PROVENANCE.any { rendered.contains(it) }

        and: "with nothing anybody said in it, cut or not"
        !rendered.contains("invoice 447")
        !rendered.contains("does not add up")
        !rendered.contains("buyer's address")
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
                new BigDecimal("4"), 8192, [])
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
        def model = new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal("4"), 8192, [])
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
        def model = new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal("4"), 8192, [])
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
                KeptAnswer.of("private service response")).toString() == 'CallOutcome$Errored()'
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
        def rendered = new StepReading.LostRecord(DidNotFitReason.CUT_OFF, "invoice 4471 was refused upstream", true, false)
                .toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("wentWrong=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('StepReading$LostRecord(')
        rendered.contains("didNotFit=CUT_OFF")
        rendered.contains("wentWrongCut=true")
        rendered == 'StepReading$LostRecord(didNotFit=CUT_OFF, wentWrongCut=true, unreadableResponsePresent=false)'
    }

    def "unreadable response records render flags but never their kept service text"() {
        when:
        def rendered = carrier.toString()

        then:
        !rendered.contains('Demo Sample private envelope')
        !rendered.contains('text=')
        !rendered.contains('altered=')
        !rendered.contains('truncated=')

        where:
        carrier << [new StepReading.UnreadableRecord('Demo Sample private envelope', true, false),
                    new StepAnswers.UnreadableResponseRecord('Demo Sample private envelope', true, false)]
    }

    def "a turnaway a step's page reads renders which attempt it was of and what became of it, and drops what was said"() {
        given:
        def attempt = new RunStepSendAttemptId(UUID.fromString("44440000-0000-4000-8000-000000000009"))

        when:
        def rendered = new StepReading.TurnawayRecord(attempt, DECIDED_AT, "rate limited for account 88", true, false)
                .toString()

        then:
        !rendered.contains("account 88")
        !rendered.contains("said=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('StepReading$TurnawayRecord(')
        rendered.contains("attempt=RunStepSendAttemptId(value=${attempt.value()})")
        rendered.contains("saidCut=true")
        rendered.contains("sentAgain=false")
        rendered == "StepReading\$TurnawayRecord(attempt=RunStepSendAttemptId(value=${attempt.value()}), at=${DECIDED_AT}, saidCut=true, sentAgain=false)" as String
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

    def "values a code step gave back that fit render as fitting and drop the values"() {
        when:
        def rendered = new GivenBack.Fits(
                [(new FieldName("summary")): new JsonValue.JsonString("invoice 4471 was refused")]).toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("values=")

        and: "under a head only the rewrite emits"
        rendered == 'GivenBack$Fits()'
    }

    def "a code step's misfit renders why and where, and drops the member nothing declares"() {
        when:
        def rendered = new GivenBack.Misfit(DidNotFitReason.FIELD_UNKNOWN, [new FieldName("details")],
                "invoice 4471 was refused").toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("undeclared=")

        and: "under a head only the rewrite emits, why and where still said"
        rendered.startsWith('GivenBack$Misfit(')
        rendered.contains("FIELD_UNKNOWN")
        rendered.contains("details")
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

    def "values a person filled in render as filled and drop what they wrote"() {
        when:
        def rendered = new FilledFields(new JsonValue.JsonObject(
                [new JsonValue.JsonMember("complaint", new JsonValue.JsonString("invoice 4471 was refused"))])).toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("values=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('FilledFields(')
        rendered == 'FilledFields()'
    }


    def "a call to a code step renders which code step it runs and drops what it takes, declares and is offered"() {
        when:
        def rendered = new CodeCall(new CodeStepName("send_reply"), SpecCodeStep.SEND_REPLY.declaration(), [:],
                new JsonValue.JsonObject([new JsonValue.JsonMember("reply", new JsonValue.JsonString("invoice 4471 was refused"))]), [:])
                .toString()

        then:
        !rendered.contains("invoice 4471")
        ["takes=", "declared=", "lists="].every { !rendered.contains(it) }

        and: "under a head only the rewrite emits, naming the code step"
        rendered.startsWith('CodeCall(')
        rendered.contains('name=CodeStepName(value=send_reply)')
        rendered == 'CodeCall(name=CodeStepName(value=send_reply))'
    }

    def "values code gave back render as given and drop them"() {
        when:
        def rendered = new CodeOutcome.Gave([new CodeOutcome.Given(new FieldName("receipt"),
                new JsonValue.JsonString("invoice 4471 was sent"), FieldStanding.ALWAYS, null)]).toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("values=")

        and: "under a head only the rewrite emits"
        rendered == 'CodeOutcome$Gave()'
    }

    def "a value code gave back renders which field it is of and how it stands, and drops the value"() {
        when:
        def rendered = new CodeOutcome.Given(new FieldName("tier"), new JsonValue.JsonString("invoice 4471 is gold"),
                FieldStanding.ABOVE_CONFIDENCE, 80).toString()

        then:
        !rendered.contains("invoice 4471")

        and: "the value's own slot gone, the only value= left being the field name's"
        !rendered.replace("FieldName(value=tier)", "").contains("value=")

        and: "under a head only the rewrite emits, the field's name rendered in turn"
        rendered.startsWith('CodeOutcome$Given(')
        rendered.contains('name=FieldName(value=tier)')
        rendered.contains('standing=ABOVE_CONFIDENCE')
        rendered.contains('floor=80')
        rendered == 'CodeOutcome$Given(name=FieldName(value=tier), standing=ABOVE_CONFIDENCE, floor=80)'
    }


    def "whether a declared field must be given renders as it is, under a head only the rewrite emits"() {
        when:
        def rendered = new Demand.Given(true).toString()

        then:
        rendered == 'Demand$Given(mustBe=true)'
    }


    def "code gone wrong renders whether what it said was cut and drops what it said and what it gave back"() {
        when:
        def rendered = new CodeOutcome.Errored(
                new CodeError.Said(ForeignProse.errorDetail("invoice 4471 was refused upstream " + "a" * 2048)),
                null).toString()
        def returned = new CodeOutcome.Errored(
                new CodeError.Fault(CodeErrorReason.TOO_LONG, [new FieldName("total")], null, null),
                '{"total":"1234.50"}').toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("1234.50")
        !returned.contains("1234.50")
        ["text=", "returned="].every { !rendered.contains(it) && !returned.contains(it) }

        and: "under a head only the rewrite emits, what it said rendered a level down as prose renders"
        rendered.startsWith('CodeOutcome$Errored(')
        rendered.contains('wentWrong=CodeError$Said(detail=ForeignProse(truncated=true))')
        rendered == 'CodeOutcome$Errored(wentWrong=CodeError$Said(detail=ForeignProse(truncated=true)))'
        returned == 'CodeOutcome$Errored(wentWrong=CodeError$Fault(reason=TOO_LONG, path=[FieldName(value=total)], readBy=null))'
    }

    def "code gone wrong for this system's reason renders the reason and where, and drops the member and what it gave back"() {
        when:
        def rendered = new CodeOutcome.Errored(
                new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [new FieldName("lines")],
                        new CodeError.UndeclaredMember("invoice_4471", false), null),
                '{"lines":[{"invoice_4471":"1234.50"}]}').toString()

        then:
        !rendered.contains("invoice_4471")
        !rendered.contains("1234.50")
        ["member=", "returned="].every { !rendered.contains(it) }

        and: "under a head only the rewrite emits, the reason and the field rendered a level down"
        rendered.startsWith('CodeOutcome$Errored(')
        rendered.contains('wentWrong=CodeError$Fault(')
        rendered.contains('reason=NOT_DECLARED')
        rendered.contains('FieldName(value=lines)')
    }

    /** CLASS-retained annotations require reading class files rather than runtime metadata. */
    private static int declaredBudget() {
        def classes = Path.of(AppLoggingConvention.protectionDomain.codeSource.location.toURI())
        def declaring = classes.resolve(AppLoggingConvention.name.replace(".", File.separator) + ".class")
        def annotation = ClassDesc.of(MaxMessageLength.name)
        ClassFile.of().parse(declaring)
                .findAttribute(Attributes.runtimeInvisibleAnnotations())
                .orElseThrow()
                .annotations()
                .find { it.classSymbol() == annotation }
                .elements()
                .find { it.name().stringValue() == "value" }
                .value()
                .resolvedValue() as int
    }
}
