package org.lilradish.lite.app.run

import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.Instant
import java.time.OffsetDateTime
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.pool.PersonRows
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPage
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.people.PersonName
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.domain.run.RunSortColumn
import org.lilradish.lite.domain.run.RunState
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** A group's runs as a member lists them, on a real server running the real baseline. */
class RunListIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001301"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001302"

    /** An overseer: reads every run. */
    static final String ANN = "00000002-0000-4000-8000-000000001301"

    static final UserId ANN_USER = new UserId("001301")

    /** An operator: reads the runs they started. */
    static final String CAT = "00000002-0000-4000-8000-000000001303"

    static final UserId CAT_USER = new UserId("001303")

    /** In no role here, and the owner of the other group. */
    static final String DAN = "00000002-0000-4000-8000-000000001304"

    static final UserId DAN_USER = new UserId("001304")

    /** Held with no name. */
    static final String EVE = "00000002-0000-4000-8000-000000001305"

    static final String HANDLE = "00000006-0000-4000-8000-000000001301"

    static final String HANDLE_VERSION = "00000007-0000-4000-8000-000000001301"

    static final String REFUND = "00000006-0000-4000-8000-000000001302"

    static final String REFUND_VERSION = "00000007-0000-4000-8000-000000001302"

    static final String ELSEWHERE = "00000006-0000-4000-8000-000000001303"

    static final String ELSEWHERE_VERSION = "00000007-0000-4000-8000-000000001303"

    /** Cat's, started first. */
    static final String FIRST = "00000008-0000-4000-8000-000000001301"

    /** Ann's, started second, with a run beneath it. */
    static final String SECOND = "00000008-0000-4000-8000-000000001302"

    /** Cat's, started last, and stopped by Ann. */
    static final String THIRD = "00000008-0000-4000-8000-000000001303"

    static final String BENEATH = "00000008-0000-4000-8000-000000001304"

    static final String OTHER_GROUPS_RUN = "00000008-0000-4000-8000-000000001305"

    static final String FOURTH = "00000008-0000-4000-8000-000000001306"

    static final String CHANGE = "00000009-0000-4000-8000-000000001301"

    /** The step every run of Handle a claim asks first. */
    static final String ASK_HANDLE = "00000009-0000-4000-8000-000000001311"

    /** The step every run of Pay a refund asks first. */
    static final String ASK_REFUND = "00000009-0000-4000-8000-000000001312"

    /** What each of those first steps is called. */
    static final StepId ASK = new StepId("ask")

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunList runList

    /** The key of what Handle a claim's first step gives back. */
    String answer

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_list_" + (++databasesMade))
        runList = new RunList(store.session, new GroupRoles(store.session), store.transactionManager(),
                new RunSnapshots(store.session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE))
        store.person(ANN, "001301", "Ann Example")
        store.person(CAT, "001303", "Cat Example")
        store.person(DAN, "001304")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, CAT, "operator")
        store.member(OTHER_GROUP, DAN, "owner")
        RunRows.workflow(store, HANDLE, HANDLE_VERSION, GROUP, "Handle a claim", null, false)
        RunRows.workflow(store, REFUND, REFUND_VERSION, GROUP, "Pay a refund", null, false)
        RunRows.workflow(store, ELSEWHERE, ELSEWHERE_VERSION, OTHER_GROUP, "Handle a claim", null, false)
        answer = StepRows.askingSomebody(store, HANDLE_VERSION, GROUP, ASK_HANDLE)
        StepRows.askingSomebody(store, REFUND_VERSION, GROUP, ASK_REFUND)
        started(FIRST, 1, "Claim from Ada", HANDLE, HANDLE_VERSION, CAT, 30)
        started(SECOND, 2, "Refund for Grace", REFUND, REFUND_VERSION, ANN, 20)
        started(THIRD, 3, "Claim from Alan", HANDLE, HANDLE_VERSION, CAT, 10)
        RunRows.stopped(store, THIRD, ANN)
        RunRows.beneath(store, BENEATH, SECOND, GROUP, 4, HANDLE, HANDLE_VERSION,
                "0000000a-0000-4000-8000-000000001301", "0000000c-0000-4000-8000-000000001301")
        RunRows.run(store, OTHER_GROUPS_RUN, OTHER_GROUP, 1, "Claim from Ada", ELSEWHERE, ELSEWHERE_VERSION, DAN)
    }

    private void started(String run, int number, String name, String entry, String version, String starter,
                         int minutesAgo) {
        RunRows.run(store, run, GROUP, number, name, entry, version, starter)
        store.session.sql("update runs set created_at = now() - make_interval(mins => ?) where run_id = ?::uuid")
                .params(minutesAgo, run).update()
    }

    private ListPage<RunList.RunRow> listed(RunList.Reading reading, ListOrder<RunSortColumn> order,
                                            ListFilter filter = null) {
        runList.page(reading, new ListQuery<>(reading.scope(), order, filter), null)
    }

    private ListPage<RunList.RunRow> listedByAnn(ListOrder<RunSortColumn> order, ListFilter filter = null) {
        listed(runList.readingOf(groupId(GROUP), ANN_USER), order, filter)
    }

    private Instant instant(String query, String run) {
        store.session.sql(query).param(run).query(OffsetDateTime).single().toInstant()
    }

    private static ListOrder<RunSortColumn> newestFirst() {
        new ListOrder<>(RunSortColumn.STARTED, true)
    }

    private static long microsecondsOf(Instant at) {
        at.epochSecond * 1_000_000L + at.nano.intdiv(1000)
    }

    /** Asked of the person it is theirs to answer, as the engine asks once a run starts. */
    private void driven(String run) {
        def executor = EngineExecutors.of(store.database)
        try {
            def tree = new RunTree(store.session)
            def writes = new EngineWrites(store.session)
            def none = org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE
            def snapshots = new RunSnapshots(store.session, none)
            def ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
            new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                    new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                    new EngineCodes(new org.lilradish.lite.app.codestep.CodeRuns(none), store.transactions(), tree,
                            snapshots, writes, executor),
                    DeployedModels.HELD, ceilings, new GroupRoles(store.session))
                    .drive(groupId(GROUP), new RunId(UUID.fromString(run)))
        } finally {
            executor.destroy()
        }
    }

    private void answeredFirst() {
        StepRows.answered(store, ASK_HANDLE, CAT, "Nothing to add.", [(answer): '"Fine."'])
    }

    /** A model could not be sent the answer to review, which fails the step. */
    private void failedFirst() {
        store.session.sql("""
                update workflow_steps set reviewer_model = 'general', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).params(ASK_HANDLE).update()
        driven(FIRST)
        answeredFirst()
        store.session.sql("""
                insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model, reason, production_id,
                                               purpose, detail, created_by)
                select step.run_step_id, step.run_id, step.kind, step.calls_a_model, 'model_not_deployed',
                       try.production_id, 'review', 'Not deployed.', ?::uuid
                  from productions try join run_steps step on step.run_step_id = try.run_step_id
                 where try.run_id = ?::uuid
                """).params(RunRows.WORKFLOW_RUNNER, FIRST).update()
    }

    def "an overseer reads every run of the group and an operator only the ones they started"() {
        when:
        def page = listed(runList.readingOf(groupId(GROUP), reader), newestFirst())

        then:
        page.rows()*.listed()*.number() == numbers

        where:
        reader    || numbers
        ANN_USER  || [3, 2, 1]
        CAT_USER  || [3, 1]
    }

    def "somebody holding nothing in the group is refused as the group is"() {
        when:
        runList.readingOf(groupId(GROUP), DAN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
    }

    def "a reading says which runs it reaches, and is read within the group or within the reader there"() {
        when:
        def reading = RunList.readingOf(permitted as Set, groupId(GROUP), CAT_USER)

        then:
        reading.reach() == reach
        reading.scope() == scope

        where:
        permitted                                                      || reach               | scope
        [GroupPermission.READ_ALL_RUNS, GroupPermission.READ_OWN_RUNS] || RunScope.Reach.EVERY | GROUP
        [GroupPermission.READ_OWN_RUNS]                                || RunScope.Reach.OWN   | GROUP + "/001303"
        [GroupPermission.START_RUN, GroupPermission.READ_MEMBERSHIP]   || RunScope.Reach.NONE  | GROUP
    }

    /** What a reading reaches is what the reader holds; reading neither reaches none, the reader's own included. */
    def "a member lists every run at the top of the group, their own only, or none, as their permissions reach"() {
        given:
        def reading = RunList.readingOf(permitted as Set, groupId(GROUP), CAT_USER)

        when:
        def page = listed(reading, newestFirst())

        then:
        page.rows()*.listed()*.number() == numbers
        page.next() == null

        where:
        permitted                                                        || numbers
        [GroupPermission.READ_ALL_RUNS, GroupPermission.READ_OWN_RUNS]   || [3, 2, 1]
        [GroupPermission.READ_OWN_RUNS]                                  || [3, 1]
        [GroupPermission.START_RUN, GroupPermission.READ_MEMBERSHIP]     || []
        []                                                               || []
    }

    def "a cursor minted reading a member's own runs is refused reading every run, whatever scope it is asked in"() {
        given:
        def own = RunList.readingOf([GroupPermission.READ_OWN_RUNS] as Set, groupId(GROUP), CAT_USER)
        def every = RunList.readingOf([GroupPermission.READ_ALL_RUNS] as Set, groupId(GROUP), CAT_USER)
        def minted = ListCursor.mint(own.shape(), new ListQuery<>(own.scope(), newestFirst(), null),
                new ListPosition(1L, 1L))

        when:
        ListCursor.resume(every.shape(), new ListQuery<>(scope, newestFirst(), null), minted)

        then:
        thrown(IllegalArgumentException)

        where:
        scope << [GROUP, GROUP + "/" + CAT_USER.value()]
    }

    def "a reader's own runs are read within the group's spelling, a slash and their user number"() {
        when:
        def scope = RunList.ownScope(groupId(GROUP), CAT_USER)

        then:
        scope == GROUP + "/001303"
        scope != RunList.ownScope(groupId(GROUP), ANN_USER)
    }

    /** The listing is narrowed by the query's scope and the runs read by the reading's group, so neither may stray. */
    def "a query read within a scope other than its reading's is refused rather than listed: #scope"() {
        given:
        def reading = RunList.readingOf(permitted as Set, groupId(GROUP), CAT_USER)

        when:
        runList.page(reading, new ListQuery<>(scope, newestFirst(), null), null)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "RunList query is read within a scope other than its reading's"

        where:
        permitted                       || scope
        [GroupPermission.READ_OWN_RUNS] || GROUP
        [GroupPermission.READ_ALL_RUNS] || GROUP + "/001303"
        [GroupPermission.READ_ALL_RUNS] || OTHER_GROUP
    }

    def "a row says what the run is called and numbered, what ran, who started it, when, when it was last acted on, and that it is stopped on no step"() {
        given:
        def startedAt = instant("select created_at from runs where run_id = ?::uuid", THIRD)
        def stoppedAt = instant("select created_at from run_stops where run_id = ?::uuid", THIRD)

        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows.first() == new RunList.RunRow(
                new RunList.Listed(
                        new RunId(UUID.fromString(THIRD)),
                        3,
                        new RunName("Claim from Alan"),
                        new EntryName("Handle a claim"),
                        1,
                        startedAt,
                        microsecondsOf(startedAt),
                        microsecondsOf(stoppedAt),
                        new PersonRows.Person(new SubjectId(UUID.fromString(CAT)), CAT_USER,
                                new PersonName("Cat Example"))),
                RunState.STOPPED,
                null)
        rows.first().listed().lastHappenedAt() == stoppedAt
    }

    /** Nothing has been asked of any run yet, so each running one is on the step its version asks first. */
    def "a row says where its run is, and the step a running one is on, as the run's own page works them out"() {
        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows*.listed()*.number() == [3, 2, 1]
        rows*.state() == [RunState.STOPPED, RunState.RUNNING, RunState.RUNNING]
        rows*.at() == [null, ASK, ASK]
    }

    def "a run every step of which is done is listed as done and on no step, and every other as it was"() {
        given:
        driven(FIRST)
        answeredFirst()

        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows*.listed()*.number() == [3, 2, 1]
        rows*.state() == [RunState.STOPPED, RunState.RUNNING, RunState.DONE]
        rows*.at() == [null, ASK, null]
    }

    def "a run a step of which failed is listed as failed and on no step, and every other as it was"() {
        given:
        failedFirst()

        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows*.listed()*.number() == [3, 2, 1]
        rows*.state() == [RunState.STOPPED, RunState.RUNNING, RunState.FAILED]
        rows*.at() == [null, ASK, null]
    }

    /** Each run in a state of its own, so a row paired with another run's reading could not pass. */
    def "each row says where its own run is, whichever way the page lists the runs"() {
        given:
        driven(FIRST)
        answeredFirst()

        when:
        def rows = listedByAnn(new ListOrder<>(RunSortColumn.NUMBER, descending)).rows()

        then:
        rows*.listed()*.number() == numbers
        rows*.state() == states
        rows*.at() == at

        where:
        descending || numbers   | states                                                  | at
        false      || [1, 2, 3] | [RunState.DONE, RunState.RUNNING, RunState.STOPPED]     | [null, ASK, null]
        true       || [3, 2, 1] | [RunState.STOPPED, RunState.RUNNING, RunState.DONE]     | [null, ASK, null]
    }

    /** Nothing else happens to any run meanwhile, so every other row keeps what last happened to it. */
    def "a try asked moves what last happened to its run to when it was asked, and nothing else's"() {
        given:
        def before = listedByAnn(newestFirst()).rows()*.listed()

        when:
        driven(FIRST)
        def asked = listedByAnn(new ListOrder<>(RunSortColumn.LAST_HAPPENED, true)).rows()*.listed()

        then:
        asked*.number() == [1, 3, 2]
        asked[0].lastHappenedAt() == instant("select created_at from productions where run_id = ?::uuid", FIRST)
        asked[0].lastHappenedAt() > before[2].lastHappenedAt()
        asked.tail() == before.take(2)
    }

    /** Nothing else happens to any run meanwhile, so every other row keeps what last happened to it. */
    def "a try answered moves what last happened to its run to when it was answered, and nothing else's"() {
        given:
        def lastFirst = new ListOrder<>(RunSortColumn.LAST_HAPPENED, true)
        driven(FIRST)
        def asked = listedByAnn(lastFirst).rows()*.listed()

        when:
        answeredFirst()
        def answered = listedByAnn(lastFirst).rows()*.listed()

        then:
        answered*.number() == [1, 3, 2]
        answered[0].lastHappenedAt() == instant("select ended_at from productions where run_id = ?::uuid", FIRST)
        answered[0].lastHappenedAt() > asked[0].lastHappenedAt()
        answered.tail() == asked.tail()
    }

    /** Read afresh for every page, so a run more on it is never a statement more. */
    def "a page is read in as many statements however many runs it holds"() {
        given:
        (1..5).each { number ->
            RunRows.run(store, String.format("00000008-0000-4000-8000-%012d", 1400 + number), GROUP, 10 + number,
                    "Claim ${number}", HANDLE, HANDLE_VERSION, CAT)
        }
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def session = JdbcClient.create(counting)
        def counted = new RunList(session, new GroupRoles(session), store.transactionManager(counting),
                new RunSnapshots(session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE))
        def reading = RunList.readingOf([GroupPermission.READ_ALL_RUNS] as Set, groupId(GROUP), ANN_USER)
        def byNumber = new ListOrder<>(RunSortColumn.NUMBER, false)

        when:
        def one = counted.page(reading, new ListQuery<>(reading.scope(), byNumber, new ListFilter("alan")), null)
        def few = statements.size()
        statements.clear()
        def many = counted.page(reading, new ListQuery<>(reading.scope(), byNumber, new ListFilter("claim")), null)

        then:
        one.rows()*.listed()*.number() == [3]
        many.rows()*.listed()*.number() == [1, 3, 11, 12, 13, 14, 15]
        statements.size() == few
    }

    def "a run is stopped only while a stop on it is in force: #condition"() {
        given:
        change(store)

        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows.find { it.listed().number() == 1 }.state() == state
        rows.findAll { it.listed().number() != 1 }*.state() == [RunState.STOPPED, RunState.RUNNING]

        where:
        condition                  | change                                              || state
        "never stopped"            | { LibraryStore into -> }                            || RunState.RUNNING
        "stopped"                  | { LibraryStore into -> RunRows.stopped(into, FIRST, ANN) } || RunState.STOPPED
        "stopped and opened again" | { LibraryStore into -> openedAgain(into, FIRST) }  || RunState.RUNNING
    }

    private static void openedAgain(LibraryStore store, String run) {
        RunRows.stopped(store, run, ANN)
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid where run_id = ?::uuid")
                .params(ANN, run).update()
    }

    /** Matched as typed against a run's name, and against its workflow's once spaced as names are. */
    def "a filter narrows the runs to those whose name or workflow holds what was typed, whatever the case"() {
        given:
        started(FOURTH, 5, "Note  for  Bea", REFUND, REFUND_VERSION, ANN, 5)

        when:
        def page = listedByAnn(newestFirst(), new ListFilter(typed))

        then:
        page.rows()*.listed()*.number() == numbers

        where:
        typed         || numbers
        "ALAN"        || [3]
        "refund"      || [5, 2]
        "claim"       || [3, 1]
        "note  for"   || [5]
        "note for"    || []
        "PAY  A"      || [5, 2]
        "zzz"         || []
    }

    /** Ties are broken by number ascending, whichever way the column runs. */
    def "sorted by any column, the runs come in its order"() {
        when:
        def page = listedByAnn(new ListOrder<>(column, descending))

        then:
        page.rows()*.listed()*.number() == numbers

        where:
        column                      | descending || numbers
        RunSortColumn.NUMBER        | false      || [1, 2, 3]
        RunSortColumn.NUMBER        | true       || [3, 2, 1]
        RunSortColumn.NAME          | false      || [1, 3, 2]
        RunSortColumn.WORKFLOW      | false      || [1, 3, 2]
        RunSortColumn.WORKFLOW      | true       || [2, 1, 3]
        RunSortColumn.STARTED       | false      || [1, 2, 3]
        RunSortColumn.STARTED_BY    | false      || [2, 1, 3]
        RunSortColumn.LAST_HAPPENED | true       || [3, 2, 1]
        RunSortColumn.LAST_HAPPENED | false      || [1, 2, 3]
    }

    def "a starter held with no name sorts after every named one, whichever way the column runs"() {
        given:
        store.session.sql("insert into subjects (subject_id, kind, user_id, created_by) values (?::uuid, 'person', ?, ?::uuid)")
                .params(EVE, "001305", LibraryStore.SEEDER).update()
        started(FOURTH, 5, "Claim from Bea", HANDLE, HANDLE_VERSION, EVE, 5)

        when:
        def page = listedByAnn(new ListOrder<>(RunSortColumn.STARTED_BY, descending))

        then:
        page.rows()*.listed()*.number() == numbers

        where:
        descending || numbers
        false      || [2, 1, 3, 5]
        true       || [1, 3, 2, 5]
    }

    /** Stamped a minute ahead of the stop made in setup, so the act is the latest thing to happen in the group. */
    def "the run last acted on leads the runs by what last happened: #act"() {
        given:
        acted(store)

        when:
        def page = listedByAnn(new ListOrder<>(RunSortColumn.LAST_HAPPENED, true))

        then:
        page.rows()*.listed()*.number() == numbers

        where:
        act                                 | acted                                                  || numbers
        "nothing"                           | { LibraryStore into -> }                               || [3, 2, 1]
        "renamed"                           | { LibraryStore into -> renamed(into) }                 || [2, 3, 1]
        "stopped"                           | { LibraryStore into -> stoppedLater(into) }            || [2, 3, 1]
        "opened again"                      | { LibraryStore into -> reopened(into) }                || [2, 3, 1]
        "its ceiling changed"               | { LibraryStore into -> ceiling(into, SECOND, false) }  || [2, 3, 1]
        "a raise asked"                     | { LibraryStore into -> ceiling(into, SECOND, true) }   || [2, 3, 1]
        "the ceiling of the run beneath it" | { LibraryStore into -> ceiling(into, BENEATH, false) } || [2, 3, 1]
        "a try on the run beneath it"       | { LibraryStore into -> triedBeneath(into) }            || [2, 3, 1]
        "a raise approved"                  | { LibraryStore into -> decided(into, "approved") }     || [2, 3, 1]
        "a raise refused"                   | { LibraryStore into -> decided(into, "refused") }      || [2, 3, 1]
        "a raise withdrawn"                 | { LibraryStore into -> decided(into, "withdrawn") }    || [2, 3, 1]
        "a raise long asked, undecided"     | { LibraryStore into -> asked(into) }                   || [3, 2, 1]
    }

    private static void renamed(LibraryStore store) {
        store.session.sql("""
                update runs set name = 'Refund for Grace, again', updated_at = now() + interval '1 minute',
                                updated_by = ?::uuid
                 where run_id = ?::uuid
                """).params(ANN, SECOND).update()
    }

    private static void stoppedLater(LibraryStore store) {
        store.session.sql("""
                insert into run_stops (run_id, root_run_id, created_at, created_by)
                values (?::uuid, ?::uuid, now() + interval '1 minute', ?::uuid)
                """).params(SECOND, SECOND, ANN).update()
    }

    private static void reopened(LibraryStore store) {
        store.session.sql("""
                insert into run_stops (run_id, root_run_id, created_at, created_by, opened_again_at, opened_again_by)
                values (?::uuid, ?::uuid, now() - interval '15 minutes', ?::uuid, now() + interval '1 minute', ?::uuid)
                """).params(SECOND, SECOND, ANN, ANN).update()
    }

    private static void ceiling(LibraryStore store, String run, boolean awaits) {
        RunRows.ceilingChanged(store, CHANGE, run, 10L, 20L, awaits, ANN, -1)
    }

    /** A try asked of the first step of the run beneath the second, and of no step of the second itself. */
    private static void triedBeneath(LibraryStore store) {
        store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.pinned_version_id,
                       step.pinned_kind, step.producer, step.reviewed_by_model, step.tries, ?::uuid
                  from workflow_steps step
                 where step.workflow_step_id = ?::uuid
                """).params(BENEATH, RunRows.WORKFLOW_RUNNER, ASK_HANDLE).update()
        store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_at, created_by)
                select step.run_step_id, step.run_id, ?::uuid, step.kind, step.producer, step.reviewed_by_model,
                       step.tries, step.pinned_version_id, 1, step.producer, now() + interval '1 minute', ?::uuid
                  from run_steps step
                 where step.run_id = ?::uuid
                """).params(SECOND, RunRows.WORKFLOW_RUNNER, BENEATH).update()
    }

    /** Asked long before the run started, so only its deciding could move the run. */
    private static void asked(LibraryStore store) {
        RunRows.ceilingChanged(store, CHANGE, SECOND, 10L, 20L, true, ANN, 40)
    }

    private static void decided(LibraryStore store, String outcome) {
        asked(store)
        store.session.sql("""
                update run_ceiling_changes
                   set outcome = cast(? as run_ceiling_change_outcome), decided_at = now() + interval '1 minute',
                       decided_by = ?::uuid
                 where run_ceiling_change_id = ?::uuid
                """).params(outcome, CAT, CHANGE).update()
    }

    /** The reader's own runs are read within the reader, so a page after the first stays within them too. */
    def "the second page of a reader's own runs goes on within them, and ends where they do"() {
        given:
        (1..ListPage.SIZE - 1).each { number ->
            RunRows.run(store, String.format("00000008-0000-4000-8000-%012d", 1400 + number), GROUP, 10 + number,
                    "Claim ${number}", HANDLE, HANDLE_VERSION, CAT)
        }
        def reading = runList.readingOf(groupId(GROUP), CAT_USER)
        def query = new ListQuery<>(reading.scope(), new ListOrder<>(RunSortColumn.NUMBER, false), null)

        when:
        def first = runList.page(reading, query, null)
        def second = runList.page(reading, query, first.next())

        then:
        first.rows().size() == ListPage.SIZE
        first.rows()*.listed()*.number().take(2) == [1, 3]
        second.rows()*.listed()*.number() == [10 + ListPage.SIZE - 1]
        second.next() == null
    }

    /** Every tied run started at one instant, newer than the rest, so the first page ends inside the tie. */
    def "newest first, a page ending among runs started at one instant goes on with the rest of them, then the older"() {
        given:
        (1..ListPage.SIZE + 1).each { number ->
            RunRows.run(store, String.format("00000008-0000-4000-8000-%012d", 1400 + number), GROUP, 10 + number,
                    "Claim ${number}", HANDLE, HANDLE_VERSION, ANN)
        }
        store.session.sql("update runs set created_at = (select now() - interval '5 minutes') where number > 10")
                .update()
        def reading = runList.readingOf(groupId(GROUP), ANN_USER)
        def query = new ListQuery<>(reading.scope(), newestFirst(), null)

        when:
        def first = runList.page(reading, query, null)
        def second = runList.page(reading, query, first.next())

        then:
        first.rows()*.listed()*.number() == (11..10 + ListPage.SIZE).toList()
        second.rows()*.listed()*.number() == [11 + ListPage.SIZE, 3, 2, 1]
        second.next() == null
    }

    /** Started apart long ago but every one renamed at one instant, after anything else, so only the renaming ties. */
    def "by what last happened, a page ending among runs last acted on at one instant goes on with the rest, then the older"() {
        given:
        (1..ListPage.SIZE + 1).each { number ->
            RunRows.run(store, String.format("00000008-0000-4000-8000-%012d", 1400 + number), GROUP, 10 + number,
                    "Claim ${number}", HANDLE, HANDLE_VERSION, ANN)
        }
        store.session.sql("""
                update runs
                   set created_at = now() - interval '1 hour' - number * interval '1 minute',
                       updated_at = (select now() + interval '1 minute'), updated_by = ?::uuid
                 where number > 10
                """).param(ANN).update()
        def reading = runList.readingOf(groupId(GROUP), ANN_USER)
        def query = new ListQuery<>(reading.scope(), new ListOrder<>(RunSortColumn.LAST_HAPPENED, true), null)

        when:
        def first = runList.page(reading, query, null)
        def second = runList.page(reading, query, first.next())

        then:
        first.rows()*.listed()*.number() == (11..10 + ListPage.SIZE).toList()
        second.rows()*.listed()*.number() == [11 + ListPage.SIZE, 3, 2, 1]
        second.next() == null
    }

    def "a stored name the run's type refuses fails the page rather than being shown or left out"() {
        given:
        store.session.sql("update runs set name = ' ' where run_id = ?::uuid").param(FIRST).update()

        when:
        listedByAnn(newestFirst())

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Run ${FIRST} holds a name this system will not show" as String
    }
}
