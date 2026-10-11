package com.lilradish.lite.app.run

import static com.lilradish.lite.testutil.library.LibraryStore.attempting
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.codestep.CodeRuns
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.run.Ceiling
import com.lilradish.lite.domain.run.CeilingChangeId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunName
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.Blocking
import com.lilradish.lite.testutil.run.RunRows
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunChangesRaceIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001001"

    static final String ANN = "00000002-0000-4000-8000-000000001001"

    static final UserId ANN_USER = new UserId("001001")

    static final String CAT = "00000002-0000-4000-8000-000000001003"

    static final UserId CAT_USER = new UserId("001003")

    static final String VERSION = "00000007-0000-4000-8000-000000001001"

    static final String RUN = "00000008-0000-4000-8000-000000001001"

    static final String RAISE = "0000000b-0000-4000-8000-000000001001"

    static final RunId THE_RUN = new RunId(UUID.fromString(RUN))

    static final CeilingChangeId THE_RAISE = new CeilingChangeId(UUID.fromString(RAISE))

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunChanges changes

    EngineExecutor executor

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(1)

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def cleanup() {
        executor.destroy()
    }

    def setup() {
        store = LibraryStore.forCase(server, "run_races_" + (++databasesMade))
        def session = store.session
        def tree = new RunTree(session)
        def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([]))
        executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(session)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        def heldFiles = EngineWiring.files(store)
        def engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor, heldFiles),
                DeployedModels.HELD, ceilings, new GroupRoles(session), Deployments.DEEP_ENOUGH, heldFiles)
        changes = new RunChanges(session, store.transactions(), new GroupRoles(session), tree, snapshots, engine)
        store.person(ANN, "001001")
        store.person(CAT, "001003")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, CAT, "operator")
        RunRows.workflow(store, "00000006-0000-4000-8000-000000001001", VERSION, GROUP, "Handle a claim", 1000L, true)
        StepRows.askingSomebody(store, VERSION, GROUP)
        RunRows.run(store, RUN, GROUP, 1, "Claim from Ari", "00000006-0000-4000-8000-000000001001", VERSION, CAT)
    }

    private Connection inFlight(String... writes) {
        // DB-SPECIFIC: PostgreSQL FOR NO KEY UPDATE.
        store.holding((["select 1 from runs where run_id = '${RUN}' for no key update" as String] + writes.toList())
                as String[])
    }

    private Object afterLanding(Connection other, Closure act) {
        def asked = attempting(racing, act)
        Blocking.untilBlockedBy(store, other)
        other.commit()
        other.close()
        asked.get(10, TimeUnit.SECONDS)
    }


    def "a transaction opened earlier records its ceiling change after the one it waited to follow"() {
        given:
        def ready = new java.util.concurrent.CountDownLatch(1)
        def release = new java.util.concurrent.CountDownLatch(1)
        def waiting = attempting(racing) {
            changes.transactions.execute {
                // DB-SPECIFIC: PostgreSQL now().
                store.session.sql("select now()").query(java.time.OffsetDateTime).single()
                ready.countDown()
                assert release.await(10, TimeUnit.SECONDS)
                changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(800), ANN_USER)
            }
        }
        assert ready.await(10, TimeUnit.SECONDS)

        when:
        changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(900), CAT_USER)
        release.countDown()
        def later = waiting.get(10, TimeUnit.SECONDS)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def times = store.texts("""
                select (max(created_at) filter (where position = 1) <=
                        max(created_at) filter (where position = 2))::text
                  from run_ceiling_changes
                """)
        def current = new RunActs.NumberedRun(UUID.fromString(RUN), 1)
        def snapshot = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([])).asRead(groupId(GROUP), THE_RUN)
        def events = RunActs.read(store.session, current, current, UUID.fromString(GROUP), snapshot)
                .findAll { it.kind() == "ceiling_changed" }

        then:
        later == null
        changesOf() == ["1 1000>900 false -", "2 900>800 false -"]
        times == ["true"]
        events*.to() == ["900", "800"]
        events[0].at() <= events[1].at()
    }

    def "a decision made by an older transaction records when it actually followed the request"() {
        given:
        def ready = new java.util.concurrent.CountDownLatch(1)
        def release = new java.util.concurrent.CountDownLatch(1)
        def requested = new java.util.concurrent.atomic.AtomicReference<CeilingChangeId>()
        def waiting = attempting(racing) {
            changes.transactions.execute {
                // DB-SPECIFIC: PostgreSQL now().
                store.session.sql("select now()").query(java.time.OffsetDateTime).single()
                ready.countDown()
                assert release.await(10, TimeUnit.SECONDS)
                changes.approveRaise(groupId(GROUP), THE_RUN, requested.get(), ANN_USER)
            }
        }
        assert ready.await(10, TimeUnit.SECONDS)

        when:
        changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(1200), CAT_USER)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def id = store.texts("select run_ceiling_change_id::text from run_ceiling_changes where run_id = ?::uuid", RUN)[0]
        requested.set(new CeilingChangeId(UUID.fromString(id)))
        release.countDown()
        def later = waiting.get(10, TimeUnit.SECONDS)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def dates = store.texts("""
                select (decided_at > created_at)::text from run_ceiling_changes
                 where run_ceiling_change_id = ?::uuid
                """, id)
        def current = new RunActs.NumberedRun(UUID.fromString(RUN), 1)
        def snapshot = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([])).asRead(groupId(GROUP), THE_RUN)
        def events = RunActs.read(store.session, current, current, UUID.fromString(GROUP), snapshot)
                .findAll { it.kind().startsWith("ceiling_") }

        then:
        later == null
        dates == ["true"]
        events*.kind() == ["ceiling_asked", "ceiling_approved"]
        events[0].at() < events[1].at()
    }

    def "lowering a ceiling that stops a run dates the stop after the ceiling change"() {
        given:
        RunRows.called(store, RUN, "came_back", 900, 50)

        when:
        changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(950), CAT_USER)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def timestamps = store.texts("""
                select (change.created_at <= stop.created_at)::text
                  from run_ceiling_changes change join run_stops stop on stop.ceiling_run_id = change.run_id
                 where change.run_id = ?::uuid
                """, RUN)
        def current = new RunActs.NumberedRun(UUID.fromString(RUN), 1)
        def snapshot = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([])).asRead(groupId(GROUP), THE_RUN)
        def events = RunActs.read(store.session, current, current, UUID.fromString(GROUP), snapshot)
                .findAll { it.kind() in ["ceiling_changed", "stopped_by_ceiling"] }

        then:
        timestamps == ["true"]
        events*.kind() == ["ceiling_changed", "stopped_by_ceiling"]
        events[0].at() <= events[1].at()
        events[1].by() == null
    }

    private List<String> changesOf() {
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("""
                select position || ' ' || coalesce(from_ceiling::text, '-') || '>' || coalesce(to_ceiling::text, '-')
                       || ' ' || awaits_approval || ' ' || coalesce(outcome::text, '-')
                  from run_ceiling_changes order by position
                """)
    }

    def "a ceiling change asked while a stop is in flight waits for it, and both land"() {
        given:
        def stopping = inFlight("insert into run_stops (run_id, root_run_id, created_by) values ('${RUN}', '${RUN}', '${ANN}')")

        when:
        def changed = afterLanding(stopping) { changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(500), CAT_USER) }

        then:
        changed == null
        changesOf() == ["1 1000>500 false -"]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select created_by::text from run_stops where opened_again_at is null") == [ANN]
    }

    // Read the stop only after acquiring the tree lock to avoid writing a duplicate.
    def "a stop asked while another is in flight finds the run stopped, and records nothing of its own"() {
        given:
        def stopping = inFlight("insert into run_stops (run_id, root_run_id, created_by) values ('${RUN}', '${RUN}', '${ANN}')")

        when:
        def stopped = afterLanding(stopping) { changes.stop(groupId(GROUP), THE_RUN, CAT_USER) }

        then:
        stopped == null
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select created_by::text from run_stops") == [ANN]
    }

    // Read the stop only after acquiring the tree lock so a concurrent reopening is observed.
    def "a stop asked while an opening again is in flight stops the run it opened"() {
        given:
        RunRows.stopped(store, RUN, ANN)
        // DB-SPECIFIC: PostgreSQL now().
        def opening = inFlight("update run_stops set opened_again_at = now(), opened_again_by = '${ANN}' where run_id = '${RUN}'")

        when:
        def stopped = afterLanding(opening) { changes.stop(groupId(GROUP), THE_RUN, CAT_USER) }

        then:
        stopped == null
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select created_by::text from run_stops where opened_again_at is null") == [CAT]
        store.count("select count(*) from run_stops") == 2
    }

    def "a ceiling change asked while another is in flight is judged against the ceiling that one put in force"() {
        given:
        def lowering = inFlight("""
                insert into run_ceiling_changes (run_id, position, from_ceiling, to_ceiling, awaits_approval, created_by)
                values ('${RUN}', 1, 1000, 500, false, '${ANN}')
                """ as String)

        when:
        def changed = afterLanding(lowering) { changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(800), CAT_USER) }

        then:
        changed == null
        changesOf() == ["1 1000>500 false -", "2 500>800 true -"]
    }

    // Assign the change's position after acquiring the tree lock to avoid duplicate positions.
    def "ceiling changes asked while others are in flight each take the place after the one they waited on"() {
        given:
        def first = inFlight("""
                insert into run_ceiling_changes (run_id, position, from_ceiling, to_ceiling, awaits_approval, created_by)
                values ('${RUN}', 1, 1000, 900, false, '${ANN}')
                """ as String)

        when:
        def second = afterLanding(first) { changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(700), CAT_USER) }
        def third = changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(600), ANN_USER)

        then:
        second == null
        third == null
        changesOf() == ["1 1000>900 false -", "2 900>700 false -", "3 700>600 false -"]
    }

    // Recheck running after acquiring the tree lock to avoid stopping twice.
    def "a ceiling lowered under what was spent while the run is being stopped at its ceiling waits for it, and the run is stopped once"() {
        given:
        RunRows.called(store, RUN, "came_back", 900, 50)
        def stopping = inFlight("""
                insert into run_stops (run_id, root_run_id, ceiling_run_id, created_by, created_by_kind)
                values ('${RUN}', '${RUN}', '${RUN}', '${RunRows.WORKFLOW_RUNNER}', 'system')
                """ as String)

        when:
        def changed = afterLanding(stopping) { changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(1), CAT_USER) }

        then:
        changed == null
        changesOf() == ["1 1000>1 false -"]
        RunRows.stops(store) == ["system ${RUN} 0" as String]
    }

    // Measure spent calls after acquiring the tree lock so concurrent writes are counted.
    def "a ceiling lowered while a call is being written down waits for it and counts it: #to"() {
        given:
        // DB-SPECIFIC: PostgreSQL now().
        def calling = inFlight("""
                insert into model_calls (run_id, root_run_id, purpose, root_version_id, request, model, mode,
                                         envelope_version, sent_count, came_back_count, outcome, answer, ended_at,
                                         created_by)
                values ('${RUN}', '${RUN}', 'help', '${VERSION}', '{}', 'general', 'ordinary', 1, 900, 50, 'came_back',
                        'Said.', now(), '${RunRows.WORKFLOW_RUNNER}')
                """ as String)

        when:
        def changed = afterLanding(calling) { changes.changeCeiling(groupId(GROUP), THE_RUN, THE_RUN, new Ceiling(to), CAT_USER) }

        then:
        changed == null
        changesOf() == ["1 1000>${to} false -" as String]
        RunRows.stops(store) == (stopped ? ["system ${RUN} 0" as String] : [])

        where:
        to  || stopped
        950 || true
        951 || false
    }

    // Recheck the waiting raise after acquiring the tree lock so withdrawal is observed.
    def "an approval asked while its raise is being withdrawn is refused as no raise waiting, changing nothing more"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        // DB-SPECIFIC: PostgreSQL now().
        def withdrawing = inFlight("""
                update run_ceiling_changes set outcome = 'withdrawn_by_requester', decided_at = now(), decided_by = '${CAT}'
                 where run_ceiling_change_id = '${RAISE}'
                """ as String)

        when:
        def approved = afterLanding(withdrawing) {
            changes.approveRaise(groupId(GROUP), THE_RUN, THE_RAISE, ANN_USER)
        }

        then:
        approved instanceof ApiErrorException
        approved.errorCode() == RefusalCode.CEILING_RAISE_NOT_WAITING
        // DB-SPECIFIC: PostgreSQL || converts enum and uuid operands to text.
        store.texts("select outcome || ' ' || decided_by from run_ceiling_changes") == ["withdrawn_by_requester ${CAT}" as String]
    }

    def "a withdrawal asked while its raise is being approved is refused as no raise waiting, changing nothing more"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        // DB-SPECIFIC: PostgreSQL now().
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
        // DB-SPECIFIC: PostgreSQL || converts enum and uuid operands to text.
        store.texts("select outcome || ' ' || decided_by from run_ceiling_changes") == ["approved ${ANN}" as String]
    }

    // Read the caller's current roles after acquiring the tree lock.
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

    // The group's share lock must prevent membership changes while the act writes its author.
    def "an act holds the group's row until it lands, so no change of membership lands in the middle of it"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        if (act == "openAgain") {
            RunRows.stopped(store, RUN, ANN)
        }
        // DB-SPECIFIC: PostgreSQL SELECT FOR UPDATE.
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
                // DB-SPECIFIC: PostgreSQL FOR NO KEY UPDATE with NOWAIT.
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
                return changes.rename(group, THE_RUN, new RunName("Claim from Ari, again"), caller)
            case "changeCeiling":
                return changes.changeCeiling(group, THE_RUN, THE_RUN, new Ceiling(500), caller)
            case ["approveRaise", "refuseRaise", "withdrawRaise"]:
                return changes."${act}"(group, THE_RUN, THE_RAISE, caller)
            default:
                return changes."${act}"(group, THE_RUN, caller)
        }
    }
}
