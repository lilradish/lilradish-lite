package org.lilradish.lite.app.start

import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.sql.DataSource
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.filling.ValueProblemsRefusal
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.library.ConstantJson
import org.lilradish.lite.app.run.EngineCalls
import org.lilradish.lite.app.run.EngineExecutor
import org.lilradish.lite.app.run.EngineWrites
import org.lilradish.lite.app.run.RunEngine
import org.lilradish.lite.app.run.RunSnapshots
import org.lilradish.lite.app.run.RunTree
import org.lilradish.lite.app.run.Runs
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.filling.FillPath
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.library.TakingWorkflow
import org.lilradish.lite.testutil.run.Blocking
import org.lilradish.lite.testutil.run.EngineModels
import org.lilradish.lite.testutil.run.RecordedModelCalls
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * Starting a run against a real store: the row written at the group's next number with what was filled in as it is
 * kept, every refusal writing nothing, and a start meeting another change in flight deciding on what it landed.
 */
class StartRunsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001601"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001602"

    /** An operator here, and in no role in the other group. */
    static final String ANN = "00000002-0000-4000-8000-000000001601"

    static final UserId ANN_USER = new UserId("001601")

    /** In no role anywhere. */
    static final String BEN = "00000002-0000-4000-8000-000000001602"

    static final UserId BEN_USER = new UserId("001602")

    /** An overseer here, reading every run of the group rather than only those she started. */
    static final String CAL = "00000002-0000-4000-8000-000000001603"

    static final UserId CAL_USER = new UserId("001603")

    static final String CLAIM = "00000006-0000-4000-8000-000000001601"

    /** Takes what {@link TakingWorkflow#takes} declares; a run of it numbered one stands already. */
    static final String CLAIM_VERSION = "00000007-0000-4000-8000-000000001601"

    static final String HELLO = "00000006-0000-4000-8000-000000001602"

    /** Takes nothing at all. */
    static final String HELLO_VERSION = "00000007-0000-4000-8000-000000001602"

    static final String LETTER = "00000006-0000-4000-8000-000000001603"

    /** Takes one letter of up to two million characters. */
    static final String LETTER_VERSION = "00000007-0000-4000-8000-000000001603"

    static final String RETIRED_VERSION = "00000007-0000-4000-8000-000000001604"

    static final String STOPPED = "00000006-0000-4000-8000-000000001605"

    static final String STOPPED_VERSION = "00000007-0000-4000-8000-000000001605"

    static final String DRAFT_VERSION = "00000007-0000-4000-8000-000000001606"

    static final String QUESTION_VERSION = "00000007-0000-4000-8000-000000001607"

    static final String ELSEWHERE_VERSION = "00000007-0000-4000-8000-000000001608"

    static final String NOWHERE_VERSION = "00000007-0000-4000-8000-000000001609"

    static final String LIST_VERSION = "00000007-0000-4000-8000-000000001610"

    /** Its first step asks somebody a question taking nothing. */
    static final String ASK_VERSION = "00000007-0000-4000-8000-000000001611"

    /** Its one step asks a model, once; only the features that call on it write it. */
    static final String MODEL_FIRST_VERSION = "00000007-0000-4000-8000-000000001612"

    static final String FIRST_RUN = "00000008-0000-4000-8000-000000001601"

    static final String FILLED = """
            {"complaint": "Kettle broken", "amount": "12.50", "category": "damaged", "tags": ["box"],
             "contact": {"email": "a@example.org", "phone": null}}"""

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    StartRuns starts

    CountingTransactions transactions

    /** Asked of no feature but one, which consumes the one call it makes; every other finds it never called. */
    RecordedModelCalls modelCalls

    /** Every collaborator real: the engine's classes are its package's own, so they are found by Spring alone. */
    AnnotationConfigApplicationContext context

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(1)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def cleanup() {
        context.close()
        assert modelCalls.scripted.requests.isEmpty()
    }

    def setup() {
        store = LibraryStore.copied(server, "start_runs_" + (++databasesMade))
        transactions = new CountingTransactions(store.transactions())
        context = new AnnotationConfigApplicationContext()
        context.registerBean(JdbcClient, { store.session } as Supplier)
        context.registerBean(DataSource, { store.database } as Supplier)
        context.registerBean(TransactionOperations, { transactions } as Supplier)
        context.environment.propertySources.addFirst(new org.springframework.core.env.MapPropertySource(
                "codeSteps", ["lilradish.code-steps.longest-run": "1m"] as Map<String, Object>))
        context.register(Starting)
        context.refresh()
        starts = context.getBean(StartRuns)
        modelCalls = context.getBean(RecordedModelCalls)
        store.person(ANN, "001601")
        store.person(BEN, "001602")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "operator")
        store.person(CAL, "001603")
        store.member(GROUP, CAL, "overseer")
        TakingWorkflow.list(store, "00000006-0000-4000-8000-000000001610", LIST_VERSION, GROUP)
        TakingWorkflow.workflow(store, CLAIM, CLAIM_VERSION, GROUP, "Handle a claim")
        TakingWorkflow.takes(store, CLAIM_VERSION, LIST_VERSION)
        TakingWorkflow.workflow(store, HELLO, HELLO_VERSION, GROUP, "Say hello")
        TakingWorkflow.workflow(store, LETTER, LETTER_VERSION, GROUP, "Read a letter")
        TakingWorkflow.field(store, LETTER_VERSION, 1, "letter", "text", [textLimit: 2_000_000])
        store.entry("00000006-0000-4000-8000-000000001604", GROUP, "workflow", "Retired")
        store.seeded(RETIRED_VERSION, "00000006-0000-4000-8000-000000001604", 1, true)
        store.content(RETIRED_VERSION, "workflow")
        TakingWorkflow.workflow(store, STOPPED, STOPPED_VERSION, GROUP, "Stopped")
        store.stopped(STOPPED, ANN)
        store.entry("00000006-0000-4000-8000-000000001606", GROUP, "workflow", "Drafted")
        store.version(DRAFT_VERSION, "00000006-0000-4000-8000-000000001606", 1, ANN)
        store.content(DRAFT_VERSION, "workflow")
        store.entry("00000006-0000-4000-8000-000000001607", GROUP, "question", "Sort a claim")
        store.seeded(QUESTION_VERSION, "00000006-0000-4000-8000-000000001607", 1)
        store.wholeQuestion(QUESTION_VERSION)
        TakingWorkflow.workflow(store, "00000006-0000-4000-8000-000000001608", ELSEWHERE_VERSION, OTHER_GROUP,
                "Elsewhere")
        StepRows.workflow(store, "00000006-0000-4000-8000-000000001611", ASK_VERSION, GROUP, "Ask somebody")
        StepRows.askingSomebody(store, ASK_VERSION, GROUP)
        RunRows.run(store, FIRST_RUN, GROUP, 1, "Claim from Ada", CLAIM, CLAIM_VERSION, ANN)
    }

    private StartRuns.Started starting(String version, String values, UserId caller = ANN_USER) {
        starts.start(groupId(GROUP), versionId(version), new RunName("Kettle arrived broken"),
                ConstantJson.sent(values), caller)
    }

    private List<String> runsWritten() {
        store.texts("select number || ' ' || name from runs order by number")
    }

    private List<String> startedWith(StartRuns.Started started) {
        store.texts("select started_with::text from runs where run_id = ?::uuid", started.run().value().toString())
    }

    /** A change of the spec's own in flight: {@code statements} run and left uncommitted. */
    private Connection inFlight(Object... statements) {
        store.holding(statements.collect { it.toString() } as String[])
    }

    /** The start asked while {@code other} is in flight, once it is seen waiting on it and the other has landed. */
    private Object afterLanding(Connection other, Closure start) {
        def asked = attempting(racing, start)
        Blocking.untilBlockedBy(store, other)
        other.commit()
        other.close()
        asked.get(10, TimeUnit.SECONDS)
    }

    private static String takenFirst(String run, int number, String name) {
        """insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                             started_with, created_by)
           values ('${run}', '${GROUP}', ${number}, '${name}', '${CLAIM}', '${CLAIM_VERSION}', '${run}', 0, '{}',
                   '${ANN}')"""
    }

    def "starts a run at the group's next number, keeping what was filled in as its fields type it"() {
        when:
        def started = starting(CLAIM_VERSION, FILLED)

        then:
        started.number() == 2
        store.texts("""
                select concat_ws(' ', group_id, number, name, entry_id, entry_version_id, root_run_id = run_id, depth,
                                 parent_run_id is null, created_by, created_by_kind)
                  from runs where run_id = ?::uuid
                """, started.run().value().toString()) ==
                ["${GROUP} 2 Kettle arrived broken ${CLAIM} ${CLAIM_VERSION} t 0 t ${ANN} person" as String]
        startedWith(started) ==
                ['{"tags": ["box"], "amount": 12.50, "contact": {"email": "a@example.org", "phone": null}, ' +
                         '"category": "damaged", "complaint": "Kettle broken"}']

        and: "one run written, and nothing planned on it, its workflow having no step"
        runsWritten() == ["1 Claim from Ada", "2 Kettle arrived broken"]
        store.count("select count(*) from run_steps") == 0
    }

    /** Sent in an order of its own, so only the declared order read back proves neither it nor the store's is kept. */
    def "a run started reads back as started with exactly what was sent, in declared order at every level"() {
        given:
        def runs = new Runs(store.session, new GroupRoles(store.session), store.transactionManager(),
                new RunSnapshots(store.session, CodeStepsHeld.NONE))
        def sent = '''
                {"contact": {"phone": null, "email": "a@example.org"}, "tags": ["box", "lid"], "category": "damaged",
                 "amount": "12.50", "complaint": "Kettle broken"}'''

        when:
        def read = runs.run(groupId(GROUP), starting(CLAIM_VERSION, sent).run(), ANN_USER)

        then:
        read.startedWith() == JsonMapper.builder().build().readTree(sent)
        read.startedWith().propertyNames().toList() == ["complaint", "amount", "category", "tags", "contact"]
        read.startedWith().get("contact").propertyNames().toList() == ["email", "phone"]
    }

    def "a run started is one its starter may read, judged by what they may read in the group: #role"() {
        when:
        def started = starting(CLAIM_VERSION, FILLED, caller)

        then:
        started.readable()
        store.texts("select created_by::text from runs where run_id = ?::uuid", started.run().value().toString()) ==
                [starter]
        runsWritten() == ["1 Claim from Ada", "2 Kettle arrived broken"]

        where:
        caller   | starter | role
        ANN_USER | ANN     | "an operator, reading the runs they started"
        CAL_USER | CAL     | "an overseer, reading every run"
    }

    def "a workflow taking nothing starts with nothing filled in"() {
        when:
        def started = starting(HELLO_VERSION, "{}")

        then:
        started.number() == 2
        startedWith(started) == ["{}"]
    }

    def "a value that need not be given, sent as empty text, is kept as none"() {
        when:
        def started = starting(CLAIM_VERSION, FILLED.replace('"12.50"', '""').replace('"phone": null', '"phone": ""'))

        then:
        startedWith(started) ==
                ['{"tags": ["box"], "amount": null, "contact": {"email": "a@example.org", "phone": null}, ' +
                         '"category": "damaged", "complaint": "Kettle broken"}']
    }

    def "a value past 1,048,576 characters, within its field's limit, is kept whole"() {
        given:
        def letter = "a" * 1_048_577

        when:
        def started = starting(LETTER_VERSION, """{"letter": "${letter}"}""")

        then:
        store.texts("select (started_with ->> 'letter' = ?)::text from runs where run_id = ?::uuid",
                letter, started.run().value().toString()) == ["true"]
        runsWritten() == ["1 Claim from Ada", "2 Kettle arrived broken"]
    }

    def "a run started has its first step planned before the start ends: a question asked of somebody opens a try"() {
        when:
        def started = starting(ASK_VERSION, "{}")

        then:
        def run = started.run().value().toString()
        store.count("select count(*) from run_steps where run_id = ?::uuid", run) == 1
        store.count("""
                select count(*) from productions try join run_steps step on step.run_step_id = try.run_step_id
                 where step.run_id = ?::uuid and try.ended_at is null
                """, run) == 1

        and: "nothing planned on any run but the one started"
        store.count("select count(*) from run_steps where run_id <> ?::uuid", run) == 0
    }

    def "refuses a version the group does not offer now alike however it fails, writing nothing: #reason"() {
        when:
        starting(version, FILLED)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.WORKFLOW_NOT_OFFERED
        refused.message == "Only a version in service of a workflow of this group's, not stopped, may be started."
        runsWritten() == ["1 Claim from Ada"]

        where:
        reason                              | version
        "a version retired"                 | RETIRED_VERSION
        "a workflow stopped"                | STOPPED_VERSION
        "a draft"                           | DRAFT_VERSION
        "a question rather than a workflow" | QUESTION_VERSION
        "another group's workflow"          | ELSEWHERE_VERSION
        "no version at all"                 | NOWHERE_VERSION
    }

    def "refuses one who holds nothing in the group as the group is refused, writing nothing"() {
        when:
        starting(CLAIM_VERSION, FILLED, BEN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        runsWritten() == ["1 Claim from Ada"]
    }

    def "values not of the shape the version takes are refused as a body it does not take, writing nothing: #shape"() {
        when:
        starting(CLAIM_VERSION, values)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        !(refused instanceof ValueProblemsRefusal)
        runsWritten() == ["1 Claim from Ada"]

        where:
        shape                         | values
        "no object"                   | '[]'
        "a field left out"            | '{"complaint": "Kettle", "amount": null, "category": "late", "tags": []}'
        "a field it does not take"    | FILLED.replace('"tags"', '"extra": null, "tags"')
        "a number sent as a number"   | FILLED.replace('"12.50"', '12.50')
        "none where many are held"    | FILLED.replace('["box"]', 'null')
        "none among many"             | FILLED.replace('["box"]', '["box", null]')
        "fields sent as many"         | FILLED.replace('{"email": "a@example.org", "phone": null}', '[]')
    }

    def "values that do not fit are refused naming where each stands and why, writing nothing"() {
        when:
        starting(CLAIM_VERSION, """
                {"complaint": "This complaint runs past twenty", "amount": "1e3", "category": "broken",
                 "tags": ["fine", "far too long a tag"], "contact": {"email": "", "phone": "555 0100"}}""")

        then:
        def refused = thrown(ValueProblemsRefusal)
        refused.errorCode() == RefusalCode.VALUE_DOES_NOT_FIT
        refused.problems().collect { problem ->
            [problem.path().steps().collect { it instanceof FillPath.Named ? it.name().value() : it.index() },
             problem.reason().published()]
        } == [[["complaint"], "too_long"], [["amount"], "malformed"], [["category"], "not_a_term"],
              [["tags", 1], "too_long"], [["contact", "email"], "missing"]]
        refused.found() == 5
        runsWritten() == ["1 Claim from Ada"]
    }

    /** The other is a start of the same group holding its numbering, its run written and not yet committed. */
    def "a start asked while another in its group holds the numbering waits for it, and takes the number after"() {
        given:
        def other = inFlight("select pg_advisory_xact_lock(${StartRuns.NUMBERING}, hashtext('${GROUP}'))",
                takenFirst("00000008-0000-4000-8000-000000001602", 2, "Taken first"))

        when:
        def started = afterLanding(other) { starting(CLAIM_VERSION, FILLED) }

        then:
        started instanceof StartRuns.Started
        started.number() == 3
        runsWritten() == ["1 Claim from Ada", "2 Taken first", "3 Kettle arrived broken"]
    }

    /** The context closed drains the engine's threads, so every hand-over the start made has run. */
    def "a run started goes as far as it goes within the start, and nothing is handed over after it beyond that"() {
        when:
        def started = starting(CLAIM_VERSION, FILLED)
        context.close()

        then:
        started.number() == 2
        transactions.handedOver.get() == 0
    }

    /** The call errs, and the step declares one try, so what follows its end plans nothing more. */
    def "a run whose first step a model produces sends it once after the start, on the engine's thread, and hands over nothing else"() {
        given:
        modelFirst()
        modelCalls.scripted.answering(new CallOutcome.Errored("Gone."))

        when: "closed once the call has ended: one not yet written down as sent when closing began is never sent"
        def started = starting(MODEL_FIRST_VERSION, "{}")
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (store.count("select count(*) from model_calls where outcome is not null") < 1) {
            assert System.nanoTime() < deadline: "the call never ended"
            Thread.sleep(10)
        }
        context.close()

        then:
        started.number() == 2
        modelCalls.scripted.requests.size() == 1
        modelCalls.madeOn == [[true, false]]

        and: "writing it down as sent, its end and the plan after it are the only transactions on the engine's threads"
        transactions.handedOver.get() == 3
        transactions.madeBy == ["RunEngine.sendAndEnd", "EngineCalls.ended", "RunEngine.drive"]

        cleanup: "consumed, so the check every feature ends with stays strict"
        modelCalls.scripted.requests.clear()
    }

    def "a start whose transaction rolls back hands over nothing, so no model is called"() {
        given:
        modelFirst()
        modelCalls.scripted.answering(new CallOutcome.Errored("Gone."))

        when:
        transactions.executeWithoutResult { status ->
            starting(MODEL_FIRST_VERSION, "{}")
            status.setRollbackOnly()
        }
        context.close()

        then:
        modelCalls.madeOn == []
        transactions.handedOver.get() == 0
        store.count("select count(*) from runs where entry_version_id = ?::uuid", MODEL_FIRST_VERSION) == 0
    }

    /** A workflow taking nothing whose one step asks a model to sort something, once. */
    private void modelFirst() {
        def question = "00000006-0000-4000-8000-000000001613"
        def questionVersion = "00000007-0000-4000-8000-000000001613"
        StepRows.workflow(store, "00000006-0000-4000-8000-000000001612", MODEL_FIRST_VERSION, GROUP, "Ask a model")
        StepRows.question(store, question, questionVersion, GROUP, "Sort it")
        StepRows.field(store, [id: "0000000c-0000-4000-8000-000000001613", owner: questionVersion,
                               ownerKind: "question", side: "gives", position: 1, name: "sorted", mustBe: true,
                               standing: "never"])
        StepRows.step(store, "00000009-0000-4000-8000-000000001612", MODEL_FIRST_VERSION, 1, "sort", questionVersion,
                "model", 1)
    }

    def "a start refused before its run is written hands nothing over"() {
        when:
        try {
            starting(NOWHERE_VERSION, FILLED)
        } finally {
            context.close()
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.WORKFLOW_NOT_OFFERED
        transactions.handedOver.get() == 0
        runsWritten() == ["1 Claim from Ada"]
    }

    /** Decided before either lock was held, the start would run a version nothing may start any longer. */
    def "a start asked while its workflow is being put out of service waits for it, and is refused: #change"() {
        given:
        def other = inFlight(held, changed)

        when:
        def refused = afterLanding(other) { starting(CLAIM_VERSION, FILLED) }

        then:
        refused instanceof ApiErrorException
        refused.errorCode() == RefusalCode.WORKFLOW_NOT_OFFERED
        runsWritten() == ["1 Claim from Ada"]

        where:
        change         | held                                                                                            | changed
        "a stop"       | "select 1 from entries where entry_id = '${CLAIM}' for no key update"                           | "insert into entry_stops (entry_id, created_by) values ('${CLAIM}', '${ANN}')"
        "a retirement" | "select 1 from entry_versions where entry_version_id = '${CLAIM_VERSION}' for no key update" | "update entry_versions set retired_at = now(), retired_by = '${ANN}' where entry_version_id = '${CLAIM_VERSION}'"
    }

    def "a start asked while its caller's roles are being taken waits for it, and is refused as the group is"() {
        given:
        def other = store.takingRoles(GROUP, ANN)

        when:
        def refused = afterLanding(other) { starting(CLAIM_VERSION, FILLED) }

        then:
        refused instanceof ApiErrorException
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        runsWritten() == ["1 Claim from Ada"]
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import([StartRuns, GroupRoles, RunTree, RunEngine, RunSnapshots, EngineWrites, EngineExecutor, EngineCalls,
            org.lilradish.lite.app.run.EngineCodes, org.lilradish.lite.app.run.CeilingReach,
            org.lilradish.lite.app.run.OneProcess, org.lilradish.lite.testutil.run.EngineCodeSteps, EngineModels])
    static class Starting {}

    /** The store's transactions, counting those the engine's own threads run: each is a run handed over going on. */
    static final class CountingTransactions implements TransactionOperations {

        final AtomicInteger handedOver = new AtomicInteger()

        /** Which of the engine's methods began each, in the order they began, as its class and method name. */
        final List<String> madeBy = Collections.synchronizedList([])

        private final TransactionOperations transactions

        CountingTransactions(TransactionOperations transactions) {
            this.transactions = transactions
        }

        @Override
        <T> T execute(TransactionCallback<T> action) {
            if (Thread.currentThread().name.startsWith("run-engine-")) {
                handedOver.incrementAndGet()
                madeBy << StackWalker.getInstance().walk { frames ->
                    frames.filter { it.className.startsWith("org.lilradish.lite.app.run.") }
                            .findFirst()
                            .map { it.className.substring(it.className.lastIndexOf('.') + 1) + "." + it.methodName }
                            .orElse("none of the engine's")
                }
            }
            transactions.execute(action)
        }
    }
}
