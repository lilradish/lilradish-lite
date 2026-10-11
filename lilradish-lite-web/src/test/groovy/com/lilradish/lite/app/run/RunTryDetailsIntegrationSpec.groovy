package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import com.lilradish.lite.app.currency.GroupCurrencies
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.KeptAnswer
import com.lilradish.lite.domain.inference.UnreadableResponseRecord
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import com.lilradish.lite.web.ActAdmission
import com.lilradish.lite.web.CallerAdmission
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/** The one-try read over a real store and dispatcher, including original empty and unreadable evidence. */
class RunTryDetailsIntegrationSpec extends Specification {

    // DB-SPECIFIC: the SQL evidence assertions below compare PostgreSQL native model_call_purpose
    // and step_producer enum labels with literals.

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ScriptedModelCalls model

    EngineWiring wiring

    RunSteps steps

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, 'try_details_' + (++databasesMade))
        model = new ScriptedModelCalls()
        wiring = new EngineWiring(store, model, CodeStepsHeld.NONE)
        def roles = new GroupRoles(store.session)
        steps = new RunSteps(store.session, roles, wiring.snapshots, store.transactionManager(),
                Deployments.DEEP_ENOUGH, new GroupCurrencies(store.session,
                new TransactionTemplate(store.transactionManager()), roles, DeployedModels.HELD), DeployedModels.HELD)
        TicketWorkflow.seed(store, 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).param(SUMMARISE).update()
    }

    def cleanup() {
        wiring.executor.destroy()
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static WorkflowStepId stepId() {
        new WorkflowStepId(UUID.fromString(SUMMARISE))
    }

    private void produced(CallOutcome outcome) {
        model.answering(outcome)
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (store.count('select count(*) from productions where ended_at is not null') != 1) {
            assert System.nanoTime() < deadline: 'the scripted model production never ended'
            Thread.sleep(10)
        }
        wiring.drained()
    }

    def "one try GET preserves the actual model reply even where it is empty"() {
        given:
        produced(new CallOutcome.CameBack(raw, 321, 12, true, false, null, null))
        def mvc = MockMvcBuilders.standaloneSetup(new RunStepsController(steps)).build()

        when:
        def response = mvc.perform(get("/api/groups/${GROUP}/runs/${RUN}/steps/${SUMMARISE}/tries/1")
                .requestAttr(CallerAdmission.class.name + '.caller', ANN_USER)
                .requestAttr(ActAdmission.class.name + '.group', groupId(GROUP))).andReturn().response
        def answer = JsonMapper.builder().build().readTree(response.contentAsString)

        then:
        response.status == 200
        answer.path('number').asInt() == 1
        answer.path('modelReply').path('text').asText() == raw
        answer.path('modelReply').propertyNames().toSet() == ['text', 'altered', 'truncated'] as Set
        !answer.path('modelReply').path('altered').asBoolean()
        !answer.path('modelReply').path('truncated').asBoolean()
        !answer.has('review')
        !answer.has('declined')
        !answer.has('unreadableResponse')
        store.texts('select answer from model_calls where purpose = \'produce\'') == [raw]

        where:
        raw << ['{"values":{"summary":"A printer fire."},"confidences":{}}', 'An invented service answer.', '']
    }

    def "a declined try keeps words and original reply as distinct evidence"() {
        given:
        produced(new CallOutcome.CameBack(raw, 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, words))

        when:
        def answer = steps.tryDetails(groupId(GROUP), runId(), stepId(), 1, ANN_USER)

        then:
        answer.declined()?.words()?.text() == (words.isEmpty() ? null : words)
        answer.declined()?.content()?.text() == (raw.isEmpty() ? null : raw)
        answer.modelReply() == (raw.isEmpty() ? new StepAnswers.ModelReplyRecord('', false, false) : null)
        answer.unreadableResponse() == null
        answer.review() == null

        where:
        [raw, words] << [['The invented original answer.', ''], ['An invented refusal.', '']].combinations()
    }

    def "a declined review publishes its original answer once with its exact flags while an empty answer remains a reply"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set reviewer_model = 'general', reviewer_mode = 'ordinary' where workflow_step_id = ?::uuid")
                .param(SUMMARISE).update()
        model.answering(new CallOutcome.CameBack('{"values":{"summary":"An invented printer needs a check."},"confidences":{}}',
                321, 12, true, false, null, null))
        produced(new CallOutcome.CameBack(raw, 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, 'Invented review refusal.'))

        when:
        def answer = steps.tryDetails(groupId(GROUP), runId(), stepId(), 1, ANN_USER)

        then:
        answer.modelReply().text() == '{"values":{"summary":"An invented printer needs a check."},"confidences":{}}'
        answer.review().declined().words().text() == 'Invented review refusal.'
        answer.review().declined().content() == (raw.isEmpty() ? null : new StepAnswers.DeclinedTextAnswer(raw, false, false))
        answer.review().modelReply() == (raw.isEmpty() ? new StepAnswers.ModelReplyRecord('', false, false) : null)
        store.texts("select answer from model_calls where purpose = 'review'") == [raw]
        store.count('select count(*) from productions') == 1

        where:
        raw << ['Invented original review answer.', '']
    }

    def "an unreadable try keeps all four service evidence fields and omits empty evidence"() {
        given:
        produced(new CallOutcome.Errored('The model service returned a response this system could not read.',
                new UnreadableResponseRecord(KeptAnswer.of(raw), stopped)))

        when:
        def answer = steps.tryDetails(groupId(GROUP), runId(), stepId(), 1, ANN_USER)

        then:
        answer.unreadableResponse() == (raw.isEmpty() ? null :
                new StepAnswers.UnreadableResponseRecord(raw, false, false, stopped))
        answer.modelReply() == null
        answer.declined() == null
        answer.review() == null

        where:
        [raw, stopped] << [['Invented partial service envelope.', ''], [false, true]].combinations()
    }

    def "the material reader with no inference permission prepares no model body query"() {
        given:
        produced(new CallOutcome.CameBack('Invented private raw answer.', 321, 12, true, false,
                DidNotFitReason.MODEL_DECLINED, 'Invented private refusal.'))
        def statements = []
        def database = JdbcClient.create(new CountingDataSource(store.database, statements))

        when:
        def answer = new RunTryDetails(database).read(runId(), stepId(), 1, false, null)

        then:
        answer == new StepAnswers.TryDetailsRecord(1, null, null, null, null, null, null, null)
        statements.size() == 1
        !statements[0].contains('model_calls')
        !statements[0].contains('answer')
        !statements[0].contains('declined_words')
    }

    def "the one try read loses another person's run when the same reader loses read-all standing"() {
        given:
        produced(new CallOutcome.CameBack('Invented scope evidence.', 321, 12, true, false, null, null))

        when:
        def before = steps.tryDetails(groupId(GROUP), runId(), stepId(), 1, ANN_USER)

        then:
        before.modelReply().text() == 'Invented scope evidence.'

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts and group-role enum literal.
        store.session.sql("update group_members set role = 'operator' where group_id = ?::uuid and subject_id = ?::uuid")
                .params(GROUP, TicketWorkflow.ANN).update()
        steps.tryDetails(groupId(GROUP), runId(), stepId(), 1, ANN_USER)

        then:
        def refusal = thrown(ApiErrorException)
        refusal.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
    }

    def "one numbered read selects only its actual production and never parses unrelated step or child values"() {
        given:
        produced(new CallOutcome.CameBack('Invented narrow evidence.', 321, 12, true, false, null, null))
        def question = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        def field = UUID.randomUUID().toString()
        def unrelated = UUID.randomUUID().toString()
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def child = UUID.randomUUID().toString()
        def childStep = UUID.randomUUID().toString()
        StepRows.question(store, question, questionVersion, GROUP, 'Invented long archive')
        StepRows.field(store, [id: field, owner: questionVersion, ownerKind: 'question', side: 'gives',
                              position: 1, name: 'archive', limit: 1000000, standing: 'always'])
        StepRows.step(store, unrelated, TicketWorkflow.VERSION, 4, 'archive', questionVersion, 'person', 1)
        StepRows.workflow(store, childEntry, childVersion, GROUP, 'Invented child archive')
        StepRows.step(store, childStep, childVersion, 1, 'archive', questionVersion, 'person', 1)
        RunRows.beneath(store, child, RUN, GROUP, 2, childEntry, childVersion,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3)
        def large = 'Invented archive line. ' * 10000
        storedPersonAnswer(RUN, unrelated, field, large)
        storedPersonAnswer(child, childStep, field, large)
        // DB-SPECIFIC: PostgreSQL ::uuid casts and jsonb text extraction.
        def production = UUID.fromString(store.texts("select production_id::text from productions where run_id = ?::uuid and producer = 'model'", RUN)[0])
        def actual = UUID.fromString(store.texts("select run_step_id::text from productions where production_id = ?::uuid", production)[0])
        assert store.texts("select value #>> '{}' from production_values where declaration_field_id = ?::uuid", field) == [large, large]
        def observed = []
        def dataSource = observing(store.database, observed)
        def database = JdbcClient.create(dataSource)
        def transactions = new DataSourceTransactionManager(dataSource)
        def roles = new GroupRoles(database)
        def snapshots = new RunSnapshots(database, CodeStepsHeld.NONE, new SearchWays([]))
        def reader = new RunSteps(database, roles, snapshots, transactions, Deployments.DEEP_ENOUGH,
                new GroupCurrencies(database, new TransactionTemplate(transactions), roles, DeployedModels.HELD), DeployedModels.HELD)

        when:
        def answer = reader.tryDetails(groupId(GROUP), runId(), stepId(), 1, ANN_USER)

        then:
        answer.modelReply().text() == 'Invented narrow evidence.'
        !observed.isEmpty()
        observed.every { !it.sql.contains('production_values') && !it.sql.contains('started_with') }
        observed.every { !it.columns.any { column -> column in ['value', 'started_with', 'constant'] } }
        def locator = observed.findAll { it.sql.contains('join workflow_steps planned') }
        locator.size() == 1
        locator[0].bindings == [UUID.fromString(RUN), UUID.fromString(SUMMARISE), 1]
        def evidence = observed.findAll { it.sql.contains('call.declined_words') }
        evidence.size() == 1
        evidence[0].bindings == [UUID.fromString(RUN), production, actual, actual]
        observed.every { !it.bindings.contains(UUID.fromString(child)) && !it.bindings.contains(UUID.fromString(unrelated)) }
    }

    def "the public one-try reader refuses #boundary without changing any stored evidence"() {
        given:
        produced(new CallOutcome.CameBack('Invented boundary evidence.', 321, 12, true, false, null, null))
        def group = boundary == 'another group' ? groupId(UUID.randomUUID().toString()) : groupId(GROUP)
        def run = boundary == 'another run' ? new RunId(UUID.randomUUID()) : runId()
        def step = boundary == 'another step' ? new WorkflowStepId(UUID.randomUUID()) :
                boundary == 'unspent planned step' ? new WorkflowStepId(UUID.fromString(TicketWorkflow.CONFIRM)) : stepId()
        def caller = boundary == 'nonmember' ? TicketWorkflow.DAN_USER : ANN_USER
        def number = boundary == 'zero' ? 0 : boundary == 'negative' ? -1 : boundary == 'unspent number' ? 2 : 1
        def before = store.texts("select answer from model_calls order by model_call_id")

        when:
        steps.tryDetails(group, run, step, number, caller)

        then:
        def refusal = thrown(ApiErrorException)
        refusal.errorCode() == refused
        store.texts("select answer from model_calls order by model_call_id") == before
        store.count('select count(*) from productions') == 1

        where:
        boundary               | refused
        'another group'        | RefusalCode.GROUP_NOT_IN_VIEW
        'nonmember'            | RefusalCode.GROUP_NOT_IN_VIEW
        'another run'          | RefusalCode.RUN_NOT_IN_VIEW
        'another step'         | RefusalCode.STEP_NOT_IN_VIEW
        'unspent planned step' | RefusalCode.STEP_NOT_IN_VIEW
        'zero'                 | RefusalCode.STEP_NOT_IN_VIEW
        'negative'             | RefusalCode.STEP_NOT_IN_VIEW
        'unspent number'       | RefusalCode.STEP_NOT_IN_VIEW
    }

    private void storedPersonAnswer(String run, String step, String field, String value) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum literals and RETURNING.
        def actual = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by)
                select ?::uuid, entry_version_id, workflow_step_id, kind, pinned_version_id, pinned_kind,
                       producer, reviewed_by_model, tries, ?::uuid from workflow_steps where workflow_step_id = ?::uuid
                returning run_step_id
                """).params(run, RunRows.WORKFLOW_RUNNER, step).query(UUID).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts and enum literals.
        store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, created_by_kind)
                select actual.run_step_id, actual.run_id, run.root_run_id, actual.kind, actual.producer,
                       actual.reviewed_by_model, actual.tries, actual.pinned_version_id, 1, 'person', ?::uuid, 'person'
                  from run_steps actual join runs run on run.run_id = actual.run_id where actual.run_step_id = ?::uuid
                """).params(TicketWorkflow.CAT, actual).update()
        StepRows.answered(store, step, TicketWorkflow.CAT, 'An invented archive was checked.', [(field): '"' + value + '"'])
    }

    /** Observes real prepared SQL, its bound identities and result columns without reading a value column. */
    private static DelegatingDataSource observing(javax.sql.DataSource target, List observed) {
        new DelegatingDataSource(target) {
            @Override
            Connection getConnection() {
                new ObservedConnection(super.getConnection(), observed)
            }
        }
    }

    private static final class ObservedConnection implements Connection {
        @Delegate
        private final Connection connection
        private final List observed

        ObservedConnection(Connection connection, List observed) {
            this.connection = connection
            this.observed = observed
        }

        private PreparedStatement observe(String sql, PreparedStatement statement) {
            def evidence = [sql: sql, bindings: [], columns: []]
            observed << evidence
            new ObservedStatement(statement, evidence)
        }

        PreparedStatement prepareStatement(String sql) {
            observe(sql, connection.prepareStatement(sql))
        }

        PreparedStatement prepareStatement(String sql, int keys) {
            observe(sql, connection.prepareStatement(sql, keys))
        }

        PreparedStatement prepareStatement(String sql, int[] columns) {
            observe(sql, connection.prepareStatement(sql, columns))
        }

        PreparedStatement prepareStatement(String sql, String[] columns) {
            observe(sql, connection.prepareStatement(sql, columns))
        }

        PreparedStatement prepareStatement(String sql, int type, int concurrency) {
            observe(sql, connection.prepareStatement(sql, type, concurrency))
        }

        PreparedStatement prepareStatement(String sql, int type, int concurrency, int holdability) {
            observe(sql, connection.prepareStatement(sql, type, concurrency, holdability))
        }
    }

    private static final class ObservedStatement implements PreparedStatement {
        @Delegate
        private final PreparedStatement statement
        private final Map evidence

        ObservedStatement(PreparedStatement statement, Map evidence) {
            this.statement = statement
            this.evidence = evidence
        }

        java.sql.ResultSet executeQuery() {
            recordColumns(statement.executeQuery())
        }

        java.sql.ResultSet executeQuery(String sql) {
            recordColumns(statement.executeQuery(sql))
        }

        private java.sql.ResultSet recordColumns(java.sql.ResultSet result) {
            def metadata = result.metaData
            evidence.columns = (1..metadata.columnCount).collect { metadata.getColumnLabel(it) }
            result
        }

        void setNull(int parameterIndex, int sqlType) {
            evidence.bindings[parameterIndex - 1] = sqlType
            statement.setNull(parameterIndex, sqlType)
        }

        void setBoolean(int parameterIndex, boolean x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setBoolean(parameterIndex, x)
        }

        void setByte(int parameterIndex, byte x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setByte(parameterIndex, x)
        }

        void setShort(int parameterIndex, short x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setShort(parameterIndex, x)
        }

        void setInt(int parameterIndex, int x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setInt(parameterIndex, x)
        }

        void setLong(int parameterIndex, long x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setLong(parameterIndex, x)
        }

        void setFloat(int parameterIndex, float x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setFloat(parameterIndex, x)
        }

        void setDouble(int parameterIndex, double x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setDouble(parameterIndex, x)
        }

        void setBigDecimal(int parameterIndex, java.math.BigDecimal x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setBigDecimal(parameterIndex, x)
        }

        void setString(int parameterIndex, String x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setString(parameterIndex, x)
        }

        void setBytes(int parameterIndex, byte[] x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setBytes(parameterIndex, x)
        }

        void setDate(int parameterIndex, java.sql.Date x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setDate(parameterIndex, x)
        }

        void setTime(int parameterIndex, java.sql.Time x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setTime(parameterIndex, x)
        }

        void setTimestamp(int parameterIndex, java.sql.Timestamp x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setTimestamp(parameterIndex, x)
        }

        void setAsciiStream(int parameterIndex, java.io.InputStream x, int length) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setAsciiStream(parameterIndex, x, length)
        }

        void setUnicodeStream(int parameterIndex, java.io.InputStream x, int length) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setUnicodeStream(parameterIndex, x, length)
        }

        void setBinaryStream(int parameterIndex, java.io.InputStream x, int length) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setBinaryStream(parameterIndex, x, length)
        }

        void setObject(int parameterIndex, Object x, int targetSqlType) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setObject(parameterIndex, x, targetSqlType)
        }

        void setObject(int parameterIndex, Object x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setObject(parameterIndex, x)
        }

        void setCharacterStream(int parameterIndex, java.io.Reader reader, int length) {
            evidence.bindings[parameterIndex - 1] = reader
            statement.setCharacterStream(parameterIndex, reader, length)
        }

        void setRef(int parameterIndex, java.sql.Ref x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setRef(parameterIndex, x)
        }

        void setBlob(int parameterIndex, java.sql.Blob x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setBlob(parameterIndex, x)
        }

        void setClob(int parameterIndex, java.sql.Clob x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setClob(parameterIndex, x)
        }

        void setArray(int parameterIndex, java.sql.Array x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setArray(parameterIndex, x)
        }

        void setDate(int parameterIndex, java.sql.Date x, java.util.Calendar cal) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setDate(parameterIndex, x, cal)
        }

        void setTime(int parameterIndex, java.sql.Time x, java.util.Calendar cal) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setTime(parameterIndex, x, cal)
        }

        void setTimestamp(int parameterIndex, java.sql.Timestamp x, java.util.Calendar cal) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setTimestamp(parameterIndex, x, cal)
        }

        void setNull(int parameterIndex, int sqlType, String typeName) {
            evidence.bindings[parameterIndex - 1] = sqlType
            statement.setNull(parameterIndex, sqlType, typeName)
        }

        void setURL(int parameterIndex, java.net.URL x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setURL(parameterIndex, x)
        }

        void setRowId(int parameterIndex, java.sql.RowId x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setRowId(parameterIndex, x)
        }

        void setNString(int parameterIndex, String value) {
            evidence.bindings[parameterIndex - 1] = value
            statement.setNString(parameterIndex, value)
        }

        void setNCharacterStream(int parameterIndex, java.io.Reader value, long length) {
            evidence.bindings[parameterIndex - 1] = value
            statement.setNCharacterStream(parameterIndex, value, length)
        }

        void setNClob(int parameterIndex, java.sql.NClob value) {
            evidence.bindings[parameterIndex - 1] = value
            statement.setNClob(parameterIndex, value)
        }

        void setClob(int parameterIndex, java.io.Reader reader, long length) {
            evidence.bindings[parameterIndex - 1] = reader
            statement.setClob(parameterIndex, reader, length)
        }

        void setBlob(int parameterIndex, java.io.InputStream inputStream, long length) {
            evidence.bindings[parameterIndex - 1] = inputStream
            statement.setBlob(parameterIndex, inputStream, length)
        }

        void setNClob(int parameterIndex, java.io.Reader reader, long length) {
            evidence.bindings[parameterIndex - 1] = reader
            statement.setNClob(parameterIndex, reader, length)
        }

        void setSQLXML(int parameterIndex, java.sql.SQLXML xmlObject) {
            evidence.bindings[parameterIndex - 1] = xmlObject
            statement.setSQLXML(parameterIndex, xmlObject)
        }

        void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setObject(parameterIndex, x, targetSqlType, scaleOrLength)
        }

        void setAsciiStream(int parameterIndex, java.io.InputStream x, long length) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setAsciiStream(parameterIndex, x, length)
        }

        void setBinaryStream(int parameterIndex, java.io.InputStream x, long length) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setBinaryStream(parameterIndex, x, length)
        }

        void setCharacterStream(int parameterIndex, java.io.Reader reader, long length) {
            evidence.bindings[parameterIndex - 1] = reader
            statement.setCharacterStream(parameterIndex, reader, length)
        }

        void setAsciiStream(int parameterIndex, java.io.InputStream x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setAsciiStream(parameterIndex, x)
        }

        void setBinaryStream(int parameterIndex, java.io.InputStream x) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setBinaryStream(parameterIndex, x)
        }

        void setCharacterStream(int parameterIndex, java.io.Reader reader) {
            evidence.bindings[parameterIndex - 1] = reader
            statement.setCharacterStream(parameterIndex, reader)
        }

        void setNCharacterStream(int parameterIndex, java.io.Reader value) {
            evidence.bindings[parameterIndex - 1] = value
            statement.setNCharacterStream(parameterIndex, value)
        }

        void setClob(int parameterIndex, java.io.Reader reader) {
            evidence.bindings[parameterIndex - 1] = reader
            statement.setClob(parameterIndex, reader)
        }

        void setBlob(int parameterIndex, java.io.InputStream inputStream) {
            evidence.bindings[parameterIndex - 1] = inputStream
            statement.setBlob(parameterIndex, inputStream)
        }

        void setNClob(int parameterIndex, java.io.Reader reader) {
            evidence.bindings[parameterIndex - 1] = reader
            statement.setNClob(parameterIndex, reader)
        }

        void setObject(int parameterIndex, Object x, java.sql.SQLType targetSqlType, int scaleOrLength) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setObject(parameterIndex, x, targetSqlType, scaleOrLength)
        }

        void setObject(int parameterIndex, Object x, java.sql.SQLType targetSqlType) {
            evidence.bindings[parameterIndex - 1] = x
            statement.setObject(parameterIndex, x, targetSqlType)
        }
    }
    def "woven detail records reveal neither original text nor the flags belonging to it"() {
        given:
        def reply = new StepAnswers.ModelReplyRecord('Invented private model words.', true, true)
        def review = new StepAnswers.ReviewDetailsRecord(null, null, reply)
        def details = new StepAnswers.TryDetailsRecord(1,
                new StepAnswers.WentWrongAnswer('Invented private code failure.', true, null),
                'Invented private code output.', null, null, reply, review, null)

        when:
        def replyRendered = reply.toString()
        def reviewRendered = review.toString()
        def detailRendered = details.toString()

        then:
        replyRendered == 'StepAnswers$ModelReplyRecord()'
        reviewRendered == 'StepAnswers$ReviewDetailsRecord()'
        detailRendered == 'StepAnswers$TryDetailsRecord(number=1)'
        ![replyRendered, reviewRendered, detailRendered].any { it.contains('private') || it.contains('truncated=') }
    }
}
