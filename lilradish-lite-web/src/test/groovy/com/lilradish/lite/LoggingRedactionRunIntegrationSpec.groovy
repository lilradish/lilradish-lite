package com.lilradish.lite

import static com.lilradish.lite.testutil.RedactionSamples.A_VALUE
import static com.lilradish.lite.testutil.RedactionSamples.BUDGET
import static com.lilradish.lite.testutil.RedactionSamples.DECIDED_AT
import static com.lilradish.lite.testutil.RedactionSamples.FINANCE
import static com.lilradish.lite.testutil.RedactionSamples.NIGHTLY_DIGEST
import static com.lilradish.lite.testutil.RedactionSamples.OVERSEER
import static com.lilradish.lite.testutil.RedactionSamples.RUN

import com.lilradish.lite.domain.codestep.CodeError
import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.filling.FilledFields
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.DecisionRecord
import com.lilradish.lite.domain.run.InputRecord
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.ProductionValueId
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.ReviewRecord
import com.lilradish.lite.domain.run.RunInTree
import com.lilradish.lite.domain.run.RunSnapshot
import com.lilradish.lite.domain.run.RunnableWorkflow
import com.lilradish.lite.domain.run.StepInputs
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepSnapshot
import com.lilradish.lite.domain.run.TryLostReason
import com.lilradish.lite.domain.run.TryRecord
import com.lilradish.lite.domain.run.ValueRecord
import com.lilradish.lite.domain.run.fixture.Runs
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

/** What a run, its tries, values and reviews carry renders by identifier, without what anybody said or gave. */
class LoggingRedactionRunIntegrationSpec extends Specification {

    static final ProductionId A_TRY = new ProductionId(UUID.fromString("44440000-0000-4000-8000-000000000007"))

    static final List<String> PROVENANCE = ["values=", "reviews=", "inputs="]

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
        def rendered = new DecisionRecord(A_VALUE, ReviewOutcome.REFUSED, "the total does not add up", null, null).toString()

        then:
        !rendered.contains("does not add up")
        !rendered.contains("why=")

        and:
        rendered.startsWith('DecisionRecord(')
        rendered.contains('value=ProductionValueId(value=44440000-0000-4000-8000-000000000008)')
        rendered.contains('outcome=REFUSED')
        rendered == 'DecisionRecord(value=ProductionValueId(value=44440000-0000-4000-8000-000000000008), outcome=REFUSED, lengthUnits=null, excessUnits=null)'
    }

    def "a try renders what it is and who made it, and drops why they said it and what code said or gave back"() {
        given:
        def answered = new TryRecord(A_TRY, 2, StepProducer.PERSON, null, DECIDED_AT, DECIDED_AT, OVERSEER,
                "the buyer's address, not the shipper's", null, null, false, null, false, false, null, [], [], [], [], [])
        def lost = new TryRecord(A_TRY, 1, StepProducer.MODEL, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.DID_NOT_FIT, null, false, null, false, false, null, [], [], [], [], [])
        def codeSaid = new TryRecord(A_TRY, 1, StepProducer.CODE, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.ERRORED, null, true, "invoice 4471 was refused upstream", true, false, null, [], [], [], [], [])
        def codeReturned = new TryRecord(A_TRY, 1, StepProducer.CODE, null, DECIDED_AT, DECIDED_AT, null, null,
                TryLostReason.ERRORED, new CodeError.Fault(CodeErrorReason.TOO_LONG, [new FieldName("total")], null, null),
                false, null, false, true, '{"total":"1234.50"}', [], [], [], [], [])

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
                        new CodeError.UndeclaredMember("invoice_4471", false), null), false, null,
                false, false, null, [], [], [], [], [])

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
        def bound = new StepInputs.BindingRecord(binding, new JsonValue.JsonString("invoice 4471"), A_VALUE, [A_VALUE], [])

        when:
        def rendered = bound.toString()

        then:
        rendered == 'StepInputs$BindingRecord(source=ProductionValueId(value=44440000-0000-4000-8000-000000000008))'
    }

    /** Log only the identifying head: populated payloads exceed the message budget and cause tail truncation. */
    def "a fully reviewed try renders past the budget, keeping its identifiers and losing its tail"() {
        given: "every component one try can carry at once"
        def second = new ProductionValueId(UUID.fromString("44440000-0000-4000-8000-00000000000b"))
        def review = new ReviewRecord(OVERSEER, DECIDED_AT, false, null, null, [
                new DecisionRecord(A_VALUE, ReviewOutcome.ASSURED, null, null, null),
                new DecisionRecord(second, ReviewOutcome.REFUSED, "the total does not add up", null, null)])
        def aTry = new TryRecord(A_TRY, 2, StepProducer.PERSON, OVERSEER, DECIDED_AT, DECIDED_AT, NIGHTLY_DIGEST,
                "the buyer's address", null, null, false, null, false, false, null,
                [summary(A_VALUE, "invoice 4471"), summary(second, "invoice 4472")], [review],
                [new InputRecord(UUID.fromString("44440000-0000-4000-8000-00000000000a"), A_VALUE, [])], [], [])

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
}
