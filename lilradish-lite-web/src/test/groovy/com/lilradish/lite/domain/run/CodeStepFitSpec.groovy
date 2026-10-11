package com.lilradish.lite.domain.run

import static com.lilradish.lite.domain.run.fixture.Runs.GROUP
import static com.lilradish.lite.domain.run.fixture.Runs.RUN
import static com.lilradish.lite.domain.run.fixture.Runs.VERSION
import static com.lilradish.lite.domain.run.fixture.Runs.code
import static com.lilradish.lite.domain.run.fixture.Runs.codeStep
import static com.lilradish.lite.domain.run.fixture.Runs.json
import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.questionTaking
import static com.lilradish.lite.domain.run.fixture.Runs.searchQuestion
import static com.lilradish.lite.domain.run.fixture.Runs.searchStep
import static com.lilradish.lite.domain.run.fixture.Runs.standing
import static com.lilradish.lite.domain.run.fixture.Runs.stepId
import static com.lilradish.lite.domain.run.fixture.Runs.takes
import static com.lilradish.lite.domain.run.fixture.Runs.text
import static com.lilradish.lite.domain.run.fixture.Runs.tidy
import static com.lilradish.lite.domain.run.fixture.Runs.unstarted
import static com.lilradish.lite.domain.run.fixture.Runs.webSearch

import com.lilradish.lite.domain.codestep.CodeError
import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.codestep.CodeStepDeclaration
import com.lilradish.lite.domain.codestep.ReleasedCodeStep
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.fixture.Runs
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import spock.lang.Specification


class CodeStepFitSpec extends Specification {

    static final EntryVersionId TIERS = new EntryVersionId(key(950))

