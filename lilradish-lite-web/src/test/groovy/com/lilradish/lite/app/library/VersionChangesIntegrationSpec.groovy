package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.attempting
import static com.lilradish.lite.testutil.library.LibraryStore.entryId
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.declaration.TextLines
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.model.ModelMode
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.registry.EntryName
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.registry.VersionName
import com.lilradish.lite.domain.run.Ceiling
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.ModelChoice
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.Producer
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.domain.workflow.StepLabel
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.library.LibraryStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.libprunus.core.error.ApiErrorException
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Read Committed permits reading the version again after waiting for its lock. */
class VersionChangesIntegrationSpec extends Specification {

    // DB-SPECIFIC: SQL concatenations below use PostgreSQL's text || non-string overload
    // to render version numbers, UUID authors and native subject_kind labels.


    static final class NothingToName implements ContentCheck {

        @Override
        EntryKind kind() {
            EntryKind.WORKFLOW
        }

        @Override
        ContentProblemReport.Result problemsIn(GroupId group, EntryVersionId version) {
            new ContentProblemReport.Result([], 0)
        }
    }

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"


    static final String ANN = "00000002-0000-4000-8000-000000000801"

    static final String BEN = "00000002-0000-4000-8000-000000000802"


    static final String CAT = "00000002-0000-4000-8000-000000000803"

    static final UserId ANN_USER = new UserId("000801")

    static final UserId BEN_USER = new UserId("000802")

    static final UserId CAT_USER = new UserId("000803")

    static final UserId DAN_USER = new UserId("000804")

    static final String ENTRY = "00000006-0000-4000-8000-000000000801"

    static final String OTHER_ENTRY = "00000006-0000-4000-8000-000000000802"

    static final String SIBLING = "00000006-0000-4000-8000-000000000803"

    static final String HOLDER = "00000006-0000-4000-8000-000000000804"

    static final String FIELD = "0000000b-0000-4000-8000-000000000809"

    static final String VERSION = "00000007-0000-4000-8000-000000000801"

    static final String LATER = "00000007-0000-4000-8000-000000000802"

    static final String OTHER_VERSION = "00000007-0000-4000-8000-000000000803"

    static final String SIBLING_VERSION = "00000007-0000-4000-8000-000000000804"

    static final String HOLDING = "00000007-0000-4000-8000-000000000805"

    static final String NEWEST = "00000007-0000-4000-8000-000000000806"


