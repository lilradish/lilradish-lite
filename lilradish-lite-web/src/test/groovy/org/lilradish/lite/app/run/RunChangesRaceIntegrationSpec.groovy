package org.lilradish.lite.app.run

import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.run.CeilingChangeId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.Blocking
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Two acts on one tree at once: the one in flight holds the root, then writes, and is left open; the act asked
 * meanwhile waits on the tree and decides only on what it reads once it holds it.
 */
class RunChangesRaceIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001001"

    /** An overseer: may approve a raise. */
    static final String ANN = "00000002-0000-4000-8000-000000001001"

    static final UserId ANN_USER = new UserId("001001")

    /** An operator: started the run, and may ask for a raise of its ceiling. */
    static final String CAT = "00000002-0000-4000-8000-000000001003"

    static final UserId CAT_USER = new UserId("001003")

    /** Declares a ceiling of a thousand, and that a raise of it waits on approval. */
    static final String VERSION = "00000007-0000-4000-8000-000000001001"

    static final String RUN = "00000008-0000-4000-8000-000000001001"

    static final String RAISE = "0000000b-0000-4000-8000-000000001001"

    static final RunId THE_RUN = new RunId(UUID.fromString(RUN))

    static final CeilingChangeId THE_RAISE = new CeilingChangeId(UUID.fromString(RAISE))

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunChanges changes

    EngineExecutor executor

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(1)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def cleanup() {
        executor.destroy()
    }

    def setup() {
        store = LibraryStore.copied(server, "run_races_" + (++databasesMade))
        def session = store.session
        def tree = new RunTree(session)
        def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE)
        executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(session)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        def engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(session))
        changes = new RunChanges(session, store.transactions(), new GroupRoles(session), tree, snapshots, engine)
        store.person(ANN, "001001")
        store.person(CAT, "001003")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, CAT, "operator")
        RunRows.workflow(store, "00000006-0000-4000-8000-000000001001", VERSION, GROUP, "Handle a claim", 1000L, true)
        StepRows.askingSomebody(store, VERSION, GROUP)
        RunRows.run(store, RUN, GROUP, 1, "Claim from Ada", "00000006-0000-4000-8000-000000001001", VERSION, CAT)
    }

    /** An act on the tree in flight: the root held as every act holds it, then {@code writes}, left uncommitted. */
    private Connection inFlight(String... writes) {
        store.holding((["select 1 from runs where run_id = '${RUN}' for no key update" as String] + writes.toList())
                as String[])
    }

    /** The act asked while the other is in flight, once it is seen waiting on the other and the other has landed. */
    private Object afterLanding(Connection other, Closure act) {
        def asked = attempting(racing, act)
        Blocking.untilBlockedBy(store, other)
        other.commit()
        other.close()
        asked.get(10, TimeUnit.SECONDS)
    }


    private List<String> changesOf() {
        store.texts("""
                select position || ' ' || coalesce(from_ceiling::text, '-') || '>' || coalesce(to_ceiling::text, '-')
                       || ' ' || awaits_approval || ' ' || coalesce(outcome::text, '-')
                  from run_ceiling_changes order by position
                """)
    }

    /** Neither decides on what the other writes; the one asked second waits, then lands beside it. */
    def "a ceiling change asked while a stop is in flight waits for it, and both land"() {
        given:
        def stopping = inFlight("insert into run_stops (run_id, root_run_id, created_by) values ('${RUN}', '${RUN}', '${ANN}')")

        when:
        def changed = afterLanding(stopping) { changes.changeCeiling(groupId(GROUP), THE_RUN, new Ceiling(500), CAT_USER) }

        then:
        changed == null
        changesOf() == ["1 1000>500 false -"]
        store.texts("select created_by::text from run_stops where opened_again_at is null") == [ANN]
    }

    /** Decided before the tree was held, the second would find no stop and write one the store then refuses. */
    def "a stop asked while another is in flight finds the run stopped, and records nothing of its own"() {
        given:
        def stopping = inFlight("insert into run_stops (run_id, root_run_id, created_by) values ('${RUN}', '${RUN}', '${ANN}')")

        when:
        def stopped = afterLanding(stopping) { changes.stop(groupId(GROUP), THE_RUN, CAT_USER) }

        then:
        stopped == null
        store.texts("select created_by::text from run_stops") == [ANN]
    }

    /** Decided before the tree was held, the stop would find the run stopped already, and the run would run on. */
    def "a stop asked while an opening again is in flight stops the run it opened"() {
        given:
        RunRows.stopped(store, RUN, ANN)
        def opening = inFlight("update run_stops set opened_again_at = now(), opened_again_by = '${ANN}' where run_id = '${RUN}'")

        when:
        def stopped = afterLanding(opening) { changes.stop(groupId(GROUP), THE_RUN, CAT_USER) }

        then:
        stopped == null
        store.texts("select created_by::text from run_stops where opened_again_at is null") == [CAT]
        store.count("select count(*) from run_stops") == 2
    }

    /**
     * The ceiling in flight lowers it to five hundred. Judged against the thousand before it, eight hundred would be
     * a lowering held at once; against five hundred it is a raise, and waits on approval.
     */
    def "a ceiling change asked while another is in flight is judged against the ceiling that one put in force"() {
        given:
        def lowering = inFlight("""
                insert into run_ceiling_changes (run_id, position, from_ceiling, to_ceiling, awaits_approval, created_by)
                values ('${RUN}', 1, 1000, 500, false, '${ANN}')
                """ as String)

        when:
        def changed = afterLanding(lowering) { changes.changeCeiling(groupId(GROUP), THE_RUN, new Ceiling(800), CAT_USER) }

        then:
        changed == null
        changesOf() == ["1 1000>500 false -", "2 500>800 true -"]
    }

    /**
     * Its place read before the tree was held, the change would take the one the change in flight took, and the
     * store refuse it; read after, it takes the next, and the two stand in the order they were made.
     */
    def "ceiling changes asked while others are in flight each take the place after the one they waited on"() {
        given:
        def first = inFlight("""
                insert into run_ceiling_changes (run_id, position, from_ceiling, to_ceiling, awaits_approval, created_by)
                values ('${RUN}', 1, 1000, 900, false, '${ANN}')
                """ as String)

        when:
        def second = afterLanding(first) { changes.changeCeiling(groupId(GROUP), THE_RUN, new Ceiling(700), CAT_USER) }
        def third = changes.changeCeiling(groupId(GROUP), THE_RUN, new Ceiling(600), ANN_USER)

        then:
        second == null
        third == null
        changesOf() == ["1 1000>900 false -", "2 900>700 false -", "3 700>600 false -"]
    }

    /** Found waiting before the tree was held, the approval would find it withdrawn only when writing, and fail. */
    def "an approval asked while its raise is being withdrawn is refused as no raise waiting, changing nothing more"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        def withdrawing = inFlight("""
                update run_ceiling_changes set outcome = 'withdrawn', decided_at = now(), decided_by = '${CAT}'
                 where run_ceiling_change_id = '${RAISE}'
                """ as String)

        when:
        def approved = afterLanding(withdrawing) {
            changes.approveRaise(groupId(GROUP), THE_RUN, THE_RAISE, ANN_USER)
        }

        then:
        approved instanceof ApiErrorException
        approved.errorCode() == RefusalCode.CEILING_RAISE_NOT_WAITING
        store.texts("select outcome || ' ' || decided_by from run_ceiling_changes") == ["withdrawn ${CAT}" as String]
    }

    /** The same the other way round: a withdrawal arriving after the approval landed finds nothing to withdraw. */
    def "a withdrawal asked while its raise is being approved is refused as no raise waiting, changing nothing more"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        def approving = inFlight("""
                update run_ceiling_changes set outcome = 'approved', decided_at = now(), decided_by = '${ANN}'
                 where run_ceiling_change_id = '${RAISE}'
                """ as String)

        when:
        def withdrawn = afterLanding(approving) {
            changes.withdrawRaise(groupId(GROUP), THE_RUN, THE_RAISE, CAT_USER)
        }

        then:
        withdrawn instanceof ApiErrorException
        withdrawn.errorCode() == RefusalCode.CEILING_RAISE_NOT_WAITING
        store.texts("select outcome || ' ' || decided_by from run_ceiling_changes") == ["approved ${ANN}" as String]
    }

    /**
     * The roles are taken while the act waits on the tree, by a change that never touches it; what the caller
     * holds is read only once the tree is held, and an act nothing then reaches is refused.
     */
    def "an act whose caller loses their roles while it waits on the tree is refused, changing nothing"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        def before = ["runs", "run_stops", "run_ceiling_changes"].collect { store.digestOf(it) }
        def holding = inFlight()

        when:
        def asked = attempting(racing) { acting(act, caller) }
        Blocking.untilBlockedBy(store, holding)
        def taking = store.takingRoles(GROUP, subject, left as String[])
        taking.commit()
        taking.close()
        holding.commit()
        holding.close()
        def outcome = asked.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == refusal
        ["runs", "run_stops", "run_ceiling_changes"].collect { store.digestOf(it) } == before

        where:
        act            | caller   | subject | left         || refusal
        "stop"         | CAT_USER | CAT     | []           || RefusalCode.GROUP_NOT_IN_VIEW
        "approveRaise" | ANN_USER | ANN     | ["operator"] || RefusalCode.ACT_NOT_PERMITTED
    }

    /**
     * The act is parked writing its author, whose subject row the spec holds for update; by then it holds the
     * group's row for share, and a change of membership, taking that row for no key update, would wait on it.
     */
    def "an act holds the group's row until it lands, so no change of membership lands in the middle of it"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        if (act == "openAgain") {
            RunRows.stopped(store, RUN, ANN)
        }
        def parked = store.holding("select 1 from subjects where subject_id = '${subject}' for update")

        when:
        def asked = attempting(racing) { acting(act, caller) }
        Blocking.untilBlockedBy(store, parked)
        def membershipWaited = !membershipMayChange()
        parked.commit()
        parked.close()
        def outcome = asked.get(10, TimeUnit.SECONDS)

        then:
        membershipWaited
        outcome == null

        and: "and the group's row let go of once the act has landed"
        membershipMayChange()

        where:
        act             | caller   | subject
        "stop"          | CAT_USER | CAT
        "openAgain"     | CAT_USER | CAT
        "rename"        | CAT_USER | CAT
        "changeCeiling" | CAT_USER | CAT
        "approveRaise"  | ANN_USER | ANN
        "refuseRaise"   | ANN_USER | ANN
        "withdrawRaise" | CAT_USER | CAT
    }

    private boolean membershipMayChange() {
        store.database.connection.withCloseable { connection ->
            try {
                connection.prepareStatement("select 1 from groups where group_id = '${GROUP}' for no key update nowait")
                        .withCloseable { it.executeQuery().close() }
                true
            } catch (SQLException refused) {
                assert refused.SQLState == "55P03"
                false
            }
        }
    }

    private Object acting(String act, UserId caller) {
        def group = groupId(GROUP)
        switch (act) {
            case "rename":
                return changes.rename(group, THE_RUN, new RunName("Claim from Ada, again"), caller)
            case "changeCeiling":
                return changes.changeCeiling(group, THE_RUN, new Ceiling(500), caller)
            case ["approveRaise", "refuseRaise", "withdrawRaise"]:
                return changes."${act}"(group, THE_RUN, THE_RAISE, caller)
            default:
                return changes."${act}"(group, THE_RUN, caller)
        }
    }
}
