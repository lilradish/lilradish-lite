package com.lilradish.lite.app.library

import static com.lilradish.lite.domain.registry.EntryAct.LET_GO
import static com.lilradish.lite.domain.registry.EntryAct.RENAME
import static com.lilradish.lite.domain.registry.EntryAct.START_DRAFT
import static com.lilradish.lite.domain.registry.EntryAct.STOP
import static com.lilradish.lite.domain.registry.VersionAct.APPROVE
import static com.lilradish.lite.domain.registry.VersionAct.RETIRE
import static com.lilradish.lite.domain.registry.VersionAct.SUBMIT
import static com.lilradish.lite.domain.registry.VersionAct.WITHDRAW
import static com.lilradish.lite.domain.registry.VersionAct.WRITE
import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.entryId
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.group.GroupLists
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.pool.PersonRows
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.identity.SystemPrincipal
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.listing.ListCursor
import com.lilradish.lite.domain.listing.ListFilter
import com.lilradish.lite.domain.listing.ListOrder
import com.lilradish.lite.domain.listing.ListPage
import com.lilradish.lite.domain.listing.ListPosition
import com.lilradish.lite.domain.listing.ListQuery
import com.lilradish.lite.domain.people.PersonName
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.registry.EntryName
import com.lilradish.lite.domain.registry.EntryPurpose
import com.lilradish.lite.domain.registry.LibrarySortColumn
import com.lilradish.lite.domain.registry.VersionStanding
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.ExplainedQueries
import com.lilradish.lite.testutil.library.LibraryStore
import org.libprunus.core.error.ApiErrorException
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification


class LibraryIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"


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
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    Library library

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "library_" + (++databasesMade))
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

    def "loaded versions match their real entry fold, literal names and exact ASCII numbers"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Claims")
        store.version(version(1), TRIAGE, 3, ANN)
        store.entry(ESCALATE, GROUP, "question", "Straße")
        store.version(version(2), ESCALATE, 30, ANN)
        store.entry(HANDLE, GROUP, "workflow", "Claims")
        store.version(version(3), HANDLE, 3, ANN)
        store.entry(ELSEWHERE, OTHER_GROUP, "question", "Claims")
        store.version(version(4), ELSEWHERE, 3, DAN_ID)
        // DB-SPECIFIC: PostgreSQL uuid casts address the actual named versions.
        store.session.sql("update entry_versions set version_name = ? where entry_version_id = ?::uuid")
                .params(typedName, version(1)).update()
        def candidates = (1..4).collect { versionId(version(it)) }

        when:
        def found = library.versionNameMatches(groupId(GROUP), EntryKind.QUESTION, candidates, typed, CAT_USER)

        then:
        found.toSet() == expected.collect { UUID.fromString(version(it)) }.toSet()

        where:
        typedName        | typed                     || expected
        "Strict review"  | "STRICT"                  || [1]
        "Strict review"  | "CLAIMS"                  || [1]
        "Strict review"  | "STRASSE"                 || [2]
        "Caf\u00e9"      | "Cafe\u0301"              || [1]
        "Ren\u00e9"      | "Rene"                    || []
        "\uff21\uff22" | "AB"                      || []
        "\u0130pek"     | "ipek"                    || []
        "Review !_ 50%"  | "!_"                      || [1]
        "Review !_ 50%"  | "%"                       || [1]
        "Strict review"  | "_"                       || []
        "Strict review"  | "3"                       || [1]
        "Strict review"  | "003"                     || [1]
        "Strict review"  | "30"                      || [2]
        "Strict review"  | "\u0663"                  || []
        "Strict review"  | "Version 3"               || []
        "Strict review"  | "999999999999999999999999" || []
        "Strict review"  | ""                        || [1, 2]
    }

    def "loaded name matches still require current membership, including an empty candidate set"() {
        when:
        library.versionNameMatches(groupId(GROUP), EntryKind.QUESTION, [], "", DAN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
    }

    def "all three real library kinds record their complete status and service query plans at two scales"() {
        given:
        def started = System.nanoTime()
        // DB-SPECIFIC: PostgreSQL generate_series and ::entry_kind/::uuid/::text populate entries;
        // text || non-string converts the generated integer to text.
        store.session.sql("""
                insert into entries (entry_id, group_id, kind, name, created_by)
                select ('00000006-0000-4000-9000-' || lpad(number::text, 12, '0'))::uuid,
                       ?::uuid,
                       case number % 3 when 0 then 'question' when 1 then 'workflow'
                            else 'reference_list' end::entry_kind,
                       case when number % 1000 = 1 then 'Nerith claim '
                            when number % 50 = 0 then 'Mira claim !_ '
                            else 'Mira claim ' end || number, ?::uuid
                  from generate_series(1, ?) number
                """).params(GROUP, SEEDER, size).update()
        def inserted = System.nanoTime() - started
        started = System.nanoTime()
        // DB-SPECIFIC: PostgreSQL ::text casts expose the UUID identifier as text.
        store.session.sql("update entries set name = name || ' renamed' where entry_id::text like '00000006-0000-4000-9000-%'").update()
        def renamed = System.nanoTime() - started
        // DB-SPECIFIC: PostgreSQL ANALYZE refreshes the statistics used by its default planner.
        store.session.sql('analyze entries').update()
        def observed = new ExplainedQueries(store.database)
        def searched = libraryOver(JdbcClient.create(observed), observed)
        def results = []

        when:
        EntryKind.values().each { kind ->
            ['Nerith', 'claim', 'a', '!_'].each { text ->
                (1..3).each { repetition ->
                    def read = searched.page(kind, query(GROUP, ascending(LibrarySortColumn.STATUS), new ListFilter(text)), null)
                    results.add([kind: kind.name(), typed: text, repetition: repetition,
                                 ids: read.rows()*.entryId()*.value()*.toString()])
                }
            }
        }
        observed.save('library-' + size, [entriesAdded: size, insertedNanos: inserted, renamedNanos: renamed], results)

        then:
        observed.queries.size() == 36
        observed.queries.every { it.sql.contains('entry_versions') && it.sql.contains('entry_stops') && !it.bindings.empty }
        results.groupBy { [it.kind, it.typed] }.values().every { runs -> runs*.ids.toSet().size() == 1 }
        results.find { it.kind == 'WORKFLOW' && it.typed == 'Nerith' }.ids.contains('00000006-0000-4000-9000-000000000001')

        where:
        size << [300, 6000]
    }

    def "each kind's entries are read as a list of their own name"() {
        expect:
        EntryKind.values().collect { Library.listed(it).name() }.toSet().size() == EntryKind.values().size()
    }


    def "a cursor minted for one kind's list is refused by another kind's"() {
        when:
        def query = new ListQuery<>(GroupLists.within(groupId(GROUP)), ascending(LibrarySortColumn.NAME), null)
        def minted = ListCursor.mint(Library.listed(EntryKind.QUESTION), query, new ListPosition("A", "A"))
        ListCursor.resume(Library.listed(EntryKind.WORKFLOW), query, minted)

        then:
        thrown(IllegalArgumentException)
    }


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

    def "a library row reads its purpose where one was said and none where it was not"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage", "Sorts claims.")
        store.entry(ARCHIVE, GROUP, "question", "Archive")

        when:
        def rows = library.page(EntryKind.QUESTION, query(GROUP, ascending(LibrarySortColumn.NAME)), null).rows()

        then:
        rows*.purpose() == [null, new EntryPurpose("Sorts claims.")]
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


    def "sorted by what is in service or status, the rows gather by it"() {
        given:
        questions()
        store.stopped(TRIAGE, ANN)
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
        LibrarySortColumn.STATUS     | false      || ["Triage", "Escalate", "Archive", "Blank"]
        LibrarySortColumn.STATUS     | true       || ["Archive", "Blank", "Escalate", "Triage"]
        LibrarySortColumn.NAME       | true       || ["Triage", "Escalate", "Blank", "Archive"]
    }

    def "status order crosses pages with differently cased names without a row repeated or lost"() {
        given:
        questions()
        def names = (1..ListPage.SIZE + 1).collect { number ->
            number % 2 == 0 ? String.format("Case %03d", number) : String.format("case %03d", number)
        }
        names.eachWithIndex { name, index ->
            store.entry(String.format("00000006-0000-4000-8000-%012d", 1900 + index), GROUP, "question", name)
        }
        def query = query(GROUP, ascending(LibrarySortColumn.STATUS))

        when:
        def first = library.page(EntryKind.QUESTION, query, null)
        def second = library.page(EntryKind.QUESTION, query, first.next())
        def listed = (first.rows() + second.rows())*.name()*.value()

        then:
        first.rows().size() == ListPage.SIZE
        second.next() == null
        listed.size() == names.size() + 3
        listed.toSet() == (names + ["Triage", "Escalate", "Archive"]).toSet()
        listed[0..1] == ["Triage", "Escalate"]
    }

    def "status pages cross each group boundary without a row repeated or lost"() {
        given:
        questions()
        def waiting = waitingAdded == 0 ? [] : (1..waitingAdded).collect { number -> String.format("Waiting %03d", number) }
        waiting.eachWithIndex { name, index ->
            def entry = String.format("00000006-0000-4000-8000-%012d", 2100 + index)
            store.entry(entry, GROUP, "question", name)
            def draft = version(100 + index)
            store.version(draft, entry, 1, ANN)
            store.submitted(draft, ANN)
        }
        def stopped = stoppedAdded == 0 ? [] : (1..stoppedAdded).collect { number -> String.format("Stopped %03d", number) }
        stopped.eachWithIndex { name, index ->
            def entry = String.format("00000006-0000-4000-8000-%012d", 2200 + index)
            store.entry(entry, GROUP, "question", name)
            store.seeded(version(200 + index), entry, 1)
            store.stopped(entry, ANN)
        }
        def expected = (["Triage"] + waiting).sort() + (["Escalate"] + stopped).sort() + ["Archive"]
        def query = query(GROUP, ascending(LibrarySortColumn.STATUS))

        when:
        def first = library.page(EntryKind.QUESTION, query, null)
        def second = library.page(EntryKind.QUESTION, query, first.next())
        def listed = (first.rows() + second.rows())*.name()*.value()

        then:
        first.rows().size() == ListPage.SIZE
        first.rows().last().statusRank() == beforeRank
        second.rows().first().statusRank() == afterRank
        second.next() == null
        listed == expected
        listed.toSet().size() == expected.size()

        where:
        waitingAdded | stoppedAdded || beforeRank | afterRank
        49           | 0            || 0L         | 1L
        0            | 48           || 1L         | 2L
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
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


    def "each version reports the same state acts to every reader, including its writer"() {
        given:
        store.entry(TRIAGE, GROUP, "question", "Triage")
        store.seeded(version(1), TRIAGE, 1, true)
        store.seeded(version(2), TRIAGE, 2)
        store.version(version(3), TRIAGE, 3, ANN)
        store.submitted(version(3), ANN)

        when:
        def read = library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), caller)

        then:
        read.versions()*.stateActs() == acts.collect { it as Set }

        where:
        caller   || acts
        BEN_USER || [[WITHDRAW, APPROVE], [RETIRE], []]
        ANN_USER || [[WITHDRAW, APPROVE], [RETIRE], []]
        CAT_USER || [[WITHDRAW, APPROVE], [RETIRE], []]
    }


    def "an entry reports state acts independently of its reader"() {
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
        read.stateActs() == acts as Set

        where:
        caller   | underWay | stopped || acts
        BEN_USER | false    | false   || [START_DRAFT, RENAME, STOP]
        BEN_USER | true     | true    || [RENAME, LET_GO]
        CAT_USER | false    | true    || [START_DRAFT, RENAME, LET_GO]
    }


    def "a new draft is offered on an entry of every kind, whoever reads it"() {
        given:
        store.entry(TRIAGE, GROUP, kind, "Triage")
        store.seeded(version(1), TRIAGE, 1)

        when:
        def read = library.entry(groupId(GROUP), EntryKind.valueOf(kind.toUpperCase(Locale.ROOT)), entryId(TRIAGE),
                BEN_USER)

        then:
        read.stateActs() == acts as Set

        where:
        kind             || acts
        "question"       || [START_DRAFT, RENAME, STOP]
        "workflow"       || [START_DRAFT, RENAME, STOP]
        "reference_list" || [START_DRAFT, RENAME, STOP]
    }


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
                new Library.PinnedBy(entryId(HANDLE), EntryKind.WORKFLOW, new EntryName("Handle"), versionId(version(11)), 1, null),
                new Library.PinnedBy(entryId(ROUTER), EntryKind.WORKFLOW, new EntryName("Router"), versionId(version(12)), 1, null),
        ]
        two.pinnedBy() == []
    }

    def "a question counts ordinary and search pins once per in-service holder version in its group"() {
        given:
        store.entry(TRIAGE, GROUP, 'question', 'Triage')
        store.seeded(version(1), TRIAGE, 1)
        store.seeded(version(2), TRIAGE, 2)
        store.content(version(1), 'question')
        store.content(version(2), 'question')
        holding(HANDLE, 'Handle', version(11), true, false)
        store.seeded(version(12), HANDLE, 2)
        store.content(version(12), 'workflow')
        store.step('0000000c-0000-4000-8000-000000000811', version(11), 1, version(1), 'question')
        searchPin('0000000c-0000-4000-8000-000000000812', version(11), 2, version(1))
        searchPin('0000000c-0000-4000-8000-000000000813', version(12), 1, version(1))
        holding(ESCALATE, 'Draft holder', version(13), false, false)
        searchPin('0000000c-0000-4000-8000-000000000814', version(13), 1, version(1))
        holding(ARCHIVE, 'Retired holder', version(14), true, true)
        searchPin('0000000c-0000-4000-8000-000000000815', version(14), 1, version(1))
        holding(ROUTER, 'Submitted holder', version(16), false, false)
        store.submitted(version(16), ANN)
        searchPin('0000000c-0000-4000-8000-000000000816', version(16), 1, version(1))
        store.entry(ELSEWHERE, OTHER_GROUP, 'workflow', 'Elsewhere holder')
        store.seeded(version(15), ELSEWHERE, 1)
        store.content(version(15), 'workflow')
        searchPin('0000000c-0000-4000-8000-000000000817', version(15), 1, version(1))

        when:
        def read = library.entry(groupId(GROUP), EntryKind.QUESTION, entryId(TRIAGE), BEN_USER)

        then:
        read.versions().find { it.number() == 1 }.pinnedBy() == [
                new Library.PinnedBy(entryId(HANDLE), EntryKind.WORKFLOW, new EntryName('Handle'), versionId(version(11)), 1, null),
                new Library.PinnedBy(entryId(HANDLE), EntryKind.WORKFLOW, new EntryName('Handle'), versionId(version(12)), 2, null)]
        read.versions().find { it.number() == 2 }.pinnedBy().empty
    }

    private void searchPin(String step, String holder, int position, String question) {
        store.step(step, holder, position, question, 'question')
        // DB-SPECIFIC: PostgreSQL uuid casts.
        store.session.sql('''
                update workflow_steps set kind = 'search', search_way = 'web_search',
                       search_title_standing = 'always', search_body_standing = 'never',
                       search_references_standing = 'never', search_other_standing = 'never'
                 where workflow_step_id = ?::uuid
                ''').params(step).update()
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
                [new Library.PinnedBy(entryId(TRIAGE), EntryKind.QUESTION, new EntryName("Triage"), versionId(version(2)), 1, null)]
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

        and:
        def versionStatements = statements.findAll {
            it.contains("version.revision") && it.contains("order by version.number desc")
        }
        versionStatements.size() == 1
        !versionStatements[0].contains("written_by_caller")

        where:
        versions << [1, 12]
    }

    /** A version must be read from one snapshot so a concurrent change cannot mix its parts. */
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
