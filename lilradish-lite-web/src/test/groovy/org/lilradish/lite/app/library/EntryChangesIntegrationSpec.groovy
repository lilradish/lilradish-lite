package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryPurpose
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Starting, renaming, describing, stopping and letting go of an entry, on a real server running the real
 * baseline; where two changes race, only a change naming Read Committed reads what it waited on.
 */
class EntryChangesIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"

    /** An overseer: may write, approve and revoke an entry. */
    static final String ANN = "00000002-0000-4000-8000-000000000801"

    static final UserId ANN_USER = new UserId("000801")

    /** An operator: may write an entry, and neither approve nor revoke one. */
    static final String CAT = "00000002-0000-4000-8000-000000000803"

    static final UserId CAT_USER = new UserId("000803")

    /** In no role in the group. */
    static final UserId DAN_USER = new UserId("000804")

    static final String ENTRY = "00000006-0000-4000-8000-000000000801"

    static final String OTHER_ENTRY = "00000006-0000-4000-8000-000000000802"

    static final String VERSION = "00000007-0000-4000-8000-000000000801"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    EntryChanges changes

    /** Every entry whose let-go was handed on, in the order handed. */
    List<EntryId> goneOn = []

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "entries_" + (++databasesMade))
        changes = new EntryChanges(store.session, store.transactions(), new GroupRoles(store.session),
                { entry -> goneOn << entry } as EntryLetGo)
        store.person(ANN, "000801")
        store.person(CAT, "000803")
        store.person("00000002-0000-4000-8000-000000000804", "000804")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, CAT, "operator")
        store.member(OTHER_GROUP, ANN, "owner")
    }

    def "starts an entry the group owns as the caller's act, its first version an empty draft numbered one"() {
        when:
        def started = changes.start(groupId(GROUP), kind, new EntryName("Summarise a complaint"),
                new EntryPurpose("Says what a complaint is about."), CAT_USER)

        then:
        store.texts("""
                select group_id || ' ' || kind || ' ' || name || ' ' || purpose || ' ' || created_by || ' '
                       || coalesce(updated_by::text, '-')
                  from entries where entry_id = ?::uuid
                """, started.value()) ==
                ["${GROUP} ${label} Summarise a complaint Says what a complaint is about. ${CAT} -" as String]

        and: "one version, the first, started by the caller and neither submitted, approved nor retired"
        store.texts("""
                select number || ' ' || entry_kind || ' ' || created_by || ' '
                       || coalesce(approved_at::text, '-') || ' ' || coalesce(retired_at::text, '-')
                  from entry_versions where entry_id = ?::uuid
                """, started.value()) == ["1 ${label} ${CAT} - -" as String]
        store.count("select count(*) from entry_version_submissions") == 0

        and: "its content row in its kind's table alone, empty, and the starter held as no writer"
        store.texts("select created_by::text from ${table}") == [CAT]
        (["workflow_versions", "question_versions", "reference_list_versions"] - table)
                .every { store.count("select count(*) from ${it}") == 0 }
        store.count("select count(*) from entry_version_writers") == 0

        where:
        kind                     || label            | table
        EntryKind.WORKFLOW       || "workflow"       | "workflow_versions"
        EntryKind.QUESTION       || "question"       | "question_versions"
        EntryKind.REFERENCE_LIST || "reference_list" | "reference_list_versions"
    }

    def "starts an entry saying nothing of what it is for, which holds no purpose rather than an empty one"() {
        when:
        def started = changes.start(groupId(GROUP), EntryKind.QUESTION, new EntryName("Triage"), null, ANN_USER)

        then:
        store.texts("select coalesce(purpose, '-') from entries where entry_id = ?::uuid", started.value()) == ["-"]
    }

    def "refuses a name another entry of the kind in the group holds, whatever case either was typed in, writing nothing"() {
        given:
        store.entry(ENTRY, GROUP, "question", "Triage")
        def before = store.contents()

        when:
        changes.start(groupId(GROUP), EntryKind.QUESTION, new EntryName(typed), null, ANN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ENTRY_NAME_TAKEN

        and:
        store.contents() == before

        where:
        typed << ["Triage", "TRIAGE", "triage"]
    }

    def "a name held by an entry of another kind or in another group is free"() {
        given:
        store.entry(ENTRY, holder, holderKind, "Triage")

        when:
        changes.start(groupId(GROUP), EntryKind.QUESTION, new EntryName("Triage"), null, ANN_USER)

        then:
        store.count("select count(*) from entries where name = 'Triage'") == 2

        where:
        holder      | holderKind
        GROUP       | "workflow"
        OTHER_GROUP | "question"
    }

    /** Only the store sees a name another has not committed; the start waits on it, then decides. */
    def "a start racing another for the same name is decided by the name's uniqueness"() {
        given:
        store.repeatableReadByDefault()
        def other = store.holding("insert into entries (entry_id, group_id, kind, name, created_by)" +
                " values ('${OTHER_ENTRY}', '${GROUP}', 'question', 'TRIAGE', '${SEEDER}')")

        when:
        def starting = attempting(racing) {
            changes.start(groupId(GROUP), EntryKind.QUESTION, new EntryName("Triage"), null, ANN_USER)
        }
        store.untilWaiting(1)
        otherLands ? other.commit() : other.rollback()
        other.close()
        def outcome = starting.get(10, TimeUnit.SECONDS)

        then:
        outcome.getClass() == (refusal == null ? EntryId : ApiErrorException)
        refusal == null || outcome.errorCode() == refusal
        store.count("select count(*) from entry_versions") == (refusal == null ? 1 : 0)

        where:
        otherLands || refusal
        true       || RefusalCode.ENTRY_NAME_TAKEN
        false      || null
    }

    /**
     * The name and what the entry is for change as one act, either or both; its own name in another case is
     * no other entry's, and saying nothing clears what it said.
     */
    def "renames an entry and says what it is for as the caller's one act, leaving every version as it was"() {
        given:
        store.entry(ENTRY, GROUP, "question", "Triage", before)
        store.version(VERSION, ENTRY, 1, SEEDER)
        def versions = store.digestOf("entry_versions")

        when:
        changes.rename(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), new EntryName(name),
                purpose == null ? null : new EntryPurpose(purpose), CAT_USER)

        then:
        store.texts("select name || ' ' || coalesce(purpose, '-') || ' ' || updated_by from entries") ==
                ["${name} ${purpose ?: '-'} ${CAT}" as String]
        store.digestOf("entry_versions") == versions

        where:
        before               | name               | purpose
        "Sorts a complaint." | "Sort a complaint" | "Sorts a complaint."
        "Sorts a complaint." | "TRIAGE"           | "Sorts a complaint."
        null                 | "Triage"           | "Routes a complaint."
        "Sorts a complaint." | "Triage"           | null
        null                 | "Sort a complaint" | "Routes a complaint."
    }

    def "renaming an entry to the name it has and saying what it already says records nothing"() {
        given:
        store.entry(ENTRY, GROUP, "question", "Triage", held)
        def before = store.contents()

        when:
        changes.rename(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), new EntryName("Triage"),
                held == null ? null : new EntryPurpose(held), ANN_USER)

        then:
        store.contents() == before

        where:
        held << [null, "Sorts a complaint."]
    }

    def "refuses to rename an entry to a name another of its kind in its group holds, changing nothing of either"() {
        given:
        store.entry(ENTRY, GROUP, "question", "Triage")
        store.entry(OTHER_ENTRY, GROUP, "question", "Escalate")
        def before = store.contents()

        when:
        changes.rename(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), new EntryName(typed),
                new EntryPurpose("Said."), ANN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ENTRY_NAME_TAKEN
        store.contents() == before

        where:
        typed << ["Escalate", "ESCALATE"]
    }

    /**
     * Another change has named the entry so, said the same of it, and not committed. The rename reads both
     * only once it holds the row, finds them as given, and records nothing of its own.
     */
    def "a rename waiting on another making the entry as given finds it so, and records nothing"() {
        given:
        store.entry(ENTRY, GROUP, "question", "Triage")
        store.repeatableReadByDefault()
        def other = store.holding("update entries set name = 'Sort', purpose = 'Said.', updated_at = now()," +
                " updated_by = '${FIRST_STEWARD}' where entry_id = '${ENTRY}'")

        when:
        def renaming = attempting(racing) {
            changes.rename(groupId(GROUP), EntryKind.QUESTION, entryId(ENTRY), new EntryName("Sort"),
                    new EntryPurpose("Said."), ANN_USER)
        }
        store.untilWaiting(1)
        other.commit()
        other.close()

        then:
        renaming.get(10, TimeUnit.SECONDS) == null
        store.texts("select name || ' ' || purpose || ' ' || updated_by from entries") ==
                ["Sort Said. ${FIRST_STEWARD}" as String]
    }

    def "stops an entry being used at once, as the caller's act, every version keeping the standing it had"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        store.seeded(VERSION, ENTRY, 1)
        def versions = store.digestOf("entry_versions")

        when:
        changes.stop(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER)

        then:
        store.texts("select entry_id || ' ' || created_by || ' ' || coalesce(let_go_at::text, '-') from entry_stops") ==
                ["${ENTRY} ${ANN} -" as String]
        store.digestOf("entry_versions") == versions
    }

    def "stopping an entry already stopped leaves it as it was stopped, by whoever stopped it"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        store.stopped(ENTRY, FIRST_STEWARD)
        def before = store.contents()

        when:
        changes.stop(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER)

        then:
        store.contents() == before
    }

    def "lets a stopped entry go again as the caller's act, and a later stop is a stop of its own"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        store.stopped(ENTRY, FIRST_STEWARD)

        when:
        changes.letGo(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER)

        then:
        store.texts("select created_by || ' ' || let_go_by from entry_stops where let_go_at is not null") ==
                ["${FIRST_STEWARD} ${ANN}" as String]
        store.count("select count(*) from entry_stops where let_go_at is null") == 0

        and: "what the stop held handed on once, for that entry"
        goneOn == [entryId(ENTRY)]

        when:
        changes.stop(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER)

        then:
        store.count("select count(*) from entry_stops") == 2
        store.texts("select created_by::text from entry_stops where let_go_at is null") == [ANN]

        and: "a stop handing nothing on"
        goneOn == [entryId(ENTRY)]
    }

    def "a letting go inside a transaction of the caller's hands on only once that transaction commits"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        store.stopped(ENTRY, FIRST_STEWARD)
        List<EntryId> seenBeforeCommit = null

        when:
        store.transactions().executeWithoutResult {
            changes.letGo(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER)
            seenBeforeCommit = new ArrayList<>(goneOn)
            if (rolledBack) {
                it.setRollbackOnly()
            }
        }

        then:
        seenBeforeCommit == []
        goneOn == (rolledBack ? [] : [entryId(ENTRY)])

        and: "the stop let go exactly where the transaction committed"
        store.count("select count(*) from entry_stops where let_go_at is null") == (rolledBack ? 1 : 0)

        where:
        rolledBack << [false, true]
    }

    def "letting go of an entry that is not stopped records nothing"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        def before = store.contents()

        when:
        changes.letGo(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER)

        then:
        store.contents() == before

        and: "nothing handed on, nothing having been held"
        goneOn == []
    }

    /**
     * The stop is made after the letting go began and before it read it; its moment is then later than the
     * change's own, and the stop is let go no earlier than it was made.
     */
    def "an entry let go of by a change begun before the stop was made is let go no earlier than it was stopped"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        def membership = store.holding("select 1 from groups where group_id = '${GROUP}' for no key update")

        when:
        def lettingGo = attempting(racing) { changes.letGo(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER) }
        store.untilWaiting(1)
        store.stopped(ENTRY, FIRST_STEWARD)
        membership.commit()
        membership.close()

        then:
        lettingGo.get(10, TimeUnit.SECONDS) == null
        store.count("select count(*) from entry_stops where let_go_at >= created_at") == 1
    }

    /**
     * A run starting holds the entry for share until it has started. Writing a stop alone takes only a key
     * share, which passes it; the switch waits for the start to land, and lands once it has.
     */
    def "stopping or letting go waits for a run starting on the entry, and lands once that start has"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        if (change == "letGo") {
            store.stopped(ENTRY, FIRST_STEWARD)
        }
        def before = store.digestOf("entry_stops")
        def starting = store.holding("select 1 from entries where entry_id = '${ENTRY}' for share" as String)

        when:
        def switching = attempting(racing) { changes."${change}"(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER) }
        store.untilWaiting(1)
        def whileStarting = store.digestOf("entry_stops")
        starting.commit()
        starting.close()

        then:
        switching.get(10, TimeUnit.SECONDS) == null
        whileStarting == before
        store.count(landed) == 1

        where:
        change  || landed
        "stop"  || "select count(*) from entry_stops where created_by = '${ANN}' and let_go_at is null"
        "letGo" || "select count(*) from entry_stops where let_go_by = '${ANN}' and let_go_at is not null"
    }

    /**
     * An identifier nobody holds, an entry of another group and one of another kind are one refusal: telling
     * them apart would say what another group's library holds.
     */
    def "a change naming no entry of the kind in the group is refused as no entry, changing nothing"() {
        given:
        store.entry(ENTRY, GROUP, "question", "Triage")
        store.entry(OTHER_ENTRY, OTHER_GROUP, "workflow", "Escalate")
        def before = store.contents()

        when:
        attempt(change, ANN_USER, named)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ENTRY_NOT_IN_VIEW
        refused.message == "That entry is not in this group's library."
        store.contents() == before

        where:
        [change, named] << [["rename", "stop", "letGo"],
                            [ENTRY, OTHER_ENTRY, "00000009-0000-4000-8000-000000000009"]].combinations()
    }

    /** Somebody in no role here cannot see into the group at all, which is how a group that is not is answered. */
    def "a change by somebody holding nothing in the group is refused as no group, whatever it names"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        def before = store.contents()

        when:
        attempt(change, DAN_USER, ENTRY)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        store.contents() == before

        where:
        change << ["start", "rename", "stop", "letGo"]
    }

    def "stopping or letting go by a member whose roles do not reach revoking is refused, changing nothing"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        store.stopped(ENTRY, FIRST_STEWARD)
        def before = store.contents()

        when:
        attempt(change, CAT_USER, ENTRY)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED
        store.contents() == before

        where:
        change << ["stop", "letGo"]
    }

    /**
     * The roles are taken by a change holding the group, which this one waits on; once it lands, what the
     * caller holds is read again, and a change nothing then reaches is refused.
     */
    def "a change whose caller loses their roles while it waits on the group is refused, changing nothing"() {
        given:
        store.entry(ENTRY, GROUP, "workflow", "Triage")
        store.stopped(ENTRY, FIRST_STEWARD)
        store.repeatableReadByDefault()
        def before = library()
        def taking = store.takingRoles(GROUP, ANN, left as String[])

        when:
        def changing = attempting(racing) { attempt(change, ANN_USER, ENTRY) }
        store.untilWaiting(1)
        taking.commit()
        taking.close()
        def outcome = changing.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == refusal
        library() == before

        where:
        change     | left         || refusal
        "start"    | []           || RefusalCode.GROUP_NOT_IN_VIEW
        "rename"   | []           || RefusalCode.GROUP_NOT_IN_VIEW
        "stop"     | ["operator"] || RefusalCode.ACT_NOT_PERMITTED
        "letGo"    | ["operator"] || RefusalCode.ACT_NOT_PERMITTED
    }

    private List<String> library() {
        ["entries", "entry_versions", "entry_stops", "workflow_versions"].collect { store.digestOf(it) }
    }

    private Object attempt(String change, UserId caller, String named) {
        if (change == "start") {
            return changes.start(groupId(GROUP), EntryKind.WORKFLOW, new EntryName("Renamed"), null, caller)
        }
        if (change == "rename") {
            return changes.rename(groupId(GROUP), EntryKind.WORKFLOW, entryId(named), new EntryName("Renamed"),
                    new EntryPurpose("Said."), caller)
        }
        changes."${change}"(groupId(GROUP), EntryKind.WORKFLOW, entryId(named), caller)
    }
}
