package org.lilradish.lite.app.library

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.registry.VersionStanding
import org.lilradish.lite.domain.search.SearchWay
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.SearchQuestionPart
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/** What the answer to a read is built from, where what the read gathered does not hold what the version names. */
class WorkflowControllerSpec extends Specification {

    static final EntryVersionId ASKED = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000f51"))

    static final UUID STEP = UUID.fromString("0000000c-0000-4000-8000-000000000f51")

    static final UUID FIRST_STEP = UUID.fromString("0000000c-0000-4000-8000-000000000f52")

    static final UUID PART = UUID.fromString("00000020-0000-4000-8000-000000000f51")

    static final UUID OTHER_PART = UUID.fromString("00000020-0000-4000-8000-000000000f52")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final StoredDeclarations.Half NOTHING_TAKEN = new StoredDeclarations.Half(
            new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), []), [])

    static final StoredDeclarations.Half NOTHING_GIVEN = new StoredDeclarations.Half(
            new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), []), [])

    static final Workflows.Offers NOTHING_OFFERED = new Workflows.Offers([], [], [], [], [], [])

    static final SearchWay WEB_SEARCH = new SearchWay("web_search", new ModelToolName("web_tool"), 200, 4000, 2000, 1000)

    def "a step pinning a version whose declaration the read did not gather is a reader gone wrong"() {
        when:
        WorkflowController.answer(view(workflow([pinnedStep()], []), [:], [(ASKED): pinned()]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A step pins version ${ASKED.value()}, which no reader declared" as String
    }

    def "a step pinning a version the read did not name is a reader gone wrong"() {
        when:
        WorkflowController.answer(view(workflow([pinnedStep()], []),
                [(ASKED): new StoredDeclarations.Halves(NOTHING_TAKEN, NOTHING_GIVEN)], [:]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A step pins version ${ASKED.value()}, which no reader named" as String
    }

    def "a binding reading a step its version does not hold is a store gone wrong"() {
        given:
        def stray = new Binding(UUID.fromString("0000000e-0000-4000-8000-000000000f51"), Pointer.parse("summary"),
                new BindingSource.StepOutput(UUID.fromString("0000000c-0000-4000-8000-0000000000ff"),
                        Pointer.parse("summary")))

        when:
        WorkflowController.answer(view(workflow([], [stray]), [:], [:]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "What is read names a step its version does not hold"
    }

    /** Another group's list is never told, so what the read did not gather of a code step is answered as nothing. */
    def "a code step's step is answered with what the release declares of it only where the read gathered it"() {
        given:
        def sending = StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration())
        def step = new StoredWorkflow.Step(STEP, new StepId("send"), new StoredWorkflow.Runs.Code("send_reply"),
                new Producer.Code(), 1, null, [])

        when:
        def runs = WorkflowController.answer(new Workflows.WorkflowView(workflow([step], []), [:],
                gathered ? [send_reply: sending] : [:], [:], [:], [:], new ContentProblemReport.Result([], 0), [], NOTHING_OFFERED)).steps()[0].runs()

        then:
        runs.codeStep() == "send_reply"
        runs.takes() == (gathered ? DeclarationAnswers.of(sending.takes(), [:]) : null)
        runs.gives() == (gathered ? DeclarationAnswers.of(sending.gives(), [:]) : null)
        runs.version() == null

        where:
        gathered << [true, false]
    }

    /** A part read in carries its key, which a problem with what it reads is placed by. */
    def "a search's step is answered with its way, its question in order as a page names each part, and each standing chosen"() {
        given:
        def first = new StoredWorkflow.Step(FIRST_STEP, new StepId("summarise"), new StoredWorkflow.Runs.Unchosen(), null,
                null, null, [])
        def search = new StoredWorkflow.Step(STEP, new StepId("find_context"), new StoredWorkflow.Runs.Search(
                "web_search",
                [new SearchQuestionPart.LiteralPart("Find "),
                 new SearchQuestionPart.Read(PART, new BindingSource.WorkflowInput(Pointer.parse("complaint"))),
                 new SearchQuestionPart.Read(OTHER_PART, new BindingSource.StepOutput(FIRST_STEP, Pointer.parse("summary")))],
                [(SearchWay.OTHER): FieldStanding.NEVER, (SearchWay.TITLE): FieldStanding.ALWAYS]),
                new Producer.Person(), 2, null, [])

        when:
        def runs = WorkflowController.answer(view(workflow([first, search], []), [:], [:])).steps()[1].runs()

        then:
        JSON.convertValue(JSON.valueToTree(runs), Map) == [
                kind     : "search",
                searchWay: "web_search",
                question : [[text: "Find "], [partId: PART.toString(), from: [input: "complaint"]],
                            [partId: OTHER_PART.toString(), from: [step: 0, path: "summary"]]],
                standings: [title: "always", other: "never"]]
        runs.standings().keySet() as List == ["title", "other"]
    }

    /** Each field keyed as a problem placed in it is keyed, so a page can find it by that key. */
    def "a search's step is answered with what its way gives back where the release holds the way"() {
        given:
        def gives = StoredDeclarations.ofSearchWay(WEB_SEARCH)
        def keys = gives.keys()*.id()*.toString()

        when:
        def runs = WorkflowController.answer(searchView([web_search: gives])).steps()[0].runs()

        then:
        JSON.convertValue(JSON.valueToTree(runs.gives()), List) == [
                [fieldId: keys[0], name: "title", kind: "text", lines: "several", longest: 200, many: false, mustBeGiven: true],
                [fieldId: keys[1], name: "body", kind: "text", lines: "several", longest: 4000, many: false, mustBeGiven: true],
                [fieldId: keys[2], name: "references", kind: "text", lines: "several", longest: 2000, many: false, mustBeGiven: false],
                [fieldId: keys[3], name: "other", kind: "text", lines: "several", longest: 1000, many: false, mustBeGiven: false]]
        runs.searchWay() == "web_search"
    }

    def "a search's step running a way the release does not hold is answered without what it gives back"() {
        when:
        def runs = WorkflowController.answer(searchView(held)).steps()[0].runs()

        then:
        runs.gives() == null
        !JSON.convertValue(JSON.valueToTree(runs), Map).containsKey("gives")
        runs.searchWay() == "web_search"

        where:
        held << [[:], [news: StoredDeclarations.ofSearchWay(WEB_SEARCH)]]
    }

    def "a search nothing is chosen for yet is answered as asking nothing and standing nothing, its way left out"() {
        given:
        def search = new StoredWorkflow.Step(STEP, new StepId("find_context"),
                new StoredWorkflow.Runs.Search(null, [], [:]), null, null, null, [])

        when:
        def runs = WorkflowController.answer(view(workflow([search], []), [:], [:])).steps()[0].runs()

        then:
        JSON.convertValue(JSON.valueToTree(runs), Map) == [kind: "search", question: [], standings: [:]]
    }

    def "a part of a search's question renders for a log without the text written or where it reads from"() {
        expect:
        part.toString() == rendered
        !part.toString().contains("Demo Sample")
        !part.toString().contains("complaint")

        where:
        part                                                                                  || rendered
        new WorkflowController.WrittenPart("Demo Sample")                                     || 'WorkflowController$WrittenPart()'
        new WorkflowController.ReadPart(PART, new WorkflowController.InputSource("complaint")) || 'WorkflowController$ReadPart(partId=' + PART + ')'
    }

    /** Each way answered by its name, the tool it needs and what it gives back under the keys a step running it has. */
    def "what a draft may choose from holds each way to search the release holds"() {
        given:
        def gives = StoredDeclarations.ofSearchWay(WEB_SEARCH)
        def offers = new Workflows.Offers([], [], [], [], [new Workflows.OfferedSearchWay(WEB_SEARCH, gives)], [])

        when:
        def offered = WorkflowController.answer(new Workflows.WorkflowView(workflow([], []), [:], [:], [:], [:], [:],
                new ContentProblemReport.Result([], 0), [], offers)).offered()

        then:
        offered.searchWays() == [new WorkflowController.SearchWayAnswer("web_search", "web_tool", DeclarationAnswers.of(gives, [:]))]
        offered.codeSteps().isEmpty()
        offered.models().isEmpty()
    }

    def "models offer only their tool names in deployment order, and an empty list when none"() {
        given:
        def offers = new Workflows.Offers([], [], [], [], [], DeployedModels.SEARCHING.all())

        when:
        def offered = WorkflowController.answer(new Workflows.WorkflowView(workflow([], []), [:], [:], [:], [:], [:],
                new ContentProblemReport.Result([], 0), [], offers)).offered()

        then:
        offered.models() == [new WorkflowController.ModelAnswer("general", ["research"],
                ["web_search", "archive_search"]), new WorkflowController.ModelAnswer("small", [], [])]
    }

    private static StoredWorkflow.Step pinnedStep() {
        new StoredWorkflow.Step(STEP, new StepId("classify"), new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, ASKED),
                null, null, null, [])
    }

    private static StoredWorkflow workflow(List<StoredWorkflow.Step> steps, List<Binding> outputs) {
        new StoredWorkflow(1, NOTHING_TAKEN, NOTHING_GIVEN, steps, outputs, null, false, false, false, null)
    }

    private static PinnedVersions.PinnedVersion pinned() {
        new PinnedVersions.PinnedVersion(new EntryName("Classify"), ASKED, 1, VersionStanding.IN_SERVICE, null)
    }

    private static Workflows.WorkflowView view(
            StoredWorkflow stored,
            Map<EntryVersionId, StoredDeclarations.Halves> declared,
            Map<EntryVersionId, PinnedVersions.PinnedVersion> pins) {
        new Workflows.WorkflowView(stored, declared, [:], [:], pins, [:], new ContentProblemReport.Result([], 0), [], NOTHING_OFFERED)
    }

    /** One searching step running the way named {@code web_search}, read with what the ways {@code held} give back. */
    private static Workflows.WorkflowView searchView(Map<String, StoredDeclarations.Half> held) {
        def search = new StoredWorkflow.Step(STEP, new StepId("find_context"), new StoredWorkflow.Runs.Search(
                "web_search", [new SearchQuestionPart.LiteralPart("Find it")], [:]), new Producer.Person(), 2, null, [])
        new Workflows.WorkflowView(workflow([search], []), [:], [:], held, [:], [:], new ContentProblemReport.Result([], 0),
                [], NOTHING_OFFERED)
    }
}
