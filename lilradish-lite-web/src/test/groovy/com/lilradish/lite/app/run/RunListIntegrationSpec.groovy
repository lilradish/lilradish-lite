package com.lilradish.lite.app.run

import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.pool.PersonRows
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.listing.ListCursor
import com.lilradish.lite.domain.listing.ListFilter
import com.lilradish.lite.domain.listing.ListOrder
import com.lilradish.lite.domain.listing.ListPage
import com.lilradish.lite.domain.listing.ListPosition
import com.lilradish.lite.domain.listing.ListQuery
import com.lilradish.lite.domain.people.PersonName
import com.lilradish.lite.domain.registry.EntryName
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunName
import com.lilradish.lite.domain.run.RunSortColumn
import com.lilradish.lite.domain.run.RunState
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.ExplainedQueries
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import java.time.Instant
import java.time.OffsetDateTime
import org.libprunus.core.error.ApiErrorException
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunListIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001301"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001302"

    static final String ANN = "00000002-0000-4000-8000-000000001301"

    static final UserId ANN_USER = new UserId("001301")

    static final String CAT = "00000002-0000-4000-8000-000000001303"

    static final UserId CAT_USER = new UserId("001303")

    static final String DAN = "00000002-0000-4000-8000-000000001304"

    static final UserId DAN_USER = new UserId("001304")

    static final String EVE = "00000002-0000-4000-8000-000000001305"

    static final String HANDLE = "00000006-0000-4000-8000-000000001301"

    static final String HANDLE_VERSION = "00000007-0000-4000-8000-000000001301"

    static final String REFUND = "00000006-0000-4000-8000-000000001302"

    static final String REFUND_VERSION = "00000007-0000-4000-8000-000000001302"

    static final String ELSEWHERE = "00000006-0000-4000-8000-000000001303"

    static final String ELSEWHERE_VERSION = "00000007-0000-4000-8000-000000001303"

    static final String FIRST = "00000008-0000-4000-8000-000000001301"

    static final String SECOND = "00000008-0000-4000-8000-000000001302"

    static final String THIRD = "00000008-0000-4000-8000-000000001303"

    static final String BENEATH = "00000008-0000-4000-8000-000000001304"

    static final String OTHER_GROUPS_RUN = "00000008-0000-4000-8000-000000001305"

    static final String FOURTH = "00000008-0000-4000-8000-000000001306"

    static final String ASK_HANDLE = "00000009-0000-4000-8000-000000001311"

    static final String ASK_REFUND = "00000009-0000-4000-8000-000000001312"

    static final StepId ASK = new StepId("ask")

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunList runList

    String answer

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "run_list_" + (++databasesMade))
        runList = new RunList(store.session, new GroupRoles(store.session), store.transactionManager(),
                new RunSnapshots(store.session, com.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([])))
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
        started(FIRST, 1, "Claim from Ari", HANDLE, HANDLE_VERSION, CAT, 30)
        started(SECOND, 2, "Refund for Greno", REFUND, REFUND_VERSION, ANN, 20)
        started(THIRD, 3, "Claim from Arlo", HANDLE, HANDLE_VERSION, CAT, 10)
        RunRows.stopped(store, THIRD, ANN)
        RunRows.beneath(store, BENEATH, SECOND, GROUP, 4, HANDLE, HANDLE_VERSION,
                "0000000a-0000-4000-8000-000000001301", "0000000c-0000-4000-8000-000000001301")
        RunRows.run(store, OTHER_GROUPS_RUN, OTHER_GROUP, 1, "Claim from Ari", ELSEWHERE, ELSEWHERE_VERSION, DAN)
    }

    private void started(String run, int number, String name, String entry, String version, String starter,
                         int minutesAgo) {
        RunRows.run(store, run, GROUP, number, name, entry, version, starter)
        // DB-SPECIFIC: now(), make_interval() and ::uuid casts use PostgreSQL syntax and semantics.
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

    private void driven(String run) {
        def executor = EngineExecutors.of(store.database)
        try {
            def tree = new RunTree(store.session)
            def writes = new EngineWrites(store.session)
            def none = com.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE
            def snapshots = new RunSnapshots(store.session, none, new SearchWays([]))
            def ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
            def heldFiles = EngineWiring.files(store)
            new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                    new EngineCalls(EngineWiring.withoutTools(), new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                    new EngineCodes(new com.lilradish.lite.app.codestep.CodeRuns(none), store.transactions(), tree,
                            snapshots, writes, executor, heldFiles),
                    DeployedModels.HELD, ceilings, new GroupRoles(store.session), Deployments.DEEP_ENOUGH, heldFiles)
                    .drive(groupId(GROUP), new RunId(UUID.fromString(run)))
        } finally {
            executor.destroy()
        }
    }

    private void answeredFirst() {
        StepRows.answered(store, ASK_HANDLE, CAT, "Nothing to add.", [(answer): '"Fine."'])
    }

    private void failedFirst() {
        // DB-SPECIFIC: ::uuid casts use PostgreSQL syntax.
        store.session.sql("""
                update workflow_steps set reviewer_model = 'general', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).params(ASK_HANDLE).update()
        driven(FIRST)
        answeredFirst()
        // DB-SPECIFIC: ::uuid casts use PostgreSQL syntax.
        store.session.sql("""
                insert into run_step_failures (run_step_id, run_id, run_step_kind, calls_a_model, reason, production_id,
                                               purpose, detail, created_by)
                select step.run_step_id, step.run_id, step.kind, step.calls_a_model, 'model_not_deployed',
                       try.production_id, 'review', 'Not deployed.', ?::uuid
                  from productions try join run_steps step on step.run_step_id = try.run_step_id
                 where try.run_id = ?::uuid
                """).params(RunRows.WORKFLOW_RUNNER, FIRST).update()
    }

    def "the complete run history query records both name predicates and every reach at two scales"() {
        given:
        def started = System.nanoTime()
        // DB-SPECIFIC: PostgreSQL generate_series and ::uuid/::text casts populate runs;
        // || converts the generated integer to text.
        store.session.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id,
                                  root_run_id, depth, started_with, created_by)
                select ('00000008-0000-4000-9000-' || lpad(number::text, 12, '0'))::uuid,
                       ?::uuid, number + 10000,
                       case when number % 1000 = 1 then 'Nerith claim '
                            when number % 50 = 0 then 'Mira claim !_ '
                            else 'Mira claim ' end || number,
                       ?::uuid, ?::uuid,
                       ('00000008-0000-4000-9000-' || lpad(number::text, 12, '0'))::uuid,
                       0, '{}', cast(case when number % 2 = 0 then ? else ? end as uuid)
                  from generate_series(1, ?) number
                """).params(GROUP, HANDLE, HANDLE_VERSION, ANN, CAT, size).update()
        def inserted = System.nanoTime() - started
        started = System.nanoTime()
        store.session.sql("update runs set name = name || ' renamed' where number > 10000").update()
        def renamed = System.nanoTime() - started
        // DB-SPECIFIC: PostgreSQL ANALYZE refreshes the statistics used by its default planner.
        store.session.sql('analyze runs').update()
        def observed = new ExplainedQueries(store.database)
        def session = JdbcClient.create(observed)
        def searched = new RunList(session, new GroupRoles(session), store.transactionManager(observed),
                new RunSnapshots(session, com.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([])))
        def results = []

        when:
        [[GroupPermission.READ_ALL_RUNS], [GroupPermission.READ_OWN_RUNS], []].each { allowed ->
            def reading = RunList.readingOf(allowed as Set, groupId(GROUP), CAT_USER)
            ['Nerith', 'claim', 'a', '!_', 'Handle'].each { text ->
                (1..3).each { repetition ->
                    def read = searched.page(reading,
                            new ListQuery<>(reading.scope(), newestFirst(), new ListFilter(text)), null)
                    results.add([reach: reading.reach().name(), typed: text, repetition: repetition,
                                 ids: read.rows()*.listed()*.runId()*.value()*.toString()])
                }
            }
        }
        observed.save('runs-' + size, [rootRunsAdded: size, insertedNanos: inserted, renamedNanos: renamed], results)

        then:
        observed.queries.findAll { it.sql.contains('run.name_folded') }.size() == 45
        observed.queries.findAll { it.sql.contains('run.name_folded') }.every { it.sql.contains('entry.name_folded') && it.sql.contains('parent_run_id is null') && !it.bindings.empty }
        results.findAll { it.reach == 'NONE' }.every { it.ids.empty }
        results.groupBy { [it.reach, it.typed] }.values().every { runs -> runs*.ids.toSet().size() == 1 }
        results.find { it.reach == 'OWN' && it.typed == 'Nerith' }.ids.contains('00000008-0000-4000-9000-000000000001')

        where:
        size << [300, 6000]
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

    def "a row says what the run is called and numbered, what ran, who started it and when, and that it is stopped on no step"() {
        given:
        // DB-SPECIFIC: ::uuid casts use PostgreSQL syntax.
        def startedAt = instant("select created_at from runs where run_id = ?::uuid", THIRD)

        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows.first() == new RunList.RunRow(
                new RunList.Listed(
                        new RunId(UUID.fromString(THIRD)),
                        3,
                        new RunName("Claim from Arlo"),
                        new EntryName("Handle a claim"),
                        1,
                        startedAt,
                        microsecondsOf(startedAt),
                        new PersonRows.Person(new SubjectId(UUID.fromString(CAT)), CAT_USER,
                                new PersonName("Cat Example")), null),
                RunState.STOPPED,
                null, null)
    }

    def "a row says where its run is, and the step a running one is on, as the run's own page works them out"() {
        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows*.listed()*.number() == [3, 2, 1]
        rows*.state() == [RunState.STOPPED, RunState.RUNNING, RunState.RUNNING]
        rows*.at() == [null, ASK, ASK]
    }

    def "history displays the step name from each run's own version and keeps the machine position"() {
        given:
        def laterVersion = "00000007-0000-4000-8000-000000001304"
        def laterStep = "00000009-0000-4000-8000-000000001313"
        store.seeded(laterVersion, HANDLE, 2)
        store.content(laterVersion, "workflow")
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        def asked = store.texts("select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid", ASK_HANDLE)[0]
        StepRows.step(store, laterStep, laterVersion, 0, "ask", asked, "person", 1)
        started(FOURTH, 5, "Claim from Cove", HANDLE, laterVersion, CAT, 0)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update workflow_steps set label = ? where entry_version_id = ?::uuid")
                .params(label, HANDLE_VERSION).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update workflow_steps set label = ? where entry_version_id = ?::uuid")
                .params("\u65b0\u3057\u3044\u8acb\u6c42\u3092\u78ba\u8a8d", laterVersion).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update workflow_steps set label = ? where entry_version_id = ?::uuid")
                .params("\u8fd4\u91d1\u3092\u78ba\u8a8d", REFUND_VERSION).update()

        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows*.listed()*.number() == [5, 3, 2, 1]
        rows*.state() == [RunState.RUNNING, RunState.STOPPED, RunState.RUNNING, RunState.RUNNING]
        rows*.at() == [ASK, null, ASK, ASK]
        rows*.atLabel() == ["\u65b0\u3057\u3044\u8acb\u6c42\u3092\u78ba\u8a8d", null, "\u8fd4\u91d1\u3092\u78ba\u8a8d", label]
        rows*.listed()*.runId() == [FOURTH, THIRD, SECOND, FIRST].collect { new RunId(UUID.fromString(it)) }
        rows*.listed()*.version() == [2, 1, 1, 1]
        rows*.listed()*.workflow()*.value() == ["Handle a claim", "Handle a claim", "Pay a refund", "Handle a claim"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; || converts the integer run number and UUID to text.
        store.texts("select number || ':' || entry_version_id from runs where group_id = ?::uuid and parent_run_id is null order by number desc", GROUP) ==
                ["5:" + laterVersion, "3:" + HANDLE_VERSION, "2:" + REFUND_VERSION, "1:" + HANDLE_VERSION]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("select label from workflow_steps where entry_version_id = ?::uuid")
                .param(HANDLE_VERSION).query(String).optional().orElse(null) == label
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select name || ':' || label from workflow_steps where entry_version_id = ?::uuid", laterVersion) ==
                ["ask:\u65b0\u3057\u3044\u8acb\u6c42\u3092\u78ba\u8a8d"]

        where:
        label << [null, "\u8acb\u6c42\u3092\u78ba\u8a8d\u202e"]
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
                new RunSnapshots(session, com.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([])))
        def reading = RunList.readingOf([GroupPermission.READ_ALL_RUNS] as Set, groupId(GROUP), ANN_USER)
        def byNumber = new ListOrder<>(RunSortColumn.NUMBER, false)

        when:
        def one = counted.page(reading, new ListQuery<>(reading.scope(), byNumber, new ListFilter("arlo")), null)
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
        if (condition == "stopped") {
            RunRows.stopped(store, FIRST, ANN)
        } else if (condition == "stopped and opened again") {
            openedAgain(store, FIRST)
        }

        when:
        def rows = listedByAnn(newestFirst()).rows()

        then:
        rows.find { it.listed().number() == 1 }.state() == state
        rows.findAll { it.listed().number() != 1 }*.state() == [RunState.STOPPED, RunState.RUNNING]

        where:
        condition                  || state
        "never stopped"            || RunState.RUNNING
        "stopped"                  || RunState.STOPPED
        "stopped and opened again" || RunState.RUNNING
    }

    private static void openedAgain(LibraryStore store, String run) {
        RunRows.stopped(store, run, ANN)
        // DB-SPECIFIC: now() and ::uuid casts use PostgreSQL syntax and semantics.
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid where run_id = ?::uuid")
                .params(ANN, run).update()
    }

    def "a filter narrows the runs to those whose name or workflow holds what was typed, whatever the case"() {
        given:
        started(FOURTH, 5, "Note  for  Bea", REFUND, REFUND_VERSION, ANN, 5)

        when:
        def page = listedByAnn(newestFirst(), new ListFilter(typed))

        then:
        page.rows()*.listed()*.number() == numbers

        where:
        typed         || numbers
        "ARLO"        || [3]
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
    }

    def "a starter held with no name sorts after every named one, whichever way the column runs"() {
        given:
        // DB-SPECIFIC: ::uuid casts use PostgreSQL syntax.
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

    def "newest first, a page ending among runs started at one instant goes on with the rest of them, then the older"() {
        given:
        (1..ListPage.SIZE + 1).each { number ->
            RunRows.run(store, String.format("00000008-0000-4000-8000-%012d", 1400 + number), GROUP, 10 + number,
                    "Claim ${number}", HANDLE, HANDLE_VERSION, ANN)
        }
        // DB-SPECIFIC: PostgreSQL now() and verbose interval quantity/unit input.
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

    def "a stored name the run's type refuses fails the page rather than being shown or left out"() {
        given:
        // DB-SPECIFIC: ::uuid casts use PostgreSQL syntax.
        store.session.sql("update runs set name = ' ' where run_id = ?::uuid").param(FIRST).update()

        when:
        listedByAnn(newestFirst())

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Run ${FIRST} holds a name this system will not show" as String
    }
}
