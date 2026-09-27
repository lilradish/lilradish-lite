package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What a workflow's stored content is held to, read off a real server running the real baseline: at submitting
 * through the change that submits it, and again whenever a version in service is read afresh.
 */
class WorkflowContentCheckIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000c01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000c02"

    /** An overseer: may write and submit an entry. */
    static final String ANN = "00000002-0000-4000-8000-000000000c01"

    static final UserId ANN_USER = new UserId("000c01")

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000c01"

    static final String VERSION = "00000007-0000-4000-8000-000000000c01"

    static final String QUESTION = "00000006-0000-4000-8000-000000000c02"

    static final String ASKED = "00000007-0000-4000-8000-000000000c02"

    static final String OTHER_QUESTION = "00000006-0000-4000-8000-000000000c03"

    static final String OTHER_ASKED = "00000007-0000-4000-8000-000000000c03"

    static final String LIST = "00000006-0000-4000-8000-000000000c04"

    static final String LISTED = "00000007-0000-4000-8000-000000000c04"

    static final String STEP = "0000000c-0000-4000-8000-000000000c01"

    static final String ROUTE = "0000000c-0000-4000-8000-000000000c02"

    static final String OUTPUT = "0000000b-0000-4000-8000-000000000c01"

    static final String INPUT = "0000000b-0000-4000-8000-000000000c02"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    VersionChanges changes

    TransactionTemplate snapshot

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "workflow_checks_" + (++databasesMade))
        changes = new VersionChanges(store.session, store.transactions(), new GroupRoles(store.session), checks(LibraryStore.MODELS))
        snapshot = new TransactionTemplate(store.transactionManager())
        snapshot.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        store.person(ANN, "000c01")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(LISTED, LIST, 1)
        store.content(LISTED, "reference_list")
        term(LISTED, 1, "Billing")
        term(LISTED, 2, "Delivery")
        store.entry(QUESTION, GROUP, "question", "Classify")
        store.seeded(ASKED, QUESTION, 1)
        store.content(ASKED, "question", "Say which category it is.")
        field(ASKED, "question", "takes", 1, "complaint", "text", 4000, null, true, null)
        field(ASKED, "question", "gives", 1, "category", "term", null, LISTED, true, "never")
        field(ASKED, "question", "gives", 2, "summary", "text", 500, null, true, "always")
        store.entry(OTHER_QUESTION, OTHER_GROUP, "question", "Elsewhere")
        store.seeded(OTHER_ASKED, OTHER_QUESTION, 1)
        store.entry(WORKFLOW, GROUP, "workflow", "Handle a complaint")
        store.version(VERSION, WORKFLOW, 1, FIRST_STEWARD)
        store.content(VERSION, "workflow")
        field(VERSION, "workflow", "takes", 1, "complaint", "text", 4000, null, true, null, INPUT)
        field(VERSION, "workflow", "gives", 1, "summary", "text", 500, null, true, null, OUTPUT)
    }

    def "a workflow whose stored content holds is submitted"() {
        given:
        whole()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
    }

    /** Every place is named at once, in the order the content reads, and the draft is left as it was. */
    def "a workflow whose stored content does not hold is refused, naming every place, and changing nothing"() {
        given:
        pinned(STEP, 1, "classify", ASKED)
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refused = thrown(ContentProblemsRefusal)
        refused.errorCode() == RefusalCode.VERSION_CONTENT_DOES_NOT_HOLD
        refused.problems() == [
                problem(ContentProblemCode.PRODUCER_MISSING, new ContentPlace.AtStep(UUID.fromString(STEP))),
                problem(ContentProblemCode.TRIES_MISSING, new ContentPlace.AtStep(UUID.fromString(STEP))),
                problem(ContentProblemCode.INPUT_UNBOUND, new ContentPlace.AtInput(UUID.fromString(STEP), null,
                        UUID.fromString(key(ASKED, "complaint")))),
                problem(ContentProblemCode.OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(OUTPUT)))]
        refused.pins() == []
        store.contents() == before
    }

    /**
     * Read afresh, a version in service is held to the same check, against what the deployment holds now: a model
     * it held when the version was approved and holds no longer is named as such.
     */
    def "rechecking a version in service holds it to what submitting would, against the models held now"() {
        given:
        whole()
        store.approved(VERSION, ANN)
        def withoutGeneral = checks(new ModelCatalog([
                new DeployedModel(new ModelName("small"), [], 100_000, 4.0G, 4_000, [])]))

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).recheck(groupId(GROUP), versionId(VERSION)) } == []
        snapshot.execute { withoutGeneral.recheck(groupId(GROUP), versionId(VERSION)) } ==
                [problem(ContentProblemCode.PRODUCER_NOT_HELD, new ContentPlace.AtStep(UUID.fromString(STEP)))]
    }

    /** Written round every check, a pin of another group's is never read; it is named where it is pinned. */
    def "a step pinning another group's version is named as pinned elsewhere, and nothing of that version is read"() {
        given:
        pinned(STEP, 1, "classify", OTHER_ASKED)
        store.session.sql("update workflow_steps set producer = 'person', tries = 1").update()
        bind(STEP, "complaint", "complaint")

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).recheck(groupId(GROUP), versionId(VERSION)) } == [
                problem(ContentProblemCode.PIN_ELSEWHERE, new ContentPlace.AtStep(UUID.fromString(STEP))),
                problem(ContentProblemCode.OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(OUTPUT)))]
    }

    /** What a route may be on is the pinned list's terms, as that list version holds them, and none of another. */
    def "a route's cases are held to the terms of the list what it chooses by is pinned to"() {
        given:
        whole()
        routed(term)

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).recheck(groupId(GROUP), versionId(VERSION)) }*.code() == codes

        where:
        term       || codes
        "Delivery" || []
        "Refunds"  || [ContentProblemCode.CASE_NOT_OFFERED]
    }

    /**
     * Another group's list is never read here, so its terms offer nothing, even the one a case is on. A person
     * produces, so no model is told the question pinning it.
     */
    def "a route choosing by a field pinned to another group's list is offered none of that list's terms"() {
        given:
        theirListPinned()
        whole()
        store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null")
                .update()
        routed("Refunds")

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).recheck(groupId(GROUP), versionId(VERSION)) }*.code() ==
                [ContentProblemCode.CASE_NOT_OFFERED]
    }

    /** Drafts pin only the group's own lists, so a question a model is told pinning another's is a store gone wrong. */
    def "a question a model is told, pinning another group's list, fails the check as it fails the question's own"() {
        given:
        theirListPinned()
        whole()
        routed("Refunds")

        when:
        snapshot.execute { checks(LibraryStore.MODELS).recheck(groupId(GROUP), versionId(VERSION)) }

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Question version ${ASKED} pins a list this group does not hold" as String
    }

    /** Published by key or to every group; a key another group holds publishes nothing here. */
    def "a code step is held to having been published to the group, by its key or to every group"() {
        given:
        codeStep("send_reply", published)

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).recheck(groupId(GROUP), versionId(VERSION)) }
                .findAll { it.place() == new ContentPlace.AtStep(UUID.fromString(STEP)) }*.code() == codes

        where:
        published || codes
        "SUPPORT" || []
        "every"   || []
        "BILLING" || [ContentProblemCode.CODE_STEP_NOT_PUBLISHED]
        "none"    || [ContentProblemCode.CODE_STEP_NOT_PUBLISHED]
    }

    /** What it takes is not known, so nothing bound to it is judged; that the release declares nothing is named. */
    def "a code step published to the group that this release does not hold is named as undeclared, and nothing bound to it is judged"() {
        given:
        codeStep("archive", "SUPPORT")
        bind(STEP, "anything", "complaint")

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).recheck(groupId(GROUP), versionId(VERSION)) } == [
                problem(ContentProblemCode.CODE_STEP_NOT_DECLARED, new ContentPlace.AtStep(UUID.fromString(STEP))),
                problem(ContentProblemCode.OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(OUTPUT)))]
    }

    /** The release's declaration is what the step's bindings fill and what a later reader of it reads. */
    def "a code step this release holds has its inputs bound against what it takes, and gives back what it declares"() {
        given:
        codeStep("send_reply", "SUPPORT")
        if (replyBound) {
            store.session.sql("""
                    insert into bindings (entry_version_id, workflow_step_id, target_path, constant, created_by)
                    values (?::uuid, ?::uuid, 'reply', '"Thank you."', ?::uuid)
                    """).params(VERSION, STEP, SEEDER).update()
        }
        store.session.sql("""
                insert into bindings (entry_version_id, target_path, source_step_id, source_path, created_by)
                values (?::uuid, 'summary', ?::uuid, 'receipt', ?::uuid)
                """).params(VERSION, STEP, SEEDER).update()
        def reply = StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration()).takes().keyAt([0])

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).recheck(groupId(GROUP), versionId(VERSION)) } ==
                (replyBound ? [] : [problem(ContentProblemCode.INPUT_UNBOUND,
                        new ContentPlace.AtInput(UUID.fromString(STEP), null, reply))])

        where:
        replyBound << [true, false]
    }

    /**
     * Another group's list is refused as though the code step were not published here, telling nothing of that group;
     * a list of the group's own not in service, or none here at all, is named by why, and what it declares still holds.
     */
    def "a code step taking a term from a list is named by where that list stands"() {
        given:
        def entry = "00000006-0000-4000-8000-000000000c0b"
        def listed = "00000007-0000-4000-8000-000000000c0b"
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
        codeStep("sort_out", "SUPPORT")
        def sortOut = new SpecCodeStep("sort_out", [], [SpecCodeStep.standing("category",
                new FieldShape.Term(versionId(listed)))], false)

        expect:
        snapshot.execute { checks(LibraryStore.MODELS, new CodeSteps([sortOut])).recheck(groupId(GROUP), versionId(VERSION)) }
                .findAll { it.place() == new ContentPlace.AtStep(UUID.fromString(STEP)) }*.code() == codes

        where:
        standing  || codes
        "ours"    || []
        "draft"   || [ContentProblemCode.CODE_STEP_LIST_NOT_YET_IN_SERVICE]
        "retired" || [ContentProblemCode.CODE_STEP_LIST_RETIRED]
        "none"    || [ContentProblemCode.CODE_STEP_LIST_MISSING]
        "theirs"  || [ContentProblemCode.CODE_STEP_NOT_PUBLISHED]
    }

    def "a version holding no workflow content is a store gone wrong for a workflow submitted"() {
        given:
        def check = new WorkflowContentCheck(store.session, LibraryStore.MODELS, new ReleasedCodeSteps(LibraryStore.CODE_STEPS))

        when:
        check.problemsIn(groupId(GROUP), versionId("00000007-0000-4000-8000-000000000cff"))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Workflow version 00000007-0000-4000-8000-000000000cff holds no workflow content"
    }

    private ContentChecks checks(ModelCatalog models, CodeSteps codeSteps = LibraryStore.CODE_STEPS) {
        new ContentChecks(store.session, [new QuestionContentCheck(store.session),
                                          new WorkflowContentCheck(store.session, models, new ReleasedCodeSteps(codeSteps)),
                                          new ReferenceListContentCheck()])
    }

    /** A step running the code step {@code name}, published to {@code published} as a key, every group, or none. */
    private void codeStep(String name, String published) {
        store.session.sql("alter type code_step add value '${name}'" as String).update()
        if (published != "none") {
            store.session.sql("insert into code_step_publications (code_step, group_key, every_group, created_by) values (?::code_step, ?, ?, ?::uuid)")
                    .params(name, published == "every" ? null : published, published == "every", SEEDER).update()
        }
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step,
                                            producer, tries, created_by)
                values (?::uuid, ?::uuid, 1, 'send', 'code_step', ?::code_step, 'code', 1, ?::uuid)
                """).params(STEP, VERSION, name, SEEDER).update()
    }

    /** One step asking the question by a model, fed what the workflow takes, and what it gives back bound to it. */
    private void whole() {
        pinned(STEP, 1, "classify", ASKED)
        store.session.sql("update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary', tries = 3")
                .update()
        bind(STEP, "complaint", "complaint")
        store.session.sql("""
                insert into bindings (entry_version_id, target_path, source_step_id, source_path, created_by)
                values (?::uuid, 'summary', ?::uuid, 'summary', ?::uuid)
                """).params(VERSION, STEP, SEEDER).update()
    }

    /** The question's category pinned to a list of another group's, holding the term a case is on. */
    private void theirListPinned() {
        def theirs = "00000006-0000-4000-8000-000000000c0a"
        def theirsListed = "00000007-0000-4000-8000-000000000c0a"
        store.entry(theirs, OTHER_GROUP, "reference_list", "Their categories")
        store.seeded(theirsListed, theirs, 1)
        store.content(theirsListed, "reference_list")
        term(theirsListed, 1, "Refunds")
        store.session.sql("update declaration_fields set term_list_version_id = ?::uuid where name = 'category'")
                .params(theirsListed).update()
    }

    /** A route after the step, choosing by its category, whose one case on {@code term} leads to a workflow in service. */
    private void routed(String term) {
        def target = "00000006-0000-4000-8000-000000000c05"
        def led = "00000007-0000-4000-8000-000000000c05"
        store.entry(target, GROUP, "workflow", "Escalate")
        store.seeded(led, target, 1)
        store.content(led, "workflow")
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by)
                values (?::uuid, ?::uuid, 2, 'route', 'route', ?::uuid)
                """).params(ROUTE, VERSION, SEEDER).update()
        store.session.sql("""
                insert into route_cases (entry_version_id, workflow_step_id, term, target_version_id, created_by)
                values (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid)
                """).params(VERSION, ROUTE, term, led, SEEDER).update()
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, step_kind, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, 'route', ?::uuid, 'category', ?::uuid)
                """).params(VERSION, ROUTE, STEP, SEEDER).update()
    }

    private void pinned(String step, int position, String name, String version) {
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, pinned_version_id,
                                            pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'entry', ?::uuid, 'question', ?::uuid)
                """).params(step, VERSION, position, name, version, SEEDER).update()
    }

    private void bind(String step, String target, String input) {
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, target_path, source_path, created_by)
                values (?::uuid, ?::uuid, ?, ?, ?::uuid)
                """).params(VERSION, step, target, input, SEEDER).update()
    }

    private void term(String list, int position, String term) {
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, 'When it is.', ?::uuid)
                """).params(list, position, term, SEEDER).update()
    }

    private String key(String version, String name) {
        store.texts("select declaration_field_id::text from declaration_fields where entry_version_id = ?::uuid and name = ?",
                version, name).first()
    }

    private void field(String version, String kind, String side, int position, String name, String fieldKind,
                       Integer longest, String list, Boolean mustBeGiven, String standing, String key = null) {
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name,
                                                kind, text_limit, term_list_version_id, must_be_given, standing, created_by)
                values (coalesce(?::uuid, uuidv7()), ?::uuid, ?::entry_kind, ?::declaration_side, ?, ?, ?::field_kind, ?,
                        ?::uuid, ?, ?::field_standing, ?::uuid)
                """).params(key, version, kind, side, position, name, fieldKind, longest, list, mustBeGiven, standing, SEEDER)
                .update()
    }

    private static ContentProblem problem(ContentProblemCode code, ContentPlace place) {
        new ContentProblem(code, place, null)
    }
}
