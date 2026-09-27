package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Starting a draft and moving a version from standing to standing, on a real server running the real
 * baseline; where a change waits on a lock, only one reading after it sees what it waited on.
 *
 * <p>A workflow here stands for whatever pins another version, so what submitting holds its content to is
 * {@link WorkflowContentCheckIntegrationSpec}'s to show; its check names nothing here.
 */
class VersionChangesIntegrationSpec extends Specification {

    /** A workflow's content held to nothing, so each pin is all a workflow submitted here is refused for. */
    static final class NothingToName implements ContentCheck {

        @Override
        EntryKind kind() {
            EntryKind.WORKFLOW
        }

        @Override
        List<ContentProblem> problemsIn(GroupId group, EntryVersionId version) {
            []
        }
    }

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"

    /** Ann and Ben are overseers: each may write, approve and revoke an entry. */
    static final String ANN = "00000002-0000-4000-8000-000000000801"

    static final String BEN = "00000002-0000-4000-8000-000000000802"

    /** An operator: may write an entry, and neither approve nor revoke one. */
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

    /** The kind of entry each way of pinning names, as the arrangement below makes it. */
    static final Map<String, EntryKind> PINNED_KIND =
            [step: EntryKind.QUESTION, route: EntryKind.WORKFLOW, term: EntryKind.REFERENCE_LIST]

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    VersionChanges changes

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "versions_" + (++databasesMade))
        changes = new VersionChanges(store.session, store.transactions(), new GroupRoles(store.session),
                new ContentChecks(store.session, [new QuestionContentCheck(store.session), new NothingToName(),
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

    /**
     * Numbers are never reused, so a retired one still counts toward the next; what the draft holds is the
     * newest in service's, however high a retired one's number.
     */
    def "starts the entry's next version as the caller's draft, holding what the newest in service holds"() {
        given:
        seededWith(VERSION, 1, "One.", false)
        seededWith(LATER, 2, "Two.", false)
        seededWith(OTHER_VERSION, 3, "Three.", true)

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), CAT_USER)

        then:
        store.texts("""
                select number || ' ' || created_by || ' ' || coalesce(approved_at::text, '-')
                  from entry_versions where entry_version_id = ?::uuid
                """, started.value()) == ["4 ${CAT} -" as String]
        store.texts("select instruction || ' ' || created_by from question_versions where entry_version_id = ?::uuid",
                started.value()) == ["Two. ${CAT}" as String]

        and: "the ones in service untouched, and the starter held as no writer"
        store.count("select count(*) from entry_versions where approved_at is not null and retired_at is null") == 2
        store.count("select count(*) from entry_version_writers") == 0
    }

    def "a draft started where nothing is in service holds what the highest-numbered version holds"() {
        given:
        store.entry(HOLDER, GROUP, "question", "Holder")
        store.seeded(VERSION, HOLDER, 1, true)
        store.content(VERSION, "question", "Older.")
        store.seeded(LATER, HOLDER, 2, true)
        store.content(LATER, "question", "Newer.")

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(HOLDER), ANN_USER)

        then:
        store.texts("select instruction from question_versions where entry_version_id = ?::uuid", started.value()) ==
                ["Newer."]
    }

    /** Every term in its place, and the note, each written by the one who started it. */
    def "a reference list draft started holds a copy of every term and the note of the version it starts from"() {
        given:
        store.entry(HOLDER, GROUP, "reference_list", "Categories")
        store.seeded(VERSION, HOLDER, 1)
        store.content(VERSION, "reference_list", "Pick the nearest.")
        ["Billing", "Delivery", "Returns"].eachWithIndex { term, index ->
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
        store.texts("select note || ' ' || created_by from reference_list_versions where entry_version_id = ?::uuid",
                started.value()) == ["Pick the nearest. ${CAT}" as String]

        and: "each copy written by the starter, and the version it starts from as it was"
        store.texts("select distinct created_by::text from reference_list_terms where entry_version_id = ?::uuid",
                started.value()) == [CAT]
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
        store.count("select count(*) from reference_list_versions where entry_version_id = ?::uuid and note is null",
                started.value()) == 1
        store.count("select count(*) from reference_list_terms") == 0
    }

    /**
     * Every field of both halves, a field another holds under the copy of what held it, a list pinned as it
     * was pinned, each written by the one who started the draft.
     */
    def "a question draft started holds a copy of every field the version it starts from declares"() {
        given:
        store.entry(SIBLING, GROUP, "reference_list", "Categories")
        store.seeded(SIBLING_VERSION, SIBLING, 1)
        store.content(SIBLING_VERSION, "reference_list")
        seededWith(VERSION, 1, "Sort it.", false)
        declared(VERSION)

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), CAT_USER)

        then:
        fieldsOf(started.value().toString()) == fieldsOf(VERSION)
        fieldsOf(VERSION).size() == 5

        and: "every field another holds held by the copy of it, and every copy written by the starter"
        store.count("""
                select count(*) from declaration_fields copy
                  join declaration_fields parent on parent.declaration_field_id = copy.parent_field_id
                 where copy.entry_version_id = ?::uuid and parent.entry_version_id = ?::uuid
                """, started.value(), started.value()) == 2
        store.texts("select distinct created_by::text from declaration_fields where entry_version_id = ?::uuid",
                started.value()) == [CAT]

        and: "the version it starts from as it was"
        store.texts("select distinct created_by::text from declaration_fields where entry_version_id = ?::uuid",
                VERSION) == [SEEDER]
    }

    /**
     * Every part of a workflow version is carried whole: its halves, every step in its place, what a route chooses
     * by and between and what it gives back, what fills each input and output, its ceiling and its helper. What
     * points at a step or a case points at the copy of it, and nothing is shared with the version it came from.
     */
    def "a workflow draft started holds a copy of all the version it starts from holds, under keys of its own"() {
        given:
        def target = "00000006-0000-4000-8000-000000000805"
        def led = "00000007-0000-4000-8000-000000000807"
        store.entry(SIBLING, GROUP, "reference_list", "Categories")
        store.seeded(SIBLING_VERSION, SIBLING, 1)
        store.content(SIBLING_VERSION, "reference_list")
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'Billing', 'A charge.', ?::uuid)
                """).params(SIBLING_VERSION, SEEDER).update()
        seededWith(VERSION, 1, "Sort it.", false)
        declared(VERSION)
        store.entry(target, GROUP, "workflow", "Escalate")
        store.seeded(led, target, 1)
        store.content(led, "workflow")
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, text_limit,
                                                must_be_given, created_by)
                values (?::uuid, 'workflow', 'takes', 1, 'complaint', 'text', 4000, true, ?::uuid)
                """).params(led, SEEDER).update()
        store.entry(HOLDER, GROUP, "workflow", "Handle")
        store.version(HOLDING, HOLDER, 1, ANN)
        store.content(HOLDING, "workflow")
        writtenWhole(HOLDING, led)
        store.approved(HOLDING, BEN)

        when:
        def started = changes.startDraft(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), CAT_USER)

        then:
        def copy = StoredWorkflow.read(store.session, started).get()
        def source = StoredWorkflow.read(store.session, versionId(HOLDING)).get()
        shapeOf(copy) == shapeOf(source)
        shapeOf(copy).steps.size() == 2

        and: "under keys of its own, each row written by the one who started the draft"
        copy.steps()*.id().disjoint(source.steps()*.id())
        (copy.steps()[1].runs() as StoredWorkflow.Runs.Route).cases()*.id()
                .disjoint((source.steps()[1].runs() as StoredWorkflow.Runs.Route).cases()*.id())
        (copy.steps()[1].runs() as StoredWorkflow.Runs.Route).gives().keys()*.id()
                .disjoint((source.steps()[1].runs() as StoredWorkflow.Runs.Route).gives().keys()*.id())
        ["workflow_steps", "route_cases", "bindings"].every { table ->
            store.texts("select distinct created_by::text from ${table} where entry_version_id = ?::uuid" as String,
                    started.value()) == [CAT]
        }
        store.texts("""
                select distinct field.created_by::text from declaration_fields field
                  join workflow_steps step on step.workflow_step_id = field.workflow_step_id
                 where step.entry_version_id = ?::uuid""", started.value()) == [CAT]

        and: "the version it starts from as it was"
        StoredWorkflow.read(store.session, versionId(HOLDING)).get() == source
        store.texts("select distinct created_by::text from workflow_steps where entry_version_id = ?::uuid", HOLDING) == [ANN]
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

    /** Whichever of the two constraints the waiting start meets, it is one refusal. */
    def "a start racing another is decided by the store, refused alike whichever constraint decides it"() {
        given:
        seededWith(VERSION, 1, "One.", false)
        store.repeatableReadByDefault()
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

    /**
     * The approval of version 2 has not committed when the draft is started; the start waits on it, and what
     * the draft holds is read after, so it is version 2's and not version 1's.
     */
    def "a draft started while a version is being approved holds what that version holds once it is in service"() {
        given:
        seededWith(VERSION, 1, "One.", false)
        store.version(LATER, ENTRY, 2, ANN)
        store.content(LATER, "question", "Two.")
        store.submitted(LATER, ANN)
        store.repeatableReadByDefault()
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
        store.texts("""
                select created_by || ' ' || created_by_kind || ' ' || coalesce(withdrawn_at::text, '-')
                  from entry_version_submissions where entry_version_id = ?::uuid
                """, VERSION) == ["${CAT} person -" as String]
        store.count("select count(*) from entry_version_writers") == 0
        store.count("select count(*) from entry_versions where approved_at is not null") == 0

        where:
        stopped << [false, true]
    }

    /**
     * A draft telling nobody anything, taking a field of text that says nothing of how long, and giving nothing
     * back: each place named, in the order the question reads, and the draft left a draft.
     */
    def "submitting a question whose stored content does not hold is refused, naming every place, changing nothing"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.content(VERSION, "question")
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
                        ContentProblemCode.INSTRUCTION_MISSING, new ContentPlace.Whole(ContentPart.INSTRUCTION), null),
                new ContentProblem(ContentProblemCode.LONGEST_MISSING,
                        new ContentPlace.AtField(ContentPart.TAKES, UUID.fromString(FIELD)), null),
                new ContentProblem(ContentProblemCode.NOTHING_GIVEN_BACK, new ContentPlace.Whole(ContentPart.GIVES), null)]
        refused.pins() == []
        store.contents() == before
    }

    /**
     * A draft whose content does not hold and which pins a list retired since: neither refusal hides the other,
     * so every place and every pin is named in the one refusal.
     */
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

    /**
     * The content is read under the version's lock, which a write of it holds until it lands: the submission
     * waits, and holds the question to what the write left, never to what it found before waiting.
     */
    def "a submission waiting on a write that makes the question whole reads it whole, and goes ahead"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.content(VERSION, "question")
        def writing = store.holding(
                "select 1 from entry_versions where entry_version_id = '${VERSION}' for no key update" as String,
                "update question_versions set instruction = 'Say what it is about.' where entry_version_id = '${VERSION}'" as String,
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

    /** Withdrawn, it is a draft again, and a submission of it is a submission of its own beside the one withdrawn. */
    def "a version whose submission was withdrawn may be submitted anew"() {
        given:
        store.version(VERSION, ENTRY, 1, BEN)
        store.wholeQuestion(VERSION)
        store.submitted(VERSION, BEN)
        store.session.sql("update entry_version_submissions set withdrawn_at = now(), withdrawn_by = ?::uuid")
                .param(BEN).update()

        when:
        changes.submit(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), CAT_USER)

        then:
        store.count("select count(*) from entry_version_submissions") == 2
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
        store.texts("""
                select approved_by || ' ' || approved_by_kind || ' ' || coalesce(retired_at::text, '-')
                  from entry_versions where entry_version_id = ?::uuid
                """, VERSION) == ["${BEN} person -" as String]
        store.count("select count(*) from entry_version_submissions where withdrawn_at is null") == 1
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

    /** Standing moves one way and withdrawing is the one step back, so every other move is refused. */
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

    /**
     * A version of another group's entry, one of another kind, one of another entry of this group, and an
     * identifier nobody holds are one refusal.
     */
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

    /**
     * Each way a version names another — a step, a route's case, a field's terms — whose target was retired
     * since, refused naming it and offering the newest in service of its entry, or none where none is.
     */
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
                new EntryName(via == "step" ? "Triage" : "Escalate"),
                new RetiredPinsRefusal.NumberedVersion(versionId(pinned), 1),
                replaced ? new RetiredPinsRefusal.NumberedVersion(versionId(NEWEST), 2) : null)]
        store.contents() == before

        where:
        [act, via, replaced] << [["submit", "approve"], ["step", "route", "term"], [true, false]].combinations()
    }

    /** Several versions of one entry may be in service at once; the one offered is the newest of them. */
    def "offers the newest of several versions in service in place of a pin retired since"() {
        given:
        def (String pinnedEntry, String pinned) = pinning("step")
        store.retired(pinned, FIRST_STEWARD)
        store.seeded(NEWEST, pinnedEntry, 2)
        store.seeded(LATER, pinnedEntry, 3)

        when:
        changes.submit(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)

        then:
        def refused = thrown(RetiredPinsRefusal)
        refused.pins()*.newestInService() == [new RetiredPinsRefusal.NumberedVersion(versionId(LATER), 3)]
    }

    def "submitting or approving a version whose every pin is in service goes ahead"() {
        given:
        pinning(via)
        if (act == "approve") {
            store.submitted(HOLDING, ANN)
        }

        when:
        changes."${act}"(groupId(GROUP), EntryKind.WORKFLOW, entryId(HOLDER), versionId(HOLDING), BEN_USER)

        then:
        store.count("select count(*) from entry_version_submissions where withdrawn_at is null") == 1
        store.count("select count(*) from entry_versions where approved_by = ?::uuid", BEN) == (act == "approve" ? 1 : 0)

        where:
        [act, via] << [["submit", "approve"], ["step", "route", "term"]].combinations()
    }

    /**
     * Nothing may pin another group's version, so a row doing so was written round every check; it is no pin
     * of this group's to report, and another group's version is neither named nor held.
     */
    def "a pin of another group's version written round every check is not reported, whatever its standing"() {
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
        store.texts("select created_by::text from entry_version_submissions") == [BEN]
    }

    /** The retirement has not committed when the submission asks; it waits, and reads what landed. */
    def "a submission waiting on the retirement of a version it pins reads it retired, and is refused"() {
        given:
        def (String pinnedEntry, String pinned) = pinning("step")
        store.repeatableReadByDefault()
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

    /** The withdrawal holds the version and has not committed; the approval waits, then reads it withdrawn. */
    def "an approval waiting on a withdrawal reads the version withdrawn, and is refused"() {
        given:
        store.version(VERSION, ENTRY, 1, ANN)
        store.submitted(VERSION, ANN)
        store.repeatableReadByDefault()
        def withdrawing = store.holding(
                "select 1 from entry_versions where entry_version_id = '${VERSION}' for no key update",
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
        def starting = store.holding("select 1 from entry_versions where entry_version_id = '${VERSION}' for key share")

        when:
        def retiring = attempting(racing) {
            changes.retire(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), versionId(VERSION), ANN_USER)
        }

        then:
        retiring.get(10, TimeUnit.SECONDS) == null
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

    /**
     * What the act answers was made after the act began and before it read it. Its moment is then later than
     * the act's own, and the act is recorded no earlier than what it answers.
     */
    def "an act begun before what it answers was made is recorded no earlier than that"() {
        given:
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

    /** Both halves of a question version, a field of fields among them and a list pinned. */
    private void declared(String version) {
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

    /**
     * A workflow draft's every part written as a page writes it: halves, a question's step fed what the workflow
     * takes, a route after it choosing by its category and giving back a field holding a field, its two cases, what
     * fills what the workflow gives back, its ceiling and its helper, each over the revision the one before left.
     */
    private void writtenWhole(String version, String led) {
        def roles = new GroupRoles(store.session)
        def released = new ReleasedCodeSteps(LibraryStore.CODE_STEPS)
        def drafts = new WorkflowDrafts(store.session, new Drafts(store.session, store.transactions(), roles),
                new Workflows(store.session, roles, LibraryStore.MODELS, released, store.transactionManager()), released)
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
                new FlowBody.SentStep(null, new StepId("classify"),
                        new FlowBody.SentRuns.Pinned(EntryKind.QUESTION, versionId(VERSION)),
                        new Producer.Model(new ModelChoice(new ModelName("general"), new ModelMode("research")), true), 2,
                        new ModelChoice(new ModelName("small"), null),
                        [new FlowBody.SentBinding(Pointer.parse("complaint"),
                                new FlowBody.SentSource.Input(Pointer.parse("complaint")))]),
                new FlowBody.SentStep(null, new StepId("route"), new FlowBody.SentRuns.Route(
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

    /** What a workflow version holds with every key taken out, a step read from named by its place instead. */
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
             [step.name(), runs, step.producer(), step.tries(), step.reviewer(), step.bindings().collect(bound)]
         },
         outputs : workflow.outputs().collect(bound),
         settings: [workflow.ceiling(), workflow.keepsOwnCeiling(), workflow.raiseNeedsApproval(),
                    workflow.mayBeHelped(), workflow.helper()]]
    }

    /** Every field of the version as it reads, each under the name of what holds it rather than any key. */
    private List<String> fieldsOf(String version) {
        store.texts("""
                select concat_ws(' ', field.side, coalesce(parent.name, '-'), field.position, field.name,
                                 coalesce(field.label, '-'), coalesce(field.help, '-'), field.kind, field.holds_many,
                                 coalesce(field.text_limit::text, '-'), coalesce(field.many_limit::text, '-'),
                                 coalesce(field.term_list_version_id::text, '-'), coalesce(field.must_be_given::text, '-'),
                                 coalesce(field.standing::text, '-'), coalesce(field.standing_threshold::text, '-'))
                  from declaration_fields field
                  left join declaration_fields parent on parent.declaration_field_id = field.parent_field_id
                 where field.entry_version_id = ?::uuid
                 order by 1
                """, version)
    }

    /** Every term of the version in its order, by its place and its words rather than any key. */
    private List<String> termsOf(String version) {
        store.texts("""
                select concat_ws(' ', position, term, meaning) from reference_list_terms
                 where entry_version_id = ?::uuid order by position
                """, version)
    }

    /** A draft of a workflow of this group pinning, through {@code via}, a version in service: its entry and itself. */
    private List<String> pinning(String via) {
        store.entry(HOLDER, GROUP, "workflow", "Handle")
        store.version(HOLDING, HOLDER, 1, ANN)
        store.content(HOLDING, "workflow")
        if (via == "step") {
            store.seeded(VERSION, ENTRY, 1)
            store.step("0000000c-0000-4000-8000-000000000801", HOLDING, 1, VERSION, "question")
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
