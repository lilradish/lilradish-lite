package org.lilradish.lite.app.run

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.run.CeilingChangeId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.Blocking
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunChangesIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000f01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000f02"

    static final String ANN = "00000002-0000-4000-8000-000000000f01"

    static final UserId ANN_USER = new UserId("000f01")

    static final String CAT = "00000002-0000-4000-8000-000000000f03"

    static final UserId CAT_USER = new UserId("000f03")

    static final UserId DAN_USER = new UserId("000f04")

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000f01"

    static final String VERSION = "00000007-0000-4000-8000-000000000f01"

    static final String APPROVED_WORKFLOW = "00000006-0000-4000-8000-000000000f02"

    static final String APPROVED_VERSION = "00000007-0000-4000-8000-000000000f02"

    static final String OTHER_WORKFLOW = "00000006-0000-4000-8000-000000000f03"

    static final String OTHER_VERSION = "00000007-0000-4000-8000-000000000f03"

    static final String RUN = "00000008-0000-4000-8000-000000000f01"

    static final String ANNS_RUN = "00000008-0000-4000-8000-000000000f02"

    static final String BENEATH = "00000008-0000-4000-8000-000000000f03"

    static final String OTHER_GROUPS_RUN = "00000008-0000-4000-8000-000000000f04"

    static final String NOBODYS_RUN = "00000008-0000-4000-8000-000000000f09"

    static final String RAISE = "0000000b-0000-4000-8000-000000000f01"

    static final String DECIDED = "0000000b-0000-4000-8000-000000000f02"

    static final String ANNS_RAISE = "0000000b-0000-4000-8000-000000000f03"

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

    def setup() {
        store = LibraryStore.copied(server, "run_changes_" + (++databasesMade))
        executor = EngineExecutors.of(store.database)
        changes = changesOver(store.session, store.transactions())
        store.person(ANN, "000f01")
        store.person(CAT, "000f03")
        store.person("00000002-0000-4000-8000-000000000f04", "000f04")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, CAT, "operator")
        store.member(OTHER_GROUP, ANN, "owner")
        RunRows.workflow(store, WORKFLOW, VERSION, GROUP, "Handle a complaint", 1000L, false)
        RunRows.workflow(store, APPROVED_WORKFLOW, APPROVED_VERSION, GROUP, "Handle a claim", 1000L, true)
        RunRows.workflow(store, OTHER_WORKFLOW, OTHER_VERSION, OTHER_GROUP, "Handle a refund", null, false)
        StepRows.askingSomebody(store, VERSION, GROUP)
        StepRows.askingSomebody(store, APPROVED_VERSION, GROUP)
        StepRows.askingSomebody(store, OTHER_VERSION, OTHER_GROUP)
        RunRows.run(store, RUN, GROUP, 1, "Claim from Ari", APPROVED_WORKFLOW, APPROVED_VERSION, CAT)
        RunRows.run(store, ANNS_RUN, GROUP, 2, "Complaint from Greno", WORKFLOW, VERSION, ANN)
        RunRows.run(store, OTHER_GROUPS_RUN, OTHER_GROUP, 1, "Refund for Arlo", OTHER_WORKFLOW, OTHER_VERSION, ANN)
    }

    def cleanup() {
        executor.destroy()
    }

    private RunChanges changesOver(JdbcClient session, TransactionOperations transactions) {
        def tree = new RunTree(session)
        def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([]))
        def writes = new EngineWrites(session)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        def heldFiles = EngineWiring.files(store)
        def engine = new RunEngine(session, transactions, tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), new ScriptedModelCalls(), transactions, tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), transactions, tree, snapshots, writes, executor, heldFiles),
                DeployedModels.HELD, ceilings, new GroupRoles(session), Deployments.DEEP_ENOUGH, heldFiles)
        new RunChanges(session, transactions, new GroupRoles(session), tree, snapshots, engine)
    }

    private static RunId runId(String spelled) {
        new RunId(UUID.fromString(spelled))
    }

    private static CeilingChangeId changeId(String spelled) {
        new CeilingChangeId(UUID.fromString(spelled))
    }

    private List<String> runRows() {
        ["runs", "run_stops", "run_ceiling_changes"].collect { store.digestOf(it) }
    }

    private List<String> changesOf(String run) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select created_by || ' ' || coalesce(from_ceiling::text, '-') || '>' || coalesce(to_ceiling::text, '-')
                       || ' ' || awaits_approval || ' ' || coalesce(outcome::text, '-') || ' '
                       || coalesce(decided_by::text, '-')
                  from run_ceiling_changes where run_id = ?::uuid order by position
                """, run)
    }

    def "stops a run at once, as the caller's act, changing nothing else about it"() {
        given:
        def unchanged = ["runs", "run_ceiling_changes"].collect { store.digestOf(it) }

        when:
        changes.stop(groupId(GROUP), runId(RUN), CAT_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select run_id || ' ' || created_by || ' ' || created_by_kind || ' ' || coalesce(ceiling_run_id::text, '-')" +
                " || ' ' || coalesce(opened_again_at::text, '-') from run_stops") == ["${RUN} ${CAT} person - -" as String]

        and:
        ["runs", "run_ceiling_changes"].collect { store.digestOf(it) } == unchanged
    }

    def "stopping a run already stopped leaves it as it was stopped, by whoever stopped it"() {
        given:
        RunRows.stopped(store, RUN, FIRST_STEWARD)
        def before = runRows()

        when:
        changes.stop(groupId(GROUP), runId(RUN), ANN_USER)

        then:
        runRows() == before
    }

    def "stopping a run every step of which is done records nothing, there being nothing left to stop"() {
        given:
        RunRows.workflow(store, "00000006-0000-4000-8000-000000000f09", "00000007-0000-4000-8000-000000000f09", GROUP,
                "Acknowledge", null, false)
        RunRows.run(store, NOBODYS_RUN, GROUP, 9, "Done already", "00000006-0000-4000-8000-000000000f09",
                "00000007-0000-4000-8000-000000000f09", CAT)
        def before = runRows()

        when:
        changes.stop(groupId(GROUP), runId(NOBODYS_RUN), CAT_USER)

        then:
        runRows() == before
        store.count("select count(*) from run_stops") == 0
    }

    def "opens a stopped run again as the caller's act, and a later stop is a stop of its own, made after it"() {
        given:
        RunRows.stoppedByCeiling(store, RUN, RUN)

        when:
        changes.openAgain(groupId(GROUP), runId(RUN), ANN_USER)

        then:
        store.texts("select created_by_kind || ' ' || opened_again_by from run_stops where opened_again_at >= created_at") ==
                ["system ${ANN}" as String]
        store.count("select count(*) from run_stops where opened_again_at is null") == 0

        when:
        changes.stop(groupId(GROUP), runId(RUN), CAT_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select created_by::text from run_stops where opened_again_at is null") == [CAT]
        store.count("""
                select count(*) from run_stops later, run_stops earlier
                 where later.opened_again_at is null and later.created_at >= earlier.opened_again_at
                """) == 1
    }

    def "a run opened again goes on from wherever its steps are, the step it was stopped before asked"() {
        given:
        RunRows.stopped(store, RUN, CAT)

        when:
        changes.openAgain(groupId(GROUP), runId(RUN), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by_kind
                  from productions try where try.run_id = ?::uuid
                """, RUN) == ["1 person system"]

        and: "and no other run gone on with it"
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from productions where run_id <> ?::uuid", RUN) == 0
    }

    def "opening a run that is not stopped records nothing, and makes it go on no further"() {
        given:
        def before = runRows()

        when:
        changes.openAgain(groupId(GROUP), runId(RUN), ANN_USER)

        then:
        runRows() == before
        store.count("select count(*) from productions") == 0
    }

    // Read the stop after acquiring the tree lock, including stops committed while opening waited.
    def "a run opened by an act begun before the stop was made is opened, and no earlier than it was stopped"() {
        given:
        // DB-SPECIFIC: PostgreSQL FOR NO KEY UPDATE.
        def holding = store.holding("select 1 from runs where run_id = '${RUN}' for no key update")

        when:
        def opening = attempting(racing) { changes.openAgain(groupId(GROUP), runId(RUN), ANN_USER) }
        Blocking.untilBlockedBy(store, holding)
        holding.createStatement().withCloseable {
            it.execute("insert into run_stops (run_id, root_run_id, created_by) values ('${RUN}', '${RUN}', '${CAT}')")
        }
        holding.commit()
        holding.close()

        then:
        opening.get(10, TimeUnit.SECONDS) == null
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from run_stops where opened_again_at >= created_at and opened_again_by = ?::uuid",
                ANN) == 1
    }

    def "renames a run as the caller's act, stopped or not"() {
        given:
        if (stopped) {
            RunRows.stopped(store, RUN, ANN)
        }

        when:
        changes.rename(groupId(GROUP), runId(RUN), new RunName("Claim from Ari, again"), CAT_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.texts("select name || ' ' || updated_by from runs where run_id = ?::uuid", RUN) ==
                ["Claim from Ari, again ${CAT}" as String]
        store.count("select count(*) from runs where updated_at >= created_at") == 1

        where:
        stopped << [false, true]
    }

    def "renaming a run to the name it has records nothing"() {
        given:
        def before = runRows()

        when:
        changes.rename(groupId(GROUP), runId(RUN), new RunName("Claim from Ari"), CAT_USER)

        then:
        runRows() == before
    }

    def "a lowering, or a ceiling given where there was none, holds at once from the ceiling in force"() {
        given:
        if (from == null) {
            RunRows.ceilingChanged(store, DECIDED, RUN, 1000L, null, false, ANN, 10)
        }
        RunRows.called(store, RUN, "came_back", 900, 50)

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(to), CAT_USER)

        then:
        changesOf(RUN).last() == "${CAT} ${from ?: '-'}>${to} false - -" as String

        where:
        from | to
        1000 | 999
        1000 | 1
        null | 5000
    }

    def "a ceiling lowered, or given, to what the run spent stops the run at once for that ceiling alone: #from to #to"() {
        given:
        if (from == null) {
            RunRows.ceilingChanged(store, DECIDED, RUN, 1000L, null, false, ANN, 10)
        }
        RunRows.called(store, RUN, "came_back", 900, 50)
        def calls = store.digestOf("model_calls")

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(to), CAT_USER)

        then:
        changesOf(RUN).last() == "${CAT} ${from ?: '-'}>${to} false - -" as String
        RunRows.stops(store) == (stopped ? ["system ${RUN} 0" as String] : [])

        and: "no call touched either way"
        store.digestOf("model_calls") == calls

        where:
        from | to   || stopped
        1000 | 950  || true
        1000 | 951  || false
        null | 950  || true
        null | 951  || false
    }

    def "a ceiling beneath lowered from the top's address to what that run spent stops the top for that ceiling alone: #to"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_versions set keeps_own_ceiling = true where entry_version_id = ?::uuid")
                .param(VERSION).update()
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, WORKFLOW, VERSION, "00000009-0000-4000-8000-000000000f01",
                "0000000a-0000-4000-8000-000000000f01")
        RunRows.called(store, BENEATH, "came_back", 900, 50)
        RunRows.called(store, RUN, "came_back", 100, 0)

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(BENEATH), new Ceiling(to), CAT_USER)

        then:
        changesOf(BENEATH).last() == "${CAT} 1000>${to} false - -" as String
        changesOf(RUN) == []
        RunRows.stops(store) == (stopped ? ["system ${BENEATH} 0" as String] : [])

        where:
        to  || stopped
        950 || true
        951 || false
    }

    def "a ceiling lowered under what was spent on a run stopped already leaves the stop as it was"() {
        given:
        RunRows.stopped(store, RUN, ANN)
        RunRows.called(store, RUN, "came_back", 900, 50)
        def before = store.digestOf("run_stops")

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(1), CAT_USER)

        then:
        changesOf(RUN).last() == "${CAT} 1000>1 false - -" as String
        store.digestOf("run_stops") == before
        store.count("select count(*) from run_stop_additional_ceilings") == 0
    }

    def "a ceiling given under what was spent on a run every step of which is done stops nothing"() {
        given:
        RunRows.workflow(store, "00000006-0000-4000-8000-000000000f09", "00000007-0000-4000-8000-000000000f09", GROUP,
                "Acknowledge", null, false)
        RunRows.run(store, NOBODYS_RUN, GROUP, 9, "Done already", "00000006-0000-4000-8000-000000000f09",
                "00000007-0000-4000-8000-000000000f09", CAT)
        RunRows.called(store, NOBODYS_RUN, "came_back", 10, 0)

        when:
        changes.changeCeiling(groupId(GROUP), runId(NOBODYS_RUN), runId(NOBODYS_RUN), new Ceiling(1), CAT_USER)

        then:
        changesOf(NOBODYS_RUN) == ["${CAT} ->1 false - -" as String]
        RunRows.stops(store) == []
    }

    def "a ceiling lowered beneath to what that run spent stops the top only where that run is not done: done #done"() {
        given:
        def entry = done ? "00000006-0000-4000-8000-000000000f09" : WORKFLOW
        def version = done ? "00000007-0000-4000-8000-000000000f09" : VERSION
        if (done) {
            RunRows.workflow(store, entry, version, GROUP, "Acknowledge", 1000L, false, true)
        } else {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("update workflow_versions set keeps_own_ceiling = true where entry_version_id = ?::uuid")
                    .param(VERSION).update()
        }
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, entry, version, "00000009-0000-4000-8000-000000000f01",
                "0000000a-0000-4000-8000-000000000f01")
        RunRows.called(store, BENEATH, "came_back", 900, 50)

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(BENEATH), new Ceiling(950), CAT_USER)

        then:
        changesOf(BENEATH) == ["${CAT} 1000>950 false - -" as String]
        RunBudget.inForceAsRead(store.session, runId(BENEATH)).ceiling() == new Ceiling(950)
        RunRows.stops(store) == (done ? [] : ["system ${BENEATH} 0" as String])

        where:
        done << [true, false]
    }

    def "a change withdrawing a raise waiting stops the run only where it lowers the ceiling in force to what was spent: #to"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        RunRows.called(store, RUN, "came_back", spent, 0)

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(to), ANN_USER)

        then:
        changesOf(RUN) == ["${CAT} 1000>2000 true superseded_by_change ${ANN}" as String,
                           "${ANN} 1000>${to} ${awaits} - -" as String]
        RunRows.stops(store) == (stopped ? ["system ${RUN} 0" as String] : [])

        where:
        to   | spent || awaits | stopped
        950  | 950   || false  | true
        951  | 950   || false  | false
        1500 | 1500  || true   | false
    }

    def "a raise asked, a raise refused, a raise held at once and a taking away stop nothing: #run to #to"() {
        given:
        RunRows.called(store, run, "came_back", 1500, 0)

        when:
        changes.changeCeiling(groupId(GROUP), runId(run), runId(run), to == null ? null : new Ceiling(to), caller)
        if (refused) {
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
            def raise = store.texts("select run_ceiling_change_id::text from run_ceiling_changes where run_id = ?::uuid", run)[0]
            changes.refuseRaise(groupId(GROUP), runId(run), changeId(raise), ANN_USER)
        }

        then:
        changesOf(run) == ["${callerId} 1000>${to ?: '-'} ${awaits} ${refused ? 'refused' : '-'} ${refused ? ANN : '-'}" as String]
        RunRows.stops(store) == []

        where:
        run      | to   | refused | caller   | callerId || awaits
        RUN      | 1200 | false   | CAT_USER | CAT      || true
        RUN      | 1200 | true    | CAT_USER | CAT      || true
        ANNS_RUN | 1200 | false   | ANN_USER | ANN      || false
        ANNS_RUN | null | false   | ANN_USER | ANN      || false
    }

    def "a raise or a taking away waits on approval where the version asks it, and holds at once where it does not"() {
        when:
        changes.changeCeiling(groupId(GROUP), runId(run), runId(run), to == null ? null : new Ceiling(to), ANN_USER)

        then:
        changesOf(run) == ["${ANN} 1000>${to ?: '-'} ${awaits} - -" as String]

        where:
        run      | to   || awaits
        RUN      | 1001 || true
        RUN      | null || true
        ANNS_RUN | 1001 || false
        ANNS_RUN | null || false
    }

    def "asking for the ceiling in force records nothing, and leaves a raise waiting as it was"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        def before = runRows()

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(1000), ANN_USER)

        then:
        runRows() == before
    }

    def "any other change withdraws a raise waiting, recording who did, and starts from the ceiling in force"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), to == null ? null : new Ceiling(to), ANN_USER)

        then:
        changesOf(RUN) == ["${CAT} 1000>2000 true superseded_by_change ${ANN}" as String,
                           "${ANN} 1000>${to ?: '-'} ${awaits} - -" as String]

        where:
        to   || awaits
        500  || false
        3000 || true
        null || true
    }

    def "the ceiling a change starts from is the one made last of those in force, an approved raise among them"() {
        given:
        RunRows.ceilingChanged(store, "0000000b-0000-4000-8000-000000000f11", RUN, 1000L, 800L, false, ANN, 1)
        RunRows.ceilingChanged(store, "0000000b-0000-4000-8000-000000000f12", RUN, 800L, 1500L, true, CAT, 30)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update run_ceiling_changes set outcome = 'approved', decided_by = ?::uuid, decided_at = now()
                 where run_ceiling_change_id = '0000000b-0000-4000-8000-000000000f12'
                """).params(ANN).update()
        RunRows.ceilingChanged(store, "0000000b-0000-4000-8000-000000000f13", RUN, 1500L, 9000L, true, CAT, 20)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update run_ceiling_changes set outcome = 'refused', decided_by = ?::uuid, decided_at = now()
                 where run_ceiling_change_id = '0000000b-0000-4000-8000-000000000f13'
                """).params(ANN).update()
        RunRows.ceilingChanged(store, "0000000b-0000-4000-8000-000000000f14", RUN, 1500L, 3000L, true, CAT, 15)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update run_ceiling_changes set outcome = 'superseded_by_change', decided_by = ?::uuid, decided_at = now()
                 where run_ceiling_change_id = '0000000b-0000-4000-8000-000000000f14'
                """).params(CAT).update()

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(1400), ANN_USER)

        then:
        changesOf(RUN).last() == "${ANN} 1500>1400 false - -" as String
    }

    // Order changes by their assigned position, not by their timestamp.
    def "a change takes the place after every change of its run, whatever the times say"() {
        given:
        RunRows.ceilingChanged(store, DECIDED, RUN, 1000L, 900L, false, ANN, -60)
        RunRows.ceilingChanged(store, "0000000b-0000-4000-8000-000000000f15", ANNS_RUN, 1000L, 700L, false, ANN, 1)

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(800), CAT_USER)
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(850), CAT_USER)

        then: "the second judged against the first, though the one before both is dated after them"
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select position || ' ' || coalesce(from_ceiling::text, '-') || '>' || to_ceiling
                  from run_ceiling_changes where run_id = ?::uuid order by position
                """, RUN) == ["1 1000>900", "2 900>800", "3 800>850"]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select position::text from run_ceiling_changes where run_id = ?::uuid", ANNS_RUN) == ["1"]
    }

    def "a new ceiling event follows a legal later decision on an older position"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 120)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, clock_timestamp().
        store.session.sql("""
                update run_ceiling_changes set outcome = 'approved', decided_by = ?::uuid,
                                               decided_at = clock_timestamp() + interval '1 hour'
                 where run_ceiling_change_id = ?::uuid
                """).params(ANN, RAISE).update()
        RunRows.ceilingChanged(store, DECIDED, RUN, 2000L, 900L, false, ANN, 60)

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(800), CAT_USER)

        then:
        changesOf(RUN).last() == "${CAT} 900>800 false - -" as String
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select (max(created_at) filter (where position = 3) >=
                        max(decided_at) filter (where position = 1))::text
                  from run_ceiling_changes where run_id = ?::uuid
                """, RUN) == ["true"]
    }

    def "deciding a raise some change of the run was made after is refused as no raise waiting, changing nothing"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 10)
        RunRows.ceilingChanged(store, DECIDED, RUN, 1000L, 900L, false, ANN, 5)
        def before = runRows()

        when:
        changes."${act}"(groupId(GROUP), runId(RUN), changeId(RAISE), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CEILING_RAISE_NOT_WAITING
        runRows() == before

        where:
        act             | caller
        "approveRaise"  | ANN_USER
        "refuseRaise"   | ANN_USER
        "withdrawRaise" | CAT_USER
    }

    def "approving a raise puts it in force as the approver's act, and a stopped run stays stopped"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        RunRows.stoppedByCeiling(store, RUN, RUN)
        def stops = store.digestOf("run_stops")

        when:
        changes.approveRaise(groupId(GROUP), runId(RUN), changeId(RAISE), ANN_USER)

        then:
        changesOf(RUN) == ["${CAT} 1000>2000 true approved ${ANN}" as String]
        store.count("select count(*) from run_ceiling_changes where decided_at >= created_at") == 1

        and: "the next change starting from what it put in force, and the stop left as it was"
        store.digestOf("run_stops") == stops

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(1500), ANN_USER)

        then:
        changesOf(RUN).last() == "${ANN} 2000>1500 false - -" as String
    }

    def "refusing a raise, or its asker withdrawing it, leaves the ceiling that was in force"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)

        when:
        changes."${act}"(groupId(GROUP), runId(RUN), changeId(RAISE), caller)

        then:
        changesOf(RUN) == ["${CAT} 1000>2000 true ${outcome} ${decider}" as String]
        store.count("select count(*) from run_ceiling_changes where decided_at >= created_at") == 1

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(RUN), new Ceiling(900), ANN_USER)

        then:
        changesOf(RUN).last() == "${ANN} 1000>900 false - -" as String
        changesOf(RUN).first() == "${CAT} 1000>2000 true ${outcome} ${decider}" as String

        where:
        act             | caller   || outcome     | decider
        "refuseRaise"   | ANN_USER || "refused"   | ANN
        "withdrawRaise" | CAT_USER || "withdrawn_by_requester" | CAT
    }

    def "a raise waiting on a stopped run is decided as on one running, and the run stays as it was stopped"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 5)
        RunRows.stopped(store, RUN, CAT)
        def stops = store.digestOf("run_stops")

        when:
        changes."${act}"(groupId(GROUP), runId(RUN), changeId(RAISE), ANN_USER)

        then:
        changesOf(RUN) == ["${CAT} 1000>2000 true ${outcome} ${ANN}" as String]
        store.digestOf("run_stops") == stops

        where:
        act           || outcome
        "approveRaise" || "approved"
        "refuseRaise"  || "refused"
    }

    def "a raise decided by whoever asked for it, or withdrawn by anybody else, is refused, changing nothing"() {
        given:
        RunRows.ceilingChanged(store, ANNS_RAISE, RUN, 1000L, 2000L, true, ANN, 5)
        def before = runRows()

        when:
        changes."${act}"(groupId(GROUP), runId(RUN), changeId(ANNS_RAISE), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == refusal
        runRows() == before

        where:
        act             | caller   || refusal
        "approveRaise"  | ANN_USER || RefusalCode.CEILING_RAISE_ASKED_BY_CALLER
        "refuseRaise"   | ANN_USER || RefusalCode.CEILING_RAISE_ASKED_BY_CALLER
        "withdrawRaise" | CAT_USER || RefusalCode.CEILING_RAISE_ASKED_BY_ANOTHER
    }

    def "deciding a raise that is not this run's waiting raise is refused, changing nothing"() {
        given:
        RunRows.ceilingChanged(store, DECIDED, RUN, 1000L, 3000L, true, CAT, 10)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_ceiling_changes set outcome = 'refused', decided_by = ?::uuid, decided_at = now()")
                .params(ANN).update()
        RunRows.ceilingChanged(store, RAISE, ANNS_RUN, 1000L, 2000L, true, CAT, 5)
        def before = runRows()

        when:
        changes."${act}"(groupId(GROUP), runId(RUN), changeId(named), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CEILING_RAISE_NOT_WAITING
        runRows() == before

        where:
        [act, named, caller] << [["approveRaise", "refuseRaise"], [DECIDED, RAISE, NOBODYS_RUN], [ANN_USER]]
                .combinations() + [["withdrawRaise", DECIDED, CAT_USER], ["withdrawRaise", RAISE, CAT_USER]]
    }

    def "a root address changes a child that keeps its own ceiling and decides its raise"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_versions set keeps_own_ceiling = true, raise_needs_approval = true " +
                "where entry_version_id = ?::uuid").param(VERSION).update()
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, WORKFLOW, VERSION, "00000009-0000-4000-8000-000000000f01",
                "0000000a-0000-4000-8000-000000000f01")
        def rootBefore = changesOf(RUN)

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(BENEATH), new Ceiling(500), CAT_USER)

        then:
        changesOf(BENEATH).last() == "${CAT} 1000>500 false - -" as String
        changesOf(RUN) == rootBefore

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(BENEATH), new Ceiling(1500), CAT_USER)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def raised = store.texts("select run_ceiling_change_id::text from run_ceiling_changes " +
                "where run_id = ?::uuid and awaits_approval", BENEATH)[0]
        changes.approveRaise(groupId(GROUP), runId(RUN), changeId(raised), ANN_USER)

        then:
        changesOf(BENEATH).last() == "${CAT} 500>1500 true approved ${ANN}" as String
        changesOf(RUN) == rootBefore
        RunBudget.inForceAsRead(store.session, runId(BENEATH)).ceiling() == new Ceiling(1500)
    }

    def "a root address refuses a target outside its tree or without its own ceiling"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, WORKFLOW, VERSION, "00000009-0000-4000-8000-000000000f01",
                "0000000a-0000-4000-8000-000000000f01")
        def before = runRows()

        when:
        changes.changeCeiling(groupId(GROUP), runId(RUN), runId(target), new Ceiling(500), ANN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
        runRows() == before

        where:
        target << [BENEATH, ANNS_RUN]
    }

    def "a child raise is refused from a child address, another tree, or a child without its own ceiling"() {
        given:
        def otherTree = scenario == "otherTree"
        def childVersion = otherTree ? APPROVED_VERSION : VERSION
        if (scenario != "withoutOwn") {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("update workflow_versions set keeps_own_ceiling = true " +
                    "where entry_version_id = ?::uuid").param(childVersion).update()
        }
        def above = otherTree ? ANNS_RUN : RUN
        RunRows.beneath(store, BENEATH, above, GROUP, 3,
                otherTree ? APPROVED_WORKFLOW : WORKFLOW, childVersion,
                "00000009-0000-4000-8000-000000000f01", "0000000a-0000-4000-8000-000000000f01")
        RunRows.ceilingChanged(store, RAISE, BENEATH, 1000L, 2000L, true, CAT, 5)
        def before = runRows()

        when:
        changes."${act}"(groupId(GROUP), runId(scenario == "directChild" ? BENEATH : RUN),
                changeId(RAISE), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == expected
        runRows() == before
        RunBudget.inForceAsRead(store.session, runId(BENEATH)).ceiling() == new Ceiling(1000)

        where:
        scenario      | act             | caller   || expected
        "directChild" | "approveRaise"  | ANN_USER || RefusalCode.RUN_BENEATH_ANOTHER
        "directChild" | "refuseRaise"   | ANN_USER || RefusalCode.RUN_BENEATH_ANOTHER
        "directChild" | "withdrawRaise" | CAT_USER || RefusalCode.RUN_BENEATH_ANOTHER
        "otherTree"   | "approveRaise"  | ANN_USER || RefusalCode.CEILING_RAISE_NOT_WAITING
        "otherTree"   | "refuseRaise"   | ANN_USER || RefusalCode.CEILING_RAISE_NOT_WAITING
        "otherTree"   | "withdrawRaise" | CAT_USER || RefusalCode.CEILING_RAISE_NOT_WAITING
        "withoutOwn"  | "approveRaise"  | ANN_USER || RefusalCode.CEILING_RAISE_NOT_WAITING
        "withoutOwn"  | "refuseRaise"   | ANN_USER || RefusalCode.CEILING_RAISE_NOT_WAITING
        "withoutOwn"  | "withdrawRaise" | CAT_USER || RefusalCode.CEILING_RAISE_NOT_WAITING
    }

    def "an act on a run beneath another is refused, changing nothing"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, WORKFLOW, VERSION, "00000009-0000-4000-8000-000000000f01",
                "0000000a-0000-4000-8000-000000000f01")
        def before = runRows()

        when:
        attempt(act, CAT_USER, BENEATH)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.RUN_BENEATH_ANOTHER
        runRows() == before

        where:
        act << ["stop", "openAgain", "rename", "changeCeiling"]
    }

    // Use the same refusal for missing, foreign, and unreadable runs to avoid revealing their existence.
    def "an act naming no run the caller may read in the group is refused as no run, changing nothing"() {
        given:
        def before = runRows()

        when:
        attempt(act, CAT_USER, named)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
        refused.message == "That run is not in view."
        runRows() == before

        where:
        [act, named] << [["stop", "openAgain", "rename", "changeCeiling", "withdrawRaise"],
                         [ANNS_RUN, OTHER_GROUPS_RUN, NOBODYS_RUN]].combinations()
    }

    // Statement counts must not distinguish missing runs from runs the caller cannot read.
    def "an act on a run the caller may not read takes exactly the path of one nobody holds, and takes no lock"() {
        given:
        def asked = []
        def counting = new CountingDataSource(store.database, asked)
        def session = JdbcClient.create(counting)
        def counted = changesOver(session, store.transactions(counting))

        when:
        def paths = [ANNS_RUN, NOBODYS_RUN, RUN].collect { named ->
            asked.clear()
            try {
                counted.stop(groupId(GROUP), runId(named), CAT_USER)
            } catch (ApiErrorException refused) {
                assert refused.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
            }
            List.copyOf(asked)
        }

        then:
        paths[0] == paths[1]
        !paths[0].isEmpty()
        paths[0].every { !it.contains("for no key update") }

        and: "while a run the caller may read is locked, which is what the two above never were"
        paths[2].any { it.contains("for no key update") }
    }

    // Refuse an unreadable run without waiting for its tree lock to avoid revealing its existence.
    def "an act on a run the caller may not read is refused at once while somebody else holds its tree"() {
        given:
        // DB-SPECIFIC: PostgreSQL FOR NO KEY UPDATE.
        def holding = store.holding("select 1 from runs where run_id = '${ANNS_RUN}' for no key update")

        when:
        def outcome = attempting(racing) { changes.stop(groupId(GROUP), runId(ANNS_RUN), CAT_USER) }
                .get(5, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.RUN_NOT_IN_VIEW

        cleanup:
        holding.rollback()
        holding.close()
    }

    def "a run not the caller's own may be acted on by somebody who may read every run"() {
        when:
        changes.stop(groupId(GROUP), runId(RUN), ANN_USER)

        then:
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select created_by::text from run_stops where run_id = ?::uuid", RUN) == [ANN]
    }

    def "an act by somebody holding nothing in the group is refused as no group, whatever it names"() {
        given:
        def before = runRows()

        when:
        attempt(act, DAN_USER, named)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        runRows() == before

        where:
        [act, named] << [["stop", "approveRaise"], [RUN, NOBODYS_RUN]].combinations()
    }

    def "deciding a raise by a member whose roles do not reach approving is refused, changing nothing"() {
        given:
        RunRows.ceilingChanged(store, ANNS_RAISE, RUN, 1000L, 2000L, true, ANN, 5)
        def before = runRows()

        when:
        attempt(act, CAT_USER, RUN)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED
        runRows() == before

        where:
        act << ["approveRaise", "refuseRaise"]
    }

    private Object attempt(String act, UserId caller, String named) {
        def group = groupId(GROUP)
        def run = runId(named)
        switch (act) {
            case "rename":
                return changes.rename(group, run, new RunName("Renamed"), caller)
            case "changeCeiling":
                return changes.changeCeiling(group, run, run, new Ceiling(500), caller)
            case ["approveRaise", "refuseRaise", "withdrawRaise"]:
                return changes."${act}"(group, run, changeId(ANNS_RAISE), caller)
            default:
                return changes."${act}"(group, run, caller)
        }
    }
}