    static final Binding NOTE_FROM_ANSWER = new Binding(key(801), Pointer.parse("note"),
            new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("answer")))

    static final Binding RECEIPT_READ = new Binding(key(802), Pointer.parse("receipt"),
            new BindingSource.StepOutput(stepId(2).value(), Pointer.parse("receipt")))

    static final Binding RESULT_OUT = new Binding(key(804), Pointer.parse("result"),
            new BindingSource.StepOutput(stepId(2).value(), Pointer.parse("receipt")))

    static final Binding NOTE_FROM_TITLE = new Binding(key(806), Pointer.parse("note"),
            new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("title")))

    static final Binding RECEIPT_ASKED =
            new Binding(key(807), Pointer.parse("receipt"),
                    new BindingSource.StepOutput(stepId(2).value(), Pointer.parse("receipt")))

    def "code whose release still declares all its version binds into and out of it fits"() {
        given:
        def run = running(released([mustBe("note", 200), text("extra")], [standing("receipt"), standing("more")]))

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == Optional.empty()
        CodeStepFit.givesOtherwise(run, run.steps()[1]) == Optional.empty()
    }

    def "what is bound into the step is judged against what the release takes now, the first thing wrong given with its field"() {
        given:
        def run = running(released(taken, [standing("receipt")]), into)

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == Optional.of(fault(CodeErrorReason.TAKES_OTHERWISE, field))

        where:
        taken                                                   | into                                 || field
        [mustBe("memo", 200)]                                   | [NOTE_FROM_ANSWER]                   || "note"
        [mustBe("note", 100)]                                   | [NOTE_FROM_ANSWER]                   || "note"
        [mustBe("note", 200), mustBe("urgent", 10)]             | [NOTE_FROM_ANSWER]                   || "urgent"
        [mustBe("note", 200)]                                   | [NOTE_FROM_ANSWER, NOTE_FROM_ANSWER] || "note"
        [mustBe("note", 3)]                                     | [written("note", "abcd")]            || "note"
        [mustBe("note", 3)]                                     | [written("note", null)]              || "note"
        [nested("parts", [mustBe("sku", 8)])]                   | [written("parts.colour", "red")]     || "parts.colour"
        [nested("parts", [mustBe("sku", 8), mustBe("bin", 8)])] | [written("parts.sku", "A1")]         || "parts.bin"
    }

    def "a constant that still fits, and a field it may be given that nothing binds, fit"() {
        given:
        def run = running(released([mustBe("note", 3), text("extra")], [standing("receipt")]), [written("note", "abc")])

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == Optional.empty()
    }

    def "what may be missing from where it is read may not be bound where the release says it must be given"() {
        given:
        def fromTicket = new Binding(key(803), Pointer.parse("note"), new BindingSource.WorkflowInput(Pointer.parse("ticket")))
        def run = running(released([mustBe("note", 200)], [standing("receipt")]), [fromTicket], [RECEIPT_READ],
                [text("ticket")])

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == Optional.of(fault(CodeErrorReason.TAKES_OTHERWISE, "note"))
    }

    def "a search source is judged against its pinned question even when the current way differs or is absent"() {
        given:
        def into = unstarted(codeStep(2, 1, released([mustBe("note", 200)], [standing("receipt")]), code(),
                [NOTE_FROM_TITLE]))
        def question = searchQuestion(1, webSearch(questionLimit))
        def run = Runs.run([unstarted(searchStep(1, releaseLimit == null ? null : webSearch(releaseLimit), question)), into])

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == (fits ? Optional.empty() :
                Optional.of(fault(CodeErrorReason.TAKES_OTHERWISE, "note")))

        where:
        releaseLimit | questionLimit || fits
        200          | 200           || true
        201          | 200           || true
        201          | 201           || false
        null         | 200           || true
        null         | 201           || false
    }

    def "what a later step or the workflow reads of the step is judged against what the release gives back now, naming the reader"() {
        given:
        def run = running(released([mustBe("note", 200)], givenBack), [NOTE_FROM_ANSWER], readBy, [], outputs)
        def expected = new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE, [new FieldName("receipt")], null, reader)

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == Optional.of(expected)
        CodeStepFit.givesOtherwise(run, run.steps()[1]) == Optional.of(expected)

        where:
        givenBack               | readBy         | outputs      || reader
        [standing("ticket_id")] | [RECEIPT_READ] | []           || new CodeError.StepReads(stepId(3).value())
        [yesNo("receipt")]      | [RECEIPT_READ] | []           || new CodeError.StepReads(stepId(3).value())
        [standing("ticket_id")] | []             | [RESULT_OUT] || new CodeError.OutputReads([new FieldName("result")])
    }

    def "what a search's question takes must still fit its declaration, naming the search reading it"() {
        given:
        def run = searchedFrom(released([mustBe("note", 200)], givenBack))

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == expected
        CodeStepFit.givesOtherwise(run, run.steps()[1]) == expected

        where:
        givenBack                             || expected
        [standing("ticket_id")]               || Optional.of(new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE,
                [new FieldName("receipt")], null, new CodeError.StepReads(stepId(3).value())))
        [standing("receipt"), yesNo("more")]  || Optional.empty()
        [yesNo("receipt"), standing("other")] || Optional.of(new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE,
                [new FieldName("receipt")], null, new CodeError.StepReads(stepId(3).value())))
    }

    def "a current code output must fit the search question's input shape and mandatory demand"() {
        given:
        def receipt = new Field(new FieldName('receipt'), null, null, shape, new HowMany.One(),
                new com.lilradish.lite.domain.declaration.Demand.Stands(mustBeGiven,
                        FieldStanding.ALWAYS, null))
        def taken = new Field(new FieldName('receipt'), null, null, new FieldShape.Text(200), new HowMany.One(),
                new Demand.Given(requiredInput))
        def run = Runs.run([
                unstarted(question(1, person(), 1)),
                unstarted(codeStep(2, 1, released([mustBe('note', 200)], [receipt]), code(), [NOTE_FROM_ANSWER])),
                unstarted(searchStep(3, webSearch(), searchQuestion(3, webSearch(), takes([taken])), [RECEIPT_ASKED])),
        ])
        def expected = refused ? Optional.of(new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE,
                [new FieldName('receipt')], null, new CodeError.StepReads(stepId(3).value()))) : Optional.empty()

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == expected
        CodeStepFit.givesOtherwise(run, run.steps()[1]) == expected

        where:
        shape                    | mustBeGiven | requiredInput || refused
        new FieldShape.Text(200) | true        | true    || false
        new FieldShape.Text(200) | false       | true    || true
        new FieldShape.Text(200) | false       | false   || false
        new FieldShape.File([new com.lilradish.lite.domain.file.FileExtension('.txt')], null) | true | false || true
        new FieldShape.File([new com.lilradish.lite.domain.file.FileExtension('.txt')], null) | true | true  || true
    }

    def "an unstarted workflow's required whole or nested value does not fill a different mandatory search input"() {
        given:
        def receipt = new Field(new FieldName('receipt'), null, null, new FieldShape.Text(200), new HowMany.One(),
                new Demand.Stands(false, FieldStanding.ALWAYS, null))
        def note = new Field(new FieldName('note'), null, null, new FieldShape.Text(200), new HowMany.One(),
                new Demand.Given(nestedPath || required))
        def fields = nestedPath ? [new Field(new FieldName('bundle'), null, null, new FieldShape.Nested([note]),
                new HowMany.One(), new Demand.Given(required))] : [note]
        def declared = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), fields)
        def workflow = new PlannedStep(stepId(3), 3, Runs.workflowStep(3).name(), Runs.workflowStep(3).label(),
                new StepRuns.Workflow(Runs.pinned(3), declared), null, null, null, [])
        def other = new Binding(key(923), Pointer.parse('note'),
                new BindingSource.StepOutput(stepId(3).value(), Pointer.parse(nestedPath ? 'bundle.note' : 'note')))
        def run = Runs.run([
                unstarted(question(1, person(), 1)),
                unstarted(codeStep(2, 1, released([mustBe('note', 200)], [receipt]), code(), [NOTE_FROM_ANSWER])),
                unstarted(workflow),
                unstarted(searchStep(4, webSearch(), searchQuestion(4, webSearch(),
                        takes([mustBe('receipt', 200), text('note')])), [RECEIPT_ASKED, other])),
        ])
        def expected = Optional.of(new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE,
                [new FieldName('receipt')], null, new CodeError.StepReads(stepId(4).value())))

        expect:
        run.steps()[2].beneath() == null
        CodeStepFit.misfit(run, run.steps()[1]) == expected
        CodeStepFit.givesOtherwise(run, run.steps()[1]) == expected

        where:
        [nestedPath, required] << [[true, false], [true, false]].combinations()
    }

    def "a temporarily absent search way's other outputs cannot supply a mandatory question input"() {
        given:
        def receipt = new Field(new FieldName('receipt'), null, null, new FieldShape.Text(200), new HowMany.One(),
                new Demand.Stands(false, FieldStanding.ALWAYS, null))
        def other = new Binding(key(924), Pointer.parse('note'),
                new BindingSource.StepOutput(stepId(3).value(), Pointer.parse(field)))
        def run = Runs.run([
                unstarted(question(1, person(), 1)),
                unstarted(codeStep(2, 1, released([mustBe('note', 200)], [receipt]), code(), [NOTE_FROM_ANSWER])),
                unstarted(searchStep(3, null)),
                unstarted(searchStep(4, webSearch(), searchQuestion(4, webSearch(),
                        takes([mustBe('receipt', 200), text('note')])), [RECEIPT_ASKED, other])),
        ])
        def expected = Optional.of(new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE,
                [new FieldName('receipt')], null, new CodeError.StepReads(stepId(4).value())))

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == expected
        CodeStepFit.givesOtherwise(run, run.steps()[1]) == expected

        where:
        field << ['title', 'body', 'references', 'other']
    }

    def "a term of a list the group does not hold is given with the field it is in, in what it takes before what it gives"() {
        given:
        def declared = new CodeStepDeclaration(takes(taken), givesBack(givenBack), false)
        def run = running(new ReleasedCodeStep(declared, [:]), [])

        expect:
        CodeStepFit.misfit(run, run.steps()[1]) == Optional.of(fault(reason, field))

        where:
        taken                             | givenBack                                    || reason                                | field
        [tier("tier")]                    | [standing("receipt")]                        || CodeErrorReason.TAKES_A_LIST_NOT_HERE | "tier"
        [nested("line", [tier("grade")])] | [standing("receipt")]                        || CodeErrorReason.TAKES_A_LIST_NOT_HERE | "line.grade"
        []                                | [standing("receipt"), standingTier("level")] || CodeErrorReason.GIVES_A_LIST_NOT_HERE | "level"
        [tier("tier")]                    | [standing("receipt"), standingTier("level")] || CodeErrorReason.TAKES_A_LIST_NOT_HERE | "tier"
    }

    def "a term of a list the group does not hold in what it gives back is what a person answering it would give otherwise"() {
        given:
        def declared = new CodeStepDeclaration(takes([]), givesBack([standing("receipt"), standingTier("level")]), false)
        def run = running(new ReleasedCodeStep(declared, [:]), [])

        expect:
        CodeStepFit.givesOtherwise(run, run.steps()[1]) ==
                Optional.of(fault(CodeErrorReason.GIVES_A_LIST_NOT_HERE, "level"))
    }


    def "what a person would give back matches where only what goes into the step no longer does"() {
        given:
        def run = running(release)

        expect:
        CodeStepFit.misfit(run, run.steps()[1]).isPresent()
        CodeStepFit.givesOtherwise(run, run.steps()[1]) == Optional.empty()

        where:
        release << [
                released([mustBe("memo", 200)], [standing("receipt")]),
                new ReleasedCodeStep(new CodeStepDeclaration(takes([tier("tier")]), givesBack([standing("receipt")]),
                        false), [:]),
        ]
    }

    def "only a code step the release holds is judged"() {
        given:
        def step = unstarted(planned)
        def run = Runs.run([step])

        when:
        CodeStepFit."$judged"(run, step)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "CodeStepFit judges only a code step the release holds, not step step_1"

        where:
        [judged, planned] << [["misfit", "givesOtherwise"], [question(1, person(), 1), codeStep(1, 1, null)]]
                .combinations()
    }

    private static RunSnapshot running(ReleasedCodeStep released, List<Binding> into = [NOTE_FROM_ANSWER],
                                       List<Binding> readBy = [RECEIPT_READ], List<Field> workflowTakes = [],
                                       List<Binding> outputs = []) {
        def steps = [
                unstarted(question(1, person(), 1)),
                unstarted(codeStep(2, 1, released, code(), into)),
                unstarted(questionTaking(3, takes([text("receipt")]), readBy)),
        ]
        def workflow = new RunnableWorkflow(
                new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), workflowTakes),
                new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES),
                        outputs.isEmpty() ? [] : [text("result")]),
                [:], outputs, steps*.planned())
        new RunSnapshot(RUN, RUN, GROUP, VERSION, false, null, json([:]) as JsonValue.JsonObject, workflow, steps)
    }


    private static RunSnapshot searchedFrom(ReleasedCodeStep released) {
        Runs.run([
                unstarted(question(1, person(), 1)),
                unstarted(codeStep(2, 1, released, code(), [NOTE_FROM_ANSWER])),
                unstarted(searchStep(3, webSearch(), searchQuestion(3, webSearch(), takes([text('receipt')])),
                        [RECEIPT_ASKED])),
        ])
    }


    private static CodeError.Fault fault(CodeErrorReason reason, String field) {
        new CodeError.Fault(reason, Pointer.parse(field).names(), null, null)
    }

    private static ReleasedCodeStep released(List<Field> taken, List<Field> givenBack) {
        tidy(false, taken, givenBack)
    }

    private static Binding written(String target, String constant) {
        new Binding(key(805), Pointer.parse(target), new BindingSource.Written(json(constant)))
    }

    private static Field mustBe(String name, int longest) {
        new Field(new FieldName(name), null, null, new FieldShape.Text(longest), new HowMany.One(), new Demand.Given(true))
    }

    private static Field nested(String name, List<Field> fields) {
        new Field(new FieldName(name), null, null, new FieldShape.Nested(fields), new HowMany.One(), new Demand.Given(true))
    }

    private static Field tier(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.Term(TIERS), new HowMany.One(), new Demand.Given(true))
    }

    private static Field standingTier(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.Term(TIERS), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
    }

    private static Field yesNo(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.Plain(FieldKind.YES_NO), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
    }

    private static Declaration givesBack(List<Field> fields) {
        new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), fields)
    }
}
