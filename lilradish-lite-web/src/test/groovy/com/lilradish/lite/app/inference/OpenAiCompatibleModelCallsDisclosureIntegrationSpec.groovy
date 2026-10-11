package com.lilradish.lite.app.inference

import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.inference.ModelEndpoint.VendorMode
import com.lilradish.lite.app.inference.ModelEndpoint.VendorModel
import com.lilradish.lite.app.run.Deployments
import com.lilradish.lite.app.run.EngineWiring
import com.lilradish.lite.app.run.RunSteps
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.SnapshottingAppender
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import java.time.Duration
import java.time.InstantSource
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.web.client.ResourceAccessException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class OpenAiCompatibleModelCallsDisclosureIntegrationSpec extends Specification {

    static final URI COMPLETIONS = URI.create("https://models.example/v1/chat/completions")

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    EngineWiring wiring

    MockRestServiceServer wire

    Logger jdbcLogger = LoggerFactory.getLogger(JdbcTemplate) as Logger

    Level previousLevel

    SnapshottingAppender logged

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "transport_disclosure_" + (++databasesMade))
        TicketWorkflow.seed(store, 1)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid""").params(TicketWorkflow.SUMMARISE).update()
        previousLevel = jdbcLogger.level
        jdbcLogger.level = Level.DEBUG
        logged = new SnapshottingAppender()
        logged.start()
        (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger).addAppender(logged)
    }

    def cleanup() {
        if (wiring != null) {
            wiring.executor.destroy()
        }
        (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logged)
        jdbcLogger.level = previousLevel
        logged.stop()
    }

    def "local #kind failure keeps its category across the stored and public detail without its diagnostics in any channel"() {
        given:
        def marker = UUID.randomUUID().toString()
        def attempts = new AtomicInteger()
        def modelCalls = adapter()
        def response = responseFor(kind, marker)
        wire.expect(requestTo(COMPLETIONS)).andRespond({ request ->
            attempts.incrementAndGet()
            response.createResponse(request)
        } as ResponseCreator)
        wiring = new EngineWiring(store, modelCalls, CodeStepsHeld.NONE)
        def steps = new RunSteps(store.session, new GroupRoles(store.session), wiring.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new com.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)

        when:
        started()
        wiring.drained()
        def page = steps.step(groupId(TicketWorkflow.GROUP), runId(), summarise(), TicketWorkflow.ANN_USER)

        then:
        attempts.get() == 1
        wire.verify()
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select outcome::text from model_calls") == ["errored"]
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select lost_reason::text from productions") == ["errored"]
        store.count("select count(*) from model_call_turnaways") == 0
        store.count("select count(*) from production_values") == 0
        store.texts("select error_detail from model_calls") == ["No answer came back: " + category]
        page.triesMade().size() == 1
        page.triesMade()[0].ended() == "errored"
        page.triesMade()[0].wentWrong().detail() == "No answer came back: " + category
        !page.triesMade()[0].wentWrong().cut()
        page.triesMade()[0].wentWrong().withheld() == null
        page.step().where().reason() == "tries_spent"

        and:
        !storedRows().any { it.contains(marker) }
        !page.toString().contains(marker)
        !logged.list.empty
        logged.list.any { it.loggerName == JdbcTemplate.name && it.formattedMessage.contains("model_calls") }
        logged.list.every { event ->
            !event.formattedMessage.contains(marker) &&
                    !(event.argumentArray?.any { it.toString().contains(marker) }) &&
                    !throwableText(event.throwableProxy).contains(marker)
        }

        where:
        kind          || category
        "connection"  || ConnectException.name
        "tls"         || SSLHandshakeException.name
        "body"        || EOFException.name
        "access"      || ResourceAccessException.name
    }

    def "a supplier's bounded error words remain in the stored and public detail"() {
        given:
        def marker = UUID.randomUUID().toString()
        def modelCalls = adapter()
        wire.expect(requestTo(COMPLETIONS)).andRespond(withRawStatus(500).body(
                '{"error":{"message":"' + marker + '"}}'))
        wiring = new EngineWiring(store, modelCalls, CodeStepsHeld.NONE)
        def steps = new RunSteps(store.session, new GroupRoles(store.session), wiring.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH, new com.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)

        when:
        started()
        wiring.drained()
        def page = steps.step(groupId(TicketWorkflow.GROUP), runId(), summarise(), TicketWorkflow.ANN_USER)

        then:
        wire.verify()
        store.texts("select error_detail from model_calls") == ["HTTP 500: " + marker]
        storedRows().any { it.contains(marker) }
        page.triesMade()[0].wentWrong().detail() == "HTTP 500: " + marker
        page.triesMade()[0].ended() == "errored"
        store.count("select count(*) from model_calls") == 1
        store.count("select count(*) from model_call_turnaways") == 0
    }

    private List<String> storedRows() {
        // DB-SPECIFIC: PostgreSQL pg_tables; current_schema().
        def tables = store.texts("""
                select tablename from pg_tables
                 where schemaname = current_schema() and tablename <> 'flyway_schema_history'
                 order by tablename
                """)
        assert tables.containsAll(["model_calls", "productions", "reviews"])
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        tables.collectMany { table -> store.texts('select stored::text from "' + table.replace('"', '""') + '" stored') }
    }

    private OpenAiCompatibleModelCalls adapter() {
        def endpoint = new ModelEndpoint(true, Duration.ofSeconds(1),
                Duration.ofSeconds(1), DeployedModels.HELD.all().collect { model -> new VendorModel(model.name(), "vendor-" + model.name().value(), "https://models.example/v1", "SAMPLE_MODEL_KEY", model.modes().collect { mode -> new VendorMode(mode, ReasoningEffort.HIGH) }, []) })
        def shutdown = new ModelCallShutdown()
        shutdown.start()
        def resend = new TurnAwayResend(new ResendPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1)),
                shutdown, InstantSource.system())
        def builder = ModelCallsConfiguration.modelClient(endpoint, "test-key", resend).mutate()
        wire = MockRestServiceServer.bindTo(builder).build()
        def compatible = new OpenAiCompatibleCallConversion()
        new OpenAiCompatibleModelCalls(new ModelCallTransport(DeployedModels.HELD.all().collectEntries { [(it.name()): builder.build()] },
                endpoint.callWays(DeployedModels.HELD), new ModelCallConversions([compatible], [compatible])))
    }

    private void started() {
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(TicketWorkflow.GROUP), runId()).orElseThrow())
        }
    }

    private static ResponseCreator responseFor(String kind, String marker) {
        switch (kind) {
            case "connection":
                return withException(new ConnectException(marker))
            case "tls":
                return withException(new SSLHandshakeException(marker))
            case "body":
                return { request -> new MockClientHttpResponse(new BrokenBody(marker), 200) } as ResponseCreator
            case "access":
                return { request -> throw new ResourceAccessException(marker) } as ResponseCreator
            default:
                throw new IllegalArgumentException(kind)
        }
    }

    private static String throwableText(ch.qos.logback.classic.spi.IThrowableProxy failure) {
        failure == null ? "" : failure.message + throwableText(failure.cause) +
                failure.suppressed.collect { throwableText(it) }.join("")
    }

    private static RunId runId() {
        new RunId(UUID.fromString(TicketWorkflow.RUN))
    }

    private static WorkflowStepId summarise() {
        new WorkflowStepId(UUID.fromString(TicketWorkflow.SUMMARISE))
    }

    private static final class BrokenBody extends InputStream {

        final String marker

        BrokenBody(String marker) {
            this.marker = marker
        }

        @Override
        int read() {
            throw new EOFException(marker)
        }
    }
}
