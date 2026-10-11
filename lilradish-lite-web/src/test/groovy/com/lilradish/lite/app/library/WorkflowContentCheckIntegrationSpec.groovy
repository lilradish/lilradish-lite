package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.inference.Askings.longest
import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.entryId
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.Envelope
import com.lilradish.lite.domain.inference.Payload
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.search.SearchAnswer
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.unit.DataSize
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification


class WorkflowContentCheckIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000c01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000c02"


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

    static final String SEARCH = "0000000c-0000-4000-8000-000000000c03"

    static final String SEARCH_QUESTION = "00000006-0000-4000-8000-000000000c30"

    static final String SEARCH_ASKED = "00000007-0000-4000-8000-000000000c30"

    static final SearchWays WAYS = new SearchWays([DeployedModels.WEB])

    static final String OUTPUT = "0000000b-0000-4000-8000-000000000c01"

    static final String INPUT = "0000000b-0000-4000-8000-000000000c02"

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    VersionChanges changes

    TransactionTemplate snapshot

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "workflow_checks_" + (++databasesMade), specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
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
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
    }

    def "a workflow with a file at either deployment field bound submits"() {
        given:
        whole()
        fileTaken(maximumBytes, most)

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]

        where:
        maximumBytes | most
        104_857_600L  | 1
        1L            | 100
    }

    def "a person may answer and review a question step giving back a file"() {
        given:
        whole()
        fileGiven()
        // DB-SPECIFIC: PostgreSQL native step_producer enum literal.
        store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null, tries = 1")
                .update()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
    }

    def "a file returned by a question step cannot be assigned to a model for production or review"() {
        given:
        whole()
        fileGiven()
        // DB-SPECIFIC: PostgreSQL :: casts to step_producer.
        store.session.sql("""
                update workflow_steps
                   set producer = ?::step_producer, producer_model = ?, producer_mode = ?, reviewer_model = ?, reviewer_mode = ?
                """).params(producer, producerModel, producerMode, reviewerModel, reviewerMode).update()
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refusal = thrown(ContentProblemsRefusal)
        refusal.problems() == [new ContentProblem(expected,
                new ContentPlace.AtStep(UUID.fromString(STEP)), null, null)]
        store.contents() == before

        where:
        producer | producerModel | producerMode | reviewerModel | reviewerMode || expected
        'model'  | 'general'     | 'ordinary'   | null          | null         || ContentProblemCode.STEP_FILE_MADE_BY_MODEL
        'person' | null          | null         | 'general'     | 'ordinary'   || ContentProblemCode.STEP_FILE_REVIEWED_BY_MODEL
    }

    def "a workflow file past either deployment bound is refused without writing a submission"() {
        given:
        whole()
        fileTaken(maximumBytes, most)
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def failure = thrown(ContentProblemsRefusal)
        failure.problems() == [new ContentProblem(ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT,
                new ContentPlace.AtField(ContentPart.TAKES, UUID.fromString(fileKey())), null,
                new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))]
        store.contents() == before

        where:
        maximumBytes | most
        104_857_601L  | 1
        1L            | 101
    }

    def "an old stored file field with sixty-five types refuses workflow submission without rewriting it"() {
        given:
        whole()
        fileTaken(1024L, 1)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; generate_series(); || converts integer operands to text.
        store.session.sql("""
                update declaration_fields
                   set file_types = ARRAY(select '.x' || n from generate_series(0, 64) n)
                 where declaration_field_id = ?::uuid
                """).params(fileKey()).update()
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refusal = thrown(ContentProblemsRefusal)
        refusal.problems() == [problem(ContentProblemCode.FILE_TYPES_TOO_MANY,
                new ContentPlace.AtField(ContentPart.TAKES, UUID.fromString(fileKey())))]
        store.contents() == before
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid; array_length().
        store.texts("select array_length(file_types, 1)::text from declaration_fields where declaration_field_id = ?::uuid",
                fileKey()) == ["65"]
    }

    def "an old nested route file is found on recheck at its field"() {
        given:
        whole()
        routed("Billing")
        def parent = "0000000b-0000-4000-8000-000000000c03"
        def document = "0000000b-0000-4000-8000-000000000c04"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, workflow_step_id, side, position, name,
                                                kind, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'gives', 1, 'details', 'fields', false, ?::uuid)
                """).params(parent, ROUTE, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, workflow_step_id, side, parent_field_id,
                                                position, name, kind, file_types, file_maximum_bytes,
                                                must_be_given, created_by)
                values (?::uuid, ?::uuid, 'gives', ?::uuid, 1, 'document', 'file',
                        array['.pdf'], 1024, false, ?::uuid)
                """).params(document, ROUTE, parent, SEEDER).update()
        store.approved(VERSION, ANN)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; generate_series(); || converts integer operands to text.
        store.session.sql("""
                update declaration_fields
                   set file_types = ARRAY(select '.x' || n from generate_series(0, 64) n)
                 where declaration_field_id = ?::uuid
                """).params(document).update()

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() ==
                [problem(ContentProblemCode.FILE_TYPES_TOO_MANY,
                        new ContentPlace.AtField(ContentPart.STEPS, UUID.fromString(document)))]
    }

    def "a recheck finds a file declaration after deployment file limits decrease"() {
        given:
        whole()
        fileTaken(104_857_600L, 1)
        store.approved(VERSION, ANN)
        def smaller = new FileDeployment(DataSize.ofMegabytes(50), 50, DataSize.ofGigabytes(20))

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == []
        snapshot.execute { checks(LibraryStore.MODELS, LibraryStore.CODE_STEPS, smaller)
                .problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == [
                new ContentProblem(ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT,
                        new ContentPlace.AtField(ContentPart.TAKES, UUID.fromString(fileKey())), null,
                        new ContentProblem.FileLimits(52_428_800L, 50, 21_474_836_480L))]
    }

    def "a workflow's files given back exactly at the handover total submit"() {
        given:
        whole()
        workflowFilesGiven(67_108_864L, false)

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
    }

    def "a workflow's files given back one byte past the handover total refuse submission"() {
        given:
        whole()
        workflowFilesGiven(67_108_864L, true)
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refusal = thrown(ContentProblemsRefusal)
        refusal.problems() == [new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                new ContentPlace.Whole(ContentPart.GIVES), null,
                new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))]
        store.contents() == before
    }

    def "a workflow's files given back are rechecked against a lower handover total"() {
        given:
        whole()
        workflowFilesGiven(67_108_864L, false)
        store.approved(VERSION, ANN)
        def smaller = new FileDeployment(DataSize.ofMegabytes(100), 100, DataSize.ofBytes(1_073_741_823L))

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == []
        snapshot.execute { checks(LibraryStore.MODELS, LibraryStore.CODE_STEPS, smaller)
                .problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == [
                new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                        new ContentPlace.Whole(ContentPart.GIVES), null,
                        new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_823L))]
    }

    def "a route's optional nested files exactly at the handover total submit"() {
        given:
        whole()
        routeFilesGiven(67_108_863L, 16L)

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
    }

    def "a route's optional nested files one byte past the handover total refuse submission"() {
        given:
        whole()
        routeFilesGiven(67_108_864L, 1L)
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refusal = thrown(ContentProblemsRefusal)
        refusal.problems() == [new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                new ContentPlace.AtStep(UUID.fromString(ROUTE)), null,
                new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))]
        store.contents() == before
    }

    def "an old route's optional nested files are found again on recheck"() {
        given:
        whole()
        routeFilesGiven(67_108_864L, 1L)
        store.approved(VERSION, ANN)

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == [
                new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                        new ContentPlace.AtStep(UUID.fromString(ROUTE)), null,
                        new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))]
    }

    def "a workflow total exactly at the handover bound submits"() {
        given:
        whole()
        fileTaken(67_108_864L, 16)

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
    }

    def "a workflow total one byte past the handover bound is refused at the whole takes"() {
        given:
        whole()
        fileTaken(67_108_864L, 16)
        secondFileTaken(1L)
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refusal = thrown(ContentProblemsRefusal)
        refusal.problems() == [new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                new ContentPlace.Whole(ContentPart.TAKES), null,
                new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))]
        store.contents() == before
    }

    def "rechecking a workflow finds a lower handover total even when its fields still fit"() {
        given:
        whole()
        fileTaken(67_108_864L, 16)
        store.approved(VERSION, ANN)
        def smaller = new FileDeployment(DataSize.ofMegabytes(100), 100, DataSize.ofBytes(1_073_741_823L))

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == []
        snapshot.execute { checks(LibraryStore.MODELS, LibraryStore.CODE_STEPS, smaller)
                .problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == [
                new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                        new ContentPlace.Whole(ContentPart.TAKES), null,
                        new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_823L))]
    }


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


    def "rechecking a version in service holds it to what submitting would, against the models held now"() {
        given:
        whole()
        store.approved(VERSION, ANN)
        def withoutGeneral = checks(new ModelCatalog([
                new DeployedModel(new ModelName("small"), [], 100_000, 4.0G, 4_000, [], [])]))

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == []
        snapshot.execute { withoutGeneral.problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() ==
                [problem(ContentProblemCode.PRODUCER_NOT_HELD, new ContentPlace.AtStep(UUID.fromString(STEP)))]
    }

    /** A pin outside the group is reported without reading the pinned version. */
    def "a step pinning another group's version is named as pinned elsewhere, and nothing of that version is read"() {
        given:
        pinned(STEP, 1, "classify", OTHER_ASKED)
        // DB-SPECIFIC: PostgreSQL native step_producer enum literal.
        store.session.sql("update workflow_steps set producer = 'person', tries = 1").update()
        bind(STEP, "complaint", "complaint")

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == [
                problem(ContentProblemCode.PIN_ELSEWHERE, new ContentPlace.AtStep(UUID.fromString(STEP))),
                problem(ContentProblemCode.OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(OUTPUT)))]
    }


    def "a route's cases are held to the terms of the list what it chooses by is pinned to"() {
        given:
        whole()
        routed(term)

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems()*.code() == codes

        where:
        term       || codes
        "Delivery" || []
        "Refunds"  || [ContentProblemCode.CASE_NOT_OFFERED]
    }

    /** Only lists held by the group may contribute offered terms. */
    def "a route choosing by a field pinned to another group's list is offered none of that list's terms"() {
        given:
        theirListPinned()
        whole()
        // DB-SPECIFIC: PostgreSQL native step_producer enum literal.
        store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null")
                .update()
        routed("Refunds")

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems()*.code() ==
                [ContentProblemCode.CASE_NOT_OFFERED]
    }

    /** A question sent to a model must pin only lists held by its group. */
    def "a question a model is told, pinning another group's list, fails the check as it fails the question's own"() {
        given:
        theirListPinned()
        whole()
        routed("Refunds")

        when:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems()

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Question version ${ASKED} pins a list this group does not hold" as String
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "a code step is held to having been published to the group, by its key or to every group"() {
        given:
        codeStep("send_reply", published)

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems()
                .findAll { it.place() == new ContentPlace.AtStep(UUID.fromString(STEP)) }*.code() == codes

        where:
        published || codes
        "SUPPORT" || []
        "every"   || []
        "BILLING" || [ContentProblemCode.CODE_STEP_NOT_PUBLISHED]
        "none"    || [ContentProblemCode.CODE_STEP_NOT_PUBLISHED]
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "a code step published to the group that this release does not hold is named as undeclared, and nothing bound to it is judged"() {
        given:
        codeStep("archive", "SUPPORT")
        bind(STEP, "anything", "complaint")

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() == [
                problem(ContentProblemCode.CODE_STEP_NOT_DECLARED, new ContentPlace.AtStep(UUID.fromString(STEP))),
                problem(ContentProblemCode.OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(OUTPUT)))]
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "a code step this release holds has its inputs bound against what it takes, and gives back what it declares"() {
        given:
        codeStep("send_reply", "SUPPORT")
        if (replyBound) {
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    insert into bindings (entry_version_id, workflow_step_id, target_path, constant, created_by)
                    values (?::uuid, ?::uuid, 'reply', '"Thank you."', ?::uuid)
                    """).params(VERSION, STEP, SEEDER).update()
        }
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into bindings (entry_version_id, target_path, source_step_id, source_path, created_by)
                values (?::uuid, 'summary', ?::uuid, 'receipt', ?::uuid)
                """).params(VERSION, STEP, SEEDER).update()
        def reply = StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration()).takes().keyAt([0])

        expect:
        snapshot.execute { checks(LibraryStore.MODELS).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems() ==
                (replyBound ? [] : [problem(ContentProblemCode.INPUT_UNBOUND,
                        new ContentPlace.AtInput(UUID.fromString(STEP), null, reply))])

        where:
        replyBound << [true, false]
    }

    def "submitting a search refuses binding a file to its text input at that binding"() {
        given:
        whole()
        searching("web", "general", "always")
        field(VERSION, "workflow", "takes", 2, "attachment", "file", null, null, true, null)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                update declaration_fields
                   set file_types = array['.pdf'], file_maximum_bytes = 1024
                 where entry_version_id = ?::uuid and name = 'attachment'
                """).params(VERSION).update()
        written(0, "Find ")
        reading(1, "attachment")
        // DB-SPECIFIC: PostgreSQL ::uuid cast.
        def partId = store.session.sql(
                "select binding_id from bindings where workflow_step_id = ?::uuid and source_path = 'attachment'")
                .params(SEARCH)
                .query(UUID).single()
        def before = store.contents()

        when:
        searchingChanges().submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refused = thrown(ContentProblemsRefusal)
        refused.problems() == [new ContentProblem(ContentProblemCode.SOURCE_DOES_NOT_FIT,
                new ContentPlace.AtBinding(ContentPart.STEPS, partId), null, null)]
        store.contents() == before
    }

    /**
     * Another group's list is refused as though the code step were not published here, telling nothing of that group;
     * a list of the group's own not in service, or none here at all, is named by why, and what it declares still holds.
     */
    @spock.lang.Tag("postgres-schema-changing")
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
        snapshot.execute { checks(LibraryStore.MODELS, new CodeSteps([sortOut])).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }.problems()
                .findAll { it.place() == new ContentPlace.AtStep(UUID.fromString(STEP)) }*.code() == codes

        where:
        standing  || codes
        "ours"    || []
        "draft"   || [ContentProblemCode.CODE_STEP_LIST_NOT_YET_IN_SERVICE]
        "retired" || [ContentProblemCode.CODE_STEP_LIST_RETIRED]
        "none"    || [ContentProblemCode.CODE_STEP_LIST_MISSING]
        "theirs"  || [ContentProblemCode.CODE_STEP_NOT_PUBLISHED]
    }


    def "a workflow searching with a way the release holds, by a model offering its tool, is submitted"() {
        given:
        whole()
        searching("web", "general", "always")
        written(0, "Find what is said of ")
        reading(1, "complaint")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update bindings set source_step_id = ?::uuid, source_path = 'title' where workflow_step_id is null")
                .params(SEARCH).update()

        when:
        searchingChanges().submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "a searching step is refused at submitting, named for every part of it that does not hold, and nothing is changed"() {
        given:
        whole()
        field(VERSION, "workflow", "takes", 2, "remark", "text", 4000, null, false, null)
        searching("web", "small", null)
        written(0, "Find fictional complaint evidence.")
        reading(1, "remark")
        def before = store.contents()

        when:
        searchingChanges().submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refused = thrown(ContentProblemsRefusal)
        refused.errorCode() == RefusalCode.VERSION_CONTENT_DOES_NOT_HOLD
        refused.problems() == [problem(ContentProblemCode.SEARCH_STANDING_MISSING,
                new ContentPlace.AtStepFields(UUID.fromString(SEARCH), ["body"]))] +
                [problem(ContentProblemCode.SEARCH_TOOL_NOT_OFFERED, new ContentPlace.AtStep(UUID.fromString(SEARCH))),
                 problem(ContentProblemCode.SOURCE_MAY_BE_EMPTY, new ContentPlace.AtBinding(ContentPart.STEPS,
                         store.session.sql("select binding_id from bindings where workflow_step_id = ?::uuid")
                                 .params(SEARCH).query(UUID).single()))]
        store.contents() == before
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions") == []
    }


    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "submitting search refuses required null and empty list bindings without writing a submission or execution"() {
        given:
        whole()
        searching("web", "general", "always")
        def binding = searchConstant(most, true, constant)
        def before = store.contents()

        when:
        searchingChanges().submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refused = thrown(ContentProblemsRefusal)
        refused.errorCode() == RefusalCode.VERSION_CONTENT_DOES_NOT_HOLD
        refused.problems() == [problem(ContentProblemCode.CONSTANT_DOES_NOT_FIT,
                new ContentPlace.AtBinding(ContentPart.STEPS, binding))]
        store.contents() == before
        store.texts("select created_by::text from entry_version_submissions") == []
        store.count("select count(*) from runs") == 0
        store.count("select count(*) from productions") == 0
        store.count("select count(*) from run_step_send_attempts") == 0
        store.count("select count(*) from model_calls") == 0

        where:
        most | constant
        null | "null"
        2    | "[]"
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "submitting search accepts optional empty and required nonempty input bindings"() {
        given:
        whole()
        searching("web", "general", "always")
        searchConstant(most, required, constant)

        when:
        searchingChanges().submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
        store.count("select count(*) from runs") == 0
        store.count("select count(*) from productions") == 0
        store.count("select count(*) from run_step_send_attempts") == 0
        store.count("select count(*) from model_calls") == 0

        where:
        most | required | constant
        null | false    | "null"
        2    | false    | "[]"
        null | true     | '"Fictional complaint evidence"'
        2    | true     | '["Fictional complaint evidence"]'
    }

    def "rechecking a searching version in service names a way the release no longer holds, or a tool its model no longer offers"() {
        given:
        whole()
        searching("web", "general", "always")
        written(0, "Find it")
        store.approved(VERSION, ANN)

        expect:
        snapshot.execute { checks(models, LibraryStore.CODE_STEPS, LibraryStore.FILES, ways).problemsIn(EntryKind.WORKFLOW, groupId(GROUP), versionId(VERSION)) }
                .problems() == expected.collect { problem(it, new ContentPlace.AtStep(UUID.fromString(SEARCH))) }

        where:
        models                   | ways               || expected
        DeployedModels.SEARCHING | WAYS               || []
        DeployedModels.SEARCHING | new SearchWays([]) || [ContentProblemCode.SEARCH_WAY_NOT_HELD]
        LibraryStore.MODELS      | WAYS               || [ContentProblemCode.SEARCH_TOOL_NOT_OFFERED]
    }


    def "a searching step whose one asking could send one character past the most one may is refused, by how much"() {
        given:
        whole()
        searching("web", "general", "always")
        def instruction = filling(1)
        reading(1, "bulk")
        reading(2, "complaint")
        written(0, instruction)
        def before = store.contents()

        when:
        searchingChanges().submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        def refused = thrown(ContentProblemsRefusal)
        refused.problems() == [new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST,
                new ContentPlace.AtStep(UUID.fromString(SEARCH)), 1L, null)]
        store.contents() == before
    }

    def "a searching step whose one asking sends exactly the most one may is submitted"() {
        given:
        whole()
        searching("web", "general", "always")
        def instruction = filling(0)
        reading(1, "bulk")
        reading(2, "complaint")
        written(0, instruction)

        when:
        searchingChanges().submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(VERSION), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [ANN]
    }

    def "a version holding no workflow content is a store gone wrong for a workflow submitted"() {
        given:
        def check = new WorkflowContentCheck(store.session, LibraryStore.MODELS, new ReleasedCodeSteps(LibraryStore.CODE_STEPS), LibraryStore.FILES, new SearchWays([]))

        when:
        check.problemsIn(groupId(GROUP), versionId("00000007-0000-4000-8000-000000000cff"))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Workflow version 00000007-0000-4000-8000-000000000cff holds no workflow content"
    }

    private ContentChecks checks(ModelCatalog models, CodeSteps codeSteps = LibraryStore.CODE_STEPS,
                                 FileDeployment deployment = LibraryStore.FILES, SearchWays ways = new SearchWays([])) {
        new ContentChecks([new QuestionContentCheck(store.session, deployment, ways),
                                          new WorkflowContentCheck(store.session, models, new ReleasedCodeSteps(codeSteps), deployment, ways),
                                          new ReferenceListContentCheck()])
    }


    private VersionChanges searchingChanges() {
        new VersionChanges(store.session, store.transactions(), new GroupRoles(store.session),
                checks(DeployedModels.SEARCHING, LibraryStore.CODE_STEPS, LibraryStore.FILES, WAYS))
    }


    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private void searching(String way, String model, String bodyStanding) {
        store.entry(SEARCH_QUESTION, GROUP, "question", "Find fictional complaint context")
        store.seeded(SEARCH_ASKED, SEARCH_QUESTION, 1)
        store.content(SEARCH_ASKED, "question", "Find sources.")
        store.session.sql("update question_versions set search_way = 'web' where entry_version_id = ?::uuid")
                .params(SEARCH_ASKED).update()
        DeployedModels.WEB.outputs().eachWithIndex { output, index ->
            field(SEARCH_ASKED, "question", "gives", index + 1, output.name().value(), "text",
                    ((FieldShape.Text) output.shape()).longest(), null, output.demand().mustBe(), "never")
        }
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, field_standing. Native step_kind, step_producer, entry_kind and field_standing enum literals.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, producer,
                                            producer_model, producer_mode, tries, search_way, search_title_standing,
                                            search_body_standing, search_references_standing, search_other_standing,
                                            pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, 2, 'find', 'search', 'model', ?, 'ordinary', 3, ?, 'always',
                        ?::field_standing, 'never', 'never', ?::uuid, 'question', ?::uuid)
                """).params(SEARCH, VERSION, model, way, bodyStanding, SEARCH_ASKED, SEEDER).update()
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL ::uuid and ::jsonb casts. Native declaration_side enum literals.
    private UUID searchConstant(Integer most, boolean required, String constant) {
        field(SEARCH_ASKED, "question", "takes", 1, "query", "text", 40, null, required, null)
        store.session.sql("""
                update declaration_fields set holds_many = ?, many_limit = ?
                 where entry_version_id = ?::uuid and side = 'takes' and name = 'query'
                """).params(most != null, most, SEARCH_ASKED).update()
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, target_path, constant, created_by)
                values (?::uuid, ?::uuid, 'query', ?::jsonb, ?::uuid)
                """).params(VERSION, SEARCH, constant, SEEDER).update()
        store.session.sql("select binding_id from bindings where workflow_step_id = ?::uuid")
                .params(SEARCH).query(UUID).single()
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private void written(int position, String text) {
        assert position == 0
        store.session.sql("update question_versions set instruction = ? where entry_version_id = ?::uuid")
                .params(text, SEARCH_ASKED).update()
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts. Native declaration_side enum literals.
    private void reading(int position, String path) {
        int limit = store.session.sql("""
                select coalesce(text_limit, 4000) from declaration_fields
                 where entry_version_id = ?::uuid and side = 'takes' and name = ?
                """).params(VERSION, path).query(Integer).single()
        field(SEARCH_ASKED, "question", "takes", position, path, "text", limit, null, true, null)
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, target_path, source_path, created_by)
                values (?::uuid, ?::uuid, ?, ?, ?::uuid)
                """).params(VERSION, SEARCH, path, path, SEEDER).update()
    }

    private String filling(long over) {
        def complaint = new AskedField(new FieldName("complaint"), FieldKind.TEXT, 4000, null, null, [], true, false)
        int fits = 1
        int past = 2_000_000
        while (past - fits > 1) {
            int middle = (fits + past).intdiv(2) as int
            def bulk = new AskedField(new FieldName("bulk"), FieldKind.TEXT, middle, null, null, [], true, false)
            if (boundarySent(bulk, complaint, "Find sources.") <= Declaration.MOST_SENT - 6000) {
                fits = middle
            } else {
                past = middle
            }
        }
        def bulk = new AskedField(new FieldName("bulk"), FieldKind.TEXT, fits, null, null, [], true, false)
        long shortOf = Declaration.MOST_SENT - boundarySent(bulk, complaint, "Find sources.")
        String instruction = "a" * (shortOf + 13 + over)
        assert instruction.length() <= 8192
        assert boundarySent(bulk, complaint, instruction) == Declaration.MOST_SENT + over
        field(VERSION, "workflow", "takes", 2, "bulk", "text", fits, null, true, null)
        instruction
    }

    private static long boundarySent(AskedField bulk, AskedField complaint, String instruction) {
        def asking = new Asking(new Instruction(instruction), [bulk, complaint], SearchAnswer.asked(DeployedModels.WEB))
        def payload = Payload.toProduce(asking,
                [(bulk.name()): longest(bulk), (complaint.name()): longest(complaint)], null, [bulk.name(), complaint.name()])
        String written = CanonicalJson.write(payload)
        String envelope = Envelope.toSearch(asking.gives(), asking.instruction())
        written.codePointCount(0, written.length()) + envelope.codePointCount(0, envelope.length())
    }

    private void codeStep(String name, String published) {
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value '${name}'" as String).update()
        if (published != "none") {
            // DB-SPECIFIC: PostgreSQL :: casts to code_step, uuid.
            store.session.sql("insert into code_step_publications (code_step, group_key, every_group, created_by) values (?::code_step, ?, ?, ?::uuid)")
                    .params(name, published == "every" ? null : published, published == "every", SEEDER).update()
        }
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, code_step; native step_kind and step_producer enum literals.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step,
                                            producer, tries, created_by)
                values (?::uuid, ?::uuid, 1, 'send', 'code_step', ?::code_step, 'code', 1, ?::uuid)
                """).params(STEP, VERSION, name, SEEDER).update()
    }


    private void whole() {
        pinned(STEP, 1, "classify", ASKED)
        // DB-SPECIFIC: PostgreSQL native step_producer enum literal.
        store.session.sql("update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary', tries = 3")
                .update()
        bind(STEP, "complaint", "complaint")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into bindings (entry_version_id, target_path, source_step_id, source_path, created_by)
                values (?::uuid, 'summary', ?::uuid, 'summary', ?::uuid)
                """).params(VERSION, STEP, SEEDER).update()
    }


    private void theirListPinned() {
        def theirs = "00000006-0000-4000-8000-000000000c0a"
        def theirsListed = "00000007-0000-4000-8000-000000000c0a"
        store.entry(theirs, OTHER_GROUP, "reference_list", "Their categories")
        store.seeded(theirsListed, theirs, 1)
        store.content(theirsListed, "reference_list")
        term(theirsListed, 1, "Refunds")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update declaration_fields set term_list_version_id = ?::uuid where name = 'category'")
                .params(theirsListed).update()
    }


    private void routed(String term) {
        def target = "00000006-0000-4000-8000-000000000c05"
        def led = "00000007-0000-4000-8000-000000000c05"
        store.entry(target, GROUP, "workflow", "Escalate")
        store.seeded(led, target, 1)
        store.content(led, "workflow")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native step_kind enum literals.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by)
                values (?::uuid, ?::uuid, 2, 'route', 'route', ?::uuid)
                """).params(ROUTE, VERSION, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into route_cases (entry_version_id, workflow_step_id, term, target_version_id, created_by)
                values (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid)
                """).params(VERSION, ROUTE, term, led, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native step_kind enum literals.
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, step_kind, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, 'route', ?::uuid, 'category', ?::uuid)
                """).params(VERSION, ROUTE, STEP, SEEDER).update()
    }

    private void pinned(String step, int position, String name, String version) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native step_kind and entry_kind enum literals.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, pinned_version_id,
                                            pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'entry', ?::uuid, 'question', ?::uuid)
                """).params(step, VERSION, position, name, version, SEEDER).update()
    }

    private void bind(String step, String target, String input) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, target_path, source_path, created_by)
                values (?::uuid, ?::uuid, ?, ?, ?::uuid)
                """).params(VERSION, step, target, input, SEEDER).update()
    }

    private void term(String list, int position, String term) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, 'When it is.', ?::uuid)
                """).params(list, position, term, SEEDER).update()
    }

    private String key(String version, String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select declaration_field_id::text from declaration_fields where entry_version_id = ?::uuid and name = ?",
                version, name).first()
    }

    private void fileGiven() {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid and native field_kind enum literal.
        store.session.sql("""
                update declaration_fields
                   set kind = 'file', text_limit = null, file_types = array['.pdf'], file_maximum_bytes = 1024
                 where entry_version_id in (?::uuid, ?::uuid) and name = 'summary'
                """).params(ASKED, VERSION).update()
    }

    private void workflowFilesGiven(long maximumBytes, boolean extra) {
        fileGiven()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid and native step_producer enum literal.
        store.session.sql("""
                update workflow_steps
                   set producer = 'person', producer_model = null, producer_mode = null, tries = 1
                 where workflow_step_id = ?::uuid
                """).params(STEP).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                update declaration_fields
                   set holds_many = true, many_limit = 16,
                       file_maximum_bytes = case when entry_version_id = ?::uuid then 1 else ? end
                 where entry_version_id in (?::uuid, ?::uuid) and name = 'summary'
                """).params(ASKED, maximumBytes, ASKED, VERSION).update()
        if (extra) {
            field(ASKED, "question", "gives", 3, "extra", "file", null, null, true, "always")
            field(VERSION, "workflow", "gives", 2, "extra", "file", null, null, true, null)
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    update declaration_fields
                       set file_types = array['.pdf'], file_maximum_bytes = 1
                     where entry_version_id in (?::uuid, ?::uuid) and name = 'extra'
                    """).params(ASKED, VERSION).update()
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    insert into bindings (entry_version_id, target_path, source_step_id, source_path, created_by)
                    values (?::uuid, 'extra', ?::uuid, 'extra', ?::uuid)
                    """).params(VERSION, STEP, SEEDER).update()
        }
    }

    private void routeFilesGiven(long maximumBytes, long extraBytes) {
        routed("Billing")
        def parent = "0000000b-0000-4000-8000-000000000c31"
        def document = "0000000b-0000-4000-8000-000000000c32"
        def extra = "0000000b-0000-4000-8000-000000000c33"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, workflow_step_id, side, position, name,
                                                kind, holds_many, many_limit, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'gives', 1, 'details', 'fields', true, 16, false, ?::uuid)
                """).params(parent, ROUTE, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, workflow_step_id, side, parent_field_id,
                                                position, name, kind, file_types, file_maximum_bytes,
                                                must_be_given, created_by)
                values (?::uuid, ?::uuid, 'gives', ?::uuid, 1, 'document', 'file',
                        array['.pdf'], ?, false, ?::uuid)
                """).params(document, ROUTE, parent, maximumBytes, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, workflow_step_id, side, position, name,
                                                kind, file_types, file_maximum_bytes, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'gives', 2, 'extra', 'file', array['.pdf'], ?, false, ?::uuid)
                """).params(extra, ROUTE, extraBytes, SEEDER).update()
    }

    private void secondFileTaken(long maximumBytes) {
        field(VERSION, "workflow", "takes", 3, "extra", "file", null, null, false, null)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                update declaration_fields
                   set file_types = array['.pdf'], file_maximum_bytes = ?
                 where entry_version_id = ?::uuid and name = 'extra'
                """).params(maximumBytes, VERSION).update()
    }

    private String fileKey() {
        key(VERSION, "attachment")
    }

    private void fileTaken(long maximumBytes, int most) {
        field(VERSION, "workflow", "takes", 2, "attachment", "file", null, null, false, null)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                update declaration_fields
                   set file_types = array['.pdf'], file_maximum_bytes = ?, holds_many = true, many_limit = ?
                 where declaration_field_id = ?::uuid
                """).params(maximumBytes, most, fileKey()).update()
    }

    private void field(String version, String kind, String side, int position, String name, String fieldKind,
                       Integer longest, String list, Boolean mustBeGiven, String standing, String key = null) {
        // DB-SPECIFIC: PostgreSQL uuidv7() and :: casts to uuid, entry_kind, declaration_side,
        // field_kind and field_standing.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name,
                                                kind, text_limit, term_list_version_id, must_be_given, standing, created_by)
                values (coalesce(?::uuid, uuidv7()), ?::uuid, ?::entry_kind, ?::declaration_side, ?, ?, ?::field_kind, ?,
                        ?::uuid, ?, ?::field_standing, ?::uuid)
                """).params(key, version, kind, side, position, name, fieldKind, longest, list, mustBeGiven, standing, SEEDER)
                .update()
    }

    private static ContentProblem problem(ContentProblemCode code, ContentPlace place) {
        new ContentProblem(code, place, null, null)
    }
}
