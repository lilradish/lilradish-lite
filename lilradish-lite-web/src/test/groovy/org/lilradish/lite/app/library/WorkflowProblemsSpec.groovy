package org.lilradish.lite.app.library

import static org.lilradish.lite.domain.registry.ContentProblemCode.ASKING_PAST_LARGEST
import static org.lilradish.lite.domain.registry.ContentProblemCode.CASE_GIVES_OTHERWISE
import static org.lilradish.lite.domain.registry.ContentProblemCode.CASE_NOT_OFFERED
import static org.lilradish.lite.domain.registry.ContentProblemCode.CASE_REPEATED
import static org.lilradish.lite.domain.registry.ContentProblemCode.CASE_TARGET_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.CODE_STEP_LIST_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.CODE_STEP_LIST_NOT_YET_IN_SERVICE
import static org.lilradish.lite.domain.registry.ContentProblemCode.CODE_STEP_LIST_RETIRED
import static org.lilradish.lite.domain.registry.ContentProblemCode.CODE_STEP_NOT_DECLARED
import static org.lilradish.lite.domain.registry.ContentProblemCode.CODE_STEP_NOT_PUBLISHED
import static org.lilradish.lite.domain.registry.ContentProblemCode.CODE_STEP_REVIEW_PAST_LARGEST
import static org.lilradish.lite.domain.registry.ContentProblemCode.CODE_STEP_TAKES_AND_GIVES_NOTHING
import static org.lilradish.lite.domain.registry.ContentProblemCode.CONSTANT_CONCEALS
import static org.lilradish.lite.domain.registry.ContentProblemCode.CONSTANT_DOES_NOT_FIT
import static org.lilradish.lite.domain.registry.ContentProblemCode.CONSTANT_TOO_LONG
import static org.lilradish.lite.domain.registry.ContentProblemCode.DISCRIMINATOR_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.DISCRIMINATOR_NOT_TERM
import static org.lilradish.lite.domain.registry.ContentProblemCode.HELPER_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.HELPER_MODE_NOT_OFFERED
import static org.lilradish.lite.domain.registry.ContentProblemCode.HELPER_NOT_HELD
import static org.lilradish.lite.domain.registry.ContentProblemCode.INPUT_UNBOUND
import static org.lilradish.lite.domain.registry.ContentProblemCode.LONGEST_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.MODEL_GIVES_NOTHING
import static org.lilradish.lite.domain.registry.ContentProblemCode.NO_CASES
import static org.lilradish.lite.domain.registry.ContentProblemCode.NO_STEPS
import static org.lilradish.lite.domain.registry.ContentProblemCode.OUTPUT_NOT_FROM_STEP
import static org.lilradish.lite.domain.registry.ContentProblemCode.OUTPUT_UNBOUND
import static org.lilradish.lite.domain.registry.ContentProblemCode.PIN_ELSEWHERE
import static org.lilradish.lite.domain.registry.ContentProblemCode.POINTER_INTO_MANY
import static org.lilradish.lite.domain.registry.ContentProblemCode.PRODUCER_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.PRODUCER_MODE_NOT_OFFERED
import static org.lilradish.lite.domain.registry.ContentProblemCode.PRODUCER_NOT_HELD
import static org.lilradish.lite.domain.registry.ContentProblemCode.REVIEWER_MODE_NOT_OFFERED
import static org.lilradish.lite.domain.registry.ContentProblemCode.REVIEWER_NOT_HELD
import static org.lilradish.lite.domain.registry.ContentProblemCode.RUNS_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.SOURCE_DOES_NOT_FIT
import static org.lilradish.lite.domain.registry.ContentProblemCode.SOURCE_MAY_BE_EMPTY
import static org.lilradish.lite.domain.registry.ContentProblemCode.SOURCE_NOT_EARLIER
import static org.lilradish.lite.domain.registry.ContentProblemCode.SOURCE_UNKNOWN
import static org.lilradish.lite.domain.registry.ContentProblemCode.STEP_NAME_REPEATED
import static org.lilradish.lite.domain.registry.ContentProblemCode.TAKES_PAST_LARGEST
import static org.lilradish.lite.domain.registry.ContentProblemCode.TARGET_BOUND_TWICE
import static org.lilradish.lite.domain.registry.ContentProblemCode.TARGET_UNKNOWN
import static org.lilradish.lite.domain.registry.ContentProblemCode.TRIES_MISSING

import org.lilradish.lite.domain.codestep.CodeStepDeclaration
import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.Asking
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.inference.SendMeasure
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.RouteCase
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.OfferedLists
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
import spock.lang.Specification

/**
 * Every place a workflow version's stored content does not hold, worked out in memory from content and what it
 * pins, as submitting and every reading since work it out. A place is named by its key alone, so each is looked
 * for by the key the content was built with.
 */
class WorkflowProblemsSpec extends Specification {

    static final EntryVersionId CATEGORIES = version(0x51)

    static final EntryVersionId CLASSIFY = version(0x61)

    static final EntryVersionId ESCALATE = version(0x62)

    static final EntryVersionId REFUND = version(0x63)

    static final EntryVersionId SILENT = version(0x64)

    static final EntryVersionId ELSEWHERE = version(0x65)

    static final EntryVersionId SILENT_QUESTION = version(0x66)

    static final EntryVersionId SENDER = version(0x67)

    static final EntryVersionId NARROW = version(0x68)

    static final EntryVersionId CHANNELLED = version(0x69)

    static final EntryVersionId LINES = version(0x6a)

    static final EntryVersionId REMARKED = version(0x6b)

    static final ModelChoice GENERAL = new ModelChoice(new ModelName("general"), null)

    static final WorkflowProblems.Resolved RESOLVED = new WorkflowProblems.Resolved([:], [:], [] as Set, [:], [:], [:])

