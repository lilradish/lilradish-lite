package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.run.StepRuns
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import com.lilradish.lite.testutil.library.LibraryStore
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunnableWorkflowsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-00000000e601"
    static final String WORKFLOW = "00000006-0000-4000-8000-00000000e601"
    static final String VERSION = "00000007-0000-4000-8000-00000000e601"
    static final String TARGET = "00000006-0000-4000-8000-00000000e602"
    static final String TARGET_VERSION = "00000007-0000-4000-8000-00000000e602"
    static final String ROUTE = "0000000c-0000-4000-8000-00000000e601"
    static final String FALLBACK = "0000000d-0000-4000-8000-00000000e601"
    static final String CASE = "0000000d-0000-4000-8000-00000000e602"
    static final String DISCRIMINATOR = "0000000e-0000-4000-8000-00000000e601"
    static final String INPUT = "0000000e-0000-4000-8000-00000000e602"
    static final String FALLBACK_INPUT = "0000000e-0000-4000-8000-00000000e603"
    static final String STEP_INPUT = "0000000e-0000-4000-8000-00000000e604"
    static final String OUTPUT = "0000000e-0000-4000-8000-00000000e605"
    static final String LIST = "00000006-0000-4000-8000-00000000e605"
    static final String LIST_VERSION = "00000007-0000-4000-8000-00000000e605"
    static final String CATEGORY = "0000000b-0000-4000-8000-00000000e601"
    static final String QUESTION = "00000006-0000-4000-8000-00000000e604"
    static final String QUESTION_VERSION = "00000007-0000-4000-8000-00000000e604"
    static final String QUESTION_STEP = "0000000c-0000-4000-8000-00000000e604"
    static final String SEARCHING = "00000006-0000-4000-8000-00000000e607"
    static final String SEARCHING_VERSION = "00000007-0000-4000-8000-00000000e607"
    static final String SEARCH_STEP = "0000000c-0000-4000-8000-00000000e607"
    static final String SEARCH_QUESTION = "00000006-0000-4000-8000-00000000e608"
    static final String SEARCH_QUESTION_VERSION = "00000007-0000-4000-8000-00000000e608"
    static final String SEARCH_INPUT = "0000000e-0000-4000-8000-00000000e607"
    static final SearchWays NONE_HELD = new SearchWays([])
    static final SearchWay WEB_SEARCH = new SearchWay("web_search", new ModelToolName("web_search"), 100, 1000, 1000, 1000)
    static final SearchWay OTHER_WAY = new SearchWay("other_search", new ModelToolName("web_search"), 100, 1000, 1000, 1000)

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "runnable_workflows_" + (++databasesMade), specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        store.group(GROUP, "SUPPORT", "Demo Sample group")
        store.entry(LIST, GROUP, "reference_list", "Demo Sample terms")
        store.seeded(LIST_VERSION, LIST, 1)
        store.content(LIST_VERSION, "reference_list")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'go', 'Demo Sample.', ?::uuid)
                """).params(LIST_VERSION, SEEDER).update()

        store.entry(WORKFLOW, GROUP, "workflow", "Demo Sample route")
        store.seeded(VERSION, WORKFLOW, 1)
        store.content(VERSION, "workflow")
        store.termField(CATEGORY, VERSION, "workflow", LIST_VERSION, "takes")

        store.entry(QUESTION, GROUP, "question", "Demo Sample question")
        store.seeded(QUESTION_VERSION, QUESTION, 1)
        store.content(QUESTION_VERSION, "question", "Demo Sample.")
        StepRows.field(store, [id: "0000000b-0000-4000-8000-00000000e604", owner: QUESTION_VERSION,
                               ownerKind: "question", side: "takes", position: 1, name: "message", limit: 100,
                               mustBe: true])
        StepRows.field(store, [id: "0000000b-0000-4000-8000-00000000e605", owner: QUESTION_VERSION,
                               ownerKind: "question", side: "gives", position: 1, name: "answer", limit: 100,
                               mustBe: true, standing: "always"])

        store.entry(TARGET, GROUP, "workflow", "Demo Sample target")
        store.seeded(TARGET_VERSION, TARGET, 1)
        store.content(TARGET_VERSION, "workflow")
        StepRows.field(store, [id: "0000000b-0000-4000-8000-00000000e606", owner: TARGET_VERSION,
                               ownerKind: "workflow", side: "takes", position: 1, name: "message", limit: 100,
                               mustBe: true])
        StepRows.field(store, [id: "0000000b-0000-4000-8000-00000000e607", owner: TARGET_VERSION,
                               ownerKind: "workflow", side: "gives", position: 1, name: "answer", limit: 100,
                               mustBe: true])
        StepRows.step(store, QUESTION_STEP, TARGET_VERSION, 0, "answer", QUESTION_VERSION, "person", 1)
        StepRows.binding(store, STEP_INPUT, TARGET_VERSION, QUESTION_STEP, "message", null, "message")
        StepRows.binding(store, OUTPUT, TARGET_VERSION, null, "answer", QUESTION_STEP, "answer")

        store.routed(ROUTE, FALLBACK, VERSION, 0, TARGET_VERSION)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id,
                                         created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'go', ?::uuid, ?::uuid)
                """).params(CASE, VERSION, ROUTE, TARGET_VERSION, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into bindings (binding_id, entry_version_id, workflow_step_id, step_kind, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'route', 'category', ?::uuid)
                """).params(DISCRIMINATOR, VERSION, ROUTE, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, jsonb.
        store.session.sql("""
                insert into bindings (binding_id, entry_version_id, route_case_id, target_path, constant, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'message', '"Demo Sample fallback"'::jsonb, ?::uuid)
                """).params(FALLBACK_INPUT, VERSION, FALLBACK, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, jsonb.
        store.session.sql("""
                insert into bindings (binding_id, entry_version_id, route_case_id, target_path, constant, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'message', '"Demo Sample case"'::jsonb, ?::uuid)
                """).params(INPUT, VERSION, CASE, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (workflow_step_id, step_kind, side, position, name, kind,
                                                text_limit, must_be_given, created_by)
                values (?::uuid, 'route', 'gives', 1, 'answer', 'text', 100, true, ?::uuid)
                """).params(ROUTE, SEEDER).update()
    }

    def "reads a route with its discriminator, declaration, cases, targets and case bindings"() {
        when:
        def versions = RunnableWorkflows.read(store.session, groupId(GROUP),
                [versionId(VERSION), versionId(TARGET_VERSION)], CodeStepsHeld.NONE, NONE_HELD)
        def workflow = versions[versionId(VERSION)]
        def target = versions[versionId(TARGET_VERSION)]
        def route = workflow.steps()[0].runs() as StepRuns.Route

        then:
        workflow.steps().size() == 1
        workflow.takes().fields()*.name()*.value() == ["category"]
        workflow.lists()[versionId(LIST_VERSION)].offers("go")
        !workflow.lists()[versionId(LIST_VERSION)].offers("other")
        route.discriminator().id() == UUID.fromString(DISCRIMINATOR)
        route.discriminator().source() == new BindingSource.WorkflowInput(Pointer.parse("category"))
        route.gives() == target.gives()
        route.gives().fields()*.name()*.value() == ["answer"]
        target.takes().fields()*.name()*.value() == ["message"]
        target.outputs()*.source() == [new BindingSource.StepOutput(UUID.fromString(QUESTION_STEP),
                Pointer.parse("answer"))]
        route.cases()*.id() == [FALLBACK, CASE].collect(UUID::fromString)
        route.cases()*.term() == [null, "go"]
        route.cases()*.target()*.version() == [TARGET_VERSION, TARGET_VERSION].collect { versionId(it) }
        route.cases()[0].bindings()*.id() == [UUID.fromString(FALLBACK_INPUT)]
        route.cases()[0].bindings()[0].source() == new BindingSource.Written(
                new JsonValue.JsonString("Demo Sample fallback"))
        route.cases()[1].bindings()*.id() == [UUID.fromString(INPUT)]
        route.cases()[1].bindings()[0].source() == new BindingSource.Written(
                new JsonValue.JsonString("Demo Sample case"))
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "each runnable version keeps its own display name while machine identities and bindings stay unchanged"() {
        given:
        store.session.sql("update workflow_steps set label = ? where workflow_step_id = ?::uuid")
                .params(label, ROUTE).update()
        store.session.sql("update workflow_steps set label = ? where workflow_step_id = ?::uuid")
                .params("\u56de\u7b54\u3092\u78ba\u304b\u3081\u308b", QUESTION_STEP).update()

        when:
        def versions = RunnableWorkflows.read(store.session, groupId(GROUP),
                [versionId(TARGET_VERSION), versionId(VERSION)], CodeStepsHeld.NONE, NONE_HELD)
        def route = versions[versionId(VERSION)].steps()[0]
        def question = versions[versionId(TARGET_VERSION)].steps()[0]

        then:
        route.id().value() == UUID.fromString(ROUTE)
        route.label()?.value() == label
        question.id().value() == UUID.fromString(QUESTION_STEP)
        question.name().value() == "answer"
        question.label().value() == "\u56de\u7b54\u3092\u78ba\u304b\u3081\u308b"
        question.bindings()*.id() == [UUID.fromString(STEP_INPUT)]
        question.bindings()[0].source() == new BindingSource.WorkflowInput(Pointer.parse("message"))
        versions[versionId(TARGET_VERSION)].outputs()[0].source() ==
                new BindingSource.StepOutput(UUID.fromString(QUESTION_STEP), Pointer.parse("answer"))
        store.session.sql("select label from workflow_steps where workflow_step_id = ?::uuid")
                .param(ROUTE).query(String).optional().orElse(null) == label

        where:
        label << [null, "\u6b21\u3078\u9032\u3081\u308b\u202e"]
    }

    def "reads a search in service with its way, pinned question instruction and declaration, keyed bindings, and every standing"() {
        given:
        searchInService()

        when:
        def step = RunnableWorkflows.read(store.session, groupId(GROUP), [versionId(SEARCHING_VERSION)],
                CodeStepsHeld.NONE, NONE_HELD)[versionId(SEARCHING_VERSION)].steps()[0]
        def search = step.runs() as StepRuns.Search

        then:
        search.way() == "web_search"
        search.released() == null
        search.question().pinned().version() == versionId(SEARCH_QUESTION_VERSION)
        search.question().instruction().value() == "Find sources about the supplied category."
        search.question().takes().fields()*.name()*.value() == ["category"]
        search.question().gives().fields()*.name()*.value() == ["title", "body", "references", "other"]
        search.markedWay() == "web_search"
        step.bindings() == [new Binding(UUID.fromString(SEARCH_INPUT), Pointer.parse("category"),
                new BindingSource.WorkflowInput(Pointer.parse("category")))]
        search.standings() == [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.NEVER,
                               (SearchWay.REFERENCES): FieldStanding.NEVER, (SearchWay.OTHER): FieldStanding.ALWAYS]
    }

    def "a search in service is read with what the release declares of its way now, and no other way it holds"() {
        given:
        searchInService()

        when:
        def search = RunnableWorkflows.read(store.session, groupId(GROUP), [versionId(SEARCHING_VERSION)],
                CodeStepsHeld.NONE, new SearchWays([OTHER_WAY, WEB_SEARCH]))[versionId(SEARCHING_VERSION)].steps()[0]
                .runs() as StepRuns.Search

        then:
        search.released() == WEB_SEARCH
        search.released() != OTHER_WAY
        search.way() == "web_search"
    }


    def "a search in service naming no way, leaving a standing unchosen or pinning no question fails the read"() {
        given:
        searchInService()
        store.session.sql(change).update()

        when:
        RunnableWorkflows.read(store.session, groupId(GROUP), [versionId(SEARCHING_VERSION)], CodeStepsHeld.NONE, NONE_HELD)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version " + SEARCHING_VERSION + " holds what no version in service could hold"
        failed.cause instanceof IllegalArgumentException
        failed.cause.message == reason as String

        where:
        change                                                                        || reason
        "update workflow_steps set search_way = null where kind = 'search'"           || "Step ${SEARCH_STEP} runs no way to search chosen"
        "update workflow_steps set search_body_standing = null where kind = 'search'" || "StepRuns.Search must say how each field of its way stands, and of no other"
        "update workflow_steps set pinned_version_id = null, pinned_kind = null where kind = 'search'" || "Step ${SEARCH_STEP} pins no question its group holds"
    }

    def "refuses a case pinned to a version outside the workflow's group"() {
        given:
        def otherGroup = "00000003-0000-4000-8000-00000000e603"
        def otherEntry = "00000006-0000-4000-8000-00000000e603"
        def otherVersion = "00000007-0000-4000-8000-00000000e603"
        store.group(otherGroup, "BILLING", "Demo Sample other group")
        store.entry(otherEntry, otherGroup, "workflow", "Demo Sample other workflow")
        store.seeded(otherVersion, otherEntry, 1)
        store.content(otherVersion, "workflow")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update route_cases set target_version_id = ?::uuid where route_case_id = ?::uuid")
                .params(otherVersion, CASE).update()

        when:
        RunnableWorkflows.read(store.session, groupId(GROUP), [versionId(VERSION)], CodeStepsHeld.NONE, NONE_HELD)

        then:
        def refused = thrown(IllegalStateException)
        refused.cause.message == "Case " + UUID.fromString(CASE) + " pins a version its group does not hold"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from route_cases where route_case_id = ?::uuid and target_version_id = ?::uuid",
                CASE, otherVersion) == 1
    }

    def "a workflow pin carries its actual output demands for whole and nested sources without reading its steps"() {
        given:
        pinWorkflow(TARGET_VERSION, 1)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update declaration_fields set must_be_given = ? where entry_version_id = ?::uuid and side = 'gives'")
                .params(required, TARGET_VERSION).update()
        def bundle = UUID.randomUUID().toString()
        StepRows.field(store, [id: bundle, owner: TARGET_VERSION, ownerKind: "workflow", side: "gives", position: 2,
                               name: "bundle", kind: "fields", mustBe: required])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: TARGET_VERSION, ownerKind: "workflow",
                               side: "gives", parent: bundle, position: 1, name: "note", mustBe: true])
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update workflow_steps set pinned_version_id = null, pinned_kind = null where workflow_step_id = ?::uuid")
                .param(QUESTION_STEP).update()

        when:
        def read = RunnableWorkflows.read(store.session, groupId(GROUP), [versionId(VERSION)],
                CodeStepsHeld.NONE, NONE_HELD)
        def workflow = read[versionId(VERSION)].steps()[1].runs() as StepRuns.Workflow
        def given = workflow.gives().fields()

        then:
        read.keySet() == [versionId(VERSION)] as Set
        workflow.pinned().version() == versionId(TARGET_VERSION)
        given*.name()*.value() == ["answer", "bundle"]
        (Pointer.parse("answer").reach(given) as Pointer.Reach.At).given() == required
        (Pointer.parse("bundle").reach(given) as Pointer.Reach.At).given() == required
        (Pointer.parse("bundle.note").reach(given) as Pointer.Reach.At).given() == required
        (Pointer.parse("bundle.note").reach(given) as Pointer.Reach.At).field().demand().mustBe()
        Pointer.parse("bundle.absent").reach(given) instanceof Pointer.Reach.Nowhere

        where:
        required << [true, false]
    }

    def "workflow pins are read in one additional declaration batch however many distinct or repeated pins are held"() {
        given:
        def statements = []
        def counted = JdbcClient.create(new CountingDataSource(store.database, statements))
        def targets = (0..<distinct).collect { number ->
            def entry = UUID.randomUUID().toString()
            def version = UUID.randomUUID().toString()
            store.entry(entry, GROUP, "workflow", "Demo Sample child " + number)
            store.seeded(version, entry, 1)
            store.content(version, "workflow")
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: "workflow", side: "gives",
                                   position: 1, name: "note", mustBe: number % 2 == 0])
            version
        }
        def held = targets * repeats
        held.eachWithIndex { version, index -> pinWorkflow(version, index + 1) }

        when:
        def read = RunnableWorkflows.read(counted, groupId(GROUP), [versionId(VERSION), versionId(VERSION)],
                CodeStepsHeld.NONE, NONE_HELD)
        def pins = read[versionId(VERSION)].steps().drop(1)*.runs()

        then:
        read.keySet() == [versionId(VERSION)] as Set
        pins*.pinned()*.version() == held.collect { versionId(it) }
        pins*.gives()*.fields().collect { it[0].demand().mustBe() } ==
                ((0..<distinct).collect { it % 2 == 0 } * repeats)
        statements.count { it.contains("field.entry_version_id as owner") } == 2
        statements.count { it.contains("from workflow_steps") } == 1
        !statements.any { it.stripLeading().startsWith("insert") || it.stripLeading().startsWith("update") }

        where:
        distinct | repeats
        1        | 1
        3        | 1
        3        | 2
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a workflow pin absent from the group's holdings fails before any pinned declaration is read"() {
        given:
        def target = UUID.randomUUID().toString()
        if (missing) {
            store.session.sql("alter table workflow_steps drop constraint workflow_steps_pinned_fk").update()
        } else {
            def otherGroup = UUID.randomUUID().toString()
            def otherEntry = UUID.randomUUID().toString()
            store.group(otherGroup, "BILLING", "Demo Sample other group")
            store.entry(otherEntry, otherGroup, "workflow", "Demo Sample other child")
            store.seeded(target, otherEntry, 1)
            store.content(target, "workflow")
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: target, ownerKind: "workflow", side: "gives",
                                   position: 1, name: "private_note", mustBe: true])
        }
        pinWorkflow(target, 1)
        def statements = []
        def counted = JdbcClient.create(new CountingDataSource(store.database, statements))

        when:
        RunnableWorkflows.read(counted, groupId(GROUP), [versionId(VERSION)], CodeStepsHeld.NONE, NONE_HELD)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Version " + target + " is not held by the workflow's group"
        statements.count { it.contains("field.entry_version_id as owner") } == 1
        !statements.any { it.stripLeading().startsWith("insert") || it.stripLeading().startsWith("update") }

        where:
        missing << [false, true]
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "code lists omit an unapproved version but keep approved and retired versions without changing preview"() {
        given:
        def entry = "00000006-0000-4000-8000-00000000e608"
        def version = "00000007-0000-4000-8000-00000000e608"
        store.entry(entry, GROUP, "reference_list", "Demo Sample code terms")
        if (approved) {
            store.seeded(version, entry, 1, retired)
        } else {
            store.version(version, entry, 1, SEEDER)
        }
        store.content(version, "reference_list")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'go', 'Demo Sample.', ?::uuid)
                """).params(version, SEEDER).update()
        def code = codeWorkflow(version, 1)
        def statements = []
        def counted = JdbcClient.create(new CountingDataSource(store.database, statements))

        when:
        def read = RunnableWorkflows.read(counted, groupId(GROUP),
                [versionId("00000007-0000-4000-8000-00000000e610")], CodeStepsHeld.of(code), NONE_HELD)
        def released = (read.values().first().steps()[0].runs() as StepRuns.Code).released()

        then:
        released != null
        released.lists().containsKey(versionId(version)) == approved
        !approved || released.lists()[versionId(version)].offers("go")
        StoredQuestion.offered(store.session, groupId(GROUP), [versionId(version)] as Set)
                .get(versionId(version)).offers("go")
        statements.count { it.contains("version.approved_at is not null") } == 1

        where:
        approved | retired
        false    | false
        true     | false
        true     | true
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a code list is held by its exact version and current group rather than another version or group"() {
        given:
        def entry = "00000006-0000-4000-8000-00000000e608"
        def version = "00000007-0000-4000-8000-00000000e608"
        if (location != "missing") {
            def owner = GROUP
            if (location == "other_group") {
                owner = "00000003-0000-4000-8000-00000000e608"
                store.group(owner, "SALES", "Demo Sample other group")
            }
            store.entry(entry, owner, "reference_list", "Demo Sample code terms")
            store.seeded(version, entry, 1)
            store.content(version, "reference_list")
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, 1, 'go', 'Demo Sample.', ?::uuid)
                    """).params(version, SEEDER).update()
        }
        def pinned = location == "other_version" ? "00000007-0000-4000-8000-00000000e609" : version
        def code = codeWorkflow(pinned, 1)

        when:
        def read = RunnableWorkflows.read(store.session, groupId(GROUP),
                [versionId("00000007-0000-4000-8000-00000000e610")], CodeStepsHeld.of(code), NONE_HELD)

        then:
        (read.values().first().steps()[0].runs() as StepRuns.Code).released().lists().isEmpty()

        where:
        location << ["missing", "other_group", "other_version"]
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "reading repeated code pins asks for approved lists in one batch and the same number of statements"() {
        given:
        def code = codeWorkflow(LIST_VERSION, 1)
        def firstStatements = []
        def firstDatabase = JdbcClient.create(new CountingDataSource(store.database, firstStatements))
        RunnableWorkflows.read(firstDatabase, groupId(GROUP),
                [versionId("00000007-0000-4000-8000-00000000e610")], CodeStepsHeld.of(code), NONE_HELD)
        (1..8).each { position ->
            StepRows.codeStep(store, UUID.randomUUID().toString(),
                    "00000007-0000-4000-8000-00000000e610", position, "send_" + position,
                    "send_reply", "code", 1)
        }
        def statements = []
        def counted = JdbcClient.create(new CountingDataSource(store.database, statements))

        when:
        def read = RunnableWorkflows.read(counted, groupId(GROUP),
                [versionId("00000007-0000-4000-8000-00000000e610")], CodeStepsHeld.of(code), NONE_HELD)

        then:
        read.values().first().steps().size() == 9
        read.values().first().steps().every {
            (it.runs() as StepRuns.Code).released().lists()[versionId(LIST_VERSION)].offers("go")
        }
        statements.size() == firstStatements.size()
        statements.count { it.contains("version.approved_at is not null") } == 1
        firstStatements.count { it.contains("version.approved_at is not null") } == 1
    }

    private SpecCodeStep codeWorkflow(String list, int pins) {
        def entry = "00000006-0000-4000-8000-00000000e610"
        def version = "00000007-0000-4000-8000-00000000e610"
        store.entry(entry, GROUP, "workflow", "Demo Sample code workflow")
        store.seeded(version, entry, 1)
        store.content(version, "workflow")
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        (0..<pins).each { position ->
            StepRows.codeStep(store, UUID.randomUUID().toString(), version, position, "send_" + position,
                    "send_reply", "code", 1)
        }
        new SpecCodeStep("send_reply", [],
                [SpecCodeStep.standing("category", new FieldShape.Term(versionId(list)))], false)
    }

    private void pinWorkflow(String version, int position) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                                            pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'entry', ?::uuid, 'workflow', ?::uuid)
                """).params(UUID.randomUUID().toString(), VERSION, position, "child_" + position, version, SEEDER).update()
    }


    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private void searchInService() {
        store.entry(SEARCH_QUESTION, GROUP, "question", "Source finder")
        store.seeded(SEARCH_QUESTION_VERSION, SEARCH_QUESTION, 1)
        store.content(SEARCH_QUESTION_VERSION, "question", "Find sources about the supplied category.")
        store.session.sql("update question_versions set search_way = 'web_search' where entry_version_id = ?::uuid")
                .param(SEARCH_QUESTION_VERSION).update()
        store.termField(UUID.randomUUID().toString(), SEARCH_QUESTION_VERSION, "question", LIST_VERSION, "takes")
        WEB_SEARCH.outputs().eachWithIndex { field, index ->
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: SEARCH_QUESTION_VERSION,
                                   ownerKind: "question", side: "gives", position: index + 1,
                                   name: field.name().value(), limit: (field.shape() as FieldShape.Text).longest(),
                                   mustBe: field.demand().mustBe(), standing: "never"])
        }
        store.entry(SEARCHING, GROUP, "workflow", "Demo Sample search")
        store.seeded(SEARCHING_VERSION, SEARCHING, 1)
        store.content(SEARCHING_VERSION, "workflow")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, search_way,
                                            producer, tries, search_title_standing, search_body_standing,
                                            search_references_standing, search_other_standing,
                                            pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, 0, 'find_context', 'search', 'web_search', 'person', 1, 'always', 'never',
                        'never', 'always', ?::uuid, 'question', ?::uuid)
                """).params(SEARCH_STEP, SEARCHING_VERSION, SEARCH_QUESTION_VERSION, SEEDER).update()
        StepRows.binding(store, SEARCH_INPUT, SEARCHING_VERSION, SEARCH_STEP, "category", null, "category")
    }
}
