package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.SendMeasure
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.VersionStanding
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Reading one workflow version as one moment, on a real server running the real baseline: what it holds, what
 * submitting would refuse of it now, what it pins as a reader names it, and everything a draft may choose from.
 */
class WorkflowsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000d81"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000d82"

    /** An operator here. */
    static final String ANN = "00000002-0000-4000-8000-000000000d81"

    static final UserId ANN_USER = new UserId("000d81")

    /** Holds a role only in the other group. */
    static final String DAN = "00000002-0000-4000-8000-000000000d82"

    static final UserId DAN_USER = new UserId("000d82")

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000d81"

    static final String DRAFT = "00000007-0000-4000-8000-000000000d81"

    static final String WORKFLOW_IN_SERVICE = "00000007-0000-4000-8000-000000000d80"

    static final String QUESTION = "00000006-0000-4000-8000-000000000d82"

    static final String ASKED_OLD = "00000007-0000-4000-8000-000000000d82"

    static final String ASKED_NEW = "00000007-0000-4000-8000-000000000d83"

    static final String LIST = "00000006-0000-4000-8000-000000000d84"

    static final String LISTED = "00000007-0000-4000-8000-000000000d84"

    static final String OTHER_WORKFLOW = "00000006-0000-4000-8000-000000000d85"

    static final String OTHER_VERSION = "00000007-0000-4000-8000-000000000d85"

    static final String STEP = "0000000c-0000-4000-8000-000000000d81"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    Workflows workflows

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "workflows_" + (++databasesMade))
        workflows = new Workflows(store.session, new GroupRoles(store.session), LibraryStore.MODELS,
                new ReleasedCodeSteps(LibraryStore.CODE_STEPS), store.transactionManager())
        store.person(ANN, "000d81")
        store.person(DAN, "000d82")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "operator")
        store.member(OTHER_GROUP, DAN, "owner")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(LISTED, LIST, 1)
        store.content(LISTED, "reference_list")
        store.entry(QUESTION, GROUP, "question", "Classify")
        [ASKED_OLD, ASKED_NEW].eachWithIndex { version, index ->
            store.seeded(version, QUESTION, index + 1)
            store.content(version, "question", "Say which category it is.")
            store.termField(key(version), version, "question", LISTED)
        }
        store.entry(WORKFLOW, GROUP, "workflow", "Handle a complaint")
        store.seeded(WORKFLOW_IN_SERVICE, WORKFLOW, 1)
        store.content(WORKFLOW_IN_SERVICE, "workflow")
        store.version(DRAFT, WORKFLOW, 2, FIRST_STEWARD)
        store.content(DRAFT, "workflow")
        store.step(STEP, DRAFT, 1, ASKED_OLD, "question")
    }

    /** A pin that is not the newest in service says which is, as any pin does; the step names the version it pinned. */
    def "reads what a version holds, what it pins as a reader names it, and what submitting would refuse of it now"() {
        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.stored().steps()*.id() == [UUID.fromString(STEP)]
        view.stored().revision() == 1
        view.pins()[versionId(ASKED_OLD)] == new PinnedVersions.PinnedVersion(new EntryName("Classify"),
                versionId(ASKED_OLD), 1, VersionStanding.IN_SERVICE,
                new RetiredPinsRefusal.NumberedVersion(versionId(ASKED_NEW), 2))
        view.pins()[versionId(LISTED)].standing() == VersionStanding.IN_SERVICE
        view.declared()[versionId(ASKED_OLD)].gives().declaration().fields()*.name() == [new FieldName("category")]
        view.problems() == [ContentProblemCode.PRODUCER_MISSING, ContentProblemCode.TRIES_MISSING].collect {
            new ContentProblem(it, new ContentPlace.AtStep(UUID.fromString(STEP)), null)
        }
    }

    /**
     * Worked out from the question the step pins, against the models held as it is read; a step sending past what
     * its model takes is still one submitting takes, so nothing is refused for it.
     */
    def "a step that could send its models more than they take says so for each, and refuses nothing for it"() {
        given:
        takingLetters(ASKED_OLD, longest)
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, target_path, constant, created_by)
                values (?::uuid, ?::uuid, 'letter', '"x"'::jsonb, ?::uuid)
                """).params(DRAFT, STEP, SEEDER).update()
        asked(STEP, producer, told, reviewer)
        def asking = new QuestionsAsked(store.session, store.transactionManager()).of(groupId(GROUP), versionId(ASKED_OLD))

        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.sendsPast() == said.collect { role, name ->
            def model = LibraryStore.MODELS.find(new ModelName(name)).get()
            long sent = role == SendingRole.REVIEWING
                    ? SendMeasure.mostSentToReview(asking)
                    : SendMeasure.mostSent(asking, told)
            new SendPast(UUID.fromString(STEP), role, model.name(), model.unitsOf(sent) - model.sentPerCallLimit())
        }
        view.problems() == []

        where:
        longest | producer | told  | reviewer  || said
        100_000 | "model"  | true  | "general" || [[SendingRole.PRODUCING, "small"], [SendingRole.REVIEWING, "general"]]
        100_000 | "model"  | false | null      || [[SendingRole.PRODUCING, "small"]]
        100_000 | "person" | false | "general" || [[SendingRole.REVIEWING, "general"]]
        10      | "model"  | true  | "general" || []
    }

    /**
     * Read twice, against what two deployments hold: the figure is worked out against the models held at each
     * reading, and nothing of it is written to the step either reading reads.
     */
    def "a version in service is measured against the models held as it is read, and the figure is never stored"() {
        given:
        def served = "0000000c-0000-4000-8000-000000000d84"
        store.step(served, WORKFLOW_IN_SERVICE, 1, ASKED_OLD, "question")
        takingLetters(ASKED_OLD, 100_000)
        asked(served, "model", false, null)
        def roomier = new Workflows(store.session, new GroupRoles(store.session), new ModelCatalog([
                new DeployedModel(new ModelName("small"), [], 10_000_000, 4.0G, 4_000, [])]),
                new ReleasedCodeSteps(LibraryStore.CODE_STEPS), store.transactionManager())
        def stepsBefore = stepsOf(WORKFLOW_IN_SERVICE)

        when:
        def heldNow = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(WORKFLOW_IN_SERVICE), ANN_USER)
        def heldLater = roomier.read(groupId(GROUP), entryId(WORKFLOW), versionId(WORKFLOW_IN_SERVICE), ANN_USER)

        then:
        heldNow.sendsPast()*.step() == [UUID.fromString(served)]
        heldNow.sendsPast()*.role() == [SendingRole.PRODUCING]
        heldLater.sendsPast() == []
        stepsOf(WORKFLOW_IN_SERVICE) == stepsBefore
    }

    /** A question submitting would refuse could not be told, so it is never measured, however much it could send. */
    def "a step asking a question submitting would refuse reads as any other, and says nothing of what it could send"() {
        given:
        takingLetters(ASKED_OLD, 8_388_609)
        asked(STEP, "model", true, "general")

        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.sendsPast() == []
        view.stored().steps()*.id() == [UUID.fromString(STEP)]
    }

    /**
     * What may be chosen now: every version of the group's in service of each kind, each with what it declares,
     * the code steps published to it that this release holds, with what the release declares of them, and every
     * model the deployment holds; a draft, another group's and a retired version are none of them, and neither is a
     * code step published elsewhere or one the release does not hold.
     */
    def "offers every version of the group's in service it may pin, the code steps published to it the release holds, and the models held"() {
        given:
        store.retired(ASKED_OLD, FIRST_STEWARD)
        store.entry(OTHER_WORKFLOW, OTHER_GROUP, "workflow", "Elsewhere")
        store.seeded(OTHER_VERSION, OTHER_WORKFLOW, 1)
        store.content(OTHER_VERSION, "workflow")
        store.session.sql("alter type code_step add value 'send_reply'").update()
        store.session.sql("alter type code_step add value 'archive'").update()
        store.session.sql("alter type code_step add value 'close_ticket'").update()
        publish("send_reply", "SUPPORT")
        publish("close_ticket", null)
        publish("archive", "BILLING")

        when:
        def offers = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER).offers()

        then:
        offers.lists() == [new PinnedVersions.OfferedVersion(new EntryName("Categories"), versionId(LISTED), 1)]
        offers.questions()*.version() == [new PinnedVersions.OfferedVersion(new EntryName("Classify"), versionId(ASKED_NEW), 2)]
        offers.questions()[0].declared().gives().declaration().fields()*.name() == [new FieldName("category")]
        offers.workflows()*.version() ==
                [new PinnedVersions.OfferedVersion(new EntryName("Handle a complaint"), versionId(WORKFLOW_IN_SERVICE), 1)]
        offers.codeSteps() == [new Workflows.OfferedCodeStep("send_reply",
                StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration()))]
        offers.models() == LibraryStore.MODELS.all()
    }

    /**
     * A code step's field may pin any group's list, or one not here at all; only one pinning the group's own lists,
     * each in service, could be written into a draft that could be submitted, so no other is offered.
     */
    def "a code step whose declaration pins a list is offered only where the group's own list is in service, that list then named with its terms"() {
        given: "a list nothing else read here pins: the group's own in service, as a draft or retired, another's, or none"
        def entry = "00000006-0000-4000-8000-000000000d87"
        def listed = "00000007-0000-4000-8000-000000000d87"
        if (standing != "none") {
            store.entry(entry, standing == "theirs" ? OTHER_GROUP : GROUP, "reference_list", "Priorities")
        }
        if (standing == "draft") {
            store.version(listed, entry, 1, FIRST_STEWARD)
        } else if (standing != "none") {
            store.seeded(listed, entry, 1, standing == "retired")
        }
        if (standing != "none") {
            store.content(listed, "reference_list")
        }
        def list = versionId(listed)
        def sortOut = new SpecCodeStep("sort_out", [SpecCodeStep.given("reply", new FieldShape.Text(2000))],
                [SpecCodeStep.standing("category", new FieldShape.Term(list))], false)
        store.session.sql("alter type code_step add value 'sort_out'").update()
        publish("sort_out", "SUPPORT")
        def reading = new Workflows(store.session, new GroupRoles(store.session), LibraryStore.MODELS,
                new ReleasedCodeSteps(new CodeSteps([sortOut])), store.transactionManager())

        when:
        def view = reading.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.offers().codeSteps()*.name() == (offered ? ["sort_out"] : [])
        view.pins().containsKey(list) == offered
        view.terms().containsKey(list) == offered

        where:
        standing  || offered
        "ours"    || true
        "draft"   || false
        "retired" || false
        "theirs"  || false
        "none"    || false
    }

    /**
     * What a draft's code step declares is read to name its inputs where the group may name it and every list it pins
     * is here; another group's list is never told, and one not here could be named by no reader, so the draft reads.
     */
    def "a draft naming a code step reads what it declares only where each list it pins is the group's, naming why at the step"() {
        given:
        def entry = "00000006-0000-4000-8000-000000000d88"
        def listed = "00000007-0000-4000-8000-000000000d88"
        if (standing != "none") {
            store.entry(entry, standing == "theirs" ? OTHER_GROUP : GROUP, "reference_list", "Priorities")
            store.seeded(listed, entry, 1, standing == "retired")
            store.content(listed, "reference_list")
        }
        def sortOut = new SpecCodeStep("sort_out", [SpecCodeStep.given("reply", new FieldShape.Text(2000))],
                [SpecCodeStep.standing("category", new FieldShape.Term(versionId(listed)))], false)
        store.session.sql("alter type code_step add value 'sort_out'").update()
        publish("sort_out", "SUPPORT")
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step,
                                            producer, tries, created_by)
                values (?::uuid, ?::uuid, 2, 'sort', 'code_step', 'sort_out', 'code', 1, ?::uuid)
                """).params("0000000c-0000-4000-8000-000000000d88", DRAFT, SEEDER).update()
        def reading = new Workflows(store.session, new GroupRoles(store.session), LibraryStore.MODELS,
                new ReleasedCodeSteps(new CodeSteps([sortOut])), store.transactionManager())

        when:
        def view = reading.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.problems().findAll { it.place() == new ContentPlace.AtStep(UUID.fromString("0000000c-0000-4000-8000-000000000d88")) }
                *.code() == codes
        view.codeSteps().keySet() as List == (read ? ["sort_out"] : [])
        view.pins().containsKey(versionId(listed)) == read

        where:
        standing  || read  | codes
        "ours"    || true  | []
        "retired" || true  | [ContentProblemCode.CODE_STEP_LIST_RETIRED]
        "theirs"  || false | [ContentProblemCode.CODE_STEP_NOT_PUBLISHED]
        "none"    || false | [ContentProblemCode.CODE_STEP_LIST_MISSING]
    }

    /** A route's own field pins its list as any field does, and that list's terms are what a later route may be on. */
    def "a route's own field pinning a list is named among the pins, and holds the cases of a route choosing by it"() {
        given:
        def refundKinds = "00000006-0000-4000-8000-000000000d86"
        def refundKindsListed = "00000007-0000-4000-8000-000000000d86"
        def firstRoute = "0000000c-0000-4000-8000-000000000d82"
        def secondRoute = "0000000c-0000-4000-8000-000000000d83"
        def secondCase = "0000000d-0000-4000-8000-000000000d83"
        store.entry(refundKinds, GROUP, "reference_list", "Refund kinds")
        store.seeded(refundKindsListed, refundKinds, 1)
        store.content(refundKindsListed, "reference_list")
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'Refunds', 'Money back.', ?::uuid)
                """).params(refundKindsListed, SEEDER).update()
        store.routed(firstRoute, "0000000d-0000-4000-8000-000000000d82", DRAFT, 2, WORKFLOW_IN_SERVICE)
        store.routed(secondRoute, secondCase, DRAFT, 3, WORKFLOW_IN_SERVICE)
        store.session.sql("""
                insert into declaration_fields (workflow_step_id, step_kind, side, position, name, kind,
                                                term_list_version_id, must_be_given, created_by)
                values (?::uuid, 'route', 'gives', 1, 'category', 'term', ?::uuid, true, ?::uuid)
                """).params(firstRoute, refundKindsListed, SEEDER).update()
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, step_kind, source_step_id, source_path,
                                      created_by)
                values (?::uuid, ?::uuid, 'route', ?::uuid, 'category', ?::uuid)
                """).params(DRAFT, secondRoute, firstRoute, SEEDER).update()
        store.session.sql("update route_cases set term = 'Billing' where route_case_id = ?::uuid").param(secondCase).update()

        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.pins()[versionId(refundKindsListed)].name() == new EntryName("Refund kinds")
        view.terms()[versionId(refundKindsListed)].terms()*.term()*.value() == ["Refunds"]
        view.problems().findAll { it.code() == ContentProblemCode.CASE_NOT_OFFERED }*.place() ==
                [new ContentPlace.AtCase(UUID.fromString(secondRoute), UUID.fromString(secondCase))]
    }

    /**
     * Another group's workflow, a question's version, another entry's, and one nobody holds are one refusal;
     * somebody in no role here is refused as the group is.
     */
    def "a version not in view is refused alike however it is not, and a reader in no role here as no group"() {
        given:
        store.entry(OTHER_WORKFLOW, OTHER_GROUP, "workflow", "Elsewhere")
        store.seeded(OTHER_VERSION, OTHER_WORKFLOW, 1)
        store.content(OTHER_VERSION, "workflow")

        when:
        workflows.read(groupId(GROUP), entryId(entry), versionId(version), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code

        where:
        entry          | version                                | caller   || code
        OTHER_WORKFLOW | OTHER_VERSION                          | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        WORKFLOW       | OTHER_VERSION                          | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        QUESTION       | ASKED_OLD                              | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        WORKFLOW       | "00000009-0000-4000-8000-000000000009" | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        WORKFLOW       | DRAFT                                  | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
    }

    /** Nothing but a page pins, and only its own group's: a version reaching another group's cannot be named, so fails. */
    def "a version pinning another group's version fails the read rather than naming it"() {
        given:
        store.entry(OTHER_WORKFLOW, OTHER_GROUP, "workflow", "Elsewhere")
        store.seeded(OTHER_VERSION, OTHER_WORKFLOW, 1)
        store.content(OTHER_VERSION, "workflow")
        store.session.sql("update workflow_steps set pinned_version_id = ?::uuid, pinned_kind = 'workflow'")
                .param(OTHER_VERSION).update()

        when:
        workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A version pins a version group ${GROUP} does not hold" as String
    }

    private static String key(String version) {
        "0000000b" + version.substring(8)
    }

    /** The question made one a model could be told, every value it gives back standing, taking text of that length. */
    private void takingLetters(String version, int longest) {
        store.session.sql("update declaration_fields set standing = 'always' where entry_version_id = ?::uuid")
                .params(version).update()
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, text_limit,
                                                must_be_given, created_by)
                values (?::uuid, 'question', 'takes', 1, 'letter', 'text', ?, true, ?::uuid)
                """).params(version, longest, SEEDER).update()
    }

    /** The step's question asked of the small model or a person, telling what happened as said, reviewed as named. */
    private void asked(String step, String producer, boolean told, String reviewer) {
        def model = producer == "model" ? "small" : null
        store.session.sql("""
                update workflow_steps
                   set producer = cast(? as step_producer), producer_model = ?, producer_mode = ?, tries = 1,
                       tells_what_happened = ?, reviewer_model = ?, reviewer_mode = ?
                 where workflow_step_id = ?::uuid
                """).params(producer, model, model == null ? null : "ordinary", told, reviewer,
                reviewer == null ? null : "ordinary", step).update()
    }

    private List<Map<String, Object>> stepsOf(String version) {
        store.session.sql("select * from workflow_steps where entry_version_id = ?::uuid order by position")
                .params(version).query().listOfRows()
    }

    private void publish(String codeStep, String key) {
        store.session.sql("""
                insert into code_step_publications (code_step, group_key, every_group, created_by)
                values (?::code_step, ?, ?, ?::uuid)
                """).params(codeStep, key, key == null, SEEDER).update()
    }
}
