package org.lilradish.lite.app.run

import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.SQLException
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** The lock a run tree is changed under, taken on a real server running the real baseline. */
class RunTreeIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000e01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000e02"

    static final String ANN = "00000002-0000-4000-8000-000000000e01"

    static final String ROOT = "00000008-0000-4000-8000-000000000e01"

    static final String BENEATH = "00000008-0000-4000-8000-000000000e02"

    static final String UNRELATED = "00000008-0000-4000-8000-000000000e03"

    static final String NOBODYS = "00000008-0000-4000-8000-000000000e09"

    /** Asked of a lock somebody else holds, as {@code nowait} answers. */
    static final String LOCK_NOT_AVAILABLE = "55P03"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_tree_" + (++databasesMade))
        tree = new RunTree(store.session)
        store.person(ANN, "000e01")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        RunRows.workflow(store, "00000006-0000-4000-8000-000000000e01", "00000007-0000-4000-8000-000000000e01", GROUP,
                "Handle a complaint", null, false)
        RunRows.workflow(store, "00000006-0000-4000-8000-000000000e02", "00000007-0000-4000-8000-000000000e02", GROUP,
                "Escalate", null, false)
        RunRows.run(store, ROOT, GROUP, 1, "Complaint from Ada", "00000006-0000-4000-8000-000000000e01",
                "00000007-0000-4000-8000-000000000e01", ANN)
        RunRows.run(store, UNRELATED, GROUP, 2, "Complaint from Grace", "00000006-0000-4000-8000-000000000e01",
                "00000007-0000-4000-8000-000000000e01", ANN)
        RunRows.beneath(store, BENEATH, ROOT, GROUP, 3, "00000006-0000-4000-8000-000000000e02",
                "00000007-0000-4000-8000-000000000e02", "00000009-0000-4000-8000-000000000e01",
                "0000000a-0000-4000-8000-000000000e01")
    }

    /** Whether another session may take the row's lock now, rather than waiting for it. */
    private boolean lockable(String run) {
        store.database.connection.withCloseable { connection ->
            try {
                connection.prepareStatement("select 1 from runs where run_id = '${run}' for no key update nowait")
                        .withCloseable { it.executeQuery().close() }
                true
            } catch (SQLException refused) {
                assert refused.SQLState == LOCK_NOT_AVAILABLE
                false
            }
        }
    }

    def "locks the root of the tree whichever run of it is named, and nothing else, until the transaction ends"() {
        when:
        def seen = store.transactions().execute { status ->
            def locked = tree.lock(groupId(GROUP), new RunId(UUID.fromString(named))).orElseThrow()
            [locked.group(), locked.root(), lockable(ROOT), lockable(BENEATH), lockable(UNRELATED)]
        }

        then:
        seen == [groupId(GROUP), new RunId(UUID.fromString(ROOT)), false, true, true]

        and: "let go of once the transaction that took it has ended"
        lockable(ROOT)

        where:
        named << [ROOT, BENEATH]
    }

    /** A tree held is proof of the lock only while the transaction that took it holds it. */
    def "a locked tree is refused once the transaction that locked it has ended, and on another thread meanwhile"() {
        given:
        LockedTree kept = null
        String elsewhere = null
        store.transactions().executeWithoutResult {
            kept = tree.lock(groupId(GROUP), new RunId(UUID.fromString(ROOT))).orElseThrow()
            kept.requireHeld()
            def other = Thread.start {
                try {
                    kept.requireHeld()
                } catch (IllegalStateException refused) {
                    elsewhere = refused.message
                }
            }
            other.join(10_000)
        }

        when:
        kept.requireHeld()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Run tree ${ROOT} was used outside the transaction that locked it" as String

        and: "as it was on another thread while the transaction still held it"
        elsewhere == refused.message
    }

    /** Another group's run is answered as none at all, and its tree is never waited on. */
    def "answers none for a run the group does not hold, locking nothing"() {
        when:
        def seen = store.transactions().execute { status ->
            [tree.lock(groupId(group), new RunId(UUID.fromString(named))), lockable(ROOT)]
        }

        then:
        seen == [Optional.empty(), true]

        where:
        group       | named
        OTHER_GROUP | ROOT
        GROUP       | NOBODYS
    }

    /** Outside a transaction each statement commits alone, so a lock taken would be let go of at once. */
    def "refuses to lock outside the transaction that is to hold it, reading nothing"() {
        when:
        tree.lock(groupId(GROUP), new RunId(UUID.fromString(ROOT)))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "A run tree is locked only inside the transaction that is to hold it"
    }

    def "refuses to be asked of no group or no run"() {
        when:
        store.transactions().execute { status -> tree.lock(group == null ? null : groupId(group), run) }

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        group | run                                   || message
        null  | new RunId(UUID.fromString(ROOT))      || "RunTree group must not be null"
        GROUP | null                                  || "RunTree run must not be null"
    }
}
