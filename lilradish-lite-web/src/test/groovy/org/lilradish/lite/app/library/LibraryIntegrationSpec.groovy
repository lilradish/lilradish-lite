package org.lilradish.lite.app.library

import static org.lilradish.lite.domain.registry.EntryAct.LET_GO
import static org.lilradish.lite.domain.registry.EntryAct.RENAME
import static org.lilradish.lite.domain.registry.EntryAct.START_DRAFT
import static org.lilradish.lite.domain.registry.EntryAct.STOP
import static org.lilradish.lite.domain.registry.VersionAct.APPROVE
import static org.lilradish.lite.domain.registry.VersionAct.RETIRE
import static org.lilradish.lite.domain.registry.VersionAct.SUBMIT
import static org.lilradish.lite.domain.registry.VersionAct.WITHDRAW
import static org.lilradish.lite.domain.registry.VersionAct.WRITE
import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupLists
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.pool.PersonRows
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPage
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.people.PersonName
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryPurpose
import org.lilradish.lite.domain.registry.LibrarySortColumn
import org.lilradish.lite.domain.registry.VersionStanding
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** A group's library as it is read, on a real server running the real baseline. */
class LibraryIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"

    /** Ann and Ben are overseers; Cat is an operator; Dan is in no role here. */
    static final String ANN = "00000002-0000-4000-8000-000000000801"

    static final String BEN = "00000002-0000-4000-8000-000000000802"

    static final String CAT = "00000002-0000-4000-8000-000000000803"

    static final UserId ANN_USER = new UserId("000801")

    static final UserId BEN_USER = new UserId("000802")

    static final UserId CAT_USER = new UserId("000803")

    static final UserId DAN_USER = new UserId("000804")

    static final String DAN_ID = "00000002-0000-4000-8000-000000000804"

    static final String TRIAGE = "00000006-0000-4000-8000-000000000801"

    static final String ESCALATE = "00000006-0000-4000-8000-000000000802"

    static final String ARCHIVE = "00000006-0000-4000-8000-000000000803"

    static final String HANDLE = "00000006-0000-4000-8000-000000000804"

    static final String ELSEWHERE = "00000006-0000-4000-8000-000000000805"

    static final String ROUTER = "00000006-0000-4000-8000-000000000806"

    static final String CATEGORIES = "00000006-0000-4000-8000-000000000807"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    Library library

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "library_" + (++databasesMade))
        library = libraryOver(store.session)
        store.person(ANN, "000801", "Ann Example")
        store.person(BEN, "000802", "Ben Example")
        store.person(CAT, "000803", "Cat Example")
        store.person(DAN_ID, "000804")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, BEN, "overseer")
        store.member(GROUP, CAT, "operator")
        store.member(OTHER_GROUP, DAN_ID, "owner")
    }

    def "each kind's entries are read as a list of their own name"() {
        expect:
        EntryKind.values().collect { Library.listed(it).name() }.toSet().size() == EntryKind.values().size()
    }

    /** One list per kind, so a cursor minted for one kind's page is refused by another's. */
    def "a cursor minted for one kind's list is refused by another kind's"() {
        when:
        def query = new ListQuery<>(GroupLists.within(groupId(GROUP)), ascending(LibrarySortColumn.NAME), null)
        def minted = ListCursor.mint(Library.listed(EntryKind.QUESTION), query, new ListPosition("A", "A"))
        ListCursor.resume(Library.listed(EntryKind.WORKFLOW), query, minted)

        then:
        thrown(IllegalArgumentException)
    }

    /**
     * Triage's newest is submitted over one in service; Escalate's newest in service was retired over an older
     * one still serving, and it is stopped; Archive's one version was approved with its submission left open.
     */
    def "lists every entry of the kind the group owns, and none of another kind or group"() {
        given:
        questions()

        when:
        def page = library.page(EntryKind.QUESTION, query(GROUP, ascending(LibrarySortColumn.NAME)), null)

        then:
        page.rows().collect { [it.entryId(), it.name().value(), it.inService(), it.submitted(), it.stopped()] } == [
                [entryId(ARCHIVE), "Archive", 1, false, false],
                [entryId(ESCALATE), "Escalate", 1, false, true],
                [entryId(TRIAGE), "Triage", 1, true, false],
        ]
        page.next() == null
    }

    def "an entry with nothing in service lists none, and one waiting on nothing lists no submission"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.version(version(1), TRIAGE, 1, ANN)

        when:
        def rows = library.page(EntryKind.QUESTION, query(GROUP, ascending(LibrarySortColumn.NAME)), null).rows()

        then:
        rows*.inService() == [null]
        rows*.submitted() == [false]
    }

    /** Approving a newer version retires nothing, so several may serve at once; the row names the newest. */
    def "an entry with several versions in service lists the newest of them"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.seeded(version(1), TRIAGE, 1)
        store.seeded(version(2), TRIAGE, 2)
        store.seeded(version(3), TRIAGE, 3, true)

        when:
        def rows = library.page(EntryKind.QUESTION, query(GROUP, ascending(LibrarySortColumn.NAME)), null).rows()

        then:
        rows*.inService() == [2]
    }

    def "a filter narrows the entries to those whose name holds what was typed, whatever the case"() {
        given:
        questions()

        when:
        def page = library.page(EntryKind.QUESTION,
                query(GROUP, ascending(LibrarySortColumn.NAME), new ListFilter(typed)), null)

        then:
        page.rows()*.name()*.value() == names

        where:
        typed || names
        "ES"  || ["Escalate"]
        "a"   || ["Archive", "Escalate", "Triage"]
        "zzz" || []
    }

    /** Whether one is in service, waiting or stopped gathers the rows at one end, ties in name order. */
    def "sorted by what is in service, what is waiting or what is stopped, the rows gather by it"() {
        given:
        questions()
        store.entry("00000006-0000-4000-8000-000000000809", GROUP, "question", "Blank")
        store.version(version(20), "00000006-0000-4000-8000-000000000809", 1, ANN)

        when:
        def page = library.page(EntryKind.QUESTION, query(GROUP, new ListOrder<>(column, descending)), null)

        then:
        page.rows()*.name()*.value() == names

        where:
        column                       | descending || names
        LibrarySortColumn.IN_SERVICE | false      || ["Blank", "Archive", "Escalate", "Triage"]
        LibrarySortColumn.IN_SERVICE | true       || ["Archive", "Escalate", "Triage", "Blank"]
        LibrarySortColumn.SUBMITTED  | true       || ["Triage", "Archive", "Blank", "Escalate"]
        LibrarySortColumn.STOPPED    | true       || ["Escalate", "Archive", "Blank", "Triage"]
        LibrarySortColumn.NAME       | true       || ["Triage", "Escalate", "Blank", "Archive"]
    }

    def "the second page of a library goes on within the group and the kind, and ends where they do"() {
        given:
        (1..ListPage.SIZE + 1).each { number ->
            store.entry(String.format("00000006-0000-4000-8000-%012d", 900 + number), GROUP, "question",
                    String.format("Entry %03d", number))
        }
        store.entry(ELSEWHERE, OTHER_GROUP, "question", "Entry 999")
        store.entry(HANDLE, GROUP, "workflow", "Entry 998")
        def query = query(GROUP, ascending(LibrarySortColumn.NAME))

        when:
        def first = library.page(EntryKind.QUESTION, query, null)
        def second = library.page(EntryKind.QUESTION, query, first.next())

        then:
        first.rows().size() == ListPage.SIZE
        second.rows()*.name()*.value() == [String.format("Entry %03d", ListPage.SIZE + 1)]
        second.next() == null
    }

    /** What a page is narrowed within is the query's own scope, the value its cursors are bound to. */
    def "a page is read within the group its query names, and one statement reads it however many rows it holds"() {
        given:
        questions()
        def statements = []
        def counted = libraryOver(JdbcClient.create(new CountingDataSource(store.database, statements)))

        when:
        def page = counted.page(EntryKind.QUESTION, query(OTHER_GROUP, ascending(LibrarySortColumn.NAME)), null)

        then:
        page.rows()*.name()*.value() == ["Elsewhere"]
        statements.size() == 1
    }

    def "a stored name the entry's type refuses fails the page rather than being shown or left out"() {
        given:
        store.entry(TRIAGE, GROUP, "question", " Triage")

        when:
        library.page(EntryKind.QUESTION, query(GROUP, ascending(LibrarySortColumn.NAME)), null)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Entry ${TRIAGE} holds a name this system will not show" as String
    }

    def "reads an entry's name, what it is for and who stopped it when, or that nothing stops it"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage", "Sorts a complaint.")
        store.seeded(version(1), TRIAGE, 1)
        store.entry(ESCALATE, GROUP, "question", "Escalate")
        store.seeded(version(2), ESCALATE, 1)
        store.stopped(TRIAGE, ANN)

        when:
        def stopped = library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), BEN_USER)
        def running = library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(ESCALATE), BEN_USER)

        then:
        stopped.entryId() == entryId(TRIAGE)
        stopped.kind() == EntryKind.QUESTION
        stopped.name() == new EntryName("Triage")
        stopped.purpose() == new EntryPurpose("Sorts a complaint.")
        stopped.stop().by() == ann()
        stopped.stop().at() == store.session.sql("select created_at from entry_stops")
                .query(java.time.OffsetDateTime).single().toInstant()

        and:
        running.purpose() == null
        running.stop() == null
    }

    /**
     * A migration started version 1 and put it into service, so nobody wrote or approved it; Ann started 2,
     * Ben wrote it and the steward approved it; Ann started 3, which Ben submitted.
     */
    def "reads every version newest first: its standing, everyone who wrote it and how it came into service"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.seeded(version(1), TRIAGE, 1)
        store.version(version(2), TRIAGE, 2, ANN)
        store.writer(version(2), BEN)
        store.submitted(version(2), ANN)
        store.approved(version(2), FIRST_STEWARD)
        store.version(version(3), TRIAGE, 3, ANN)
        store.submitted(version(3), BEN)
        store.session.sql("update entry_versions set revision = 5 where entry_version_id = ?::uuid").param(version(2))
                .update()

        when:
        def read = library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), BEN_USER)

        then:
        read.versions()*.number() == [3, 2, 1]
        read.versions()*.revision() == [1, 5, 1]
        read.versions()*.versionId() == [versionId(version(3)), versionId(version(2)), versionId(version(1))]
        read.versions()*.standing() == [VersionStanding.SUBMITTED, VersionStanding.IN_SERVICE, VersionStanding.IN_SERVICE]
        read.versions()*.writers() == [[ann()], [ann(), ben()], []]
        read.versions()*.startedByMigration() == [false, false, true]
        read.versions()[0].approval() == null
        read.versions()[1].approval().approver().subjectId() == new SubjectId(UUID.fromString(FIRST_STEWARD))
        read.versions()[2].approval() == new Library.Approval.ByMigration()
    }

    /** What is offered is what a change would admit: Ben wrote nothing of version 3, Ann started it. */
    def "each version offers the caller exactly what the caller may do to it now"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.seeded(version(1), TRIAGE, 1, true)
        store.seeded(version(2), TRIAGE, 2)
        store.version(version(3), TRIAGE, 3, ANN)
        store.submitted(version(3), ANN)

        when:
        def read = library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), caller)

        then:
        read.versions()*.acts() == acts.collect { it as Set }

        where:
        caller   || acts
        BEN_USER || [[WITHDRAW, APPROVE], [RETIRE], []]
        ANN_USER || [[WITHDRAW], [RETIRE], []]
        CAT_USER || [[WITHDRAW], [], []]
    }

    /** A new draft is offered only where none is under way; a stop only where none stands. */
    def "an entry offers the caller what they may do to it as a whole, as it stands"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.seeded(version(1), TRIAGE, 1)
        if (underWay) {
            store.version(version(2), TRIAGE, 2, ANN)
        }
        if (stopped) {
            store.stopped(TRIAGE, ANN)
        }

        when:
        def read = library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), caller)

        then:
        read.acts() == acts as Set

        where:
        caller   | underWay | stopped || acts
        BEN_USER | false    | false   || [START_DRAFT, RENAME, STOP]
        BEN_USER | true     | true    || [RENAME, LET_GO]
        CAT_USER | false    | true    || [START_DRAFT, RENAME]
    }

    /** A new draft of a kind not copied whole into one would lose some of what it started from, so none is offered. */
    def "a new draft is offered on an entry of every kind, whoever reads it"() {
        given:
        store.entry(TRIAGE, GROUP, kind, "Triage")
        store.seeded(version(1), TRIAGE, 1)

        when:
        def read = library.entry(groupId(GROUP), EntryKind.valueOf(kind.toUpperCase(Locale.ROOT)), entryId(TRIAGE),
                BEN_USER)

        then:
        read.acts() == acts as Set

        where:
        kind             || acts
        "question"       || [START_DRAFT, RENAME, STOP]
        "workflow"       || [START_DRAFT, RENAME, STOP]
        "reference_list" || [START_DRAFT, RENAME, STOP]
    }

    /**
     * Handle's version in service pins Triage's 1 through two steps and counts once; the router's pins it
     * through a route; a draft and a retired version pinning it are not counted, nor is anything elsewhere.
     */
    def "each version names what in service in the group pins it, each once, and nothing else"() {
        given:
        store.entry(TRIAGE, GROUP, "workflow", "Triage")
        store.seeded(version(1), TRIAGE, 1)
        store.seeded(version(2), TRIAGE, 2)
        holding(HANDLE, "Handle", version(11), true, false)
        store.step("0000000c-0000-4000-8000-000000000801", version(11), 1, version(1), "workflow")
        store.step("0000000c-0000-4000-8000-000000000802", version(11), 2, version(1), "workflow")
        holding(ROUTER, "Router", version(12), true, false)
        store.routed("0000000c-0000-4000-8000-000000000803", "0000000d-0000-4000-8000-000000000801", version(12), 1,
                version(1))
        holding(ESCALATE, "Escalate", version(13), false, false)
        store.step("0000000c-0000-4000-8000-000000000804", version(13), 1, version(1), "workflow")
        holding(ARCHIVE, "Archive", version(14), true, true)
        store.step("0000000c-0000-4000-8000-000000000805", version(14), 1, version(1), "workflow")
        store.entry(ELSEWHERE, OTHER_GROUP, "workflow", "Invoice")
        store.seeded(version(15), ELSEWHERE, 1)
        store.content(version(15), "workflow")
        store.step("0000000c-0000-4000-8000-000000000806", version(15), 1, version(1), "workflow")

        when:
        def read = library.entry(groupId(GROUP), EntryKind.WORKFLOW, entryId(TRIAGE), BEN_USER)
        def one = read.versions().find { it.number() == 1 }
        def two = read.versions().find { it.number() == 2 }

        then:
        one.pinnedBy() == [
                new Library.PinnedBy(entryId(HANDLE), EntryKind.WORKFLOW, new EntryName("Handle"), versionId(version(11)), 1),
                new Library.PinnedBy(entryId(ROUTER), EntryKind.WORKFLOW, new EntryName("Router"), versionId(version(12)), 1),
        ]
        two.pinnedBy() == []
    }

    def "a reference list's version is pinned by a field holding its terms"() {
        given:
        store.entry(CATEGORIES, GROUP, "reference_list", "Categories")
        store.seeded(version(1), CATEGORIES, 1)
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.seeded(version(2), TRIAGE, 1)
        store.content(version(2), "question")
        store.termField("0000000b-0000-4000-8000-000000000801", version(2), "question", version(1))

        when:
        def read = library.entry(groupId(GROUP), EntryKind.REFERENCE_LIST, entryId(CATEGORIES), BEN_USER)

        then:
        read.versions()[0].pinnedBy() ==
                [new Library.PinnedBy(entryId(TRIAGE), EntryKind.QUESTION, new EntryName("Triage"), versionId(version(2)), 1)]
    }

    def "reading no entry of the kind in the group is refused as no entry"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.entry(ELSEWHERE, OTHER_GROUP, "workflow", "Invoice")

        when:
        library.entry(groupId(GROUP), EntryKind.WORKFLOW, entryId(named), ANN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ENTRY_NOT_IN_VIEW
        refused.message == "That entry is not in this group's library."

        where:
        named << [TRIAGE, ELSEWHERE, "00000009-0000-4000-8000-000000000009"]
    }

    /** Somebody in no role here cannot see into the group, and is answered as a group that is not is. */
    def "reading an entry by somebody holding nothing in the group is refused as no group, whatever it names"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")

        when:
        library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(named), DAN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW

        where:
        named << [TRIAGE, "00000009-0000-4000-8000-000000000009"]
    }

    def "an entry is read in five statements, however many versions it has"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        (1..versions).each { store.seeded(version(it), TRIAGE, it) }
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def counted = libraryOver(JdbcClient.create(counting), counting)

        when:
        def read = counted.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), BEN_USER)

        then:
        read.versions().size() == versions
        statements.size() == 5

        where:
        versions << [1, 12]
    }

    /**
     * A writer is recorded and committed after the entry's own row was read and before who wrote each version
     * is: one moment of the store is what is read, and it was not there at that moment.
     */
    def "an entry is read as one moment of the store, whatever lands while it is being read"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.version(version(1), TRIAGE, 1, ANN)
        def landing = { store.writer(version(1), BEN) }
        def statements = new ArrayList<String>() {
            @Override
            boolean add(String statement) {
                def added = super.add(statement)
                if (size() == 2) {
                    landing()
                }
                added
            }
        }
        def interleaved = new CountingDataSource(store.database, statements)
        def reading = libraryOver(JdbcClient.create(interleaved), interleaved)

        when:
        def read = reading.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), CAT_USER)

        then:
        read.versions()[0].writers() == [ann()]
        store.count("select count(*) from entry_version_writers") == 1
    }

    def "a stored value its type refuses fails the whole entry rather than being shown or left out"() {
        given:
        store.entry(TRIAGE, GROUP, "question", name, purpose)
        store.seeded(version(1), TRIAGE, 1)
        if (writer != null) {
            store.person("00000002-0000-4000-8000-000000000805", "000805", writer)
            store.version(version(2), TRIAGE, 2, "00000002-0000-4000-8000-000000000805")
        }
        if (holder != null) {
            store.entry(HANDLE, GROUP, "workflow", holder)
            store.seeded(version(3), HANDLE, 1)
            store.content(version(3), "workflow")
            store.step("0000000c-0000-4000-8000-000000000801", version(3), 1, version(1), "question")
        }

        when:
        library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), BEN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == message

        where:
        name       | purpose | writer   | holder    || message
        " Triage"  | null    | null     | null      || "Entry ${TRIAGE} holds a name this system will not show" as String
        "Triage"   | "   "   | null     | null      || "Entry ${TRIAGE} holds a purpose this system will not show" as String
        "Triage"   | null    | " Ann"   | null      || "Subject 00000002-0000-4000-8000-000000000805 holds a name this system will not show"
        "Triage"   | null    | null     | " Handle" || "Entry ${HANDLE} holds a name this system will not show" as String
    }

    /** Nobody but a person or a migration writes a version, so one written by neither is a store gone wrong. */
    def "a version that no person wrote and no migration started fails the read rather than being shown"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.version(version(1), TRIAGE, 1, SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString())

        when:
        library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), BEN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version ${version(1)} was written by nobody this system can name" as String
    }

    def "a version holding a submission by seeding that seeding did not start fails the read rather than being shown"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.version(version(1), TRIAGE, 1, ANN)
        store.submitted(version(1), SEEDER, "seeder")

        when:
        library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), BEN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version ${version(1)} holds a submission by seeding that seeding did not start" as String
    }

    private void questions() {
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.seeded(version(1), TRIAGE, 1)
        store.version(version(2), TRIAGE, 2, ANN)
        store.submitted(version(2), ANN)
        store.entry(ESCALATE, GROUP, "question", "Escalate")
        store.seeded(version(3), ESCALATE, 1)
        store.seeded(version(4), ESCALATE, 2)
        store.retired(version(4), FIRST_STEWARD)
        store.stopped(ESCALATE, ANN)
        store.entry(ARCHIVE, GROUP, "question", "Archive")
        store.version(version(5), ARCHIVE, 1, ANN)
        store.submitted(version(5), ANN)
        store.approved(version(5), FIRST_STEWARD)
        store.entry(HANDLE, GROUP, "workflow", "Handle")
        store.entry(ELSEWHERE, OTHER_GROUP, "question", "Elsewhere")
    }

    /** An entry of this group's whose one version pins, in service or not, and retired or not. */
    private void holding(String entry, String name, String holder, boolean inService, boolean retired) {
        store.entry(entry, GROUP, "workflow", name)
        if (inService) {
            store.seeded(holder, entry, 1, retired)
        } else {
            store.version(holder, entry, 1, ANN)
        }
        store.content(holder, "workflow")
    }

    private Library libraryOver(JdbcClient database, javax.sql.DataSource transacted = store.database) {
        new Library(database, new GroupRoles(database), store.transactionManager(transacted))
    }

    private static ListQuery<LibrarySortColumn> query(String group, ListOrder<LibrarySortColumn> order,
                                                      ListFilter filter = null) {
        new ListQuery<>(GroupLists.within(groupId(group)), order, filter)
    }

    private static ListOrder<LibrarySortColumn> ascending(LibrarySortColumn column) {
        new ListOrder<>(column, false)
    }

    private static PersonRows.Person ann() {
        new PersonRows.Person(new SubjectId(UUID.fromString(ANN)), ANN_USER, new PersonName("Ann Example"))
    }

    private static PersonRows.Person ben() {
        new PersonRows.Person(new SubjectId(UUID.fromString(BEN)), BEN_USER, new PersonName("Ben Example"))
    }

    private static String version(int number) {
        String.format("00000007-0000-4000-8000-%012d", 800 + number)
    }
}
