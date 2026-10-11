package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.entryId
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.codestep.ReadTextFile
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.SendMeasure
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryName
import com.lilradish.lite.domain.registry.VersionStanding
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import com.lilradish.lite.testutil.library.LibraryStore
import org.libprunus.core.error.ApiErrorException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification


class WorkflowsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000d81"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000d82"


    static final String ANN = "00000002-0000-4000-8000-000000000d81"

    static final UserId ANN_USER = new UserId("000d81")


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
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    Workflows workflows

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "workflows_" + (++databasesMade), specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        workflows = new Workflows(store.session, new GroupRoles(store.session), LibraryStore.MODELS,
                new ReleasedCodeSteps(LibraryStore.CODE_STEPS), LibraryStore.FILES, new SearchWays([]), store.transactionManager())
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


    def "reads what a version holds, what it pins as a reader names it, and what submitting would refuse of it now"() {
        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.stored().steps()*.id() == [UUID.fromString(STEP)]
        view.stored().revision() == 1
        view.pins()[versionId(ASKED_OLD)] == new PinnedVersions.PinnedVersion(new EntryName("Classify"),
                versionId(ASKED_OLD), 1, VersionStanding.IN_SERVICE,
                new RetiredPinsRefusal.NumberedVersion(versionId(ASKED_NEW), 2, null), null)
        view.pins()[versionId(LISTED)].standing() == VersionStanding.IN_SERVICE
        view.declared()[versionId(ASKED_OLD)].gives().declaration().fields()*.name() == [new FieldName("category")]
        view.problems().problems() == [ContentProblemCode.PRODUCER_MISSING, ContentProblemCode.TRIES_MISSING].collect {
            new ContentProblem(it, new ContentPlace.AtStep(UUID.fromString(STEP)), null, null)
        }
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "approved and retired GET hide current code release diagnostics while draft GET and CHECK report them"() {
        given:
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'sort_out'").update()
        publish("sort_out", "SUPPORT")
        [WORKFLOW_IN_SERVICE, DRAFT].eachWithIndex { version, index ->
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step,
                                                created_by)
                    values (?::uuid, ?::uuid, ?, 'sort', 'code_step', 'sort_out', ?::uuid)
                    """).params("0000000c-0000-4000-8000-000000000d9${index}", version,
                    version == DRAFT ? 2 : 1, SEEDER).update()
        }
        def released = new ReleasedCodeSteps(new CodeSteps([]))
        def reading = new Workflows(store.session, new GroupRoles(store.session), LibraryStore.MODELS,
                released, LibraryStore.FILES, new SearchWays([]), store.transactionManager())
        def check = new WorkflowContentCheck(store.session, LibraryStore.MODELS, released,
                LibraryStore.FILES, new SearchWays([]))

        when:
        def approved = reading.read(groupId(GROUP), entryId(WORKFLOW), versionId(WORKFLOW_IN_SERVICE), ANN_USER)
        def draft = reading.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)
        def checked = check.problemsIn(groupId(GROUP), versionId(WORKFLOW_IN_SERVICE))
        store.retired(WORKFLOW_IN_SERVICE, FIRST_STEWARD)
        def retired = reading.read(groupId(GROUP), entryId(WORKFLOW), versionId(WORKFLOW_IN_SERVICE), ANN_USER)

        then:
        draft.problems().problems()*.code().contains(ContentProblemCode.CODE_STEP_NOT_DECLARED)
        checked.problems()*.code().contains(ContentProblemCode.CODE_STEP_NOT_DECLARED)
        !approved.problems().problems()*.code().contains(ContentProblemCode.CODE_STEP_NOT_DECLARED)
        !retired.problems().problems()*.code().contains(ContentProblemCode.CODE_STEP_NOT_DECLARED)
        approved.problems().problems()*.code().containsAll([
                ContentProblemCode.PRODUCER_MISSING, ContentProblemCode.TRIES_MISSING])
        approved.problems().total() == approved.problems().problems().size()
        retired.problems().total() == retired.problems().problems().size()
    }

    def "a workflow in service reads an old file declaration and names its sixty-five types"() {
        given:
        def document = "0000000b-0000-4000-8000-000000000d89"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; generate_series(); text || non-string renders the integer suffix.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                position, name, kind, file_types, file_maximum_bytes,
                                                must_be_given, created_by)
                values (?::uuid, ?::uuid, 'workflow', 'takes', 1, 'document', 'file',
                        ARRAY(select '.x' || n from generate_series(0, 64) n), 1024, true, ?::uuid)
                """).params(document, WORKFLOW_IN_SERVICE, SEEDER).update()

        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(WORKFLOW_IN_SERVICE), ANN_USER)

        then:
        view.problems().problems().findAll { it.code() == ContentProblemCode.FILE_TYPES_TOO_MANY } ==
                [new ContentProblem(ContentProblemCode.FILE_TYPES_TOO_MANY,
                        new ContentPlace.AtField(ContentPart.TAKES, UUID.fromString(document)), null, null)]
        def answered = DeclarationAnswers.of(view.stored().takes(), view.pins())[0]
        answered.types() == (0..64).collect { ".x${it}" as String }
        answered.maximumBytes() == 1024L
    }

    def "a workflow in service reads its files given back against the handover total"() {
        given:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; uuidv7().
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name, kind,
                                                file_types, file_maximum_bytes, holds_many, many_limit,
                                                must_be_given, created_by)
                values (uuidv7(), ?::uuid, 'workflow', 'gives', 1, 'documents', 'file', array['.pdf'],
                        ?, true, 16, false, ?::uuid)
                """).params(WORKFLOW_IN_SERVICE, documentBytes, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; uuidv7().
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name, kind,
                                                file_types, file_maximum_bytes, must_be_given, created_by)
                values (uuidv7(), ?::uuid, 'workflow', 'gives', 2, 'extra', 'file', array['.pdf'],
                        ?, false, ?::uuid)
                """).params(WORKFLOW_IN_SERVICE, extraBytes, SEEDER).update()

        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(WORKFLOW_IN_SERVICE), ANN_USER)

        then:
        view.stored().gives().declaration().fields()*.name() == [new FieldName("documents"), new FieldName("extra")]
        view.problems().problems().findAll { it.code() == ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT } ==
                (past ? [new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                        new ContentPlace.Whole(ContentPart.GIVES), null,
                        new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))] : [])

        where:
        documentBytes | extraBytes || past
        67_108_863L   | 16L        || false
        67_108_864L   | 1L         || true
    }

    def "a workflow in service reads a route's nested files against the handover total"() {
        given:
        def route = "0000000c-0000-4000-8000-000000000d89"
        def parent = "0000000b-0000-4000-8000-000000000d8a"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by)
                values (?::uuid, ?::uuid, 1, 'route', 'route', ?::uuid)
                """).params(route, WORKFLOW_IN_SERVICE, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, workflow_step_id, side, position, name, kind,
                                                holds_many, many_limit, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'gives', 1, 'details', 'fields', true, 16, false, ?::uuid)
                """).params(parent, route, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; uuidv7().
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, workflow_step_id, side, parent_field_id, position, name, kind,
                                                file_types, file_maximum_bytes, must_be_given, created_by)
                values (uuidv7(), ?::uuid, 'gives', ?::uuid, 1, 'document', 'file', array['.pdf'],
                        ?, false, ?::uuid)
                """).params(route, parent, documentBytes, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; uuidv7().
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, workflow_step_id, side, position, name, kind,
                                                file_types, file_maximum_bytes, must_be_given, created_by)
                values (uuidv7(), ?::uuid, 'gives', 2, 'extra', 'file', array['.pdf'],
                        ?, false, ?::uuid)
                """).params(route, extraBytes, SEEDER).update()

        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(WORKFLOW_IN_SERVICE), ANN_USER)

        then:
        view.stored().steps()*.id() == [UUID.fromString(route)]
        view.problems().problems().findAll { it.code() == ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT } ==
                (past ? [new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                        new ContentPlace.AtStep(UUID.fromString(route)), null,
                        new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))] : [])

        where:
        documentBytes | extraBytes || past
        67_108_863L   | 16L        || false
        67_108_864L   | 1L         || true
    }


    def "a step that could send its models more than they take says so for each, and refuses nothing for it"() {
        given:
        takingLetters(ASKED_OLD, longest)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, jsonb.
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
        view.problems().problems() == []

        where:
        longest | producer | told  | reviewer  || said
        100_000 | "model"  | true  | "general" || [[SendingRole.PRODUCING, "small"], [SendingRole.REVIEWING, "general"]]
        100_000 | "model"  | false | null      || [[SendingRole.PRODUCING, "small"]]
        100_000 | "person" | false | "general" || [[SendingRole.REVIEWING, "general"]]
        10      | "model"  | true  | "general" || []
    }


    def "a version in service is measured against the models held as it is read, and the figure is never stored"() {
        given:
        def served = "0000000c-0000-4000-8000-000000000d84"
        store.step(served, WORKFLOW_IN_SERVICE, 1, ASKED_OLD, "question")
        takingLetters(ASKED_OLD, 100_000)
        asked(served, "model", false, null)
        def roomier = new Workflows(store.session, new GroupRoles(store.session), new ModelCatalog([
                new DeployedModel(new ModelName("small"), [], 10_000_000, 4.0G, 4_000, [], [])]),
                new ReleasedCodeSteps(LibraryStore.CODE_STEPS), LibraryStore.FILES, new SearchWays([]), store.transactionManager())
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


    @spock.lang.Tag("postgres-schema-changing")
    def "searches service versions independently while workflow offers retain released code steps and models"() {
        given:
        store.retired(ASKED_OLD, FIRST_STEWARD)
        store.entry(OTHER_WORKFLOW, OTHER_GROUP, "workflow", "Elsewhere")
        store.seeded(OTHER_VERSION, OTHER_WORKFLOW, 1)
        store.content(OTHER_VERSION, "workflow")
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'send_reply'").update()
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'archive'").update()
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'close_ticket'").update()
        publish("send_reply", "SUPPORT")
        publish("close_ticket", null)
        publish("archive", "BILLING")

        when:
        def offers = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER).offers()

        then:
        def candidates = new VersionCandidates(store.session, new GroupRoles(store.session), new SearchWays([]),
                store.transactionManager())
        candidates.candidates(groupId(GROUP), com.lilradish.lite.domain.registry.EntryKind.REFERENCE_LIST,
                "Categories", null, ANN_USER).items()*.versionId() == [UUID.fromString(LISTED)]
        candidates.candidates(groupId(GROUP), com.lilradish.lite.domain.registry.EntryKind.QUESTION,
                "Classify", null, ANN_USER).items()*.versionId() == [UUID.fromString(ASKED_NEW)]
        candidates.detail(groupId(GROUP), versionId(ASKED_NEW), ANN_USER).gives()*.name() == ["category"]
        candidates.candidates(groupId(GROUP), com.lilradish.lite.domain.registry.EntryKind.WORKFLOW,
                "Handle", null, ANN_USER).items()*.versionId() == [UUID.fromString(WORKFLOW_IN_SERVICE)]
        offers.codeSteps() == [
            new Workflows.OfferedCodeStep("read_text_file",
                    StoredDeclarations.ofCodeStep("read_text_file", new ReadTextFile().declaration())),
            new Workflows.OfferedCodeStep("send_reply",
                    StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration())),
        ]
        offers.models() == LibraryStore.MODELS.all()
    }

    /** Only a code step whose pinned lists belong to the group and are in service may be offered. */
    @spock.lang.Tag("postgres-schema-changing")
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
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'sort_out'").update()
        publish("sort_out", "SUPPORT")
        def reading = new Workflows(store.session, new GroupRoles(store.session), LibraryStore.MODELS,
                new ReleasedCodeSteps(new CodeSteps([sortOut])), LibraryStore.FILES, new SearchWays([]), store.transactionManager())

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

    /** A draft may expose a code step's declaration only when every pinned list belongs to the group. */
    @spock.lang.Tag("postgres-schema-changing")
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
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'sort_out'").update()
        publish("sort_out", "SUPPORT")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step,
                                            producer, tries, created_by)
                values (?::uuid, ?::uuid, 2, 'sort', 'code_step', 'sort_out', 'code', 1, ?::uuid)
                """).params("0000000c-0000-4000-8000-000000000d88", DRAFT, SEEDER).update()
        def reading = new Workflows(store.session, new GroupRoles(store.session), LibraryStore.MODELS,
                new ReleasedCodeSteps(new CodeSteps([sortOut])), LibraryStore.FILES, new SearchWays([]), store.transactionManager())

        when:
        def view = reading.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.problems().problems().findAll { it.place() == new ContentPlace.AtStep(UUID.fromString("0000000c-0000-4000-8000-000000000d88")) }
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'Refunds', 'Money back.', ?::uuid)
                """).params(refundKindsListed, SEEDER).update()
        store.routed(firstRoute, "0000000d-0000-4000-8000-000000000d82", DRAFT, 2, WORKFLOW_IN_SERVICE)
        store.routed(secondRoute, secondCase, DRAFT, 3, WORKFLOW_IN_SERVICE)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (workflow_step_id, step_kind, side, position, name, kind,
                                                term_list_version_id, must_be_given, created_by)
                values (?::uuid, 'route', 'gives', 1, 'category', 'term', ?::uuid, true, ?::uuid)
                """).params(firstRoute, refundKindsListed, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, step_kind, source_step_id, source_path,
                                      created_by)
                values (?::uuid, ?::uuid, 'route', ?::uuid, 'category', ?::uuid)
                """).params(DRAFT, secondRoute, firstRoute, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update route_cases set term = 'Billing' where route_case_id = ?::uuid").param(secondCase).update()

        when:
        def view = workflows.read(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER)

        then:
        view.pins()[versionId(refundKindsListed)].name() == new EntryName("Refund kinds")
        view.terms()[versionId(refundKindsListed)].terms()*.term()*.value() == ["Refunds"]
        view.problems().problems().findAll { it.code() == ContentProblemCode.CASE_NOT_OFFERED }*.place() ==
                [new ContentPlace.AtCase(UUID.fromString(secondRoute), UUID.fromString(secondCase))]
    }


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

    /** A stored version must pin only versions held by its group. */
    def "a version pinning another group's version fails the read rather than naming it"() {
        given:
        store.entry(OTHER_WORKFLOW, OTHER_GROUP, "workflow", "Elsewhere")
        store.seeded(OTHER_VERSION, OTHER_WORKFLOW, 1)
        store.content(OTHER_VERSION, "workflow")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
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


    private void takingLetters(String version, int longest) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update declaration_fields set standing = 'always' where entry_version_id = ?::uuid")
                .params(version).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, text_limit,
                                                must_be_given, created_by)
                values (?::uuid, 'question', 'takes', 1, 'letter', 'text', ?, true, ?::uuid)
                """).params(version, longest, SEEDER).update()
    }


    private void asked(String step, String producer, boolean told, String reviewer) {
        def model = producer == "model" ? "small" : null
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; stored enum types.
        store.session.sql("""
                update workflow_steps
                   set producer = cast(? as step_producer), producer_model = ?, producer_mode = ?, tries = 1,
                       tells_what_happened = ?, reviewer_model = ?, reviewer_mode = ?
                 where workflow_step_id = ?::uuid
                """).params(producer, model, model == null ? null : "ordinary", told, reviewer,
                reviewer == null ? null : "ordinary", step).update()
    }

    private List<Map<String, Object>> stepsOf(String version) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("select * from workflow_steps where entry_version_id = ?::uuid order by position")
                .params(version).query().listOfRows()
    }

    private void publish(String codeStep, String key) {
        // DB-SPECIFIC: PostgreSQL :: casts to code_step, uuid.
        store.session.sql("""
                insert into code_step_publications (code_step, group_key, every_group, created_by)
                values (?::code_step, ?, ?, ?::uuid)
                """).params(codeStep, key, key == null, SEEDER).update()
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "workflow GET leaves catalogues to independent details that retain every search fit and non-search workflow shape"() {
        given:
        def way = com.lilradish.lite.testutil.library.DeployedModels.WEB
        def versions = [:]
        ['matched', 'mismatch', 'ordinary', 'missing_way'].each { state ->
            def version = UUID.randomUUID().toString()
            versions[state] = version
            com.lilradish.lite.app.run.fixture.StepRows.question(store, UUID.randomUUID().toString(), version,
                    GROUP, 'Fictional question ' + state, 'Find fictional package evidence.')
            if (state != 'ordinary') {
                store.session.sql('update question_versions set search_way = ? where entry_version_id = ?::uuid')
                        .params(state == 'missing_way' ? 'archive' : 'web', version).update()
            }
            way.outputs().eachWithIndex { field, index ->
                com.lilradish.lite.app.run.fixture.StepRows.field(store, [
                        id: UUID.randomUUID().toString(), owner: version, ownerKind: 'question', side: 'gives',
                        position: index, name: field.name().value(),
                        limit: state == 'mismatch' && index == 0 ? 201 : ((FieldShape.Text) field.shape()).longest(),
                        mustBe: field.demand().mustBe(), standing: 'never'])
            }
        }
        def reading = new Workflows(store.session, new GroupRoles(store.session), LibraryStore.MODELS,
                new ReleasedCodeSteps(LibraryStore.CODE_STEPS), LibraryStore.FILES, new SearchWays([way]),
                store.transactionManager())
        def unusedWrites = Mock(WorkflowDrafts)
        def details = new VersionCandidates(store.session, new GroupRoles(store.session), new SearchWays([way]),
                store.transactionManager())
        def mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new WorkflowController(reading, unusedWrites), new VersionCandidatesController(details)).build()
        def before = store.contents()

        when:
        def response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/groups/${GROUP}/workflows/${WORKFLOW}/versions/${DRAFT}")
                .requestAttr(com.lilradish.lite.web.CallerAdmission.class.name + '.caller', ANN_USER)
                .requestAttr(com.lilradish.lite.web.ActAdmission.class.name + '.group', groupId(GROUP)))
                .andReturn().response
        def wire = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.contentAsString)
        def identifiers = (versions.values() + [ASKED_OLD, ASKED_NEW]).toSet()
        def mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        def rows = identifiers.collectEntries { identifier ->
            def detail = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/groups/${GROUP}/versions/${identifier}")
                    .requestAttr(com.lilradish.lite.web.CallerAdmission.class.name + '.caller', ANN_USER)
                    .requestAttr(com.lilradish.lite.web.ActAdmission.class.name + '.group', groupId(GROUP)))
                    .andReturn().response
            assert detail.status == 200
            def body = mapper.readTree(detail.contentAsString)
            [(body.path('version').path('versionId').asText()): body]
        }
        def workflowDetail = mapper.valueToTree(details.detail(groupId(GROUP), versionId(DRAFT), ANN_USER))

        then:
        response.status == 200
        rows.keySet() == identifiers
        rows.values().every { it.path('searchMatches').isBoolean() }
        rows[versions.matched].path('searchMatches').asBoolean()
        !rows[versions.mismatch].path('searchMatches').asBoolean()
        !rows[versions.ordinary].path('searchMatches').asBoolean()
        !rows[versions.missing_way].path('searchMatches').asBoolean()
        !rows[ASKED_NEW].path('searchMatches').asBoolean()
        !rows[ASKED_OLD].path('searchMatches').asBoolean()
        rows[versions.matched].path('searchWay').asText() == 'web'
        rows[versions.mismatch].path('searchWay').asText() == 'web'
        rows[versions.missing_way].path('searchWay').asText() == 'archive'
        !rows[versions.ordinary].has('searchWay')
        !wire.path('offered').has('questions') && !wire.path('offered').has('workflows')
        workflowDetail.path('kind').asText() == 'workflow'
        !workflowDetail.has('searchMatches') && !workflowDetail.has('searchWay')
        store.contents() == before
        0 * unusedWrites._

        when:
        def withoutWays = new VersionCandidates(store.session, new GroupRoles(store.session), new SearchWays([]),
                store.transactionManager())
        def noWay = identifiers.collect { withoutWays.detail(groupId(GROUP), versionId(it), ANN_USER) }

        then:
        noWay*.version()*.versionId().collect { it.toString() }.toSet() == rows.keySet()
        noWay.every { !it.searchMatches() }
        noWay.find { it.version().versionId().toString() == versions.matched }.searchWay() == 'web'
        store.contents() == before
    }

}
