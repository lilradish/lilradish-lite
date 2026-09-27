package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.run.StopRecord
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** A run's start and an entry's stop, each waiting for the other on a real server running the real baseline. */
class EntrySwitchIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000c01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000c02"

    static final String ANN = "00000002-0000-4000-8000-000000000c01"

    static final UserId ANN_USER = new UserId("000c01")

    static final String ENTRY = "00000006-0000-4000-8000-000000000c01"

    static final String RUNNING_ENTRY = "00000006-0000-4000-8000-000000000c03"

    static final String LET_GO_ENTRY = "00000006-0000-4000-8000-000000000c04"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "switch_" + (++databasesMade))
        store.person(ANN, "000c01")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.entry(ENTRY, GROUP, "workflow", "Triage")
    }

    private boolean starting() {
        store.transactions().execute { EntrySwitch.stoppedOnceHeld(store.session, groupId(GROUP), entryId(ENTRY)) }
    }

    def "a start reads whether the entry is stopped, as it stands"() {
        given:
        if (stopped) {
            store.stopped(ENTRY, FIRST_STEWARD)
        }
        if (letGo) {
            store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid")
                    .params(FIRST_STEWARD).update()
        }

        expect:
        starting() == reads

        where:
        stopped | letGo || reads
        false   | false || false
        true    | false || true
        true    | true  || false
    }

    /** The stop lands while the start waits for the entry; read in a later statement, it is seen. */
    def "a start waiting on a stop being written reads the entry stopped once the stop lands"() {
        given:
        def stopping = store.holding(
                "select 1 from entries where entry_id = '${ENTRY}' for no key update" as String,
                "insert into entry_stops (entry_id, created_by) values ('${ENTRY}', '${FIRST_STEWARD}')" as String)

        when:
        def start = attempting(racing) { starting() }
        store.untilWaiting(1)
        def whileStopping = start.done
        stopping.commit()
        stopping.close()

        then:
        !whileStopping
        start.get(10, TimeUnit.SECONDS) == true
    }

    /** Held for share until the start ends, the entry cannot be stopped under a start that read it running. */
    def "a stop waits for a start that has read the entry running, and lands once that start ends"() {
        given:
        def changes = new EntryChanges(store.session, store.transactions(), new GroupRoles(store.session),
                { entry -> } as EntryLetGo)
        def read = new CountDownLatch(1)
        def ended = new CountDownLatch(1)
        def start = attempting(racing) {
            store.transactions().execute {
                def stopped = EntrySwitch.stoppedOnceHeld(store.session, groupId(GROUP), entryId(ENTRY))
                read.countDown()
                ended.await(10, TimeUnit.SECONDS)
                stopped
            }
        }
        read.await(10, TimeUnit.SECONDS)

        when:
        def stop = attempting(racing) { changes.stop(groupId(GROUP), EntryKind.WORKFLOW, entryId(ENTRY), ANN_USER) }
        store.untilWaiting(1)
        def whileStarting = store.count("select count(*) from entry_stops")
        ended.countDown()

        then:
        start.get(10, TimeUnit.SECONDS) == false
        stop.get(10, TimeUnit.SECONDS) == null
        whileStarting == 0
        store.count("select count(*) from entry_stops where let_go_at is null") == 1
    }

    /** Read Committed alone reads, in its later statement, a stop that landed while it waited. */
    def "is refused inside a transaction reading one moment or naming no isolation"() {
        given:
        def elsewhere = new TransactionTemplate(store.transactionManager())
        elsewhere.isolationLevel = isolation

        when:
        elsewhere.execute { EntrySwitch.stoppedOnceHeld(store.session, groupId(GROUP), entryId(ENTRY)) }

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "EntrySwitch was asked outside a read-committed transaction"

        where:
        isolation << [TransactionDefinition.ISOLATION_DEFAULT, TransactionDefinition.ISOLATION_REPEATABLE_READ,
                      TransactionDefinition.ISOLATION_SERIALIZABLE]
    }

    def "is refused outside any transaction, where nothing would hold the entry past the read"() {
        when:
        EntrySwitch.stoppedOnceHeld(store.session, groupId(GROUP), entryId(ENTRY))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "EntrySwitch was asked outside a read-committed transaction"
    }

    def "a run's entries held together read who stopped each that is stopped and since when, and nothing of any other"() {
        given:
        store.entry(RUNNING_ENTRY, GROUP, "question", "Summarise")
        store.entry(LET_GO_ENTRY, GROUP, "question", "Confirm")
        store.stopped(ENTRY, FIRST_STEWARD)
        store.stopped(LET_GO_ENTRY, FIRST_STEWARD)
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid where entry_id = ?::uuid")
                .params(FIRST_STEWARD, LET_GO_ENTRY).update()
        def since = store.session.sql("select created_at from entry_stops where entry_id = ?::uuid")
                .params(ENTRY).query(OffsetDateTime).single().toInstant()

        when:
        def stops = store.transactions().execute {
            EntrySwitch.stopsOnceHeld(store.session, groupId(GROUP),
                    [entryId(ENTRY), entryId(RUNNING_ENTRY), entryId(LET_GO_ENTRY)])
        }

        then:
        stops == [(entryId(ENTRY)): new StopRecord(new SubjectId(UUID.fromString(FIRST_STEWARD)), since)]
    }

    def "a run's entries held together fail where the group holds one of them not, reading none"() {
        when:
        store.transactions().execute {
            EntrySwitch.stopsOnceHeld(store.session, groupId(OTHER_GROUP), [entryId(ENTRY)])
        }

        then:
        def failed = thrown(IllegalStateException)
        failed.message.startsWith("Group ${OTHER_GROUP} holds no entry among" as String)
    }

    def "a run's entries are held together only inside a read-committed transaction"() {
        when:
        EntrySwitch.stopsOnceHeld(store.session, groupId(GROUP), [entryId(ENTRY)])

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "EntrySwitch was asked outside a read-committed transaction"
    }

    def "an entry the group does not hold fails rather than being read as running"() {
        when:
        store.transactions().execute { EntrySwitch.stoppedOnceHeld(store.session, groupId(group), entryId(entry)) }

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Group ${group} holds no entry ${entry}" as String

        where:
        group       | entry
        OTHER_GROUP | ENTRY
        GROUP       | "00000006-0000-4000-8000-000000000c09"
    }
}
