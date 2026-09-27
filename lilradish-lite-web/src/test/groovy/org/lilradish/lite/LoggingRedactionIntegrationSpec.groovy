package org.lilradish.lite

import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.nio.file.Path
import java.time.Instant
import org.libprunus.core.log.annotation.MaxMessageLength
import org.lilradish.lite.app.run.EngineWrites
import org.lilradish.lite.app.run.PendingCall
import org.lilradish.lite.app.run.PendingSend
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
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.DecisionRecord
import org.lilradish.lite.domain.run.InputRecord
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.ProductionValueId
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.ReviewRecord
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunSnapshot
import org.lilradish.lite.domain.run.RunStepFailureId
import org.lilradish.lite.domain.run.RunStepSendAttemptId
import org.lilradish.lite.domain.run.RunnableWorkflow
import org.lilradish.lite.domain.run.StepInputs
import org.lilradish.lite.domain.run.TryLostReason
import org.lilradish.lite.domain.run.TryRecord
import org.lilradish.lite.domain.run.ValueRecord
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.domain.workflow.StepProducer
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import spock.lang.Specification

/**
 * What redacts these is a build-time rewrite driven by the suffix list in {@link
 * AppLoggingConvention}, so a carrier can stop being matched — by a rename, or by being introduced
 * outside the list — with nothing failing. Every carrier is asserted here, from one place, because
 * the failure mode is silence.
 *
 * <p>Both directions matter: a rewrite that emitted nothing at all would pass every "does not
 * contain" on its own.
 *
 * <p>What discriminates is the head, because the record these types would render without the
 * rewrite carries the same values under its simple name and a square bracket. Field order within a
 * layer is not contractual, so each surviving slot is searched for as a whole {@code name=value}
 * pair — pairing the name with the value is what a rendering that fell back to a hash cannot pass.
 */
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

    /** Every slot past the identifying head of a try: what it gave back, how it was reviewed, and what it took. */
    static final List<String> PROVENANCE = ["values=", "reviews=", "inputs="]

    /** Read off the declaration rather than restated, so the budget and the cut cannot drift apart. */
    static final int BUDGET = declaredBudget()

    private static ValueRecord summary(ProductionValueId id, String said) {
        new ValueRecord(id, "summary", new JsonValue.JsonString(said), null, true)
    }

    /** The value a step gave back is what every later step binds, and what a review decides on. */
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
    }

    /** A person's reason for what they said, and what code said went wrong, are both somebody's words. */
    def "a try renders what it is and who made it, and drops why they said it and what code said or gave back"() {
        given:
        def answered = new TryRecord(A_TRY, 2, StepProducer.PERSON, null, DECIDED_AT, DECIDED_AT, OVERSEER,
                "the buyer's address, not the shipper's", null, null, null, false, null, [], [], [], [], [])
        def lost = new TryRecord(A_TRY, 1, StepProducer.CODE, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.DID_NOT_FIT, null, "invoice 4471 was refused upstream", true, '{"total":"1234.50"}', [],
                [], [], [], [])

        when:
        def renderedAnswered = answered.toString()
        def renderedLost = lost.toString()

        then:
        !renderedAnswered.contains("shipper's")
        !renderedLost.contains("invoice 4471")
        !renderedLost.contains("1234.50")

        and: "the slots they were in gone rather than emptied"
        ["explanation=", "lostDetail=", "returned="].every { !renderedAnswered.contains(it) && !renderedLost.contains(it) }

        and: "under a head a record cannot emit, naming the try and who made it"
        renderedAnswered.startsWith('TryRecord(')
        renderedAnswered.contains('id=ProductionId(value=44440000-0000-4000-8000-000000000007)')
        renderedAnswered.contains('number=2')
        renderedAnswered.contains('producer=PERSON')
        renderedAnswered.contains("endedBy=SubjectId(value=" + OVERSEER.value() + ")")

        and: "and how the lost one ended, which is what a line about it is read for"
        renderedLost.contains('lost=DID_NOT_FIT')
        renderedLost.contains('lostDetailCut=true')
    }

    /** The member's name is whatever the code wrote, which may repeat what it took; why and where are this system's. */
    def "a try of code gone wrong for this system's reason renders the reason and where, and drops the member it names"() {
        given:
        def aTry = new TryRecord(A_TRY, 1, StepProducer.CODE, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.ERRORED,
                new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [new FieldName("lines")], "invoice_4471", null), null,
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

    /** Which step or output reads what no longer matches is this system's own, and is what a line about it is read for. */
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

    /** What a run was started with is whatever somebody filled in; the version it runs is only a lookup away. */
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
        def bound = new StepInputs.BindingRecord(binding, new JsonValue.JsonString("invoice 4471"), A_VALUE)

        when:
        def rendered = bound.toString()

        then:
        rendered == 'StepInputs$BindingRecord(source=ProductionValueId(value=44440000-0000-4000-8000-000000000008))'
    }

    /**
     * One entry in the suffix list stands for every id, so a type named outside it is traced by
     * nothing: the fallback is a class name and an identity hash, which names neither the run a
     * line is about nor the person a decision is charged to.
     */
    def "the identifiers a line has to be traced by are readable rather than identity hashes"() {
        expect:
        RUN.toString() == "RunId(value=44440000-0000-4000-8000-000000000001)"
        CLASSIFY.toString() == "StepId(value=classify)"
        OVERSEER.toString() == "SubjectId(value=44440000-0000-4000-8000-000000000003)"

        and: "including what a provider calls a person, which reaches a line about the act it is recorded against"
        new UserId("overseer@example.com").toString() == "UserId(value=overseer@example.com)"
    }

    /** The constants are fields of its own type: a rendering that took statics in would nest itself. */
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

    /**
     * A delegation is two subjects at once — the label that acted and the owner the call is charged
     * to — and neither is a field of its own: both sit inside the registration, one level down. So
     * does where it may be exercised, and a group is named by an identifier alone, so a line naming
     * neither the identifier nor the kind of scope names no place at all.
     */
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

    /** How sure a model was is a type of its own, readable only if it was matched as well. */
    def "how sure a model was renders as the figure rather than an identity hash"() {
        expect:
        new Confidence(90).toString() == 'Confidence(percent=90)'
    }

    /**
     * A try carrying what it gave back, its review and what it took is a screen, not a log line:
     * populated, it renders past the message budget and the runtime cuts the tail, which is where
     * those are. What survives is the head — the identifiers the dropped slots can be looked up by.
     */
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

    /** Both texts carry what a run holds, or what a model is told to do with it. */
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

    /**
     * Not matched itself, so it renders as a record does; what keeps the texts out is the value it
     * holds, which renders their length alone.
     */
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

    /**
     * Not matched either, on purpose: what it would send is held only by the request inside it, and so renders as
     * its length alone.
     */
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

    /** What it reads an answer against names fields and the keys of the values decided, never what they hold. */
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

    /** Handed over before anything of it is written, so the keys it names are all there is to render. */
    def "a send handed over renders the keys it names, and nothing besides"() {
        given:
        def send = new PendingSend(new GroupId(UUID.randomUUID()), new RunId(UUID.randomUUID()),
                new RunId(UUID.randomUUID()), new WorkflowStepId(UUID.randomUUID()), 2, purpose, presser, attemptOn,
                failedOn)

        when:
        def rendered = send.toString()

        then:
        rendered == ("PendingSend[group=GroupId(value=${send.group().value()}), " +
                "root=RunId(value=${send.root().value()}), run=RunId(value=${send.run().value()}), " +
                "step=WorkflowStepId(value=${send.step().value()}), number=2, purpose=${purpose}, ${said}]") as String

        where:
        purpose                  | presser                            | attemptOn                                                                          | failedOn                                                                       || said
        ModelCallPurpose.PRODUCE | null                               | null                                                                               | null                                                                           || "presser=null, attemptOn=null, failedOn=null"
        ModelCallPurpose.PRODUCE | new UserId("overseer@example.com") | new RunStepSendAttemptId(UUID.fromString("00000011-0000-4000-8000-000000000001")) | null                                                                           || "presser=UserId(value=overseer@example.com), attemptOn=RunStepSendAttemptId(value=00000011-0000-4000-8000-000000000001), failedOn=null"
        ModelCallPurpose.PRODUCE | new UserId("overseer@example.com") | null                                                                               | new RunStepFailureId(UUID.fromString("00000012-0000-4000-8000-000000000001")) || "presser=UserId(value=overseer@example.com), attemptOn=null, failedOn=RunStepFailureId(value=00000012-0000-4000-8000-000000000001)"
        ModelCallPurpose.REVIEW  | null                               | null                                                                               | null                                                                           || "presser=null, attemptOn=null, failedOn=null"
        ModelCallPurpose.REVIEW  | new UserId("overseer@example.com") | new RunStepSendAttemptId(UUID.fromString("00000011-0000-4000-8000-000000000002")) | null                                                                           || "presser=UserId(value=overseer@example.com), attemptOn=RunStepSendAttemptId(value=00000011-0000-4000-8000-000000000002), failedOn=null"
        ModelCallPurpose.REVIEW  | new UserId("overseer@example.com") | null                                                                               | new RunStepFailureId(UUID.fromString("00000012-0000-4000-8000-000000000002")) || "presser=UserId(value=overseer@example.com), attemptOn=null, failedOn=RunStepFailureId(value=00000012-0000-4000-8000-000000000002)"
    }

    /** What a try sent, read back to be sent again word for word, holds whatever the run was started with. */
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
    }

    def "an answer that came back renders what was counted and drops the answer"() {
        when:
        def rendered = new CallOutcome.CameBack('{"total":"1234.50"}', 120, 8, true, false).toString()

        then:
        !rendered.contains("1234.50")
        !rendered.contains("answer=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('CallOutcome$CameBack(')
        rendered.contains("sentCount=120")
        rendered.contains("cameBackCount=8")
        rendered.contains("countedByModel=true")
        rendered.contains("cutOff=false")
    }

    /** Foreign text, not yet made safe to show, and free to repeat whatever was sent. */
    def "an error renders that it went wrong and drops what the other side said"() {
        expect:
        new CallOutcome.Errored("invoice 4471 was refused upstream").toString() == 'CallOutcome$Errored()'
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
    }

    /**
     * Not matched itself, so it renders as a record does; what keeps the words out is the turnaway it
     * holds, which renders without them.
     */
    def "a call turned away renders the turnaway that ended it without what the model said"() {
        when:
        def rendered = new CallOutcome.TurnedAway(new TurnAway("quota spent for account 88", true)).toString()

        then:
        !rendered.contains("account 88")
        !rendered.contains("said=")

        and:
        rendered.contains("last=TurnAway(")
        rendered.contains("spentUp=true")
    }

    /** What a step's page reads of a model's lost try: the words its call went wrong in are the model side's. */
    def "why a model lost a try renders which way it did not fit and whether the words were cut, and drops the words"() {
        when:
        def rendered = new StepReading.LostRecord(DidNotFitReason.CUT_OFF, "invoice 4471 was refused upstream", true)
                .toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("wentWrong=")

        and: "under a head only the rewrite emits"
        rendered.startsWith('StepReading$LostRecord(')
        rendered.contains("didNotFit=CUT_OFF")
        rendered.contains("wentWrongCut=true")
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
    }

    /** Both maps are the model's: its values, and how sure it said it was, keyed by what was declared. */
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
    }

    /** What code takes is whatever the run was filled in with; what it declares and the terms offered are a lookup away. */
    def "a call to a code step renders which code step it runs and drops what it takes, declares and is offered"() {
        when:
        def rendered = new CodeCall(new CodeStepName("send_reply"), SpecCodeStep.SEND_REPLY.declaration(), [:],
                new JsonValue.JsonObject([new JsonValue.JsonMember("reply", new JsonValue.JsonString("invoice 4471 was refused"))]))
                .toString()

        then:
        !rendered.contains("invoice 4471")
        ["takes=", "declared=", "lists="].every { !rendered.contains(it) }

        and: "under a head only the rewrite emits, naming the code step"
        rendered.startsWith('CodeCall(')
        rendered.contains('name=CodeStepName(value=send_reply)')
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
    }

    /** Matched by the same suffix as a value code gave back: it holds nothing to hide, and renders what it holds. */
    def "whether a declared field must be given renders as it is, under a head only the rewrite emits"() {
        when:
        def rendered = new Demand.Given(true).toString()

        then:
        rendered == 'Demand$Given(mustBe=true)'
    }

    /** What went wrong is the code's own words, and what it gave back is its own; both may repeat what it took. */
    def "code gone wrong renders whether what it said was cut and drops what it said and what it gave back"() {
        when:
        def rendered = new CodeOutcome.Errored(
                new CodeError.Said(ForeignProse.errorDetail("invoice 4471 was refused upstream " + "a" * 2048)),
                '{"total":"1234.50"}').toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("1234.50")
        ["text=", "returned="].every { !rendered.contains(it) }

        and: "under a head only the rewrite emits, what it said rendered a level down as prose renders"
        rendered.startsWith('CodeOutcome$Errored(')
        rendered.contains('wentWrong=CodeError$Said(detail=ForeignProse(truncated=true))')
    }

    def "code gone wrong for this system's reason renders the reason and where, and drops the member and what it gave back"() {
        when:
        def rendered = new CodeOutcome.Errored(
                new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [new FieldName("lines")], "invoice_4471", null),
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

    /*
     * @MaxMessageLength is CLASS-retained, so reflection cannot see it: the class file is the only
     * place the declared budget can be read back from.
     */
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