    static final StoredWorkflow EMPTY = new StoredWorkflow(1,
            new StoredDeclarations.Half(new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), []), []),
            new StoredDeclarations.Half(new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), []), []),
            [], [], null, false, false, false, null)

    /** Each step's key by its name, so a binding can point at a step built after it. */
    static final Map<String, UUID> STEP_KEYS = [
            classify: UUID.fromString("0000000c-0000-4000-8000-000000000001"),
            route   : UUID.fromString("0000000c-0000-4000-8000-000000000002"),
            again   : UUID.fromString("0000000c-0000-4000-8000-000000000003"),
            later   : UUID.fromString("0000000c-0000-4000-8000-000000000004"),
            stray   : UUID.fromString("0000000c-0000-4000-8000-000000000005"),
            send    : UUID.fromString("0000000c-0000-4000-8000-000000000006"),
            escalate: UUID.fromString("0000000c-0000-4000-8000-000000000007"),
            silent  : UUID.fromString("0000000c-0000-4000-8000-000000000008"),
            confirm : UUID.fromString("0000000c-0000-4000-8000-000000000009"),
            narrow  : UUID.fromString("0000000c-0000-4000-8000-00000000000a"),
            after   : UUID.fromString("0000000c-0000-4000-8000-00000000000b")]

    long keysMade = 0

    /* What the group's versions declare: a question reading a complaint into a category, a summary and more, and two
     * workflows each taking a complaint and giving back nothing, or one note. */
    StoredDeclarations.Half classifyTakes = half(DeclarationSide.TAKES, [taken("complaint", text(4000))])

    StoredDeclarations.Half classifyGives = half(DeclarationSide.GIVES, [
            taken("category", new FieldShape.Term(CATEGORIES)),
            taken("summary", text(500)),
            taken("labels", text(20), new HowMany.Many(3)),
            taken("lines", new FieldShape.Nested([taken("sku", text(10))]), new HowMany.Many(5)),
            taken("tags", new FieldShape.Term(CATEGORIES), new HowMany.Many(3)),
            optional("hint", text(500))])

    StoredDeclarations.Half escalateTakes = half(DeclarationSide.TAKES, [taken("complaint", text(4000))])

    StoredDeclarations.Half nothing = half(DeclarationSide.GIVES, [])

    StoredDeclarations.Half refundGives = half(DeclarationSide.GIVES, [taken("note", text(100))])

    Map<EntryVersionId, StoredDeclarations.Halves> pinned = [
            (CLASSIFY): new StoredDeclarations.Halves(classifyTakes, classifyGives),
            (ESCALATE): new StoredDeclarations.Halves(escalateTakes, nothing),
            (REFUND)  : new StoredDeclarations.Halves(escalateTakes, refundGives),
            (SILENT)  : new StoredDeclarations.Halves(half(DeclarationSide.TAKES, []), nothing)]

    /** What a model is told of each question a step asks one of; none unless a feature measures one. */
    Map<EntryVersionId, Asking> asked = [:]

    /** The code steps the group may name, and what the release declares of those it holds: one of each. */
    Set<String> nameable = ["send_reply"] as Set

    Map<String, StoredDeclarations.Halves> released = [
            send_reply: StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration())]

    /** Why a list of a code step the group may name is not in service; none unless a feature says so. */
    Map<String, List<ContentProblemCode>> listsUnserved = [:]

    /* The workflow's own: it takes a complaint, a channel, a remark that may be left out and a sender who may be
     * unnamed but always has an address, and gives back a summary. */
    StoredDeclarations.Half takes = half(DeclarationSide.TAKES, [
            taken("complaint", text(4000)),
            taken("channel", text(32)),
            optional("remark", text(4000)),
            optional("sender", new FieldShape.Nested([taken("address", text(4000))]))])

    StoredDeclarations.Half gives = half(DeclarationSide.GIVES, [taken("summary", text(500))])

    StoredWorkflow.Step classify = step("classify", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, CLASSIFY),
            new Producer.Model(GENERAL, true), 3, null,
            [binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("complaint")))])

    StoredWorkflow.Step route = step("route", routing(
            binding(null, fromStep(classify, "category")),
            nothing,
            [routeCase("Billing", ESCALATE, [binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("complaint")))]),
             routeCase(null, ESCALATE, [binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("complaint")))])]),
            null, null, null, [])

    Binding output = binding("summary", fromStep(classify, "summary"))

    def "a workflow whose every part holds names nothing"() {
        expect:
        problems(workflow([classify, route], [output])) == []
    }

    /** A field's problems are the declaration's own, named where the field is in the part holding it. */
    def "what the workflow takes and gives back is held to what any declaration is, each field by its key"() {
        given:
        def unlimited = half(DeclarationSide.TAKES, [taken("complaint", text(null))])
        def ungiven = half(DeclarationSide.GIVES, [taken("summary", text(null))])

        when:
        def found = WorkflowProblems.of(stored(unlimited, ungiven, [classify], [output]), resolved(), DeployedModels.HELD)

        then:
        found == [problem(LONGEST_MISSING, new ContentPlace.AtField(ContentPart.TAKES, unlimited.keyAt([0]))),
                  problem(LONGEST_MISSING, new ContentPlace.AtField(ContentPart.GIVES, ungiven.keyAt([0])))]
    }

    /** A run keeps what it was started with as one value, so each field fitting alone is not enough. */
    def "all the workflow takes, written out together past the longest one value may be, is named at what it takes"() {
        given:
        def heavy = half(DeclarationSide.TAKES,
                [taken("complaint", text(4000)), taken("body", text(longest)), taken("note", text(longest))])

        when:
        def found = WorkflowProblems.of(stored(heavy, gives, [classify], [output]), resolved(), DeployedModels.HELD)

        then:
        found == named.collect { problem(it, new ContentPlace.Whole(ContentPart.TAKES)) }

        where:
        longest   || named
        4_192_286 || []
        4_192_287 || [TAKES_PAST_LARGEST]
    }

    /** It could give nothing back and act on nothing, whatever else it says. */
    def "a workflow holding no step is named at its steps, and one holding any is not"() {
        given:
        def givesNothing = half(DeclarationSide.GIVES, [])

        when:
        def found = WorkflowProblems.of(stored(takes, givesNothing, steps == 0 ? [] : [classify], []), resolved(),
                DeployedModels.HELD)

        then:
        found == named.collect { problem(it, new ContentPlace.Whole(ContentPart.STEPS)) }

        where:
        steps || named
        0     || [NO_STEPS]
        1     || []
    }

    def "a step holding the name an earlier step holds is named, and the earlier one is not"() {
        given:
        def again = step("classify", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, CLASSIFY),
                new Producer.Person(), 1, null, classify.bindings())

        expect:
        problems(workflow([classify, again], [output])) == [problem(STEP_NAME_REPEATED, at(again))]
    }

    /** Nothing chosen to run is one problem, and nothing it could have been bound to is asked after. */
    def "a step running nothing yet, or a code step naming none, is said to run nothing"() {
        given:
        def unchosen = step("later", runs, producer, null, null, [])

        expect:
        problems(workflow([classify, unchosen], [output])) == expected.collect { problem(it, at(unchosen)) }

        where:
        runs                                  | producer            || expected
        new StoredWorkflow.Runs.Unchosen()    | null                || [RUNS_MISSING]
        new StoredWorkflow.Runs.Code(null)    | new Producer.Code() || [RUNS_MISSING, TRIES_MISSING]
    }

    /** A version of another group's is never read, so nothing it declares is judged; that it is pinned is named. */
    def "a step pinning a version its group does not hold is named, and nothing bound to it is judged"() {
        given:
        def stray = step("stray", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, ELSEWHERE),
                new Producer.Person(), 1, null, [binding("anything", new BindingSource.WorkflowInput(Pointer.parse("complaint")))])

        expect:
        problems(workflow([classify, stray], [output])) == [problem(PIN_ELSEWHERE, at(stray))]
    }

    /**
     * Where the release declares nothing of it, or the group may not name it, what fills it is not judged: the binding
     * to no known input is not named.
     */
    def "a code step is named as not published where its group was not published it, and as undeclared where the release holds none"() {
        given:
        nameable = publishedHere ? ["send_reply"] as Set : [] as Set
        released = held ? released : [:]
        def code = sending(new Producer.Code(), [binding("reply", new BindingSource.WorkflowInput(Pointer.parse("channel")))])

        expect:
        problems(workflow([classify, code], [output])) == expected.collect { problem(it, at(code)) }

        where:
        publishedHere | held  || expected
        true          | true  || []
        true          | false || [CODE_STEP_NOT_DECLARED]
        false         | true  || [CODE_STEP_NOT_PUBLISHED]
        false         | false || [CODE_STEP_NOT_PUBLISHED, CODE_STEP_NOT_DECLARED]
    }

    /** Code produces a code step's values, or a person says it was done; nobody chosen is named. */
    def "a code step names who produces its values, code or a person"() {
        given:
        def code = sending(producer, [binding("reply", new BindingSource.WorkflowInput(Pointer.parse("channel")))])

        expect:
        problems(workflow([classify, code], [output])) == expected.collect { problem(it, at(code)) }

        where:
        producer              || expected
        new Producer.Code()   || []
        new Producer.Person() || []
        null                  || [PRODUCER_MISSING]
    }

    /** The release's declaration is what the step's bindings fill, as a pinned question's is. */
    def "what fills a code step the release holds is judged against what it takes, each input left unbound by its key"() {
        given:
        def fed = bindings.collect { binding("reply", new BindingSource.WorkflowInput(Pointer.parse(it))) }
        def code = sending(new Producer.Code(), fed)

        expect:
        problems(workflow([classify, code], [output])) == expected.collect {
            it == INPUT_UNBOUND
                    ? problem(it, new ContentPlace.AtInput(code.id(), null, released.send_reply.takes().keyAt([0])))
                    : problem(it, bound(fed[0]))
        }

        where:
        bindings      || expected
        ["channel"]   || []
        ["complaint"] || [SOURCE_DOES_NOT_FIT]
        ["remark"]    || [SOURCE_DOES_NOT_FIT, SOURCE_MAY_BE_EMPTY]
        []            || [INPUT_UNBOUND]
    }

    /** Nothing it did could be seen: nothing is sent to it, and nothing it gives back could be read. */
    def "a code step taking nothing and giving nothing back is named, and one taking or giving anything is not"() {
        given:
        released.quiet = StoredDeclarations.ofCodeStep("quiet",
                new SpecCodeStep("quiet", takenFields, givenFields, false).declaration())
        nameable = ["quiet"] as Set
        def code = step("send", new StoredWorkflow.Runs.Code("quiet"), new Producer.Code(), 1, null,
                takenFields.collect { binding(it.name().value(), new BindingSource.WorkflowInput(Pointer.parse("channel"))) })

        expect:
        problems(workflow([classify, code], [output])) == expected.collect { problem(it, at(code)) }

        where:
        takenFields                                            | givenFields                                               || expected
        []                                                     | []                                                        || [CODE_STEP_TAKES_AND_GIVES_NOTHING]
        [SpecCodeStep.given("note", new FieldShape.Text(32))] | []                                                        || []
        []                                                     | [SpecCodeStep.standing("note", new FieldShape.Text(32))] || []
    }

    /**
     * One the group may not name, taking a term from another group's list among them, is as though unknown: what
     * the release declares of it is never read here, however it fits.
     */
    def "a code step the release holds and the group may not name is named as not published, and nothing bound to it or read from it is judged"() {
        given:
        nameable = mayName ? ["send_reply"] as Set : [] as Set
        def code = sending(new Producer.Code(), [binding("anything", new BindingSource.WorkflowInput(Pointer.parse("channel")))])
        def read = binding(null, fromStep(code, "receipt"))
        def routed = step("route", routing(read, nothing, [routeCase("Billing", ESCALATE, escalating())]),
                null, null, null, [])

        expect:
        problems(workflow([classify, code, routed], [output])) == (mayName
                ? [problem(TARGET_UNKNOWN, bound(code.bindings()[0])),
                   problem(INPUT_UNBOUND, new ContentPlace.AtInput(code.id(), null, released.send_reply.takes().keyAt([0]))),
                   problem(DISCRIMINATOR_NOT_TERM, bound(read))]
                : [problem(CODE_STEP_NOT_PUBLISHED, at(code)), problem(SOURCE_UNKNOWN, bound(read))])

        where:
        mayName << [true, false]
    }

    /** Every reason one of its lists is not in service is named, and what it declares is still held to. */
    def "a code step the group may name whose list is not in service is named by why, and what fills it is still judged"() {
        given:
        listsUnserved = [send_reply: reasons]
        def stray = binding("anything", new BindingSource.WorkflowInput(Pointer.parse("channel")))
        def code = sending(new Producer.Code(),
                [binding("reply", new BindingSource.WorkflowInput(Pointer.parse("channel"))), stray])

        expect:
        problems(workflow([classify, code], [output])) ==
                reasons.collect { problem(it, at(code)) } + [problem(TARGET_UNKNOWN, bound(stray))]

        where:
        reasons << [[CODE_STEP_LIST_MISSING], [CODE_STEP_LIST_NOT_YET_IN_SERVICE], [CODE_STEP_LIST_RETIRED],
                    [CODE_STEP_LIST_MISSING, CODE_STEP_LIST_RETIRED], []]
    }

    /** What a code step gives back is read as any step's is, by the steps after it. */
    def "a step reading what a code step before it gives back is held to what it fills"() {
        given:
        def code = sending(new Producer.Code(), [binding("reply", new BindingSource.WorkflowInput(Pointer.parse("channel")))])
        pinned[NARROW] = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [taken("complaint", filled)]), classifyGives)
        def fed = binding("complaint", fromStep(code, "receipt"))
        def after = step("after", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, NARROW), new Producer.Person(), 1,
                null, [fed])

        expect:
        problems(workflow([classify, code, after], [output])) ==
                (fits ? [] : [problem(SOURCE_DOES_NOT_FIT, bound(fed))])

        where:
        filled   || fits
        text(64) || true
        text(63) || false
    }

    def "a question's step names who produces its values, and how many tries it may make"() {
        given:
        def asked = step("classify", classify.runs(), producer, tries, null, classify.bindings())

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])) == expected.collect { problem(it, at(asked)) }

        where:
        producer               | tries || expected
        null                   | 3     || [PRODUCER_MISSING]
        new Producer.Person()  | null  || [TRIES_MISSING]
        null                   | null  || [PRODUCER_MISSING, TRIES_MISSING]
        new Producer.Person()  | 1     || []
    }

    /** What the deployment holds decides it, as it stands now: a model it holds, and a mode that model offers. */
    def "a model producing or reviewing is one the deployment holds, in a mode that model offers"() {
        given:
        def asked = step("classify", classify.runs(), new Producer.Model(producing, false), 3, reviewing,
                classify.bindings())

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])) == expected.collect { problem(it, at(asked)) }

        where:
        producing                        | reviewing                        || expected
        choice("general", "research")    | choice("small", null)            || []
        choice("large", null)            | null                             || [PRODUCER_NOT_HELD]
        choice("small", "research")      | null                             || [PRODUCER_MODE_NOT_OFFERED]
        choice("general", null)          | choice("large", "research")      || [REVIEWER_NOT_HELD]
        choice("general", null)          | choice("general", "fast")        || [REVIEWER_MODE_NOT_OFFERED]
        choice("large", null)            | choice("small", "fast")          || [PRODUCER_NOT_HELD, REVIEWER_MODE_NOT_OFFERED]
    }

    /**
     * The review counted as the measure counts it, told what happened whoever produces, since a person answering in
     * a model's place is shown what was refused: exactly the most one asking may send, and one character past it. A
     * step no model reviews is not measured.
     */
    def "a model reviewing a question is named at its step where the review could send past the most, by how much"() {
        given:
        asked[CLASSIFY] = reviewedPast(over)
        def reviewed = step("classify", classify.runs(), producer, 3, reviewer, classify.bindings())

        expect:
        problems(workflow([reviewed], [binding("summary", fromStep(reviewed, "summary"))])) ==
                excess.collect { new ContentProblem(ASKING_PAST_LARGEST, at(reviewed), it) }

        where:
        producer                           | reviewer | over || excess
        new Producer.Model(GENERAL, true)  | GENERAL  | 0    || []
        new Producer.Model(GENERAL, true)  | GENERAL  | 1    || [1L]
        new Producer.Model(GENERAL, false) | GENERAL  | 0    || []
        new Producer.Model(GENERAL, false) | GENERAL  | 1    || [1L]
        new Producer.Person()              | GENERAL  | 0    || []
        new Producer.Person()              | GENERAL  | 1    || [1L]
        new Producer.Model(GENERAL, true)  | null     | 1    || []
    }

    /**
     * The review counted as the measure counts it where no instruction is asked: exactly the most one asking may
     * send, and one character past it. A step no model reviews is not measured. What the code step takes is bound to
     * a constant, so nothing else is named.
     */
    def "a model reviewing what a code step gives back is named at its step where the review could send past the most, by how much"() {
        given:
        released.loud = StoredDeclarations.ofCodeStep("loud", reviewedCodePast(over))
        nameable = ["loud"] as Set
        def flag = released.loud.takes().declaration().fields()[0].name().value()
        def code = step("send", new StoredWorkflow.Runs.Code("loud"), new Producer.Code(), 1, reviewer,
                [binding(flag, new BindingSource.Written(new JsonValue.JsonBoolean(true)))])

        expect:
        problems(workflow([classify, code], [output])) ==
                excess.collect { new ContentProblem(CODE_STEP_REVIEW_PAST_LARGEST, at(code), it) }

        where:
        reviewer | over || excess
        GENERAL  | 0    || []
        GENERAL  | 1    || [1L]
        null     | 1    || []
    }

    /** Nothing it pins is served, so what it would be sent cannot be told, and it is not measured. */
    def "a code step whose lists are not in service is not measured for a review, whatever it could send"() {
        given:
        released.loud = StoredDeclarations.ofCodeStep("loud", reviewedCodePast(1))
        nameable = ["loud"] as Set
        listsUnserved = [loud: [CODE_STEP_LIST_MISSING]]
        def flag = released.loud.takes().declaration().fields()[0].name().value()
        def code = step("send", new StoredWorkflow.Runs.Code("loud"), new Producer.Code(), 1, GENERAL,
                [binding(flag, new BindingSource.Written(new JsonValue.JsonBoolean(true)))])

        expect:
        problems(workflow([classify, code], [output])) == [problem(CODE_STEP_LIST_MISSING, at(code))]
    }

    /** A workflow run beneath another makes no productions of its own, so it is asked for no tries. */
    def "a step running a workflow is asked for no producer and no tries"() {
        given:
        def beneath = step("escalate", new StoredWorkflow.Runs.Pinned(EntryKind.WORKFLOW, ESCALATE), null, null, null,
                [binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("complaint")))])

        expect:
        problems(workflow([classify, beneath], [output])) == []
    }

    /** A run of a workflow beneath another is there to read, whatever it takes and gives back. */
    def "a step running a workflow that takes nothing and gives nothing back is not named"() {
        given:
        def silent = step("silent", new StoredWorkflow.Runs.Pinned(EntryKind.WORKFLOW, SILENT), null, null, null, [])

        expect:
        problems(workflow([classify, silent], [output])) == []
    }

    /** A person may be asked to do something and say so; a model asked and not listened to did nothing. */
    def "a model asked by a step giving nothing back is named, a person asked the same is not"() {
        given:
        pinned[SILENT_QUESTION] = new StoredDeclarations.Halves(classifyTakes, nothing)
        def asked = step("confirm", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, SILENT_QUESTION), producer, 1, null,
                [binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("complaint")))])

        expect:
        problems(workflow([classify, asked], [output])) == expected.collect { problem(it, at(asked)) }

        where:
        producer                             || expected
        new Producer.Model(GENERAL, false)   || [MODEL_GIVES_NOTHING]
        new Producer.Person()                || []
    }

    def "a route bound to nothing to choose by is named at the route"() {
        given:
        def unchosen = step("route", routing(null, nothing, route.runs().cases()), null, null, null, [])

        expect:
        problems(workflow([classify, unchosen], [output])) == [problem(DISCRIMINATOR_MISSING, at(unchosen))]
    }

    /** Only a list pinned to one field says what values it may take; a constant says nothing of the kind. */
    def "a route choosing by what is not one term is named at what it chooses by"() {
        given:
        def chosenBy = binding(null, source)
        def odd = step("route", routing(chosenBy, nothing, route.runs().cases()), null, null, null, [])

        expect:
        problems(workflow([classify, odd], [output])) == [problem(DISCRIMINATOR_NOT_TERM, bound(chosenBy))]

        where:
        source << [fromKey(STEP_KEYS["classify"], "summary"), fromKey(STEP_KEYS["classify"], "labels"),
                   fromKey(STEP_KEYS["classify"], "tags"), new BindingSource.Written(new JsonValue.JsonString("Billing"))]
    }

    /** What the route chooses by is judged as any source is, and its cases are then held to nothing it offers. */
    def "a route choosing by what it cannot read is named for that alone"() {
        given:
        def chosenBy = binding(null, new BindingSource.WorkflowInput(Pointer.parse("category")))
        def blind = step("route", routing(chosenBy, nothing, [routeCase("Refund", ESCALATE, escalating())]),
                null, null, null, [])

        expect:
        problems(workflow([classify, blind], [output])) == [problem(SOURCE_UNKNOWN, bound(chosenBy))]
    }

    def "a route with no case on a term is named, the fallback counting for none"() {
        given:
        def fallbackOnly = step("route", routing(route.runs().discriminator(), nothing,
                [routeCase(null, ESCALATE, escalating())]), null, null, null, [])

        expect:
        problems(workflow([classify, fallbackOnly], [output])) == [problem(NO_CASES, at(fallbackOnly))]
    }

    def "each case is held to a workflow chosen, a term offered once, and giving back what the route declares"() {
        given:
        def other = routeCase("Delivery", ESCALATE, escalating())
        def judged = routeCase(term, target, escalating())
        def routed = step("route", routing(route.runs().discriminator(), nothing, [other, judged]), null, null,
                null, [])

        expect:
        problems(workflow([classify, routed], [output])) ==
                expected.collect { problem(it, new ContentPlace.AtCase(routed.id(), judged.id())) }

        where:
        term       | target    || expected
        "Billing"  | ESCALATE  || []
        "Billing"  | null      || [CASE_TARGET_MISSING]
        "Delivery" | ESCALATE  || [CASE_REPEATED]
        "Refund"   | ESCALATE  || [CASE_NOT_OFFERED]
        "billing"  | ESCALATE  || [CASE_NOT_OFFERED]
        "Billing"  | REFUND    || [CASE_GIVES_OTHERWISE]
        "Billing"  | ELSEWHERE || [PIN_ELSEWHERE]
    }

    /** What a route must give back each case must give; a case surer of a value than its route is not held to less. */
    def "a case giving back what the route must give as what may be empty is named, and one giving it surely is not"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [new Field(new FieldName("note"), null, null, text(100),
                new HowMany.One(), new Demand.Given(routeMustBe))])
        pinned[REFUND] = new StoredDeclarations.Halves(escalateTakes, half(DeclarationSide.GIVES, [
                new Field(new FieldName("note"), null, null, text(100), new HowMany.One(), new Demand.Given(caseMustBe))]))
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", REFUND, escalating())]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) == (otherwise
                ? [problem(CASE_GIVES_OTHERWISE, new ContentPlace.AtCase(answering.id(), answering.runs().cases()[0].id()))]
                : [])

        where:
        routeMustBe | caseMustBe || otherwise
        true        | false      || true
        true        | true       || false
        false       | true       || false
        false       | false      || false
    }

    /** One step cannot give back two shapes, so what every case leads to gives back exactly what the route does. */
    def "a route that answers holds each case to what it gives back"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("note", text(100))])
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", REFUND, escalating()), routeCase(null, ESCALATE, escalating())]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) ==
                [problem(CASE_GIVES_OTHERWISE, new ContentPlace.AtCase(answering.id(), answering.runs().cases()[1].id()))]
    }

    /** A route's own declaration is held to what any declaration is, each field named by its key among the steps. */
    def "a field a route gives back is held to what any declaration is, named by its key among the steps"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("note", text(null))])
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", ESCALATE, escalating())]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) == [
                problem(LONGEST_MISSING, new ContentPlace.AtField(ContentPart.STEPS, routeGives.keyAt([0]))),
                problem(CASE_GIVES_OTHERWISE, new ContentPlace.AtCase(answering.id(), answering.runs().cases()[0].id()))]
    }

    /** What a route gives back is read as any step's is, by the steps after it. */
    def "a step reading what a route before it gives back is held to what it fills"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("note", text(100))])
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", REFUND, escalating())]), null, null, null, [])
        pinned[NARROW] = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [taken("complaint", filled)]), classifyGives)
        def fed = binding("complaint", fromStep(answering, "note"))
        def after = step("after", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, NARROW), new Producer.Person(), 1,
                null, [fed])

        expect:
        problems(workflow([classify, answering, after], [output])) ==
                (fits ? [] : [problem(SOURCE_DOES_NOT_FIT, bound(fed))])

        where:
        filled    || fits
        text(100) || true
        text(99)  || false
    }

    /** Each case binds its own target's inputs, so what one leaves unbound is named by the case and that input. */
    def "a case leaving an input of what it leads to unbound is named by the case and the input's key"() {
        given:
        def open = routeCase("Billing", ESCALATE, [])
        def routed = step("route", routing(route.runs().discriminator(), nothing, [open]), null, null, null, [])

        expect:
        problems(workflow([classify, routed], [output])) ==
                [problem(INPUT_UNBOUND, new ContentPlace.AtInput(routed.id(), open.id(), escalateTakes.keyAt([0])))]
    }

    def "an input of a step left unbound is named by the step and the input's key"() {
        given:
        def unbound = step("classify", classify.runs(), classify.producer(), 3, null, [])

        expect:
        problems(workflow([unbound], [binding("summary", fromStep(unbound, "summary"))])) ==
                [problem(INPUT_UNBOUND, new ContentPlace.AtInput(unbound.id(), null, classifyTakes.keyAt([0])))]
    }

    /** A field holding fields is bound whole, or field by field; bound in part, what is left is named below it. */
    def "a field holding fields bound in part names each of its fields left unbound, and never itself"() {
        given:
        def sender = taken("sender", new FieldShape.Nested([taken("name", text(100)), taken("address", text(200))]))
        def senderTakes = half(DeclarationSide.TAKES, [sender])
        pinned[SENDER] = new StoredDeclarations.Halves(senderTakes, classifyGives)
        def asked = step("classify", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, SENDER), new Producer.Person(),
                1, null, targets.collect { binding(it, new BindingSource.WorkflowInput(Pointer.parse("channel"))) })

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])) ==
                unbound.collect { problem(INPUT_UNBOUND, new ContentPlace.AtInput(asked.id(), null, senderTakes.keyAt(it))) }

        where:
        targets                             || unbound
        ["sender.name"]                     || [[0, 1]]
        ["sender.address", "sender.name"]   || []
        []                                  || [[0]]
    }

    def "a field holding fields bound whole leaves none of its fields unbound"() {
        given:
        def sender = taken("sender", new FieldShape.Nested([taken("name", text(100)), taken("address", text(200))]))
        pinned[SENDER] = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [sender]), classifyGives)
        def asked = step("classify", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, SENDER), new Producer.Person(),
                1, null, [binding("sender", new BindingSource.Written(new JsonValue.JsonObject([
                        new JsonValue.JsonMember("name", new JsonValue.JsonString("Jane")),
                        new JsonValue.JsonMember("address", new JsonValue.JsonString("Here"))])))])

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])) == []
    }

    /** A field holding many is only ever filled whole, so a binding into it names no place and fills none of it. */
    def "a binding filling a place among many is named where it is, and the field holding many as unbound"() {
        given:
        def lines = half(DeclarationSide.TAKES, [taken("complaint", text(4000)),
                taken("lines", new FieldShape.Nested([taken("sku", text(10))]), new HowMany.Many(5))])
        pinned[LINES] = new StoredDeclarations.Halves(lines, classifyGives)
        def stray = binding("lines.sku", new BindingSource.WorkflowInput(Pointer.parse("channel")))
        def asked = step("classify", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, LINES), new Producer.Person(),
                1, null, classify.bindings() + [stray])

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])) ==
                [problem(POINTER_INTO_MANY, bound(stray)),
                 problem(INPUT_UNBOUND, new ContentPlace.AtInput(asked.id(), null, lines.keyAt([1])))]
    }

    def "a binding filling what its step never declared is named where it is"() {
        given:
        def stray = binding(target, new BindingSource.WorkflowInput(Pointer.parse("complaint")))
        def asked = step("classify", classify.runs(), classify.producer(), 3, null, classify.bindings() + [stray])

        expect:
        problems(workflow([asked, route], [binding("summary", fromStep(asked, "summary"))])) ==
                expected.collect { problem(it, bound(stray)) }

        where:
        target               || expected
        "channel"            || [TARGET_UNKNOWN]
        "complaint.body"     || [TARGET_UNKNOWN, TARGET_BOUND_TWICE]
    }

    /** The one field filled twice is named at the later binding, whichever of the two holds the other. */
    def "a binding filling what an earlier binding fills, or part of it, is named and the earlier one is not"() {
        given:
        def sender = taken("sender", new FieldShape.Nested([taken("name", text(100)), taken("address", text(200))]))
        pinned[SENDER] = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [sender]), classifyGives)
        def earlier = binding(first, new BindingSource.WorkflowInput(Pointer.parse("channel")))
        def later = binding(second, new BindingSource.WorkflowInput(Pointer.parse("channel")))
        def asked = step("classify", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, SENDER), new Producer.Person(),
                1, null, [earlier, later])

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])).findAll {
            it.code() == TARGET_BOUND_TWICE } == (twice ? [problem(TARGET_BOUND_TWICE, bound(later))] : [])

        where:
        first            | second           || twice
        "sender"         | "sender"         || true
        "sender"         | "sender.name"    || true
        "sender.name"    | "sender"         || true
        "sender.name"    | "sender.address" || false
    }

    /** Bindings are local: each points at what this workflow takes or an earlier step gives back, and nothing else. */
    def "a binding pointing at nothing its source declares, or at a step not earlier, is named where it is"() {
        given:
        def stray = binding("complaint", source)
        def asked = step("again", classify.runs(), new Producer.Person(), 1, null, [stray])

        expect:
        problems(workflow([classify, asked], [output])) == [problem(expected, bound(stray))]

        where:
        source                                                   || expected
        new BindingSource.WorkflowInput(Pointer.parse("sender.name")) || SOURCE_UNKNOWN
        fromKey(STEP_KEYS["classify"], "missing")                || SOURCE_UNKNOWN
        fromKey(STEP_KEYS["classify"], "labels.first")           || SOURCE_UNKNOWN
        fromKey(STEP_KEYS["classify"], "lines.sku")              || POINTER_INTO_MANY
        fromKey(STEP_KEYS["again"], "summary")                   || SOURCE_NOT_EARLIER
    }

    def "a binding pointing at a step that comes later is named, however the step is written"() {
        given:
        def forward = binding("complaint", fromKey(STEP_KEYS["later"], "summary"))
        def early = step("classify", classify.runs(), classify.producer(), 3, null, [forward])
        def later = step("later", classify.runs(), new Producer.Person(), 1, null, classify.bindings())

        expect:
        problems(workflow([early, later], [binding("summary", fromStep(later, "summary"))])) ==
                [problem(SOURCE_NOT_EARLIER, bound(forward))]
    }

    def "a binding pointing at a step whose declaration is not known is said to point at nothing it declares"() {
        given:
        released = [:]
        def code = step("send", new StoredWorkflow.Runs.Code("send_reply"), new Producer.Code(), 1, null, [])
        def fed = binding("complaint", fromStep(code, "receipt"))
        def asked = step("again", classify.runs(), new Producer.Person(), 1, null, [fed])

        expect:
        problems(workflow([classify, code, asked], [output])) ==
                [problem(CODE_STEP_NOT_DECLARED, at(code)), problem(SOURCE_UNKNOWN, bound(fed))]
    }

    /** Nothing longer or more numerous may be declared where it is read than where it is bound, nor one turn many. */
    def "a binding reading what does not fit what it fills is named where it is"() {
        given:
        pinned[NARROW] = new StoredDeclarations.Halves(
                half(DeclarationSide.TAKES, [taken("complaint", filled)]), classifyGives)
        def fed = binding("complaint", fromStep(classify, pointer))
        def narrow = step("narrow", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, NARROW), new Producer.Person(),
                1, null, [fed])

        expect:
        problems(workflow([classify, narrow], [output])) == (fits ? [] : [problem(SOURCE_DOES_NOT_FIT, bound(fed))])

        where:
        pointer    | filled                                     || fits
        "summary"  | text(500)                                  || true
        "summary"  | text(499)                                  || false
        "category" | new FieldShape.Term(CATEGORIES)            || true
        "category" | text(500)                                  || false
        "labels"   | text(20)                                   || false
    }

    /** An input that must be given can arrive empty from a source that need not be, or from one held in one that need not. */
    def "an input that must be given, bound to what may be empty, is named where it is bound"() {
        given:
        pinned[REMARKED] = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [taken("complaint", text(4000)),
                optional("aside", text(4000))]), classifyGives)
        def fed = binding(target, source)
        def other = target == "aside"
                ? binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("complaint")))
                : binding("aside", new BindingSource.WorkflowInput(Pointer.parse("remark")))
        def asked = step("again", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, REMARKED), new Producer.Person(),
                1, null, [fed, other])

        expect:
        problems(workflow([classify, asked], [output])) == (mayBeEmpty ? [problem(SOURCE_MAY_BE_EMPTY, bound(fed))] : [])

        where:
        target      | source                                                            || mayBeEmpty
        "complaint" | new BindingSource.WorkflowInput(Pointer.parse("complaint"))        || false
        "complaint" | new BindingSource.WorkflowInput(Pointer.parse("remark"))           || true
        "complaint" | new BindingSource.WorkflowInput(Pointer.parse("sender.address"))   || true
        "complaint" | fromKey(STEP_KEYS["classify"], "summary")                         || false
        "complaint" | fromKey(STEP_KEYS["classify"], "hint")                            || true
        "aside"     | new BindingSource.WorkflowInput(Pointer.parse("remark"))           || false
    }

    def "an input a case leads to that must be given, bound to what may be empty, is named where it is bound"() {
        given:
        def fed = binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("remark")))
        def routed = step("route", routing(route.runs().discriminator(), nothing, [routeCase("Billing", ESCALATE, [fed])]),
                null, null, null, [])

        expect:
        problems(workflow([classify, routed], [output])) == [problem(SOURCE_MAY_BE_EMPTY, bound(fed))]
    }

    /** Bound whole, a field holding fields brings every field within it, each as given as its source declares. */
    def "an input holding fields bound whole is named where a field within it must be given and its source's need not be"() {
        given:
        takes = half(DeclarationSide.TAKES, takes.declaration().fields() + [contact(sourceMustBe)])
        pinned[REMARKED] = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [contact(targetMustBe)]), classifyGives)
        def fed = binding("contact", new BindingSource.WorkflowInput(Pointer.parse("contact")))
        def asked = step("again", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, REMARKED), new Producer.Person(),
                1, null, [fed])

        expect:
        problems(workflow([classify, asked], [output])) == (mayBeEmpty ? [problem(SOURCE_MAY_BE_EMPTY, bound(fed))] : [])

        where:
        sourceMustBe | targetMustBe || mayBeEmpty
        false        | true         || true
        true         | true         || false
        true         | false        || false
        false        | false        || false
    }

    def "a value given back that must be given, bound to what may be empty, is named where it is bound"() {
        given:
        def fed = binding("summary", fromStep(classify, "hint"))

        expect:
        problems(workflow([classify, route], [fed])) ==
                [problem(SOURCE_MAY_BE_EMPTY, new ContentPlace.AtBinding(ContentPart.GIVES, fed.id()))]
    }

    def "a constant is held to what it fills and to showing what it holds"() {
        given:
        def written = binding("channel", new BindingSource.Written(constant))
        def asked = step("classify", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, CHANNELLED),
                new Producer.Person(), 1, null, classify.bindings() + [written])
        pinned[CHANNELLED] = new StoredDeclarations.Halves(
                half(DeclarationSide.TAKES, [taken("complaint", text(4000)), taken("channel", text(9000))]), classifyGives)

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])) ==
                expected.collect { problem(it, bound(written)) }

        where:
        constant                                                            || expected
        new JsonValue.JsonString("email")                                   || []
        new JsonValue.JsonString("e" + Character.toString(0x202E) + "mail") || [CONSTANT_CONCEALS]
        new JsonValue.JsonString("x" * 8192)                                || []
        new JsonValue.JsonNumber(3G)                                        || [CONSTANT_DOES_NOT_FIT]
        new JsonValue.JsonNull()                                            || [CONSTANT_DOES_NOT_FIT]
    }

    def "a constant holding more text than one holds is named with how far past it runs"() {
        given:
        def written = binding("channel", new BindingSource.Written(new JsonValue.JsonString("x" * length)))
        def asked = step("classify", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, CHANNELLED),
                new Producer.Person(), 1, null, classify.bindings() + [written])
        pinned[CHANNELLED] = new StoredDeclarations.Halves(
                half(DeclarationSide.TAKES, [taken("complaint", text(4000)), taken("channel", text(9000))]), classifyGives)

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])) ==
                [new ContentProblem(CONSTANT_TOO_LONG, bound(written), excess)]

        where:
        length || excess
        8193   || 1L
        9000   || 808L
    }

    def "a value the workflow gives back left unbound is named at the field"() {
        expect:
        problems(workflow([classify, route], [])) ==
                [problem(OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, gives.keyAt([0])))]
    }

    /** What a workflow gives back is bound to a step's output: a run's own input or a constant is not one. */
    def "a value the workflow gives back bound to other than a step's is named where it is bound"() {
        given:
        def stray = binding("summary", source)

        expect:
        problems(workflow([classify, route], [stray])) ==
                [problem(OUTPUT_NOT_FROM_STEP, new ContentPlace.AtBinding(ContentPart.GIVES, stray.id()))]

        where:
        source << [new BindingSource.WorkflowInput(Pointer.parse("complaint")),
                   new BindingSource.Written(new JsonValue.JsonString("none"))]
    }

    def "what fills a value given back is held as any binding is, where it is bound"() {
        given:
        def stray = binding(target, fromStep(classify, pointer))

        expect:
        problems(workflow([classify, route], [output, stray])) ==
                [problem(expected, new ContentPlace.AtBinding(ContentPart.GIVES, stray.id()))]

        where:
        target    | pointer    || expected
        "missing" | "summary"  || TARGET_UNKNOWN
        "summary" | "summary"  || TARGET_BOUND_TWICE
    }

    def "a value given back reading what does not fit it is named where it is bound"() {
        given:
        def narrow = half(DeclarationSide.GIVES, [taken("summary", text(100))])

        expect:
        WorkflowProblems.of(stored(takes, narrow, [classify, route], [output]), resolved(), DeployedModels.HELD) ==
                [problem(SOURCE_DOES_NOT_FIT, new ContentPlace.AtBinding(ContentPart.GIVES, output.id()))]
    }

    def "runs that may be helped name a model the deployment holds, in a mode it offers"() {
        expect:
        WorkflowProblems.of(stored(takes, gives, [classify, route], [output], helped, helper), resolved(),
                DeployedModels.HELD) == expected.collect { problem(it, new ContentPlace.Whole(ContentPart.HELPER)) }

        where:
        helped | helper                          || expected
        false  | null                            || []
        true   | choice("general", "research")   || []
        true   | null                            || [HELPER_MISSING]
        true   | choice("large", null)           || [HELPER_NOT_HELD]
        true   | choice("small", "research")     || [HELPER_MODE_NOT_OFFERED]
    }

    /** Named in the order a reader meets them: what it takes, each step with its bindings, what it gives back, its helper. */
    def "every problem is named, in the order the content reads"() {
        given:
        def unlimited = half(DeclarationSide.TAKES, [taken("complaint", text(null))])
        def unbound = step("classify", classify.runs(), null, 3, null, [])
        def stray = binding("summary", new BindingSource.WorkflowInput(Pointer.parse("complaint")))

        when:
        def found = WorkflowProblems.of(stored(unlimited, gives, [unbound], [stray], true, null), resolved(),
                DeployedModels.HELD)

        then:
        found*.code() == [LONGEST_MISSING, PRODUCER_MISSING, INPUT_UNBOUND, OUTPUT_NOT_FROM_STEP, HELPER_MISSING]
        found*.excess().every { it == null }
    }

    def "a binding reading a step its version does not hold is a store gone wrong"() {
        given:
        def stray = binding("complaint", new BindingSource.StepOutput(UUID.fromString("0000000c-0000-4000-8000-0000000000ff"),
                Pointer.parse("summary")))
        def asked = step("again", classify.runs(), new Producer.Person(), 1, null, [stray])

        when:
        problems(workflow([classify, asked], [output]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A binding reads a step its version does not hold"
    }

    def "what is judged, what it reaches and what is held are each asked"() {
        when:
        WorkflowProblems.of(content, resolution, catalog)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        content | resolution | catalog             || expectedMessage
        null    | RESOLVED   | DeployedModels.HELD || "WorkflowProblems workflow must not be null"
        EMPTY   | null       | DeployedModels.HELD || "WorkflowProblems resolved must not be null"
        EMPTY   | RESOLVED   | null                || "WorkflowProblems models must not be null"
    }

    private List<ContentProblem> problems(StoredWorkflow workflow) {
        WorkflowProblems.of(workflow, resolved(), DeployedModels.HELD)
    }

    /** Built as it is asked for, so a declaration a feature adds to what is pinned is among what it reaches. */
    private WorkflowProblems.Resolved resolved() {
        new WorkflowProblems.Resolved(pinned, OfferedLists.offering([(CATEGORIES): ["Billing", "Delivery"]]),
                nameable, released, listsUnserved, asked)
    }

    /**
     * A question giving back one text whose review sends the most one asking may send and {@code over} characters
     * more: the text as long as fits, then the instruction lengthened by what that leaves short.
     */
    private static Asking reviewedPast(long over) {
        int fits = 1
        int past = Declaration.MOST_SENT as int
        while (past - fits > 1) {
            int middle = (fits + past).intdiv(2) as int
            if (SendMeasure.mostSentToReview(asking("Say.", middle)) <= Declaration.MOST_SENT) {
                fits = middle
            } else {
                past = middle
            }
        }
        long shortOf = Declaration.MOST_SENT - SendMeasure.mostSentToReview(asking("Say.", fits))
        asking("Say." + "." * (shortOf + over), fits)
    }

    /**
     * A code step giving back one text, whose review sends the most one asking may send and {@code over} characters
     * more: the text as long as leaves it twenty or more short, then one flag it takes, named long enough to make up
     * the rest. The flag's name is counted where what it takes is sent, and nowhere in what the reviewer is told.
     */
    private static CodeStepDeclaration reviewedCodePast(long over) {
        int fits = 1
        int past = Declaration.MOST_SENT as int
        while (past - fits > 1) {
            int middle = (fits + past).intdiv(2) as int
            if (codeReviewSent(declaredCode(middle, null)) <= Declaration.MOST_SENT - 20) {
                fits = middle
            } else {
                past = middle
            }
        }
        def named = (1..63).collect { declaredCode(fits, "f" * it) }
                .find { codeReviewSent(it) == Declaration.MOST_SENT + over }
        assert named != null: "no flag's name makes up what is short"
        named
    }

    private static CodeStepDeclaration declaredCode(int longest, String flag) {
        new SpecCodeStep("loud", flag == null ? [] : [SpecCodeStep.given(flag, new FieldShape.Plain(FieldKind.YES_NO))],
                [SpecCodeStep.standing("note", new FieldShape.Text(longest))], false).declaration()
    }

    private static long codeReviewSent(CodeStepDeclaration declared) {
        SendMeasure.mostSentToReview(Asking.told(declared.takes(), [:]), Asking.told(declared.gives(), [:]))
    }

    private static Asking asking(String said, int longest) {
        new Asking(new Instruction(said), [],
                [new AskedField(new FieldName("summary"), FieldKind.TEXT, longest, null, null, [], true, false)])
    }

    private StoredWorkflow workflow(List<StoredWorkflow.Step> steps, List<Binding> outputs) {
        stored(takes, gives, steps, outputs)
    }

    private static StoredWorkflow stored(
            StoredDeclarations.Half takes,
            StoredDeclarations.Half gives,
            List<StoredWorkflow.Step> steps,
            List<Binding> outputs,
            boolean helped = false,
            ModelChoice helper = null) {
        new StoredWorkflow(1, takes, gives, steps, outputs, null, false, false, helped, helper)
    }

    private List<Binding> escalating() {
        [binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("complaint")))]
    }

    private static StoredWorkflow.Step step(
            String name, StoredWorkflow.Runs runs, Producer producer, Integer tries, ModelChoice reviewer,
            List<Binding> bindings) {
        def id = STEP_KEYS[name]
        assert id != null: "no key is kept for a step called ${name}"
        new StoredWorkflow.Step(id, new StepId(name), runs, producer, tries, reviewer, bindings)
    }

    /** A step running the code step the release holds, which takes a reply and gives back a receipt. */
    private static StoredWorkflow.Step sending(Producer producer, List<Binding> bindings) {
        step("send", new StoredWorkflow.Runs.Code("send_reply"), producer, 1, null, bindings)
    }

    private static StoredWorkflow.Runs.Route routing(Binding chosenBy, StoredDeclarations.Half routeGives, List<RouteCase> cases) {
        new StoredWorkflow.Runs.Route(chosenBy, routeGives, cases)
    }

    private RouteCase routeCase(String term, EntryVersionId target, List<Binding> bindings) {
        new RouteCase(new UUID(0x0dL, ++keysMade), term, target, bindings)
    }

    private Binding binding(String target, BindingSource source) {
        new Binding(new UUID(0x0eL, ++keysMade), target == null ? null : Pointer.parse(target), source)
    }

    private static BindingSource fromStep(StoredWorkflow.Step step, String pointer) {
        fromKey(step.id(), pointer)
    }

    private static BindingSource fromKey(UUID step, String pointer) {
        new BindingSource.StepOutput(step, Pointer.parse(pointer))
    }

    private static ContentPlace at(StoredWorkflow.Step step) {
        new ContentPlace.AtStep(step.id())
    }

    private static ContentPlace bound(Binding binding) {
        new ContentPlace.AtBinding(ContentPart.STEPS, binding.id())
    }

    private static ContentProblem problem(ContentProblemCode code, ContentPlace place) {
        new ContentProblem(code, place, null)
    }

    private static ModelChoice choice(String model, String mode) {
        new ModelChoice(new ModelName(model), mode == null ? null : new ModelMode(mode))
    }

    /** A half built with a fresh key for each field, in the shape its fields are held in. */
    private StoredDeclarations.Half half(DeclarationSide side, List<Field> fields) {
        new StoredDeclarations.Half(new Declaration(side, Demands.ofWorkflow(side), fields), keyed(fields))
    }

    private List<StoredDeclarations.Keyed> keyed(List<Field> fields) {
        fields.collect { field ->
            new StoredDeclarations.Keyed(new UUID(0x0bL, ++keysMade),
                    field.shape() instanceof FieldShape.Nested ? keyed(((FieldShape.Nested) field.shape()).fields()) : [])
        }
    }

    private static FieldShape text(Integer longest) {
        new FieldShape.Text(longest)
    }

    /** A field that must be given. */
    private static Field taken(String name, FieldShape shape, HowMany howMany = new HowMany.One()) {
        new Field(new FieldName(name), null, null, shape, howMany, new Demand.Given(true))
    }

    /** A field that may be left empty. */
    private static Field optional(String name, FieldShape shape) {
        new Field(new FieldName(name), null, null, shape, new HowMany.One(), new Demand.Given(false))
    }

    /** A contact that must be given, holding an email that must be given or may be left empty. */
    private static Field contact(boolean emailMustBe) {
        taken("contact", new FieldShape.Nested([
                new Field(new FieldName("email"), null, null, text(100), new HowMany.One(), new Demand.Given(emailMustBe))]))
    }

    private static EntryVersionId version(int last) {
        new EntryVersionId(new UUID(0x07L, last))
    }
}