    static final Map<String, EntryKind> PINNED_KIND =
            [step: EntryKind.QUESTION, search: EntryKind.QUESTION, route: EntryKind.WORKFLOW, term: EntryKind.REFERENCE_LIST]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    VersionChanges changes

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "versions_" + (++databasesMade))
        changes = new VersionChanges(store.session, store.transactions(), new GroupRoles(store.session),
                new ContentChecks([new QuestionContentCheck(store.session, LibraryStore.FILES, new SearchWays([])), new NothingToName(),
                                   new ReferenceListContentCheck(store.session)]))
        store.person(ANN, "000801")
        store.person(BEN, "000802")
        store.person(CAT, "000803")
        store.person("00000002-0000-4000-8000-000000000804", "000804")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, BEN, "overseer")
        store.member(GROUP, CAT, "operator")
        store.member(OTHER_GROUP, BEN, "owner")
        store.entry(ENTRY, GROUP, "question", "Triage")
    }

    def "a name changes and clears at every standing without changing the version or what pins it"() {
        given:
        standing(at)
        store.writer(VERSION, ANN)
        store.entry(HOLDER, GROUP, 'workflow', 'Handle a complaint')
        store.seeded(HOLDING, HOLDER, 1)
        store.content(HOLDING, 'workflow')
        store.step('00000008-0000-4000-8000-000000000899', HOLDING, 1, VERSION, 'question')
        if (stopped) store.stopped(ENTRY, FIRST_STEWARD)
        def unchanged = store.contents('entry_versions')
        // DB-SPECIFIC: PostgreSQL to_jsonb(), jsonb subtraction and ::text preserve every other column.
        def versionsBefore = store.texts("select (to_jsonb(v) - 'version_name')::text from entry_versions v order by entry_version_id")

        when:
        changes.name(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION),
                new VersionName('Research claim'), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid.
        store.texts('select version_name from entry_versions where entry_version_id = ?::uuid', VERSION) == ['Research claim']
        store.contents('entry_versions') == unchanged
        // DB-SPECIFIC: PostgreSQL to_jsonb(), jsonb subtraction and ::text.
        store.texts("select (to_jsonb(v) - 'version_name')::text from entry_versions v order by entry_version_id") == versionsBefore

        when:
        changes.name(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION),
                new VersionName('Stricter tone'), CAT_USER)
        changes.name(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), null, ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid.
        store.count('select count(*) from entry_versions where entry_version_id = ?::uuid and version_name is null', VERSION) == 1
        store.contents('entry_versions') == unchanged
        // DB-SPECIFIC: PostgreSQL to_jsonb(), jsonb subtraction and ::text.
        store.texts("select (to_jsonb(v) - 'version_name')::text from entry_versions v order by entry_version_id") == versionsBefore

        where:
        at           | stopped
        'draft'      | false
        'submitted'  | false
        'in service' | false
        'retired'    | false
        'draft'      | true
        'submitted'  | true
        'in service' | true
        'retired'    | true
    }

    def "naming a version outside the addressed group entry or kind preserves every table"() {
        given:
        standing('draft')
        store.entry(OTHER_ENTRY, OTHER_GROUP, 'question', 'Other triage')
        store.seeded(OTHER_VERSION, OTHER_ENTRY, 1)
        store.content(OTHER_VERSION, 'question', 'Other question.')
        def before = store.contents()

        when:
        changes.name(groupId(group), kind, entryId(entry), versionId(version), new VersionName('Research claim'), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code
        store.contents() == before

        where:
        group       | kind                   | entry       | version       | caller   || code
        GROUP       | EntryKind.QUESTION     | ENTRY       | OTHER_VERSION | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        GROUP       | EntryKind.QUESTION     | OTHER_ENTRY | OTHER_VERSION | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        GROUP       | EntryKind.WORKFLOW     | ENTRY       | VERSION       | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        OTHER_GROUP | EntryKind.QUESTION     | ENTRY       | VERSION       | BEN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        GROUP       | EntryKind.QUESTION     | ENTRY       | VERSION       | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
    }

    def "a new version starts without the previous version's name"() {
        given:
        seededWith(VERSION, 1, 'One.', false)
        changes.name(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION),
                new VersionName('Research claim'), ANN_USER)

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), CAT_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid.
        store.count('select count(*) from entry_versions where entry_version_id = ?::uuid and version_name is null', started.value()) == 1
        // DB-SPECIFIC: PostgreSQL ::uuid.
        store.texts('select version_name from entry_versions where entry_version_id = ?::uuid', VERSION) == ['Research claim']
    }

    def "naming waits for the group lock and then refuses an author whose roles were removed"() {
        given:
        standing('draft')
        def membership = store.takingRoles(GROUP, ANN)

        when:
        def acting = attempting(racing) {
            changes.name(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION),
                    new VersionName('Research claim'), ANN_USER)
        }
        store.untilWaiting(1)
        membership.commit()
        membership.close()
        def before = store.contents()
        def refused = acting.get(10, TimeUnit.SECONDS)

        then:
        refused instanceof ApiErrorException
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        store.contents() == before
    }

    /** Version numbers are not reused after a version is retired. */
    def "starts the entry's next version as the caller's draft, holding what the newest in service holds"() {
        given:
        seededWith(VERSION, 1, "One.", false)
        seededWith(LATER, 2, "Two.", false)
        seededWith(OTHER_VERSION, 3, "Three.", true)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params("## Demo Sample served", LATER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params("## Demo Sample retired", OTHER_VERSION).update()

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), CAT_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("""
                select number || ' ' || created_by || ' ' || coalesce(approved_at::text, '-')
                  from entry_versions where entry_version_id = ?::uuid
                """, started.value()) == ["4 ${CAT} -" as String]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select instruction || ' ' || created_by from question_versions where entry_version_id = ?::uuid",
                started.value()) == ["Two. ${CAT}" as String]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select drawing from question_versions where entry_version_id = ?::uuid", started.value()) ==
                ["## Demo Sample served"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select drawing from question_versions where entry_version_id = ?::uuid", LATER) ==
                ["## Demo Sample served"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select drawing from question_versions where entry_version_id = ?::uuid", OTHER_VERSION) ==
                ["## Demo Sample retired"]

        and: "the ones in service untouched, and the starter held as no writer"
        store.count("select count(*) from entry_versions where approved_at is not null and retired_at is null") == 2
        store.count("select count(*) from entry_version_writers") == 0
    }

    def "a draft started where nothing is in service holds what the highest-numbered version holds"() {
        given:
        store.entry(HOLDER, GROUP, "question", "Holder")
        store.seeded(VERSION, HOLDER, 1, true)
        store.content(VERSION, "question", "Older.")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params("## Demo Sample older", VERSION).update()
        store.seeded(LATER, HOLDER, 2, true)
        store.content(LATER, "question", "Newer.")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params("## Demo Sample newest", LATER).update()

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(HOLDER), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select instruction from question_versions where entry_version_id = ?::uuid", started.value()) ==
                ["Newer."]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select drawing from question_versions where entry_version_id = ?::uuid", started.value()) ==
                ["## Demo Sample newest"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select drawing from question_versions where entry_version_id = ?::uuid", LATER) ==
                ["## Demo Sample newest"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select drawing from question_versions where entry_version_id = ?::uuid", VERSION) ==
                ["## Demo Sample older"]
    }


    def "a reference list draft started holds a copy of every term and the note of the version it starts from"() {
        given:
        store.entry(HOLDER, GROUP, "reference_list", "Categories")
        store.seeded(VERSION, HOLDER, 1)
        store.content(VERSION, "reference_list", "Pick the nearest.")
        ["Billing", "Delivery", "Returns"].eachWithIndex { term, index ->
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, ?, ?::uuid)
                    """).params(VERSION, index + 1, term, "About ${term}." as String, SEEDER).update()
        }

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.REFERENCE_LIST, entryId(HOLDER), CAT_USER)

        then:
        termsOf(started.value().toString()) == termsOf(VERSION)
        termsOf(VERSION) == ["1 Billing About Billing.", "2 Delivery About Delivery.", "3 Returns About Returns."]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select note || ' ' || created_by from reference_list_versions where entry_version_id = ?::uuid",
                started.value()) == ["Pick the nearest. ${CAT}" as String]

        and: "each copy written by the starter, and the version it starts from as it was"
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select distinct created_by::text from reference_list_terms where entry_version_id = ?::uuid",
                started.value()) == [CAT]
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select distinct created_by::text from reference_list_terms where entry_version_id = ?::uuid",
                VERSION) == [SEEDER]
    }

    def "a reference list draft started from a version holding no term and no note holds neither"() {
        given:
        store.entry(HOLDER, GROUP, "reference_list", "Categories")
        store.seeded(VERSION, HOLDER, 1)
        store.content(VERSION, "reference_list")

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.REFERENCE_LIST, entryId(HOLDER), CAT_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from reference_list_versions where entry_version_id = ?::uuid and note is null",
                started.value()) == 1
        store.count("select count(*) from reference_list_terms") == 0
    }


    def "a question draft started holds a copy of every field the version it starts from declares"() {
        given:
        store.entry(SIBLING, GROUP, "reference_list", "Categories")
        store.seeded(SIBLING_VERSION, SIBLING, 1)
        store.content(SIBLING_VERSION, "reference_list")
        seededWith(VERSION, 1, "Sort it.", false)
        declared(VERSION)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native field_kind enum literals.
        store.session.sql("update declaration_fields set text_lines = 'one' where entry_version_id = ?::uuid and kind = 'text'")
                .params(VERSION).update()
        // DB-SPECIFIC: PostgreSQL's :: casts name the stored identifier types. Native entry_kind, declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, parent_field_id, position,
                                                name, kind, file_types, file_maximum_bytes, must_be_given, created_by)
                values (?::uuid, 'question', 'takes', null, 2, 'upload', 'file',
                        array['.png', '.pdf'], 4096, false, ?::uuid),
                       (?::uuid, 'question', 'gives', '0000000b-0000-4000-8000-000000000803'::uuid,
                        3, 'attachment', 'file', array['.pdf'], 8192, false, ?::uuid)
                """).params(VERSION, SEEDER, VERSION, SEEDER).update()

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), CAT_USER)

        then:
        fieldsOf(started.value().toString()) == fieldsOf(VERSION)
        fieldsOf(VERSION).size() == 7
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native field_kind enum literals.
        store.texts("select distinct text_lines from declaration_fields where entry_version_id = ?::uuid and kind = 'text'",
                started.value()) == ["one"]

        and: "every field another holds held by the copy of it, and every copy written by the starter"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("""
                select count(*) from declaration_fields copy
                  join declaration_fields parent on parent.declaration_field_id = copy.parent_field_id
                 where copy.entry_version_id = ?::uuid and parent.entry_version_id = ?::uuid
                """, started.value(), started.value()) == 3
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select distinct created_by::text from declaration_fields where entry_version_id = ?::uuid",
                started.value()) == [CAT]

        and: "the version it starts from as it was"
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select distinct created_by::text from declaration_fields where entry_version_id = ?::uuid",
                VERSION) == [SEEDER]
    }


    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "a workflow draft started holds a copy of all the version it starts from holds, under keys of its own"() {
        given:
        def target = "00000006-0000-4000-8000-000000000805"
        def led = "00000007-0000-4000-8000-000000000807"
        store.entry(SIBLING, GROUP, "reference_list", "Categories")
        store.seeded(SIBLING_VERSION, SIBLING, 1)
        store.content(SIBLING_VERSION, "reference_list")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'Billing', 'A charge.', ?::uuid)
                """).params(SIBLING_VERSION, SEEDER).update()
        seededWith(VERSION, 1, "Sort it.", false)
        declared(VERSION)
        store.entry(target, GROUP, "workflow", "Escalate")
        store.seeded(led, target, 1)
        store.content(led, "workflow")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native entry_kind, declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, text_limit,
                                                must_be_given, created_by)
                values (?::uuid, 'workflow', 'takes', 1, 'complaint', 'text', 4000, true, ?::uuid)
                """).params(led, SEEDER).update()
        store.entry(HOLDER, GROUP, "workflow", "Handle")
        store.version(HOLDING, HOLDER, 1, ANN)
        store.content(HOLDING, "workflow")
        writtenWhole(HOLDING, led)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update workflow_steps set label = '\u78ba\u8a8d\u3059\u308b · ' || name where entry_version_id = ?::uuid")
                .param(HOLDING).update()
        // DB-SPECIFIC: PostgreSQL's :: casts name the stored identifier types. Native declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (workflow_step_id, side, parent_field_id, position, name,
                                                kind, file_types, file_maximum_bytes, must_be_given, created_by)
                select step.workflow_step_id, 'gives', parent.declaration_field_id, 2, 'attachment', 'file',
                       array['.pdf', '.png'], 16384, false, ?::uuid
                  from workflow_steps step
                  join declaration_fields parent on parent.workflow_step_id = step.workflow_step_id
                 where step.entry_version_id = ?::uuid and step.name = 'route' and parent.name = 'outcome'
                """).params(SEEDER, HOLDING).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native field_kind enum literals.
        store.session.sql("""
                update declaration_fields set text_lines = 'one' where kind = 'text' and
                (entry_version_id = ?::uuid or workflow_step_id in
                    (select workflow_step_id from workflow_steps where entry_version_id = ?::uuid))
                """).params(HOLDING, HOLDING).update()
        store.approved(HOLDING, BEN)

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), CAT_USER)

        then:
        def copy = StoredWorkflow.read(store.session, started).get()
        def source = StoredWorkflow.read(store.session, versionId(HOLDING)).get()
        shapeOf(copy) == shapeOf(source)
        shapeOf(copy).steps.size() == 2
        copy.steps()*.label()*.value() == ["\u78ba\u8a8d\u3059\u308b · classify", "\u78ba\u8a8d\u3059\u308b · route"]
        source.steps()*.label()*.value() == ["\u78ba\u8a8d\u3059\u308b · classify", "\u78ba\u8a8d\u3059\u308b · route"]
        store.texts("select label from workflow_steps where entry_version_id = ?::uuid order by position", started.value()) ==
                ["\u78ba\u8a8d\u3059\u308b · classify", "\u78ba\u8a8d\u3059\u308b · route"]
        store.texts("select label from workflow_steps where entry_version_id = ?::uuid order by position", HOLDING) ==
                ["\u78ba\u8a8d\u3059\u308b · classify", "\u78ba\u8a8d\u3059\u308b · route"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native field_kind enum literals.
        store.texts("""
                select distinct text_lines from declaration_fields where kind = 'text' and
                (entry_version_id = ?::uuid or workflow_step_id in
                    (select workflow_step_id from workflow_steps where entry_version_id = ?::uuid))
                """, started.value(), started.value()) == ["one"]

        and: "under keys of its own, each row written by the one who started the draft"
        copy.steps()*.id().disjoint(source.steps()*.id())
        (copy.steps()[1].runs() as StoredWorkflow.Runs.Route).cases()*.id()
                .disjoint((source.steps()[1].runs() as StoredWorkflow.Runs.Route).cases()*.id())
        (copy.steps()[1].runs() as StoredWorkflow.Runs.Route).gives().keys()*.id()
                .disjoint((source.steps()[1].runs() as StoredWorkflow.Runs.Route).gives().keys()*.id())
        ["workflow_steps", "route_cases", "bindings"].every { table ->
            // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
            store.texts("select distinct created_by::text from ${table} where entry_version_id = ?::uuid" as String,
                    started.value()) == [CAT]
        }
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("""
                select distinct field.created_by::text from declaration_fields field
                  join workflow_steps step on step.workflow_step_id = field.workflow_step_id
                 where step.entry_version_id = ?::uuid""", started.value()) == [CAT]

        and: "the version it starts from as it was"
        StoredWorkflow.read(store.session, versionId(HOLDING)).get() == source
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select distinct created_by::text from workflow_steps where entry_version_id = ?::uuid", HOLDING) == [ANN]

        when: "the new draft's different display names are written through the flow"
        def roles = new GroupRoles(store.session)
        def released = new ReleasedCodeSteps(LibraryStore.CODE_STEPS)
        def drafts = new WorkflowDrafts(store.session, new Drafts(store.session, store.transactions(), roles),
                new Workflows(store.session, roles, LibraryStore.MODELS, released, LibraryStore.FILES, new SearchWays([]),
                        store.transactionManager()), released, new SearchWays([]))
        def route = copy.steps()[1].runs() as StoredWorkflow.Runs.Route
        def routeGives = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [
                new Field(new FieldName("outcome"), null, null, new FieldShape.Nested([
                        new Field(new FieldName("note"), null, null, new FieldShape.Text(50, TextLines.ONE),
                                new HowMany.One(), new Demand.Given(false)),
                        new Field(new FieldName("attachment"), null, null,
                                new FieldShape.File([new FileExtension(".pdf"), new FileExtension(".png")], 16384L),
                                new HowMany.One(), new Demand.Given(false))]), new HowMany.One(), new Demand.Given(true))])
        def answered = drafts.flow(groupId(GROUP), entryId(HOLDER), started, CAT_USER, new FlowBody.Sent(copy.revision(), [
                new FlowBody.SentStep(copy.steps()[0].id(), new StepId("classify"), new StepLabel("\u5225\u306e\u8868\u793a · classify"),
                        new FlowBody.SentRuns.Pinned(EntryKind.QUESTION, versionId(VERSION)),
                        new Producer.Model(new ModelChoice(new ModelName("general"), new ModelMode("research")), true), 2,
                        new ModelChoice(new ModelName("small"), null),
                        [new FlowBody.SentBinding(Pointer.parse("complaint"),
                                new FlowBody.SentSource.Input(Pointer.parse("complaint")))]),
                new FlowBody.SentStep(copy.steps()[1].id(), new StepId("route"), new StepLabel("\u5225\u306e\u8868\u793a · route"),
                        new FlowBody.SentRuns.Route(new FlowBody.SentSource.Step(0, Pointer.parse("category")),
                                new DeclarationBody.Sent(routeGives, [route.gives().keys()[0].id(),
                                        route.gives().keys()[0].fields()[0].id(), route.gives().keys()[0].fields()[1].id()]),
                                [new FlowBody.SentCase(route.cases()[0].id(), "Billing", versionId(led),
                                        [new FlowBody.SentBinding(Pointer.parse("complaint"),
                                                new FlowBody.SentSource.Step(0, Pointer.parse("details.product")))]),
                                 new FlowBody.SentCase(route.cases()[1].id(), null, versionId(led),
                                         [new FlowBody.SentBinding(Pointer.parse("complaint"),
                                                 new FlowBody.SentSource.Written('"Unknown"'))])]), null, null, null, [])],
                [new FlowBody.SentBinding(Pointer.parse("product"),
                        new FlowBody.SentSource.Step(0, Pointer.parse("details.product")))]))

        then: "each version reads its own labels and machine names"
        def revised = StoredWorkflow.read(store.session, started).get()
        revised.steps()*.label()*.value() == ["\u5225\u306e\u8868\u793a · classify", "\u5225\u306e\u8868\u793a · route"]
        revised.steps()*.name()*.value() == ["classify", "route"]
        revised == answered.stored()
        revised.revision() == copy.revision() + 1
        revised.steps()*.id().disjoint(copy.steps()*.id())
        revised.steps()[0].runs() == new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, versionId(VERSION))
        def revisedRoute = revised.steps()[1].runs() as StoredWorkflow.Runs.Route
        revisedRoute.discriminator().target() == null
        revisedRoute.discriminator().source() == new BindingSource.StepOutput(revised.steps()[0].id(), Pointer.parse("category"))
        revisedRoute.gives().declaration() == routeGives
        revisedRoute.cases()*.id().disjoint(route.cases()*.id())
        revisedRoute.cases()*.term() == ["Billing", null]
        revisedRoute.cases()*.target() == [versionId(led), versionId(led)]
        revisedRoute.cases()*.bindings()*.size() == [1, 1]
        revisedRoute.cases()*.bindings()*.first()*.target() == [Pointer.parse("complaint"), Pointer.parse("complaint")]
        revisedRoute.cases()*.bindings()*.first()*.source() == [
                new BindingSource.StepOutput(revised.steps()[0].id(), Pointer.parse("details.product")),
                new BindingSource.Written(new JsonValue.JsonString("Unknown"))]
        revised.steps()*.producer() == copy.steps()*.producer()
        revised.steps()*.tries() == copy.steps()*.tries()
        revised.steps()*.reviewer() == copy.steps()*.reviewer()
        revised.steps()*.bindings()*.size() == [1, 0]
        revised.steps()[0].bindings()[0].target() == Pointer.parse("complaint")
        revised.steps()[0].bindings()[0].source() == new BindingSource.WorkflowInput(Pointer.parse("complaint"))
        revised.outputs().size() == 1
        revised.outputs()[0].target() == Pointer.parse("product")
        revised.outputs()[0].source() == new BindingSource.StepOutput(revised.steps()[0].id(), Pointer.parse("details.product"))
        revised.takes() == copy.takes()
        revised.gives() == copy.gives()
        [revised.ceiling(), revised.keepsOwnCeiling(), revised.raiseNeedsApproval(), revised.mayBeHelped(), revised.helper()] ==
                [new Ceiling(1000), true, true, true, new ModelChoice(new ModelName("general"), new ModelMode("research"))]
        StoredWorkflow.read(store.session, versionId(HOLDING)).get() == source
        StoredWorkflow.read(store.session, versionId(HOLDING)).get().steps()*.label()*.value() ==
                ["\u78ba\u8a8d\u3059\u308b · classify", "\u78ba\u8a8d\u3059\u308b · route"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select name || ':' || label from workflow_steps where entry_version_id = ?::uuid order by position", started.value()) ==
                ["classify:\u5225\u306e\u8868\u793a · classify", "route:\u5225\u306e\u8868\u793a · route"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select name || ':' || label from workflow_steps where entry_version_id = ?::uuid order by position", HOLDING) ==
                ["classify:\u78ba\u8a8d\u3059\u308b · classify", "route:\u78ba\u8a8d\u3059\u308b · route"]
    }


    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts. Native entry_kind, declaration_side, field_kind and field_standing enum literals.
    def "a workflow draft started preserves a searching step's way, standings and question pin, copying bindings under new keys"() {
        given:
        store.entry(HOLDER, GROUP, "workflow", "Handle")
        store.version(HOLDING, HOLDER, 1, ANN)
        store.content(HOLDING, "workflow")
        def roles = new GroupRoles(store.session)
        def released = new ReleasedCodeSteps(LibraryStore.CODE_STEPS)
        def searchEntry = "00000006-0000-4000-8000-000000000811"
        def searchVersion = "00000007-0000-4000-8000-000000000811"
        def way = new SearchWay("web_search", new ModelToolName("web_search"), 200, 4000, 2000, 1000)
        def ways = new SearchWays([way])
        store.entry(searchEntry, GROUP, "question", "Find context")
        store.seeded(searchVersion, searchEntry, 1)
        store.content(searchVersion, "question", "Find sources about the complaint and its summary.")
        store.session.sql("update question_versions set search_way = 'web_search' where entry_version_id = ?::uuid")
                .param(searchVersion).update()
        ["complaint", "summary"].eachWithIndex { name, index ->
            store.session.sql("""
                    insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind,
                                                    text_limit, must_be_given, created_by)
                    values (?::uuid, 'question', 'takes', ?, ?, 'text', 4000, false, ?::uuid)
                    """).params(searchVersion, index + 1, name, SEEDER).update()
        }
        way.outputs().eachWithIndex { output, index ->
            store.session.sql("""
                    insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind,
                                                    text_limit, must_be_given, standing, created_by)
                    values (?::uuid, 'question', 'gives', ?, ?, 'text', ?, ?, 'never', ?::uuid)
                    """).params(searchVersion, index + 1, output.name().value(),
                    (output.shape() as FieldShape.Text).longest(), output.demand().mustBe(), SEEDER).update()
        }
        def drafts = new WorkflowDrafts(store.session, new Drafts(store.session, store.transactions(), roles),
                new Workflows(store.session, roles, LibraryStore.MODELS, released, LibraryStore.FILES, ways,
                        store.transactionManager()), released, ways)
        drafts.flow(groupId(GROUP), entryId(HOLDER), versionId(HOLDING), ANN_USER, new FlowBody.Sent(1, [
                new FlowBody.SentStep(null, new StepId("summarise"), null, new FlowBody.SentRuns.Unchosen(), null, null, null,
                        []),
                new FlowBody.SentStep(null, new StepId("find_context"), null, new FlowBody.SentRuns.Search("web_search", versionId(searchVersion),
                        [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.NEVER,
                         (SearchWay.REFERENCES): FieldStanding.NEVER, (SearchWay.OTHER): FieldStanding.ALWAYS]),
                        new Producer.Person(), 1, null, [
                                new FlowBody.SentBinding(Pointer.parse("complaint"), new FlowBody.SentSource.Input(Pointer.parse("complaint"))),
                                new FlowBody.SentBinding(Pointer.parse("summary"), new FlowBody.SentSource.Step(0, Pointer.parse("summary")))])], []))
        store.approved(HOLDING, BEN)
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        def bindingKeys = "select binding_id::text from bindings where entry_version_id = ?::uuid order by target_path"
        def source = StoredWorkflow.read(store.session, versionId(HOLDING)).get()
        def sourceKeys = store.texts(bindingKeys, HOLDING)

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), CAT_USER)

        then:
        def copy = StoredWorkflow.read(store.session, started).get()
        def copiedKeys = store.texts(bindingKeys, started.value())
        copy.steps()[1].runs() == new StoredWorkflow.Runs.Search("web_search", versionId(searchVersion),
                [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.NEVER,
                 (SearchWay.REFERENCES): FieldStanding.NEVER, (SearchWay.OTHER): FieldStanding.ALWAYS])

        and: "every binding under a key of its own, written by the one who started the draft"
        copiedKeys.size() == 2
        copiedKeys.disjoint(sourceKeys)
        copy.steps()[0].id() != source.steps()[0].id()
        copy.steps()[1].bindings()*.source() as Set == [
                new BindingSource.WorkflowInput(Pointer.parse("complaint")),
                new BindingSource.StepOutput(copy.steps()[0].id(), Pointer.parse("summary"))] as Set
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select distinct created_by::text from bindings where entry_version_id = ?::uuid",
                started.value()) == [CAT]

        and: "the version it starts from as it was"
        StoredWorkflow.read(store.session, versionId(HOLDING)).get() == source
        store.texts(bindingKeys, HOLDING) == sourceKeys
    }

    /** Copies must retain stored content but regenerate keys, generated/default columns, creation time and edit metadata. */
    def "a workflow draft started writes every column of its steps and bindings but those drawn anew"() {
        when:
        // DB-SPECIFIC: PostgreSQL :: casts to text and current_schema() parentheses.
        def columns = store.texts("""
                select column_name::text from information_schema.columns
                 where table_schema = current_schema() and table_name = ?""", table) as Set
        def written = ((StartedContent.WORKFLOW_STEPS_COPIED =~ /insert into ${table} \(([^)]*)\)/)[0][1] as String)
                .split(",")*.trim() as Set

        then:
        !columns.isEmpty()
        !written.isEmpty()
        written.disjoint(drawnAnew)
        columns == written + drawnAnew

        where:
        table                   || drawnAnew
        "workflow_steps"        || ["reviewed_by_model", "created_at", "updated_at", "updated_by", "updated_by_kind"] as Set
        "bindings"              || ["binding_id", "discriminates", "reads_a_step", "reads_a_constant", "created_at", "updated_at", "updated_by", "updated_by_kind"] as Set
    }

    def "refuses to start a draft while the entry has one that is a draft or submitted, writing nothing"() {
        given:
        store.version(VERSION, ENTRY, 1, ANN)
        if (submitted) {
            store.submitted(VERSION, ANN)
        }
        def before = store.contents()

        when:
        changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), BEN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.DRAFT_ALREADY_STARTED
        refused.message == "This entry already has a version that is a draft or submitted."
        store.contents() == before

        where:
        submitted << [false, true]
    }

    def "refuses to start a draft of no entry of the kind in the group, or for somebody in no role there"() {
        given:
        store.entry(OTHER_ENTRY, OTHER_GROUP, "question", "Escalate")
        def before = store.contents()

        when:
        changes.startDraft(groupId(GROUP), kind, entryId(named), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == refusal
        store.contents() == before

        where:
        kind               | named                                  | caller   || refusal
        EntryKind.QUESTION | OTHER_ENTRY                            | BEN_USER || RefusalCode.ENTRY_NOT_IN_VIEW
        EntryKind.WORKFLOW | ENTRY                                  | ANN_USER || RefusalCode.ENTRY_NOT_IN_VIEW
        EntryKind.QUESTION | "00000009-0000-4000-8000-000000000009" | ANN_USER || RefusalCode.ENTRY_NOT_IN_VIEW
        EntryKind.QUESTION | ENTRY                                  | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
    }


    def "a start racing another is decided by the store, refused alike whichever constraint decides it"() {
        given:
        seededWith(VERSION, 1, "One.", false)
        store.repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL native entry_kind enum literals.
        def other = store.holding("insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_by)" +
                " values ('${LATER}', '${ENTRY}', 'question', ${number}, '${SEEDER}')")

        when:
        def starting = attempting(racing) {
            changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), ANN_USER)
        }
        store.untilWaiting(1)
        otherLands ? other.commit() : other.rollback()
        other.close()
        def outcome = starting.get(10, TimeUnit.SECONDS)

        then:
        outcome.getClass() == (otherLands ? ApiErrorException : EntryVersionId)
        !otherLands || outcome.errorCode() == RefusalCode.DRAFT_ALREADY_STARTED
        store.count("select count(*) from entry_versions where approved_at is null") == 1
        store.count("select count(*) from question_versions") == (otherLands ? 1 : 2)

        where:
        number | otherLands
        2      | true
        7      | true
        2      | false
    }

    /** Starting a draft must wait for approval to commit, then copy the newly approved version. */
    def "a draft started while a version is being approved holds what that version holds once it is in service"() {
        given:
        seededWith(VERSION, 1, "One.", false)
        store.version(LATER, ENTRY, 2, ANN)
        store.content(LATER, "question", "Two.")
        store.submitted(LATER, ANN)
        store.repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL now().
        def approving = store.holding("update entry_versions set approved_at = now(), approved_by = '${BEN}'" +
                " where entry_version_id = '${LATER}'")

        when:
        def starting = attempting(racing) {
            changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), CAT_USER)
        }
        store.untilWaiting(1)
        approving.commit()
        approving.close()
        def started = starting.get(10, TimeUnit.SECONDS)

        then:
        started instanceof EntryVersionId
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select instruction from question_versions where entry_version_id = ?::uuid", started.value()) ==
                ["Two."]
    }

    def "submits a draft as the caller's act, which makes nobody a writer of it, stopped or not"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.wholeQuestion(VERSION)
        if (stopped) {
            store.stopped(ENTRY, FIRST_STEWARD)
        }

        when:
        changes.submit(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), CAT_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("""
                select created_by || ' ' || created_by_kind || ' ' || coalesce(withdrawn_at::text, '-')
                  from entry_version_submissions where entry_version_id = ?::uuid
                """, VERSION) == ["${CAT} person -" as String]
        store.count("select count(*) from entry_version_writers") == 0
        store.count("select count(*) from entry_versions where approved_at is not null") == 0

        where:
        stopped << [false, true]
    }


    def "submitting a question whose stored content does not hold is refused, naming every place, changing nothing"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.content(VERSION, "question")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native entry_kind, declaration_side and field_kind enum literals.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position,
                                                name, kind, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', 'takes', 1, 'complaint', 'text', true, ?::uuid)
                """).params(FIELD, VERSION, SEEDER).update()
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), CAT_USER)

        then:
        def refused = thrown(ContentProblemsRefusal)
        refused.errorCode() == RefusalCode.VERSION_CONTENT_DOES_NOT_HOLD
        refused.problems() == [
                new ContentProblem(
                        ContentProblemCode.INSTRUCTION_MISSING, new ContentPlace.Whole(ContentPart.INSTRUCTION), null, null),
                new ContentProblem(ContentProblemCode.LONGEST_MISSING,
                        new ContentPlace.AtField(ContentPart.TAKES, UUID.fromString(FIELD)), null, null),
                new ContentProblem(ContentProblemCode.NOTHING_GIVEN_BACK, new ContentPlace.Whole(ContentPart.GIVES), null, null)]
        refused.pins() == []
        store.contents() == before
    }


    def "a submission refused for its content names every pin retired since beside every place"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.content(VERSION, "question")
        store.entry(SIBLING, GROUP, "reference_list", "Categories")
        store.seeded(SIBLING_VERSION, SIBLING, 1, true)
        store.termField(FIELD, VERSION, "question", SIBLING_VERSION)
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), CAT_USER)

        then:
        def refused = thrown(ContentProblemsRefusal)
        refused.problems()*.code() == [ContentProblemCode.INSTRUCTION_MISSING, ContentProblemCode.STANDING_MISSING]
        refused.pins()*.pinned()*.version() == [versionId(SIBLING_VERSION)]
        refused.pins()*.newestInService() == [null]
        store.contents() == before
    }

    /** Content must be read again after acquiring the version lock. */
    def "a submission waiting on a write that makes the question whole reads it whole, and goes ahead"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.content(VERSION, "question")
        def writing = store.holding(
                // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
                "select 1 from entry_versions where entry_version_id = '${VERSION}' for no key update" as String,
                "update question_versions set instruction = 'Say what it is about.' where entry_version_id = '${VERSION}'" as String,
                // DB-SPECIFIC: PostgreSQL native entry_kind, declaration_side, field_kind and field_standing enum literals.
                """insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, text_limit,
                                                   must_be_given, standing, created_by)
                   values ('${VERSION}', 'question', 'gives', 1, 'summary', 'text', 1000, true, 'always',
                           '${SEEDER}')""" as String)

        when:
        def submitting = attempting(racing) {
            changes.submit(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), CAT_USER)
        }
        store.untilWaiting(1)
        def submittedWhileWritten = store.count("select count(*) from entry_version_submissions")
        writing.commit()
        writing.close()

        then:
        submitting.get(10, TimeUnit.SECONDS) == null
        submittedWhileWritten == 0
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [CAT]
    }

    def "withdraws a submitted version as the caller's act, which makes nobody a writer of it"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.submitted(VERSION, BEN)

        when:
        changes.withdraw(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), CAT_USER)

        then:
        store.texts("select created_by || ' ' || withdrawn_by from entry_version_submissions") ==
                ["${BEN} ${CAT}" as String]
        store.count("select count(*) from entry_version_writers") == 0
    }


    def "a version whose submission was withdrawn may be submitted anew"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.wholeQuestion(VERSION)
        store.submitted(VERSION, BEN)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
        store.session.sql("update entry_version_submissions set withdrawn_at = now(), withdrawn_by = ?::uuid")
                .param(BEN).update()

        when:
        changes.submit(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), CAT_USER)

        then:
        store.count("select count(*) from entry_version_submissions") == 2
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions where withdrawn_at is null") == [CAT]
    }

    /** Seeding submits only what it started, and a person may take that submission back like any other. */
    def "withdraws a version seeding started and submitted"() {
        given:
        store.version(VERSION, ENTRY, 1, SEEDER)
        store.submitted(VERSION, SEEDER, "seeder")

        when:
        changes.withdraw(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select withdrawn_by::text from entry_version_submissions") == [ANN]
    }

    /** Submitting is not writing: whoever only submitted it may approve it, and a stopped entry is approved alike. */
    def "approves a submitted version somebody else wrote as the caller's act, putting it into service"() {
        given:
        store.version(VERSION, ENTRY, 1, ANN)
        store.writer(VERSION, FIRST_STEWARD)
        store.submitted(VERSION, BEN)
        if (stopped) {
            store.stopped(ENTRY, FIRST_STEWARD)
        }

        when:
        changes.approve(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), BEN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("""
                select approved_by || ' ' || approved_by_kind || ' ' || coalesce(retired_at::text, '-')
                  from entry_versions where entry_version_id = ?::uuid
                """, VERSION) == ["${BEN} person -" as String]
        store.count("select count(*) from entry_version_submissions where withdrawn_at is null") == 1
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_version_writers where created_by = ?::uuid", BEN) == 0

        where:
        stopped << [false, true]
    }

    def "refuses the approval of a version by anybody who wrote it, whatever they hold, changing nothing"() {
        given:
        store.version(VERSION, ENTRY, 1, starter)
        if (writer != null) {
            store.writer(VERSION, writer)
        }
        store.submitted(VERSION, ANN)
        def before = store.contents()

        when:
        changes.approve(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), BEN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.APPROVER_WROTE_VERSION
        refused.message == "Nobody approves a version they wrote."
        store.contents() == before

        where:
        starter | writer
        BEN     | null
        ANN     | BEN
        SEEDER  | BEN
    }

    def "retires a version in service as the caller's act, the one a migration put there as well"() {
        given:
        if (seeded) {
            store.seeded(VERSION, ENTRY, 1)
        } else {
            store.version(VERSION, ENTRY, 1, ANN)
            store.submitted(VERSION, ANN)
            store.approved(VERSION, FIRST_STEWARD)
        }

        when:
        changes.retire(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), ANN_USER)

        then:
        store.texts("select retired_by || ' ' || retired_by_kind from entry_versions") == ["${ANN} person" as String]

        where:
        seeded << [true, false]
    }


    def "an act from a standing it does not move a version on from is refused, changing nothing"() {
        given:
        standing(at)
        def before = store.contents()

        when:
        changes."${act}"(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), BEN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_STANDING_REFUSES
        store.contents() == before

        where:
        act        | at
        "submit"   | "submitted"
        "submit"   | "in service"
        "submit"   | "retired"
        "withdraw" | "draft"
        "withdraw" | "in service"
        "withdraw" | "retired"
        "approve"  | "draft"
        "approve"  | "in service"
        "approve"  | "retired"
        "retire"   | "draft"
        "retire"   | "submitted"
        "retire"   | "retired"
    }


    def "an act on no version of the entry addressed is refused as no version, changing nothing"() {
        given:
        store.entry(OTHER_ENTRY, OTHER_GROUP, "question", "Escalate")
        store.version(OTHER_VERSION, OTHER_ENTRY, 1, ANN)
        store.entry(SIBLING, GROUP, "question", "Classify")
        store.version(SIBLING_VERSION, SIBLING, 1, ANN)
        store.version(VERSION, ENTRY, 1, ANN)
        def before = store.contents()

        when:
        changes."${act}"(groupId(GROUP), kind, entryId(ENTRY), versionId(named), BEN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_IN_VIEW
        refused.message == "That version is not in this group's library."
        store.contents() == before

        where:
        [act, kind, named] << [["submit", "withdraw", "approve", "retire"], [EntryKind.QUESTION],
                               [OTHER_VERSION, SIBLING_VERSION, "00000009-0000-4000-8000-000000000009"]].combinations() +
                [["submit", EntryKind.WORKFLOW, VERSION]]
    }

    def "submitting a foreign or missing version reveals no content and changes nothing"() {
        given:
        store.entry(OTHER_ENTRY, OTHER_GROUP, "question", "Escalate")
        store.version(OTHER_VERSION, OTHER_ENTRY, 1, ANN)
        store.content(OTHER_VERSION, "question")
        store.version(VERSION, ENTRY, 1, ANN)
        def before = store.contents()

        when:
        changes.submit(groupId(GROUP), EntryKind.QUESTION, entryId(entry), versionId(named), BEN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_IN_VIEW
        refused.message == "That version is not in this group's library."
        store.contents() == before

        where:
        entry       | named
        OTHER_ENTRY | OTHER_VERSION
        ENTRY       | "00000007-0000-4000-8000-000000000e09"
    }

    def "an act the caller's roles in the group do not reach is refused, and one by somebody in none as no group"() {
        given:
        standing(at)
        def before = store.contents()

        when:
        changes."${act}"(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == refusal
        store.contents() == before

        where:
        act        | at           | caller   || refusal
        "approve"  | "submitted"  | CAT_USER || RefusalCode.ACT_NOT_PERMITTED
        "retire"   | "in service" | CAT_USER || RefusalCode.ACT_NOT_PERMITTED
        "submit"   | "draft"      | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
        "withdraw" | "submitted"  | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
    }

    def "a version holding a submission by seeding that seeding did not start fails the act as a store gone wrong"() {
        given:
        store.version(VERSION, ENTRY, 1, ANN)
        store.submitted(VERSION, SEEDER, "seeder")
        def before = store.contents()

        when:
        changes.approve(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), BEN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version ${VERSION} holds a submission by seeding that seeding did not start" as String
        store.contents() == before
    }


    def "submitting or approving a version pinning one retired since is refused, naming the pin and what replaces it"() {
        given:
        def (String pinnedEntry, String pinned) = pinning(via)
        store.retired(pinned, FIRST_STEWARD)
        if (replaced) {
            store.seeded(NEWEST, pinnedEntry, 2)
        }
        if (act == "approve") {
            store.submitted(HOLDING, ANN)
        }
        def before = store.contents()

        when:
        changes."${act}"(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)

        then:
        def refused = thrown(RetiredPinsRefusal)
        refused.errorCode() == RefusalCode.VERSION_PINS_RETIRED
        refused.message == "This version pins a version retired since."
        refused.pins() == [new RetiredPinsRefusal.RetiredPin(entryId(pinnedEntry), PINNED_KIND[via],
                new EntryName(via == "step" || via == "search" ? "Triage" : "Escalate"),
                new RetiredPinsRefusal.NumberedVersion(versionId(pinned), 1, null),
                replaced ? new RetiredPinsRefusal.NumberedVersion(versionId(NEWEST), 2, null) : null)]
        store.contents() == before

        where:
        [act, via, replaced] << [["submit", "approve"], ["step", "search", "route", "term"], [true, false]].combinations()
    }


    def "offers the newest of several versions in service in place of a pin retired since"() {
        given:
        def (String pinnedEntry, String pinned) = pinning(via)
        store.retired(pinned, FIRST_STEWARD)
        store.seeded(NEWEST, pinnedEntry, 2)
        store.seeded(LATER, pinnedEntry, 3)
        store.seeded('00000007-0000-4000-8000-000000000901', pinnedEntry, 4, true)
        store.version('00000007-0000-4000-8000-000000000902', pinnedEntry, 5, ANN)
        if (act == 'approve') store.submitted(HOLDING, ANN)
        def before = store.contents()

        when:
        changes."${act}"(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)

        then:
        def refused = thrown(RetiredPinsRefusal)
        refused.errorCode() == RefusalCode.VERSION_PINS_RETIRED
        refused.pins() == [new RetiredPinsRefusal.RetiredPin(entryId(pinnedEntry), EntryKind.QUESTION,
                new EntryName('Triage'), new RetiredPinsRefusal.NumberedVersion(versionId(pinned), 1, null),
                new RetiredPinsRefusal.NumberedVersion(versionId(LATER), 3, null))]
        store.contents() == before

        where:
        act       | via
        'submit'  | 'step'
        'submit'  | 'search'
        'approve' | 'step'
        'approve' | 'search'
    }

    def "submitting or approving a version whose every pin is in service goes ahead"() {
        given:
        pinning(via)
        if (act == "approve") {
            store.submitted(HOLDING, ANN)
        }
        def before = store.contents('entry_versions', 'entry_version_submissions')

        when:
        changes."${act}"(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)

        then:
        store.count("select count(*) from entry_version_submissions where withdrawn_at is null") == 1
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_versions where approved_by = ?::uuid", BEN) == (act == "approve" ? 1 : 0)
        store.contents('entry_versions', 'entry_version_submissions') == before
        // DB-SPECIFIC: PostgreSQL uuid casts.
        store.texts('select created_by::text from entry_version_submissions where entry_version_id = ?::uuid', HOLDING) ==
                [act == 'approve' ? ANN : BEN]

        where:
        [act, via] << [["submit", "approve"], ["step", "search", "route", "term"]].combinations()
    }

    /** Pins must stay within the group; another group's version is neither exposed nor held. */
    def "a pin of another group's version is not reported, whatever its standing"() {
        given:
        store.entry(OTHER_ENTRY, OTHER_GROUP, "question", "Escalate")
        store.seeded(OTHER_VERSION, OTHER_ENTRY, 1, true)
        store.entry(HOLDER, GROUP, "workflow", "Handle")
        store.version(HOLDING, HOLDER, 1, ANN)
        store.content(HOLDING, "workflow")
        store.step("0000000c-0000-4000-8000-000000000801", HOLDING, 1, OTHER_VERSION, "question")

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_submissions") == [BEN]
    }


    def "a submission waiting on the retirement of a version it pins reads it retired, and is refused"() {
        given:
        def (String pinnedEntry, String pinned) = pinning("step")
        store.repeatableReadByDefault()
        // DB-SPECIFIC: PostgreSQL now(). Native subject_kind enum literals.
        def retiring = store.holding("update entry_versions set retired_at = now(), retired_by = '${FIRST_STEWARD}'," +
                " retired_by_kind = 'person' where entry_version_id = '${pinned}'")

        when:
        def submitting = attempting(racing) {
            changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)
        }
        store.untilWaiting(1)
        retiring.commit()
        retiring.close()
        def outcome = submitting.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof RetiredPinsRefusal
        store.count("select count(*) from entry_version_submissions") == 0
    }


    def "a #act waiting on a real search-question retirement reads the committed retirement and writes nothing"() {
        given:
        def (String pinnedEntry, String pinned) = pinning('search')
        store.seeded(NEWEST, pinnedEntry, 2)
        if (act == 'approve') store.submitted(HOLDING, ANN)
        def unchanged = store.contents('entry_versions')
        def retirementHeld = new CountDownLatch(1)
        def finishRetirement = new CountDownLatch(1)
        def retiringPid = new AtomicInteger()
        def retirement = attempting(racing) {
            store.transactions().executeWithoutResult {
                // DB-SPECIFIC: PostgreSQL backend PID.
                retiringPid.set(store.session.sql('select pg_backend_pid()').query(Integer).single())
                changes.retire(groupId(GROUP), EntryKind.QUESTION, entryId(pinnedEntry), versionId(pinned), ANN_USER)
                retirementHeld.countDown()
                assert finishRetirement.await(10, TimeUnit.SECONDS)
            }
        }
        assert retirementHeld.await(10, TimeUnit.SECONDS)

        when:
        def submitted = attempting(racing) {
            changes."${act}"(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)
        }
        store.untilWaiting(1)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity, current_database(), pg_blocking_pids() and ANY(array).
        def waiting = store.count('''
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                   and query like '%for share of target%' and ? = any(pg_blocking_pids(pid))
                ''', retiringPid.get())
        def stillPending = !submitted.done
        finishRetirement.countDown()
        def retired = retirement.get(10, TimeUnit.SECONDS)
        def refused = submitted.get(10, TimeUnit.SECONDS)

        then:
        waiting == 1
        stillPending
        retired == null
        refused instanceof RetiredPinsRefusal
        refused.errorCode() == RefusalCode.VERSION_PINS_RETIRED
        refused.pins() == [new RetiredPinsRefusal.RetiredPin(entryId(ENTRY), EntryKind.QUESTION,
                new EntryName('Triage'), new RetiredPinsRefusal.NumberedVersion(versionId(VERSION), 1, null),
                new RetiredPinsRefusal.NumberedVersion(versionId(NEWEST), 2, null))]
        store.contents('entry_versions') == unchanged
        // DB-SPECIFIC: PostgreSQL uuid casts.
        store.texts('select retired_by::text from entry_versions where entry_version_id = ?::uuid', VERSION) == [ANN]
        store.count('select count(*) from entry_versions where entry_version_id = ?::uuid and approved_by is not null', HOLDING) == 0

        cleanup:
        finishRetirement?.countDown()
        if (retirement != null) retirement.get(10, TimeUnit.SECONDS)
        if (submitted != null) submitted.get(10, TimeUnit.SECONDS)

        where:
        act << ['submit', 'approve']
    }

    def "a #act holds its real search pin share until commit against retirement and a target-only row-lock probe"() {
        given:
        pinning('search')
        if (act == 'approve') store.submitted(HOLDING, ANN)
        def unchanged = store.contents('entry_versions', 'entry_version_submissions')
        def pinsHeld = new CountDownLatch(1)
        def finishAct = new CountDownLatch(1)
        def gateHits = new AtomicInteger()
        def statements = new ArrayList<String>() {
            @Override
            boolean add(String sql) {
                if (sql.contains('as newest_in_service')) {
                    gateHits.incrementAndGet()
                    pinsHeld.countDown()
                    assert finishAct.await(10, TimeUnit.SECONDS)
                }
                super.add(sql)
            }
        }
        def source = new CountingDataSource(store.database, statements)
        def counted = JdbcClient.create(source)
        def change = new VersionChanges(counted, store.transactions(source), new GroupRoles(counted),
                new ContentChecks([new QuestionContentCheck(counted, LibraryStore.FILES, new SearchWays([])),
                                           new NothingToName(), new ReferenceListContentCheck(counted)]))
        def workers = Executors.newFixedThreadPool(3)
        def submitted = attempting(workers) {
            change."${act}"(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)
        }
        assert pinsHeld.await(10, TimeUnit.SECONDS)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity; current_database().
        def holderPid = store.session.sql('''
                select pid from pg_stat_activity where datname = current_database()
                  and state = 'idle in transaction' and query like '%for share of target%'
                ''').query(Integer).single()

        when:
        def probed = attempting(workers) {
            // DB-SPECIFIC: PostgreSQL UUID literal and FOR NO KEY UPDATE.
            def connection = store.holding("select entry_version_id from entry_versions where entry_version_id = '${VERSION}' for no key update")
            try {
                connection.rollback()
            } finally {
                connection.close()
            }
        }
        store.untilWaiting(1)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity, current_database(), pg_blocking_pids() and ANY(array).
        def targetProbe = store.count('''
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                   and query like '%entry_versions%' and query like '%for no key update%'
                   and ? = any(pg_blocking_pids(pid))
                ''', holderPid)
        def probePendingBeforeRetirement = !probed.done
        def retired = attempting(workers) {
            changes.retire(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), ANN_USER)
        }
        store.untilWaiting(2)
        // DB-SPECIFIC: PostgreSQL pg_stat_activity, current_database() and pg_blocking_pids().
        def retirementWaiting = store.count('''
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                   and query like '%entry_versions%' and query like '%for no key update of version%'
                   and cardinality(pg_blocking_pids(pid)) > 0
                ''')
        def retirementPending = !retired.done
        def probePending = !probed.done
        finishAct.countDown()
        def submittedOutcome = submitted.get(10, TimeUnit.SECONDS)
        def retirementOutcome = retired.get(10, TimeUnit.SECONDS)
        def probeOutcome = probed.get(10, TimeUnit.SECONDS)

        then:
        gateHits.get() == 1
        statements.any { it.contains('for share of target') }
        targetProbe == 1
        probePendingBeforeRetirement
        retirementWaiting == 1
        retirementPending
        probePending
        submittedOutcome == null
        retirementOutcome == null
        probeOutcome == null
        store.contents('entry_versions', 'entry_version_submissions') == unchanged
        // DB-SPECIFIC: PostgreSQL uuid casts.
        store.texts('select retired_by::text from entry_versions where entry_version_id = ?::uuid', VERSION) == [ANN]
        store.count('select count(*) from entry_version_submissions where entry_version_id = ?::uuid', HOLDING) == 1
        store.count('select count(*) from entry_versions where entry_version_id = ?::uuid and approved_by = ?::uuid', HOLDING, BEN) ==
                (act == 'approve' ? 1 : 0)

        cleanup:
        finishAct?.countDown()
        try {
            if (submitted != null) submitted.get(10, TimeUnit.SECONDS)
            if (retired != null) retired.get(10, TimeUnit.SECONDS)
            if (probed != null) probed.get(10, TimeUnit.SECONDS)
        } finally {
            workers?.shutdownNow()
            assert workers == null || workers.awaitTermination(5, TimeUnit.SECONDS)
        }

        where:
        act << ['submit', 'approve']
    }

    def "an approval waiting on a withdrawal reads the version withdrawn, and is refused"() {
        given:
        store.version(VERSION, ENTRY, 1, ANN)
        store.submitted(VERSION, ANN)
        store.repeatableReadByDefault()
        def withdrawing = store.holding(
                // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
                "select 1 from entry_versions where entry_version_id = '${VERSION}' for no key update",
                // DB-SPECIFIC: PostgreSQL now().
                "update entry_version_submissions set withdrawn_at = now(), withdrawn_by = '${ANN}'" +
                        " where entry_version_id = '${VERSION}'")

        when:
        def approving = attempting(racing) {
            changes.approve(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), BEN_USER)
        }
        store.untilWaiting(1)
        withdrawing.commit()
        withdrawing.close()
        def outcome = approving.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.VERSION_STANDING_REFUSES
        store.count("select count(*) from entry_versions where approved_at is not null") == 0
    }

    def "an approval waiting on its approver being recorded as a writer is refused for having written it"() {
        given:
        store.version(VERSION, ENTRY, 1, ANN)
        store.submitted(VERSION, ANN)
        store.repeatableReadByDefault()
        def writing = store.holding(
                // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
                "select 1 from entry_versions where entry_version_id = '${VERSION}' for no key update",
                "insert into entry_version_writers (entry_version_id, created_by) values ('${VERSION}', '${BEN}')")

        when:
        def approving = attempting(racing) {
            changes.approve(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), BEN_USER)
        }
        store.untilWaiting(1)
        writing.commit()
        writing.close()
        def outcome = approving.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.APPROVER_WROTE_VERSION
        store.count("select count(*) from entry_versions where approved_at is not null") == 0
    }

    /** A run starting on a version names it under a key share, which retiring's lock does not wait on. */
    def "retiring a version is not held up by a run starting on it"() {
        given:
        store.seeded(VERSION, ENTRY, 1)
        // DB-SPECIFIC: PostgreSQL SELECT FOR KEY SHARE.
        def starting = store.holding("select 1 from entry_versions where entry_version_id = '${VERSION}' for key share")

        when:
        def retiring = attempting(racing) {
            changes.retire(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), ANN_USER)
        }

        then:
        retiring.get(10, TimeUnit.SECONDS) == null
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_versions where retired_by = ?::uuid", ANN) == 1

        cleanup:
        starting?.rollback()
        starting?.close()
    }

    /** The roles are taken by a change holding the group; once it lands, the act reads them again and is refused. */
    def "an act whose caller loses their roles while it waits on the group is refused, changing nothing"() {
        given:
        standing(at)
        store.repeatableReadByDefault()
        def before = library()
        def taking = store.takingRoles(GROUP, BEN, left as String[])

        when:
        def acting = attempting(racing) {
            act == "startDraft"
                    ? changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), BEN_USER)
                    : changes."${act}"(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), BEN_USER)
        }
        store.untilWaiting(1)
        taking.commit()
        taking.close()
        def outcome = acting.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == refusal
        library() == before

        where:
        act          | at           | left         || refusal
        "startDraft" | "in service" | []           || RefusalCode.GROUP_NOT_IN_VIEW
        "submit"     | "draft"      | []           || RefusalCode.GROUP_NOT_IN_VIEW
        "withdraw"   | "submitted"  | []           || RefusalCode.GROUP_NOT_IN_VIEW
        "approve"    | "submitted"  | ["operator"] || RefusalCode.ACT_NOT_PERMITTED
        "retire"     | "in service" | ["operator"] || RefusalCode.ACT_NOT_PERMITTED
    }

    /** A transaction timestamp must not move a later version stamp backwards. */
    def "an act begun before what it answers was made is recorded no earlier than that"() {
        given:
        // DB-SPECIFIC: PostgreSQL SELECT FOR NO KEY UPDATE.
        def membership = store.holding("select 1 from groups where group_id = '${GROUP}' for no key update")

        when:
        def acting = attempting(racing) {
            changes."${act}"(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), BEN_USER)
        }
        store.untilWaiting(1)
        standing(at)
        membership.commit()
        membership.close()

        then:
        acting.get(10, TimeUnit.SECONDS) == null
        store.count(ordered) == 1

        where:
        act        | at           || ordered
        "withdraw" | "submitted"  || "select count(*) from entry_version_submissions where withdrawn_at >= created_at"
        "approve"  | "submitted"  || "select count(*) from entry_versions where approved_at >= created_at"
        "retire"   | "in service" || "select count(*) from entry_versions where retired_at >= approved_at"
    }

    private void standing(String at) {
        store.version(VERSION, ENTRY, 1, ANN)
        store.content(VERSION, "question", "Asked.")
        if (at != "draft") {
            store.submitted(VERSION, ANN)
        }
        if (at == "in service" || at == "retired") {
            store.approved(VERSION, FIRST_STEWARD)
        }
        if (at == "retired") {
            store.retired(VERSION, FIRST_STEWARD)
        }
    }

    private void seededWith(String version, int number, String instruction, boolean retired) {
        store.seeded(version, ENTRY, number, retired)
        store.content(version, "question", instruction)
    }


    private void declared(String version) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native entry_kind, declaration_side, field_kind and field_standing enum literals.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, parent_field_id,
                                                position, name, label, help, kind, holds_many, text_limit, many_limit,
                                                term_list_version_id, must_be_given, standing, standing_threshold,
                                                created_by)
                values ('0000000b-0000-4000-8000-000000000801', ?::uuid, 'question', 'takes', null, 1, 'complaint',
                        'The complaint', 'As written.', 'text', false, 4000, null, null, true, null, null, ?::uuid),
                       ('0000000b-0000-4000-8000-000000000802', ?::uuid, 'question', 'gives', null, 1, 'category',
                        null, null, 'term', false, null, null, ?::uuid, true, 'above_confidence', 80, ?::uuid),
                       ('0000000b-0000-4000-8000-000000000803', ?::uuid, 'question', 'gives', null, 2, 'details',
                        null, null, 'fields', false, null, null, null, false, 'never', null, ?::uuid),
                       ('0000000b-0000-4000-8000-000000000804', ?::uuid, 'question', 'gives',
                        '0000000b-0000-4000-8000-000000000803', 1, 'product', null, null, 'text', false, 128, null, null,
                        true, null, null, ?::uuid),
                       ('0000000b-0000-4000-8000-000000000805', ?::uuid, 'question', 'gives',
                        '0000000b-0000-4000-8000-000000000803', 2, 'order_reference', null, null, 'text', true, 64, 3,
                        null, false, null, null, ?::uuid)
                """).params(version, SEEDER, version, SIBLING_VERSION, SEEDER, version, SEEDER, version, SEEDER, version,
                SEEDER).update()
    }


    private void writtenWhole(String version, String led) {
        def roles = new GroupRoles(store.session)
        def released = new ReleasedCodeSteps(LibraryStore.CODE_STEPS)
        def drafts = new WorkflowDrafts(store.session, new Drafts(store.session, store.transactions(), roles),
                new Workflows(store.session, roles, LibraryStore.MODELS, released, LibraryStore.FILES, new SearchWays([]),
                        store.transactionManager()), released, new SearchWays([]))
        def addressed = [groupId(GROUP), entryId(HOLDER), versionId(version), ANN_USER]
        drafts.declare(*addressed, 1, new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), [
                new Field(new FieldName("complaint"), null, null, new FieldShape.Text(4000), new HowMany.One(),
                        new Demand.Given(true))]), [null])
        drafts.declare(*addressed, 2, new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [
                new Field(new FieldName("product"), null, null, new FieldShape.Text(128), new HowMany.One(),
                        new Demand.Given(true))]), [null])
        def routeGives = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [
                new Field(new FieldName("outcome"), null, null, new FieldShape.Nested([
                        new Field(new FieldName("note"), null, null, new FieldShape.Text(50), new HowMany.One(),
                                new Demand.Given(false))]), new HowMany.One(), new Demand.Given(true))])
        drafts.flow(*addressed, new FlowBody.Sent(3, [
                new FlowBody.SentStep(null, new StepId("classify"), null,
                        new FlowBody.SentRuns.Pinned(EntryKind.QUESTION, versionId(VERSION)),
                        new Producer.Model(new ModelChoice(new ModelName("general"), new ModelMode("research")), true), 2,
                        new ModelChoice(new ModelName("small"), null),
                        [new FlowBody.SentBinding(Pointer.parse("complaint"),
                                new FlowBody.SentSource.Input(Pointer.parse("complaint")))]),
                new FlowBody.SentStep(null, new StepId("route"), null, new FlowBody.SentRuns.Route(
                        new FlowBody.SentSource.Step(0, Pointer.parse("category")),
                        new DeclarationBody.Sent(routeGives, [null, null]),
                        [new FlowBody.SentCase(null, "Billing", versionId(led), [new FlowBody.SentBinding(
                                Pointer.parse("complaint"), new FlowBody.SentSource.Step(0, Pointer.parse("details.product")))]),
                         new FlowBody.SentCase(null, null, versionId(led), [new FlowBody.SentBinding(
                                 Pointer.parse("complaint"), new FlowBody.SentSource.Written('"Unknown"'))])]),
                        null, null, null, [])],
                [new FlowBody.SentBinding(Pointer.parse("product"),
                        new FlowBody.SentSource.Step(0, Pointer.parse("details.product")))]))
        drafts.ceiling(*addressed, 4, new Ceiling(1000), true, true)
        drafts.help(*addressed, 5, true, new ModelChoice(new ModelName("general"), new ModelMode("research")))
    }


    private static Map shapeOf(StoredWorkflow workflow) {
        def positions = workflow.positions()
        def bound = { Binding binding ->
            [binding.target()?.published(), binding.source() instanceof BindingSource.StepOutput
                    ? ["step", positions[(binding.source() as BindingSource.StepOutput).step()],
                       (binding.source() as BindingSource.StepOutput).pointer()]
                    : binding.source()]
        }
        [takes   : workflow.takes().declaration(),
         gives   : workflow.gives().declaration(),
         steps   : workflow.steps().collect { step ->
             def runs = step.runs() instanceof StoredWorkflow.Runs.Route
                     ? [(step.runs() as StoredWorkflow.Runs.Route).discriminator().with(bound),
                        (step.runs() as StoredWorkflow.Runs.Route).gives().declaration(),
                        (step.runs() as StoredWorkflow.Runs.Route).cases().collect { [it.term(), it.target(), it.bindings().collect(bound)] }]
                     : step.runs()
              [step.name(), step.label(), runs, step.producer(), step.tries(), step.reviewer(), step.bindings().collect(bound)]
         },
         outputs : workflow.outputs().collect(bound),
         settings: [workflow.ceiling(), workflow.keepsOwnCeiling(), workflow.raiseNeedsApproval(),
                    workflow.mayBeHelped(), workflow.helper()]]
    }


    private List<String> fieldsOf(String version) {
        // DB-SPECIFIC: PostgreSQL :: casts, concat_ws and array_to_string render the stored field facts.
        store.texts("""
                select concat_ws(' ', field.side, coalesce(parent.name, '-'), field.position, field.name,
                                 coalesce(field.label, '-'), coalesce(field.help, '-'), field.kind, field.holds_many,
                                 coalesce(field.text_limit::text, '-'), coalesce(field.many_limit::text, '-'),
                                 coalesce(field.term_list_version_id::text, '-'),
                                 coalesce(array_to_string(field.file_types, ','), '-'),
                                 coalesce(field.file_maximum_bytes::text, '-'),
                                 coalesce(field.must_be_given::text, '-'),
                                 coalesce(field.standing::text, '-'), coalesce(field.standing_threshold::text, '-'))
                  from declaration_fields field
                  left join declaration_fields parent on parent.declaration_field_id = field.parent_field_id
                 where field.entry_version_id = ?::uuid
                 order by 1
                """, version)
    }


    private List<String> termsOf(String version) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; concat_ws().
        store.texts("""
                select concat_ws(' ', position, term, meaning) from reference_list_terms
                 where entry_version_id = ?::uuid order by position
                """, version)
    }


    private List<String> pinning(String via) {
        store.entry(HOLDER, GROUP, "workflow", "Handle")
        store.version(HOLDING, HOLDER, 1, ANN)
        store.content(HOLDING, "workflow")
        if (via == "step" || via == "search") {
            store.seeded(VERSION, ENTRY, 1)
            store.step("0000000c-0000-4000-8000-000000000801", HOLDING, 1, VERSION, "question")
            if (via == 'search') {
                // DB-SPECIFIC: PostgreSQL uuid casts. Native step_kind and field_standing enum literals.
                store.session.sql('''
                        update workflow_steps set kind = 'search', search_way = 'web_search',
                               search_title_standing = 'always', search_body_standing = 'never',
                               search_references_standing = 'never', search_other_standing = 'never'
                         where workflow_step_id = ?::uuid
                        ''').params('0000000c-0000-4000-8000-000000000801').update()
            }
            return [ENTRY, VERSION]
        }
        store.entry(SIBLING, GROUP, via == "route" ? "workflow" : "reference_list", "Escalate")
        store.seeded(SIBLING_VERSION, SIBLING, 1)
        if (via == "route") {
            store.routed("0000000c-0000-4000-8000-000000000802", "0000000d-0000-4000-8000-000000000801", HOLDING, 1,
                    SIBLING_VERSION)
        } else {
            store.termField("0000000b-0000-4000-8000-000000000801", HOLDING, "workflow", SIBLING_VERSION)
        }
        [SIBLING, SIBLING_VERSION]
    }

    private List<String> library() {
        ["entries", "entry_versions", "entry_version_submissions", "question_versions"].collect { store.digestOf(it) }
    }
}
