package org.lilradish.lite.app.library

import static org.lilradish.lite.domain.registry.ContentProblemCode.ASKING_PAST_LARGEST
import static org.lilradish.lite.domain.registry.ContentProblemCode.CASE_GIVES_OTHER_KIND
import static org.lilradish.lite.domain.registry.ContentProblemCode.CASE_GIVES_PAST_LIMIT
import static org.lilradish.lite.domain.registry.ContentProblemCode.CASE_GIVES_UNDECLARED
import static org.lilradish.lite.domain.registry.ContentProblemCode.CASE_MAY_GIVE_NOTHING
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
import static org.lilradish.lite.domain.registry.ContentProblemCode.QUESTION_TAKES_FILE
import static org.lilradish.lite.domain.registry.ContentProblemCode.REVIEWER_MODE_NOT_OFFERED
import static org.lilradish.lite.domain.registry.ContentProblemCode.REVIEWER_NOT_HELD
import static org.lilradish.lite.domain.registry.ContentProblemCode.RUNS_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.SEARCH_QUESTION_MAY_BE_EMPTY
import static org.lilradish.lite.domain.registry.ContentProblemCode.SEARCH_REVIEW_PAST_LARGEST
import static org.lilradish.lite.domain.registry.ContentProblemCode.SEARCH_STANDING_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.SEARCH_TOOL_NOT_OFFERED
import static org.lilradish.lite.domain.registry.ContentProblemCode.SEARCH_WAY_NOT_HELD
import static org.lilradish.lite.domain.registry.ContentProblemCode.SOURCE_DOES_NOT_FIT
import static org.lilradish.lite.domain.registry.ContentProblemCode.SOURCE_MAY_BE_EMPTY
import static org.lilradish.lite.domain.registry.ContentProblemCode.SOURCE_NOT_EARLIER
import static org.lilradish.lite.domain.registry.ContentProblemCode.SOURCE_UNKNOWN
import static org.lilradish.lite.domain.registry.ContentProblemCode.STANDING_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.STEP_FILE_MADE_BY_MODEL
import static org.lilradish.lite.domain.registry.ContentProblemCode.STEP_FILE_REVIEWED_BY_MODEL
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
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.declaration.TextLines
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.inference.Payload
import org.lilradish.lite.domain.inference.SendMeasure
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.search.SearchAnswer
import org.lilradish.lite.domain.search.SearchWay
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.RouteCase
import org.lilradish.lite.domain.workflow.SearchQuestionPart
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.OfferedLists
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.Specification


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

    static final Map<FieldName, FieldStanding> EVERY_STANDING = [
            (SearchWay.TITLE)     : FieldStanding.ALWAYS,
            (SearchWay.BODY)      : FieldStanding.ALWAYS,
            (SearchWay.REFERENCES): FieldStanding.NEVER,
            (SearchWay.OTHER)     : FieldStanding.NEVER]


    static final AskedField COMPLAINT_TOLD = new AskedField(new FieldName("complaint"), FieldKind.TEXT, 4000, null, null, [],
            true, false)


    static final AskedField CATEGORY_TOLD = new AskedField(new FieldName("category"), FieldKind.TERM, null, null,
            OfferedLists.offering([(CATEGORIES): ["Billing", "Delivery"]])[CATEGORIES], [], true, false)

    static final WorkflowProblems.Resolved RESOLVED = new WorkflowProblems.Resolved([:], [:], [] as Set, [:], [:], [:], [:])

    static final StoredWorkflow EMPTY = new StoredWorkflow(1,
            new StoredDeclarations.Half(new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), []), []),
            new StoredDeclarations.Half(new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), []), []),
            [], [], null, false, false, false, null)


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
            after   : UUID.fromString("0000000c-0000-4000-8000-00000000000b"),
            search  : UUID.fromString("0000000c-0000-4000-8000-00000000000c")]

    long keysMade = 0


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


    Map<EntryVersionId, Asking> asked = [:]


    Set<String> nameable = ["send_reply"] as Set

    Map<String, StoredDeclarations.Halves> released = [
            send_reply: StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration())]


    Map<String, List<ContentProblemCode>> listsUnserved = [:]


    Map<String, SearchWay> ways = [web: DeployedModels.WEB]


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

    def "a binding from several lines to one is named at the binding, while one fits either"() {
        given:
        def from = taken("title", new FieldShape.Text(30, source))
        def into = taken("title", new FieldShape.Text(30, target))
        takes = half(DeclarationSide.TAKES, takes.declaration().fields() + [from])
        released.receive = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [into]), refundGives)
        nameable = ["receive"] as Set
        def feed = binding("title", new BindingSource.WorkflowInput(Pointer.parse("title")))
        def code = step("send", new StoredWorkflow.Runs.Code("receive"), new Producer.Code(), 1, null, [feed])

        expect:
        problems(workflow([classify, code], [output])) ==
                (fits ? [] : [problem(SOURCE_DOES_NOT_FIT, bound(feed))])

        where:
        source            | target            || fits
        TextLines.ONE     | TextLines.ONE     || true
        TextLines.ONE     | TextLines.SEVERAL || true
        TextLines.SEVERAL | TextLines.SEVERAL || true
        TextLines.SEVERAL | TextLines.ONE     || false
    }

    def "a workflow whose every part holds names nothing"() {
        expect:
        problems(workflow([classify, route], [output])) == []
    }


    def "what the workflow takes and gives back is held to what any declaration is, each field by its key"() {
        given:
        def unlimited = half(DeclarationSide.TAKES, [taken("complaint", text(null))])
        def ungiven = half(DeclarationSide.GIVES, [taken("summary", text(null))])

        when:
        def found = WorkflowProblems.report(stored(unlimited, ungiven, [classify], [output]), resolved(), DeployedModels.HELD, LibraryStore.FILES).problems()

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
        def found = WorkflowProblems.report(stored(heavy, gives, [classify], [output]), resolved(), DeployedModels.HELD, LibraryStore.FILES).problems()

        then:
        found == named.collect { problem(it, new ContentPlace.Whole(ContentPart.TAKES)) }

        where:
        longest   || named
        4_192_286 || []
        4_192_287 || [TAKES_PAST_LARGEST]
    }


    def "a workflow holding no step is named at its steps, and one holding any is not"() {
        given:
        def givesNothing = half(DeclarationSide.GIVES, [])

        when:
        def found = WorkflowProblems.report(stored(takes, givesNothing, steps == 0 ? [] : [classify], []), resolved(),
                DeployedModels.HELD, LibraryStore.FILES).problems()

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


    def "a step pinning a version its group does not hold is named, and nothing bound to it is judged"() {
        given:
        def stray = step("stray", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, ELSEWHERE),
                new Producer.Person(), 1, null, [binding("anything", new BindingSource.WorkflowInput(Pointer.parse("complaint")))])

        expect:
        problems(workflow([classify, stray], [output])) == [problem(PIN_ELSEWHERE, at(stray))]
    }

    /** Without an accessible declaration, input bindings cannot be judged or reported as unknown inputs. */
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

    def "a file bound from the workflow to a code step fits its types, bytes and count"() {
        given:
        def from = taken("attachment", new FieldShape.File(
                sourceTypes.collect { new MediaTypeName(it) }, sourceBytes), new HowMany.Many(sourceCount))
        def into = taken("attachment", new FieldShape.File(
                targetTypes.collect { new MediaTypeName(it) }, targetBytes), new HowMany.Many(targetCount))
        takes = half(DeclarationSide.TAKES, takes.declaration().fields() + [from])
        released.receive = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [into]), refundGives)
        nameable = ["receive"] as Set
        def feed = binding("attachment", new BindingSource.WorkflowInput(Pointer.parse("attachment")))
        def code = step("send", new StoredWorkflow.Runs.Code("receive"), new Producer.Code(), 1, null, [feed])

        expect:
        problems(workflow([classify, code], [output])) ==
                (fits ? [] : [problem(SOURCE_DOES_NOT_FIT, bound(feed))])

        where:
        sourceTypes                             | targetTypes                              | sourceBytes | targetBytes | sourceCount | targetCount || fits
        ["application/pdf"]                     | ["application/pdf", "image/png"]        | 1024L       | 1024L       | 2           | 2           || true
        ["application/pdf", "image/png"]        | ["application/pdf"]                      | 1024L       | 1024L       | 2           | 2           || false
        ["application/pdf"]                     | ["application/pdf"]                      | 1025L       | 1024L       | 2           | 2           || false
        ["application/pdf"]                     | ["application/pdf"]                      | 1024L       | 1024L       | 3           | 2           || false
        ["application/pdf"]                     | ["application/pdf"]                      | 1024L       | 1024L       | 2           | 2           || true
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

    /** Only code steps published to the group may expose a declaration. */
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

    def "a step giving back a nested file is answered and reviewed by a person, never a model"() {
        given:
        pinned[CLASSIFY] = new StoredDeclarations.Halves(classifyTakes,
                half(DeclarationSide.GIVES, [taken("summary", text(500)),
                        optional("details", new FieldShape.Nested([
                                optional("attachment", new FieldShape.File([new MediaTypeName("application/pdf")], 1024L))]))]))
        def asked = step("classify", classify.runs(), producer, 3, reviewer, classify.bindings())

        expect:
        problems(workflow([asked], [binding("summary", fromStep(asked, "summary"))])) ==
                expected.collect { problem(it, at(asked)) }

        where:
        producer                            | reviewer || expected
        new Producer.Person()               | null     || []
        new Producer.Model(GENERAL, false)  | null     || [STEP_FILE_MADE_BY_MODEL]
        new Producer.Person()               | GENERAL  || [STEP_FILE_REVIEWED_BY_MODEL]
        new Producer.Model(GENERAL, false)  | GENERAL  || [STEP_FILE_MADE_BY_MODEL, STEP_FILE_REVIEWED_BY_MODEL]
    }

    def "a code step taking a nested file is reviewed only by a person"() {
        given:
        def file = optional("attachment", new FieldShape.Nested([
                optional("document", new FieldShape.File([new MediaTypeName("application/pdf")], 1024L))]))
        takes = half(DeclarationSide.TAKES, takes.declaration().fields() + [file])
        released.receive = new StoredDeclarations.Halves(half(DeclarationSide.TAKES, [file]), refundGives)
        nameable = ["receive"] as Set
        def code = step("send", new StoredWorkflow.Runs.Code("receive"), new Producer.Code(), 1,
                reviewer, [binding("attachment", new BindingSource.WorkflowInput(Pointer.parse("attachment")))])

        expect:
        problems(workflow([classify, code], [output])) ==
                expected.collect { problem(it, at(code)) }

        where:
        reviewer || expected
        null     || []
        GENERAL  || [STEP_FILE_REVIEWED_BY_MODEL]
    }

    def "a model reviewing a question is named at its step where the review could send past the most, by how much"() {
        given:
        asked[CLASSIFY] = reviewedPast(over)
        def reviewed = step("classify", classify.runs(), producer, 3, reviewer, classify.bindings())

        expect:
        problems(workflow([reviewed], [binding("summary", fromStep(reviewed, "summary"))])) ==
                excess.collect { new ContentProblem(ASKING_PAST_LARGEST, at(reviewed), it, null) }

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

    /** Review sending must remain within the declared sending bound. */
    def "a model reviewing what a code step gives back is named at its step where the review could send past the most, by how much"() {
        given:
        released.loud = StoredDeclarations.ofCodeStep("loud", reviewedCodePast(over))
        nameable = ["loud"] as Set
        def flag = released.loud.takes().declaration().fields()[0].name().value()
        def code = step("send", new StoredWorkflow.Runs.Code("loud"), new Producer.Code(), 1, reviewer,
                [binding(flag, new BindingSource.Written(new JsonValue.JsonBoolean(true)))])

        expect:
        problems(workflow([classify, code], [output])) ==
                excess.collect { new ContentProblem(CODE_STEP_REVIEW_PAST_LARGEST, at(code), it, null) }

        where:
        reviewer | over || excess
        GENERAL  | 0    || []
        GENERAL  | 1    || [1L]
        null     | 1    || []
    }


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


    def "a step running a workflow is asked for no producer and no tries"() {
        given:
        def beneath = step("escalate", new StoredWorkflow.Runs.Pinned(EntryKind.WORKFLOW, ESCALATE), null, null, null,
                [binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("complaint")))])

        expect:
        problems(workflow([classify, beneath], [output])) == []
    }


    def "a step running a workflow that takes nothing and gives nothing back is not named"() {
        given:
        def silent = step("silent", new StoredWorkflow.Runs.Pinned(EntryKind.WORKFLOW, SILENT), null, null, null, [])

        expect:
        problems(workflow([classify, silent], [output])) == []
    }


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

    def "a searching step whose every part holds names nothing, whether a model or a person produces"() {
        given:
        def search = searching("web", [written("Find what is said of "), reading(fromStep(classify, "summary"))],
                EVERY_STANDING, producer)

        expect:
        searchProblems(workflow([classify, search], [output])) == []

        where:
        producer << [new Producer.Model(GENERAL, true), new Producer.Person()]
    }

    def "a searching step names who produces its values, and how many tries it may make"() {
        given:
        def search = searching("web", [written("Find it")], EVERY_STANDING, producer, tries)

        expect:
        searchProblems(workflow([classify, search], [output])) == expected.collect { problem(it, at(search)) }

        where:
        producer              | tries || expected
        null                  | 3     || [PRODUCER_MISSING]
        new Producer.Person() | null  || [TRIES_MISSING]
        null                  | null  || [PRODUCER_MISSING, TRIES_MISSING]
        new Producer.Person() | 1     || []
    }


    def "a searching step running no way yet is said to run nothing, and one running a way the release does not hold is named so"() {
        given:
        def search = searching(way, [written("Find it")], EVERY_STANDING, new Producer.Model(choice("small", null), true))

        expect:
        searchProblems(workflow([classify, search], [output])) == expected.collect { problem(it, at(search)) }

        where:
        way    || expected
        null   || [RUNS_MISSING]
        "news" || [SEARCH_WAY_NOT_HELD]
    }


    def "a model producing a search offers the tool its way needs, and a person searching is asked for none"() {
        given:
        def search = searching("web", [written("Find it")], EVERY_STANDING, producer)

        expect:
        searchProblems(workflow([classify, search], [output])) == expected.collect { problem(it, at(search)) }

        where:
        producer                                         || expected
        new Producer.Model(GENERAL, true)                || []
        new Producer.Model(choice("general", "research"), false) || []
        new Producer.Model(choice("small", null), true)  || [SEARCH_TOOL_NOT_OFFERED]
        new Producer.Model(choice("large", null), true)  || [PRODUCER_NOT_HELD]
        new Producer.Person()                            || []
    }


    def "a searching step leaving how any field of its way stands unchosen names those fields at the step, in the way's order"() {
        given:
        def standings = new LinkedHashMap<FieldName, FieldStanding>()
        kept.each { standings.put(new FieldName(it), FieldStanding.NEVER) }
        def search = searching(way, [written("Find it")], standings)

        when:
        def found = searchProblems(workflow([classify, search], [output]))

        then:
        found.findAll { it.code() == SEARCH_STANDING_MISSING } ==
                (unchosen.isEmpty() ? [] : [problem(SEARCH_STANDING_MISSING, new ContentPlace.AtStepFields(search.id(), unchosen))])
        !(STANDING_MISSING in found*.code())

        where:
        way    | kept                                     || unchosen
        "web"  | ["other", "references", "body", "title"] || []
        "web"  | ["other", "references", "title"]          || ["body"]
        "web"  | ["body"]                                 || ["title", "references", "other"]
        "web"  | ["other", "title"]                       || ["body", "references"]
        "web"  | []                                       || ["title", "body", "references", "other"]
        "news" | ["references"]                           || ["title", "body", "other"]
    }


    def "parts of a searching step's question reading the same thing it cannot are each named where they are"() {
        given:
        def first = reading(new BindingSource.WorkflowInput(Pointer.parse("missing")))
        def again = reading(new BindingSource.WorkflowInput(Pointer.parse("missing")))
        def search = searching("web", [written("Find "), first, again], EVERY_STANDING)

        expect:
        searchProblems(workflow([classify, search], [output])) ==
                [problem(SOURCE_UNKNOWN, new ContentPlace.AtBinding(ContentPart.STEPS, first.id())),
                 problem(SOURCE_UNKNOWN, new ContentPlace.AtBinding(ContentPart.STEPS, again.id()))]
    }

    def "a search question refuses a file read at its own part, including an object containing a file"() {
        given:
        takes = half(DeclarationSide.TAKES, takes.declaration().fields() + [source])
        def read = reading(new BindingSource.WorkflowInput(Pointer.parse(pointer)))
        def search = searching("web", [written("Find "), read], EVERY_STANDING)
        def place = new ContentPlace.AtBinding(ContentPart.STEPS, read.id())

        when:
        def judged = WorkflowProblems.judged(workflow([classify, search], [output]), resolved(),
                DeployedModels.SEARCHING, LibraryStore.FILES, false)

        then:
        judged.problems().problems().findAll { it.place() == place } ==
                [new ContentProblem(QUESTION_TAKES_FILE, place, null, null)]
        judged.searchesSent() == [:]
        judged.searchesReviewed() == [:]

        where:
        source | pointer
        taken("attachment", new FieldShape.File([new MediaTypeName("application/pdf")], 1024L)) | "attachment"
        taken("record", new FieldShape.Nested([
                taken("attachment", new FieldShape.File([new MediaTypeName("application/pdf")], 1024L))])) | "record"
    }


    def "a part of a searching step's question reading what it cannot is named where it is, as a binding reading it would be"() {
        given:
        def read = reading(from == null ? new BindingSource.WorkflowInput(Pointer.parse(pointer)) : fromKey(STEP_KEYS[from], pointer))
        def search = searching("web", [written("Find "), read], EVERY_STANDING)
        def later = step("after", classify.runs(), new Producer.Person(), 1, null, classify.bindings())

        expect:
        searchProblems(workflow([classify, search, later], [output])) ==
                expected.collect { problem(it, new ContentPlace.AtBinding(ContentPart.STEPS, read.id())) }

        where:
        from       | pointer     || expected
        "classify" | "summary"   || []
        null       | "complaint" || []
        "after"    | "summary"   || [SOURCE_NOT_EARLIER]
        "search"   | "body"      || [SOURCE_NOT_EARLIER]
        "classify" | "nothing"   || [SOURCE_UNKNOWN]
        "classify" | "lines.sku" || [POINTER_INTO_MANY]
        null       | "missing"   || [SOURCE_UNKNOWN]
    }

    /** A value counts as required only when every parent field is required. */
    def "a searching step's question that could be sent holding nothing is named at the step, and one that could not is not"() {
        given:
        def search = searching("web", question(parts), EVERY_STANDING)

        expect:
        searchProblems(workflow([classify, search], [output])) ==
                (empty ? [problem(SEARCH_QUESTION_MAY_BE_EMPTY, at(search))] : [])

        where:
        parts                                                  || empty
        []                                                     || true
        [[text: "Find it"]]                                    || false
        [[input: "complaint"]]                                 || false
        [[input: "remark"]]                                    || true
        [[input: "sender.address"]]                            || true
        [[text: " "], [input: "remark"], [input: "complaint"]] || false
    }

    /** Count a refused output only when its model is told what happened. */
    def "a searching step whose one asking could send past the most is named at the step, by how much"() {
        given:
        def read = reading(term ? fromStep(classify, "category") : new BindingSource.WorkflowInput(Pointer.parse("complaint")))
        def search = searching("web", boundaryParts(builtTold, over, term ? CATEGORY_TOLD : COMPLAINT_TOLD, read),
                EVERY_STANDING, producer)

        expect:
        searchProblems(workflow([classify, search], [output])) ==
                excess.collect { new ContentProblem(ASKING_PAST_LARGEST, at(search), it, null) }

        where:
        producer                           | term  | builtTold | over || excess
        new Producer.Model(GENERAL, false) | false | false     | 0    || []
        new Producer.Model(GENERAL, false) | false | false     | 1    || [1L]
        new Producer.Model(GENERAL, true)  | false | true      | 0    || []
        new Producer.Model(GENERAL, true)  | false | true      | 1    || [1L]
        new Producer.Model(GENERAL, true)  | false | false     | 0    || [toldAdds()]
        new Producer.Person()              | false | false     | 1    || [1L]
        new Producer.Model(GENERAL, false) | true  | false     | 0    || []
        new Producer.Model(GENERAL, false) | true  | false     | 1    || [1L]
    }

    def "a search review has its own bound and code, measured only when a reviewer is named"() {
        given:
        def question = reviewBoundaryParts(over)
        def unreviewed = searching("web", question, EVERY_STANDING, new Producer.Model(GENERAL, false))
        def search = new StoredWorkflow.Step(unreviewed.id(), unreviewed.name(), unreviewed.runs(),
                unreviewed.producer(), unreviewed.tries(), reviewer ? GENERAL : null, unreviewed.bindings())

        when:
        def judged = WorkflowProblems.judged(workflow([search], []), resolved(), DeployedModels.SEARCHING, LibraryStore.FILES, false)

        then:
        judged.problems().problems().findAll { it.code() == SEARCH_REVIEW_PAST_LARGEST } ==
                (reviewer && over ? [new ContentProblem(SEARCH_REVIEW_PAST_LARGEST, at(search), 1L, null)] : [])
        judged.searchesReviewed() == (reviewer ? [(search.id()): Declaration.MOST_SENT + over] : [:])

        where:
        reviewer | over
        true     | 0
        true     | 1
        false    | 1
    }

    /** One declared value read 255 times is counted 255 times; one legal piece makes it exactly one past. */
    def "a searching step reading one value many times is measured as each read told alike"() {
        given:
        int fits = 1
        int past = 100_000
        while (past - fits > 1) {
            int middle = (fits + past).intdiv(2) as int
            def told = new AskedField(new FieldName("bulk"), FieldKind.TEXT, middle, null, null, [], true, false)
            def repeated = (1..255).collect { new Payload.SearchPart.ValuePart(told, null) }
            if (SendMeasure.mostSentToSearch([new Payload.SearchPart.LiteralPart("")] + repeated,
                    SearchAnswer.asked(DeployedModels.WEB), false) <= Declaration.MOST_SENT) {
                fits = middle
            } else {
                past = middle
            }
        }
        takes = half(DeclarationSide.TAKES, [taken("bulk", text(fits))])
        gives = nothing
        def told = new AskedField(new FieldName("bulk"), FieldKind.TEXT, fits, null, null, [], true, false)
        def reads = (1..255).collect { reading(new BindingSource.WorkflowInput(Pointer.parse("bulk"))) }
        long shortOf = Declaration.MOST_SENT - SendMeasure.mostSentToSearch(
                [new Payload.SearchPart.LiteralPart("")] + (1..255).collect { new Payload.SearchPart.ValuePart(told, null) },
                SearchAnswer.asked(DeployedModels.WEB), false)
        assert shortOf > 0 && shortOf <= 8191
        def search = searching("web", [written("a" * (shortOf + 1))] + reads, EVERY_STANDING,
                new Producer.Model(GENERAL, false))
        long onePast = Declaration.MOST_SENT + 1L

        when:
        def judged = WorkflowProblems.judged(workflow([search], []), resolved(), DeployedModels.SEARCHING, LibraryStore.FILES, false)

        then:
        judged.problems().problems() == [new ContentProblem(ASKING_PAST_LARGEST, at(search), 1L, null)]
        judged.searchesSent() == [(search.id()): onePast]
    }


    def "what one asking of a searching step could send is kept where it was measured, and nowhere else"() {
        given:
        def search = searching(way, [written("Find it"), reading(new BindingSource.WorkflowInput(Pointer.parse(pointer)))],
                EVERY_STANDING, new Producer.Model(GENERAL, true))

        when:
        def judged = WorkflowProblems.judged(workflow([classify, search], [output]), resolved(), DeployedModels.SEARCHING, LibraryStore.FILES, false)

        then:
        judged.searchesSent() == (measured
                ? [(search.id()): searchSent([new Payload.SearchPart.LiteralPart("Find it")], true)]
                : [:])

        where:
        way    | pointer     || measured
        "web"  | "complaint" || true
        "news" | "complaint" || false
        null   | "complaint" || false
        "web"  | "missing"   || false
    }


    def "a searching step reading a term a code step gives back from a list that is missing is not measured"() {
        given:
        def missing = version(0x70)
        nameable = ["sort_out"] as Set
        released = [sort_out: StoredDeclarations.ofCodeStep("sort_out", new SpecCodeStep("sort_out", [],
                [SpecCodeStep.standing("category", new FieldShape.Term(missing))], false).declaration())]
        listsUnserved = [sort_out: [CODE_STEP_LIST_MISSING]]
        def sorting = step("send", new StoredWorkflow.Runs.Code("sort_out"), new Producer.Code(), 1, null, [])
        def search = searching("web", [written("Find "), reading(fromStep(sorting, "category"))],
                EVERY_STANDING, new Producer.Model(GENERAL, false))

        when:
        def judged = WorkflowProblems.judged(workflow([sorting, search], []), resolved(), DeployedModels.SEARCHING, LibraryStore.FILES, false)

        then:
        judged.problems().problems().findAll { it.place() == at(search) } == []
        judged.problems().problems().contains(problem(CODE_STEP_LIST_MISSING, at(sorting)))
        judged.searchesSent() == [:]
    }


    def "a searching step reading a term from a list its version pins that was not read with it is a store gone wrong"() {
        given:
        takes = half(DeclarationSide.TAKES, [taken("region", new FieldShape.Term(version(0x71)))])
        def search = searching("web", [reading(new BindingSource.WorkflowInput(Pointer.parse("region")))], EVERY_STANDING)

        when:
        searchProblems(workflow([classify, search], [output]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A value read into a searching step's question takes a term from a list not handed over"
    }


    def "a searching step reading what it cannot is not measured, whatever it could send"() {
        given:
        def read = reading(new BindingSource.WorkflowInput(Pointer.parse("missing")))
        def search = searching("web", [written("Find "), read], EVERY_STANDING)

        expect:
        searchProblems(workflow([classify, search], [output])) ==
                [problem(SOURCE_UNKNOWN, new ContentPlace.AtBinding(ContentPart.STEPS, read.id()))]
    }

    def "a route bound to nothing to choose by is named at the route"() {
        given:
        def unchosen = step("route", routing(null, nothing, route.runs().cases()), null, null, null, [])

        expect:
        problems(workflow([classify, unchosen], [output])) == [problem(DISCRIMINATOR_MISSING, at(unchosen))]
    }


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
        problems(workflow([classify, routed], [output])) == expected.collect { code ->
            problem(code, code == CASE_GIVES_UNDECLARED
                    ? new ContentPlace.AtCaseField(routed.id(), judged.id(), refundGives.keyAt([0]))
                    : new ContentPlace.AtCase(routed.id(), judged.id()))
        }

        where:
        term       | target    || expected
        "Billing"  | ESCALATE  || []
        "Billing"  | null      || [CASE_TARGET_MISSING]
        "Delivery" | ESCALATE  || [CASE_REPEATED]
        "Refund"   | ESCALATE  || [CASE_NOT_OFFERED]
        "billing"  | ESCALATE  || [CASE_NOT_OFFERED]
        "Billing"  | REFUND    || [CASE_GIVES_UNDECLARED]
        "Billing"  | ELSEWHERE || [PIN_ELSEWHERE]
    }


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
                ? [problem(CASE_MAY_GIVE_NOTHING, new ContentPlace.AtCaseField(
                        answering.id(), answering.runs().cases()[0].id(), pinned[REFUND].gives().keyAt([0])))]
                : [])

        where:
        routeMustBe | caseMustBe || otherwise
        true        | false      || true
        true        | true       || false
        false       | true       || false
        false       | false      || false
    }


    def "a route that answers holds each case to what it gives back"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("note", text(100))])
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", REFUND, escalating()), routeCase(null, ESCALATE, escalating())]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) ==
                [problem(CASE_MAY_GIVE_NOTHING, new ContentPlace.AtCaseField(
                        answering.id(), answering.runs().cases()[1].id(), routeGives.keyAt([0])))]
    }


    def "a field a route gives back is held to what any declaration is, named by its key among the steps"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("note", text(null))])
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", ESCALATE, escalating())]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) == [
                problem(LONGEST_MISSING, new ContentPlace.AtField(ContentPart.STEPS, routeGives.keyAt([0]))),
                problem(CASE_MAY_GIVE_NOTHING, new ContentPlace.AtCaseField(
                        answering.id(), answering.runs().cases()[0].id(), routeGives.keyAt([0])))]
    }

    def "a case may give a tighter text or list bound and omit a route's optional field"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("note", routeShape, routeMany), optional("hint", text(20))])
        def caseGives = half(DeclarationSide.GIVES, [taken("note", caseShape, caseMany)])
        pinned[REFUND] = new StoredDeclarations.Halves(escalateTakes, caseGives)
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", REFUND, escalating())]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) == []

        where:
        routeShape | routeMany          | caseShape | caseMany
        text(100)  | new HowMany.One()   | text(50)  | new HowMany.One()
        text(100)  | new HowMany.Many(5) | text(50)  | new HowMany.Many(3)
    }

    def "a workflow's nested file outputs count together at the handover limit"() {
        given:
        def documents = taken("documents", new FieldShape.Nested([
                taken("file", new FieldShape.File([new MediaTypeName("application/pdf")], documentBytes),
                        new HowMany.Many(16))]))
        def givenBack = half(DeclarationSide.GIVES, [taken("summary", text(500)), documents,
                taken("extra", new FieldShape.File([new MediaTypeName("application/pdf")], extraBytes))])

        expect:
        problems(stored(takes, givenBack, [classify, route], [output])) ==
                (past ? [new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                        new ContentPlace.Whole(ContentPart.GIVES), null,
                        new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L)),
                         problem(OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, givenBack.keyAt([1]))),
                         problem(OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, givenBack.keyAt([2])))]
                        : [problem(OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, givenBack.keyAt([1]))),
                           problem(OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, givenBack.keyAt([2])))])

        where:
        documentBytes | extraBytes || past
        67_108_863L   | 16L        || false
        67_108_864L   | 1L         || true
    }

    def "a route's nested file outputs count together at the handover limit"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("documents", new FieldShape.Nested([
                taken("file", new FieldShape.File([new MediaTypeName("application/pdf")], documentBytes),
                        new HowMany.Many(16))])),
                taken("extra", new FieldShape.File([new MediaTypeName("application/pdf")], extraBytes))])
        def caseGives = half(DeclarationSide.GIVES, [taken("documents", new FieldShape.Nested([
                taken("file", new FieldShape.File([new MediaTypeName("application/pdf")], 1L),
                        new HowMany.Many(16))])),
                taken("extra", new FieldShape.File([new MediaTypeName("application/pdf")], 1L))])
        pinned[REFUND] = new StoredDeclarations.Halves(escalateTakes, caseGives)
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", REFUND, escalating())]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) ==
                (past ? [new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                        new ContentPlace.AtStep(answering.id()), null,
                        new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))] : [])

        where:
        documentBytes | extraBytes || past
        67_108_863L   | 16L        || false
        67_108_864L   | 1L         || true
    }

    def "a route case file fits the route's types, bytes and count"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("document", new FieldShape.File(
                routeTypes.collect { new MediaTypeName(it) }, routeBytes), new HowMany.Many(routeCount))])
        def caseGives = half(DeclarationSide.GIVES, [taken("document", new FieldShape.File(
                caseTypes.collect { new MediaTypeName(it) }, caseBytes), new HowMany.Many(caseCount))])
        pinned[REFUND] = new StoredDeclarations.Halves(escalateTakes, caseGives)
        def chosen = routeCase("Billing", REFUND, escalating())
        def answering = step("route", routing(route.runs().discriminator(), routeGives, [chosen]),
                null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) ==
                (fits ? [] : [problem(CASE_GIVES_PAST_LIMIT,
                        new ContentPlace.AtCaseField(answering.id(), chosen.id(), caseGives.keyAt([0])))])

        where:
        routeTypes                               | caseTypes                                | routeBytes | caseBytes | routeCount | caseCount || fits
        ["application/pdf", "image/png"]        | ["application/pdf"]                     | 1024L      | 1024L     | 2          | 2         || true
        ["application/pdf"]                     | ["application/pdf", "image/png"]        | 1024L      | 1024L     | 2          | 2         || false
        ["application/pdf"]                     | ["application/pdf"]                     | 1024L      | 1025L     | 2          | 2         || false
        ["application/pdf"]                     | ["application/pdf"]                     | 1024L      | 1024L     | 2          | 3         || false
        ["application/pdf"]                     | ["application/pdf"]                     | 1024L      | 1024L     | 2          | 2         || true
    }

    def "a case field is refused under its own key for an undeclared kind or bound mismatch"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [new Field(new FieldName("note"), null, null,
                routeShape, routeMany, new Demand.Given(routeMustBe))])
        def caseGives = half(DeclarationSide.GIVES, [taken(caseName, caseShape, caseMany)])
        pinned[REFUND] = new StoredDeclarations.Halves(escalateTakes, caseGives)
        def chosen = routeCase("Billing", REFUND, escalating())
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [chosen]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) == [
                problem(code, new ContentPlace.AtCaseField(answering.id(), chosen.id(), caseGives.keyAt([0])))]

        where:
        caseName | routeMustBe | routeShape | routeMany           | caseShape                              | caseMany            || code
        "extra"  | false       | text(100)  | new HowMany.One()    | text(50)                               | new HowMany.One()   || CASE_GIVES_UNDECLARED
        "note"   | true        | text(100)  | new HowMany.One()    | new FieldShape.Plain(FieldKind.NUMBER) | new HowMany.One()   || CASE_GIVES_OTHER_KIND
        "note"   | true        | text(100)  | new HowMany.One()    | text(100)                              | new HowMany.Many(2) || CASE_GIVES_OTHER_KIND
        "note"   | true        | text(100)  | new HowMany.One()    | text(101)                              | new HowMany.One()   || CASE_GIVES_PAST_LIMIT
        "note"   | true        | text(100)  | new HowMany.Many(3) | text(100)                              | new HowMany.Many(4) || CASE_GIVES_PAST_LIMIT
    }

    def "fallback's nested extra field uses the target key and missing required field uses the route key"() {
        given:
        def routeGives = half(DeclarationSide.GIVES, [taken("details", new FieldShape.Nested([
                taken("required", text(10)), optional("hint", text(10))]))])
        def caseGives = half(DeclarationSide.GIVES, [taken("details", new FieldShape.Nested(fields))])
        pinned[REFUND] = new StoredDeclarations.Halves(escalateTakes, routeGives)
        pinned[NARROW] = new StoredDeclarations.Halves(escalateTakes, caseGives)
        def fallback = routeCase(null, NARROW, escalating())
        def answering = step("route", routing(route.runs().discriminator(), routeGives,
                [routeCase("Billing", REFUND, escalating()), fallback]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) == [problem(code,
                new ContentPlace.AtCaseField(answering.id(), fallback.id(),
                        inCase ? caseGives.keyAt([0, 0]) : routeGives.keyAt([0, 0])))]

        where:
        fields                                  | inCase || code
        [taken("extra", text(10)), taken("required", text(10))] | true || CASE_GIVES_UNDECLARED
        [optional("hint", text(10))]            | false  || CASE_MAY_GIVE_NOTHING
    }

    def "ordinary and fallback cases each name every undeclared output field"() {
        given:
        def chosenGives = half(DeclarationSide.GIVES, [taken("first", text(10)), taken("later", text(10))])
        def fallbackGives = half(DeclarationSide.GIVES, [taken("one", text(10)), taken("another", text(10))])
        pinned[REFUND] = new StoredDeclarations.Halves(escalateTakes, chosenGives)
        pinned[NARROW] = new StoredDeclarations.Halves(escalateTakes, fallbackGives)
        def chosen = routeCase("Billing", REFUND, escalating())
        def fallback = routeCase(null, NARROW, escalating())
        def answering = step("route", routing(route.runs().discriminator(), nothing,
                [chosen, fallback]), null, null, null, [])

        expect:
        problems(workflow([classify, answering], [output])) == [
                problem(CASE_GIVES_UNDECLARED,
                        new ContentPlace.AtCaseField(answering.id(), chosen.id(), chosenGives.keyAt([0]))),
                problem(CASE_GIVES_UNDECLARED,
                        new ContentPlace.AtCaseField(answering.id(), chosen.id(), chosenGives.keyAt([1]))),
                problem(CASE_GIVES_UNDECLARED,
                        new ContentPlace.AtCaseField(answering.id(), fallback.id(), fallbackGives.keyAt([0]))),
                problem(CASE_GIVES_UNDECLARED,
                        new ContentPlace.AtCaseField(answering.id(), fallback.id(), fallbackGives.keyAt([1])))]
    }

    def "each ordinary and fallback case lists twenty fields but counts its twenty-first"() {
        given:
        def chosenGives = half(DeclarationSide.GIVES, (1..21).collect { taken("chosen${it}", text(10)) })
        def fallbackGives = half(DeclarationSide.GIVES, (1..21).collect { taken("fallback${it}", text(10)) })
        pinned[REFUND] = new StoredDeclarations.Halves(escalateTakes, chosenGives)
        pinned[NARROW] = new StoredDeclarations.Halves(escalateTakes, fallbackGives)
        def chosen = routeCase("Billing", REFUND, escalating())
        def fallback = routeCase(null, NARROW, escalating())
        def answering = step("route", routing(route.runs().discriminator(), nothing,
                [chosen, fallback]), null, null, null, [])

        when:
        def found = WorkflowProblems.report(workflow([classify, answering], [output]), resolved(), DeployedModels.HELD, LibraryStore.FILES)

        then:
        found.total() == 42
        found.problems().size() == 40
        found.problems()*.code().every { it == CASE_GIVES_UNDECLARED }
        found.problems()[0].place() == new ContentPlace.AtCaseField(answering.id(), chosen.id(), chosenGives.keyAt([0]))
        found.problems()[19].place() == new ContentPlace.AtCaseField(answering.id(), chosen.id(), chosenGives.keyAt([19]))
        found.problems()[20].place() == new ContentPlace.AtCaseField(answering.id(), fallback.id(), fallbackGives.keyAt([0]))
        found.problems()[39].place() == new ContentPlace.AtCaseField(answering.id(), fallback.id(), fallbackGives.keyAt([19]))
    }


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


    def "a step reading what a searching step before it gives back is held to what it fills, as the way declares it"() {
        given:
        def search = searching(way, [written("Find it")], EVERY_STANDING)
        def fed = binding("complaint", fromStep(search, field))
        def after = step("after", new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, ESCALATE), new Producer.Person(), 1,
                null, [fed])

        expect:
        searchProblems(workflow([classify, search, after], [output])) ==
                atSearch.collect { problem(it, at(search)) } + atBinding.collect { problem(it, bound(fed)) }

        where:
        way    | field        || atSearch              | atBinding
        "web"  | "body"       || []                    | []
        "web"  | "title"      || []                    | []
        "web"  | "references" || []                    | [SOURCE_MAY_BE_EMPTY]
        "web"  | "nothing"    || []                    | [SOURCE_UNKNOWN]
        "news" | "body"       || [SEARCH_WAY_NOT_HELD] | [SOURCE_UNKNOWN]
        null   | "body"       || [RUNS_MISSING]        | [SOURCE_UNKNOWN]
    }


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

    def "approved reading excludes current code declaration failures before the 200 problem cap and keeps independent failures"() {
        given:
        released = [:]
        def codeSteps = (1..201).collect { number ->
            new StoredWorkflow.Step(new UUID(0x11L, number), new StepId("send_${number}"),
                    new StoredWorkflow.Runs.Code("send_reply"), new Producer.Code(), 1, null, [])
        }
        def independent = binding("complaint", new BindingSource.WorkflowInput(Pointer.parse("missing")))
        def after = step("after", classify.runs(), new Producer.Person(), 1, null, [independent])
        def content = workflow(codeSteps + [after], [binding("summary", fromStep(after, "summary"))])

        when:
        def full = WorkflowProblems.judged(content, resolved(), DeployedModels.HELD, LibraryStore.FILES, false)
        def approved = WorkflowProblems.judged(content, resolved(), DeployedModels.HELD, LibraryStore.FILES, true)

        then:
        full.problems().total() == 202
        full.problems().problems().size() == 200
        full.problems().problems().every { it.code() == CODE_STEP_NOT_DECLARED }
        approved.problems().total() == 1
        approved.problems().problems() == [problem(SOURCE_UNKNOWN, bound(independent))]
    }

    def "approved reading hides current code takes and gives diagnostics but keeps stored duplicate bindings"() {
        given:
        released.send_reply = new StoredDeclarations.Halves(
                half(DeclarationSide.TAKES, [taken("new", text(32))]),
                half(DeclarationSide.GIVES, [taken("new", text(32))]))
        def first = binding("reply", new BindingSource.WorkflowInput(Pointer.parse("channel")))
        def repeated = binding("reply", new BindingSource.WorkflowInput(Pointer.parse("channel")))
        def code = sending(new Producer.Code(), [first, repeated])
        def downstream = binding("complaint", fromStep(code, "receipt"))
        def after = step("after", classify.runs(), new Producer.Person(), 1, null, [downstream])
        def content = workflow([code, after], [binding("summary", fromStep(after, "summary"))])

        when:
        def full = WorkflowProblems.judged(content, resolved(), DeployedModels.HELD, LibraryStore.FILES, false)
        def approved = WorkflowProblems.judged(content, resolved(), DeployedModels.HELD, LibraryStore.FILES, true)

        then:
        full.problems().problems()*.code().containsAll([TARGET_UNKNOWN, TARGET_BOUND_TWICE, INPUT_UNBOUND, SOURCE_UNKNOWN])
        approved.problems().problems() == [problem(TARGET_BOUND_TWICE, bound(repeated))]
        approved.problems().total() == 1
    }

    def "approved reading hides code-sourced route and search judgments but keeps an independent case failure"() {
        given:
        nameable = ["current"] as Set
        released = [current: new StoredDeclarations.Halves(half(DeclarationSide.TAKES, []),
                half(DeclarationSide.GIVES, [taken("category", new FieldShape.Term(CATEGORIES)),
                                                  taken("attachment", new FieldShape.File(
                                                          [new MediaTypeName("application/pdf")], 1024L))]))]
        def code = step("send", new StoredWorkflow.Runs.Code("current"), new Producer.Code(), 1, null, [])
        def chosenBy = binding(null, fromStep(code, "category"))
        def routeCase = routeCase("Refund", null, [])
        def routed = step("route", routing(chosenBy, nothing, [routeCase]), null, null, null, [])
        def filePart = reading(fromStep(code, "attachment"))
        def search = searching("web", [written("Find "), filePart], EVERY_STANDING)
        def content = workflow([code, routed, search], [])

        when:
        def full = WorkflowProblems.judged(content, resolved(), DeployedModels.SEARCHING, LibraryStore.FILES, false)
        def approved = WorkflowProblems.judged(content, resolved(), DeployedModels.SEARCHING, LibraryStore.FILES, true)

        then:
        full.problems().problems()*.code().containsAll([CASE_TARGET_MISSING, CASE_NOT_OFFERED, QUESTION_TAKES_FILE])
        approved.problems().problems().findAll { it.place() == new ContentPlace.AtCase(routed.id(), routeCase.id()) } ==
                [problem(CASE_TARGET_MISSING, new ContentPlace.AtCase(routed.id(), routeCase.id()))]
        !approved.problems().problems()*.code().contains(CASE_NOT_OFFERED)
        !approved.problems().problems()*.code().contains(QUESTION_TAKES_FILE)
    }


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
                [new ContentProblem(CONSTANT_TOO_LONG, bound(written), excess, null)]

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
        WorkflowProblems.report(stored(takes, narrow, [classify, route], [output]), resolved(), DeployedModels.HELD, LibraryStore.FILES).problems() ==
                [problem(SOURCE_DOES_NOT_FIT, new ContentPlace.AtBinding(ContentPart.GIVES, output.id()))]
    }

    def "runs that may be helped name a model the deployment holds, in a mode it offers"() {
        expect:
        WorkflowProblems.report(stored(takes, gives, [classify, route], [output], helped, helper), resolved(),
                DeployedModels.HELD, LibraryStore.FILES).problems() == expected.collect { problem(it, new ContentPlace.Whole(ContentPart.HELPER)) }

        where:
        helped | helper                          || expected
        false  | null                            || []
        true   | choice("general", "research")   || []
        true   | null                            || [HELPER_MISSING]
        true   | choice("large", null)           || [HELPER_NOT_HELD]
        true   | choice("small", "research")     || [HELPER_MODE_NOT_OFFERED]
    }


    def "every problem is named, in the order the content reads"() {
        given:
        def unlimited = half(DeclarationSide.TAKES, [taken("complaint", text(null))])
        def unbound = step("classify", classify.runs(), null, 3, null, [])
        def search = searching("web", [reading(new BindingSource.WorkflowInput(Pointer.parse("missing")))],
                [(SearchWay.BODY): FieldStanding.ALWAYS], null)
        def stray = binding("summary", new BindingSource.WorkflowInput(Pointer.parse("complaint")))

        when:
        def found = WorkflowProblems.report(stored(unlimited, gives, [unbound, search], [stray], true, null), resolved(),
                DeployedModels.HELD, LibraryStore.FILES).problems()

        then:
        found*.code() == [LONGEST_MISSING, PRODUCER_MISSING, INPUT_UNBOUND, PRODUCER_MISSING, SEARCH_STANDING_MISSING,
                          SOURCE_UNKNOWN, SEARCH_QUESTION_MAY_BE_EMPTY, OUTPUT_NOT_FROM_STEP, HELPER_MISSING]
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
        WorkflowProblems.report(content, resolution, catalog, LibraryStore.FILES).problems()

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
        WorkflowProblems.report(workflow, resolved(), DeployedModels.HELD, LibraryStore.FILES).problems()
    }

    private List<ContentProblem> searchProblems(StoredWorkflow workflow) {
        WorkflowProblems.report(workflow, resolved(), DeployedModels.SEARCHING, LibraryStore.FILES).problems()
    }


    private WorkflowProblems.Resolved resolved() {
        new WorkflowProblems.Resolved(pinned, OfferedLists.offering([(CATEGORIES): ["Billing", "Delivery"]]),
                nameable, released, listsUnserved, asked, ways)
    }


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

    /** Flag names and text limits change size in even steps; adjust the text name for an odd remainder. */
    private static CodeStepDeclaration reviewedCodePast(long over) {
        long sent = Declaration.MOST_SENT + over
        def named = ["note", "notes"].collectMany { noteName ->
            int fits = 1
            int past = Declaration.MOST_SENT as int
            while (past - fits > 1) {
                int middle = (fits + past).intdiv(2) as int
                if (codeReviewSent(declaredCode(noteName, middle, "f")) <= sent) {
                    fits = middle
                } else {
                    past = middle
                }
            }
            (1..63).collect { declaredCode(noteName, fits, "f" * it) }
        }.find { codeReviewSent(it) == sent }
        assert named != null: "no flag's name makes up what is short"
        named
    }

    private static CodeStepDeclaration declaredCode(String noteName, int longest, String flag) {
        new SpecCodeStep("loud", [SpecCodeStep.given(flag, new FieldShape.Plain(FieldKind.YES_NO))],
                [SpecCodeStep.standing(noteName, new FieldShape.Text(longest))], false).declaration()
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


    private static StoredWorkflow.Step sending(Producer producer, List<Binding> bindings) {
        step("send", new StoredWorkflow.Runs.Code("send_reply"), producer, 1, null, bindings)
    }

    private static StoredWorkflow.Step searching(
            String way, List<SearchQuestionPart> question, Map<FieldName, FieldStanding> standings,
            Producer producer = new Producer.Model(GENERAL, true), Integer tries = 3) {
        step("search", new StoredWorkflow.Runs.Search(way, question, standings), producer, tries, null, [])
    }

    private static SearchQuestionPart written(String text) {
        new SearchQuestionPart.LiteralPart(text)
    }

    private SearchQuestionPart.Read reading(BindingSource source) {
        new SearchQuestionPart.Read(new UUID(0x0fL, ++keysMade), source)
    }


    private List<SearchQuestionPart> question(List<Map<String, String>> parts) {
        parts.collect { part ->
            part.text != null ? written(part.text) : reading(new BindingSource.WorkflowInput(Pointer.parse(part.input)))
        }
    }

    /** One declared value supplies the size, and a legal short piece pins the 0/+1 sending boundary. */
    private List<SearchQuestionPart> boundaryParts(boolean told, long over, AskedField read, SearchQuestionPart.Read part) {
        int fits = 1
        int past = 2_000_000
        while (past - fits > 1) {
            int middle = (fits + past).intdiv(2) as int
            def bulk = new AskedField(new FieldName("bulk"), FieldKind.TEXT, middle, null, null, [], true, false)
            if (boundarySent(told, bulk, read) <= Declaration.MOST_SENT - 6000) {
                fits = middle
            } else {
                past = middle
            }
        }
        def bulk = new AskedField(new FieldName("bulk"), FieldKind.TEXT, fits, null, null, [], true, false)
        long shortOf = Declaration.MOST_SENT - boundarySent(told, bulk, read)
        assert shortOf >= 6000 && shortOf <= 8191
        takes = half(DeclarationSide.TAKES, [taken("complaint", text(4000)), taken("bulk", text(fits))])
        [written("a" * (shortOf + over)), reading(new BindingSource.WorkflowInput(Pointer.parse("bulk"))), part]
    }

    private static long boundarySent(boolean told, AskedField bulk, AskedField read) {
        SendMeasure.mostSentToSearch([
                new Payload.SearchPart.LiteralPart(""),
                new Payload.SearchPart.ValuePart(bulk, null),
                new Payload.SearchPart.ValuePart(read, null)], SearchAnswer.asked(DeployedModels.WEB), told)
    }

    private List<SearchQuestionPart> reviewBoundaryParts(long over) {
        int fits = 1
        int past = 2_000_000
        while (past - fits > 1) {
            int middle = (fits + past).intdiv(2) as int
            def bulk = new AskedField(new FieldName("bulk"), FieldKind.TEXT, middle, null, null, [], true, false)
            if (reviewSent(bulk) <= Declaration.MOST_SENT - 6000) {
                fits = middle
            } else {
                past = middle
            }
        }
        def bulk = new AskedField(new FieldName("bulk"), FieldKind.TEXT, fits, null, null, [], true, false)
        long shortOf = Declaration.MOST_SENT - reviewSent(bulk)
        assert shortOf >= 6000 && shortOf <= 8191
        takes = half(DeclarationSide.TAKES, [taken("bulk", text(fits))])
        gives = nothing
        [written("a" * (shortOf + over)), reading(new BindingSource.WorkflowInput(Pointer.parse("bulk")))]
    }

    private static long reviewSent(AskedField bulk) {
        SendMeasure.mostSentToReviewSearch([
                new Payload.SearchPart.LiteralPart(""), new Payload.SearchPart.ValuePart(bulk, null)],
                SearchAnswer.asked(DeployedModels.WEB))
    }


    private static long toldAdds() {
        searchSent([], true) - searchSent([], false)
    }

    private static long searchSent(List<Payload.SearchPart> literals, boolean told, AskedField read = COMPLAINT_TOLD) {
        SendMeasure.mostSentToSearch(literals + [new Payload.SearchPart.ValuePart(read, null)],
                SearchAnswer.asked(DeployedModels.WEB), told)
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
        new ContentProblem(code, place, null, null)
    }

    private static ModelChoice choice(String model, String mode) {
        new ModelChoice(new ModelName(model), mode == null ? null : new ModelMode(mode))
    }


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


    private static Field taken(String name, FieldShape shape, HowMany howMany = new HowMany.One()) {
        new Field(new FieldName(name), null, null, shape, howMany, new Demand.Given(true))
    }


    private static Field optional(String name, FieldShape shape) {
        new Field(new FieldName(name), null, null, shape, new HowMany.One(), new Demand.Given(false))
    }


    private static Field contact(boolean emailMustBe) {
        taken("contact", new FieldShape.Nested([
                new Field(new FieldName("email"), null, null, text(100), new HowMany.One(), new Demand.Given(emailMustBe))]))
    }

    private static EntryVersionId version(int last) {
        new EntryVersionId(new UUID(0x07L, last))
    }
}
