package com.lilradish.lite.app.start

import static java.nio.charset.StandardCharsets.UTF_8

import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.codestep.ReadTextFile
import com.lilradish.lite.app.codestep.development.StampReference
import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.app.files.RunFiles
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.inference.ToolModelCalls
import com.lilradish.lite.app.inference.development.DevelopmentCallConfiguration
import com.lilradish.lite.app.inference.development.DevelopmentModelCalls
import com.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import com.lilradish.lite.app.inference.development.DevelopmentToolModelCalls
import com.lilradish.lite.app.library.ContentChecks
import com.lilradish.lite.app.library.OffersHeld
import com.lilradish.lite.app.library.QuestionsAsked
import com.lilradish.lite.app.run.Deployments
import com.lilradish.lite.app.run.EngineWiring
import com.lilradish.lite.app.run.RunSnapshots
import com.lilradish.lite.app.run.Runs
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.app.search.development.DevelopmentSearchWays
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.declaration.TextLines
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.filling.FillField
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.SendMeasure
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.search.SearchAnswer
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.DevelopmentSeed
import com.lilradish.lite.testutil.ReadyServletStreams
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.web.ActAdmission
import com.lilradish.lite.web.CallerAdmission
import com.lilradish.lite.web.FileTransfers
import jakarta.servlet.http.HttpServletRequest
import java.util.regex.Pattern
import org.libprunus.core.error.ApiErrorException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.core.type.filter.RegexPatternTypeFilter
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncUtils
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import tools.jackson.databind.json.JsonMapper

class DevelopmentFileSearchSeedIntegrationSpec extends Specification {

    static final GroupId SUPPORT = new GroupId(UUID.fromString('00000002-0000-4000-8000-000000000101'))
    static final UserId GRACE = new UserId('000102')
    static final UserId ALAN = new UserId('000103')
    static final String VERSION = '00000007-0000-4000-8000-000000000109'
    static final WorkflowStepId RESEARCH = new WorkflowStepId(UUID.fromString('0000000c-0000-4000-8000-000000000105'))
    static final CodeSteps RELEASE = new CodeSteps([new StampReference(), new ReadTextFile()])
    static final JsonMapper JSON = JsonMapper.builder().build()

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.isolated()

    @Shared
    int databasesMade = 0

    @Shared
    ModelCatalog catalog

    @Shared
    SearchWays ways

    @Shared
    ToolModelCalls tools

    LibraryStore store
    EngineWiring wiring
    StartRunsController starting
    FileTransfers transfers
    ThreadPoolTaskExecutor uploadWorkers
    RunFiles files

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
        DevelopmentSeed.catalogBinding()
                .withPropertyValues('spring.profiles.active=local')
                .withUserConfiguration(DevelopmentCallConfiguration, DevelopmentSearchWays, SearchWays)
                .withBean(DevelopmentSearchConversion)
                .withBean(DevelopmentToolModelCalls)
                .run { context ->
                    assert context.startupFailure == null
                    catalog = context.getBean(ModelCatalog)
                    ways = context.getBean(SearchWays)
                    tools = context.getBean(ToolModelCalls)
                }
    }

    def setup() {
        assert com.lilradish.lite.domain.observability.Correlation.CURRENT.get() == null
        store = LibraryStore.forCase(server, 'development_file_search_' + (++databasesMade), true)
        DevelopmentSeed.appliedTo(store.database)
        wiring = new EngineWiring(store, new DevelopmentModelCalls(), RELEASE, catalog, RELEASE,
                Deployments.DEEP_ENOUGH, EngineWiring.fileDeployment(), ways, tools)
        files = EngineWiring.files(store)
        def roles = new GroupRoles(store.session)
        uploadWorkers = new ThreadPoolTaskExecutor()
        uploadWorkers.setVirtualThreads(true)
        uploadWorkers.initialize()
        transfers = new FileTransfers(EngineWiring.fileDeployment(), System::nanoTime, uploadWorkers)
        starting = new StartRunsController(new StartRuns(store.session, store.transactions(), roles,
                wiring.tree, wiring.engine, files, EngineWiring.fileDeployment()), transfers)
    }

    def cleanup() {
        wiring?.close()
        transfers?.close()
        uploadWorkers?.destroy()
        try {
            assert com.lilradish.lite.domain.observability.Correlation.CURRENT.get() == null
            assert org.slf4j.MDC.get('correlationId') == null
        } finally {
            com.lilradish.lite.domain.observability.Correlation.clear()
            org.slf4j.MDC.clear()
        }
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts and a native step_kind enum literal.
    def 'the offered attachment workflow keeps its file separate from the bounded search topic'() {
        when:
        def workflow = snapshot(run(13)).workflow()
        def fields = workflow.takes().fields()
        def offered = store.transactions().execute {
            OffersHeld.held(store.session,
                    new GroupRoles(store.session).stillReached(GRACE, SUPPORT, GroupPermission.START_RUN),
                    new EntryVersionId(UUID.fromString(VERSION)))
        }

        then:
        fields*.name()*.value() == ['topic', 'attachment']
        fields[0].shape() == new FieldShape.Text(512, TextLines.ONE)
        fields[1].shape() instanceof FieldShape.File
        fields[1].shape().types()*.value() == ['.txt', '.md', '.csv', '.json']
        fields[1].shape().maximumBytes() == 2097152L
        fields.every { it.demand().mustBe() }
        fields[0].howMany() instanceof HowMany.One
        fields[1].howMany() == new HowMany.Many(3)
        offered.present
        offered.get().entry().value().toString() == '00000006-0000-4000-8000-000000000106'
        offered.get().takes() == FillField.of(fields, [:])
        workflow.gives().fields()*.name()*.value() == ['title', 'body', 'references']
        workflow.gives().fields()*.shape() == ways.all()['sample_web_search'].outputs().take(3)*.shape()
        workflow.gives().fields()*.demand() == ways.all()['sample_web_search'].outputs().take(3)*.demand()
        store.texts("""select binding.source_path from bindings binding
                join workflow_steps step using (workflow_step_id)
                where step.kind = 'search' and step.entry_version_id = '${VERSION}'::uuid""") as Set == ['topic', 'text'] as Set
    }

    def 'the stored attachment workflow passes the content check used at submission against the real release'() {
        when:
        def result = null
        new AnnotationConfigApplicationContext().withCloseable { context ->
            context.registerBean(JdbcClient, { -> store.session })
            context.registerBean(CodeSteps, { -> RELEASE })
            context.registerBean(ModelCatalog, { -> catalog })
            context.registerBean(FileDeployment, { -> EngineWiring.fileDeployment() })
            context.registerBean(SearchWays, { -> ways })
            def scanner = new ClassPathBeanDefinitionScanner(context, false)
            scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(
                    'com[.]lilradish[.]lite[.]app[.]library[.](ContentChecks|WorkflowContentCheck|' +
                            'QuestionContentCheck|ReferenceListContentCheck|ReleasedCodeSteps)')))
            scanner.scan('com.lilradish.lite.app.library')
            context.refresh()
            def reading = new TransactionTemplate(store.transactionManager())
            reading.readOnly = true
            reading.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            result = reading.execute {
                context.getBean(ContentChecks).problemsIn(com.lilradish.lite.domain.registry.EntryKind.WORKFLOW, SUPPORT, new EntryVersionId(UUID.fromString(VERSION)))
            }
        }

        def asked = new QuestionsAsked(store.session, store.transactionManager()).of(SUPPORT,
                new EntryVersionId(UUID.fromString('00000007-0000-4000-8000-000000000112')))
        def producer = snapshot(run(13)).workflow().step(RESEARCH).orElseThrow().producer()
        def maximum = SendMeasure.mostSentToSearch(new Asking(asked.instruction(), asked.takes(),
                SearchAnswer.asked(ways.all()['sample_web_search'])), producer.toldWhatHappened())
        println "Demo file search maximum sent: ${maximum}; allowed: ${Declaration.MOST_SENT}"

        then:
        result.total() == 0L
        result.problems().empty
        maximum <= Declaration.MOST_SENT
    }

    def 'each seeded attachment downloads its stored bytes and exact metadata for run #number'() {
        when:
        def snapshot = snapshot(run(number))
        def values = named(snapshot.startedWith())
        def attachments = values.attachment.items().collect { StoredFile.fromKept(it) }
        def responses = attachments.collect { downloaded(run(number), it) }

        then:
        values.keySet() == ['topic', 'attachment'] as Set
        values.topic instanceof JsonValue.JsonString
        attachments.size() == 2
        attachments.withIndex().every { file, index ->
            def response = responses[index]
            file.name().value().startsWith('Mooncrate ') &&
                    file.kept().members()*.name() == ['id', 'name', 'size'] &&
                    response.contentAsByteArray == storedBytes(file.id()) &&
                    response.contentAsByteArray.length == file.size() &&
                    response.getHeader('Content-Disposition').startsWith('attachment;') &&
                    response.getHeader('Content-Type') == 'application/octet-stream' &&
                    response.getHeader('Content-Length') == file.size().toString() &&
                    response.getHeader('X-Content-Type-Options') == 'nosniff' &&
                    store.count('select count(*) from run_file_holders where file_id = ?', file.id()) == 1
        }
        new String(responses[0].contentAsByteArray, UTF_8).startsWith('Invented Mooncrate claim DEMO-CLAIM-17:')
        new String(responses[1].contentAsByteArray, UTF_8).startsWith('# Invented evidence note\n')
        store.count('select count(*) from run_file_holders where run_id = ?', run(number).value()) == 2
        store.count('select count(*) from stored_files') == store.count('select count(*) from run_file_holders')

        where:
        number << [13, 14, 15]
    }

    def 'a new run accepts #filenames in order and searches all their texts before a person assures the body'() {
        given:
        def texts = filenames.withIndex().collect { name, index -> "Invented file ${index + 1}: ${name} says keep its evidence.\n" as String }
        def bytes = texts.collect { it.getBytes(UTF_8) }
        def request = upload(filenames, bytes)
        def otherCalls = store.count('select count(*) from model_calls')
        def highestNumber = store.count('select max(number) from runs where group_id = ?', SUPPORT.value())
        def asked = 'Demo Sample fresh topic'
        def sent = JSON.writeValueAsString([envelope_version: 6,
                instruction: 'Read the supplied texts alongside the fictional topic. Find supporting evidence, identify missing proof and report sources for each text.',
                takes: [topic: asked, text: texts]])
        def replied = JSON.readTree(DevelopmentSearchConversion.replyTo(
                JSON.writeValueAsBytes([question: sent, tools: [[name: 'web_search', maximum: 3]]])))

        when:
        def response = start(request)
        def started = new RunId(response.body.runId())
        wiring.drained()
        def beforeReview = snapshot(started)
        def attachments = named(beforeReview.startedWith()).attachment.items().collect { StoredFile.fromKept(it) }

        then:
        response.statusCode.value() == 201
        response.body.number() == highestNumber + 1
        new Runs(store.session, new GroupRoles(store.session), store.transactionManager(),
                new RunSnapshots(store.session, RELEASE, ways)).run(SUPPORT, started, GRACE).runId() == started
        positions(started) == ['Done', 'AwaitingReview']
        attachments*.name()*.value() == filenames
        attachments.withIndex().every { file, index ->
            downloaded(started, file).contentAsByteArray == bytes[index] && file.kept().members()*.name() == ['id', 'name', 'size']
        }
        callFacts(started).size() == 1
        def facts = callFacts(started).first()
        JSON.readTree(facts.payload) == JSON.readTree(sent)
        def query = asked + texts.withIndex().collect { text, index -> "\nFile ${index + 1}: " + text }.join('')
        def observations = callSearches(started)
        observations*.searched == [query, query + ' / evidence ledger']
        observations*.found == replied.get('observations').collect { it.get('found').stringValue() }
        facts.answer == replied.get('answer').stringValue()
        facts.envelope_version == 6
        callFacts(run(14)).first().envelope_version == 5
        facts.subMap(['sent_count', 'came_back_count', 'counted_by_model', 'tool', 'most_uses', 'uses', 'uses_said']) ==
                callFacts(run(14)).first().subMap(['sent_count', 'came_back_count', 'counted_by_model', 'tool', 'most_uses', 'uses', 'uses_said'])
        callPrices(started) == [[currency: 'EUR', per_use: 0.02G], [currency: 'USD', per_use: 0.01G]]
        callPrices(started) == callPrices(run(14))
        store.count('select count(*) from model_calls') == otherCalls + 1
        beforeReview.steps().last().tries().size() == 1
        beforeReview.steps().first().tries().size() == 1
        // DB-SPECIFIC: PostgreSQL :: casts to text and uuid.
        def storedText = store.texts("select value::text from production_values where production_id = '" + beforeReview.steps().first().tries().first().id().value() + "'::uuid")
        storedText.size() == 1
        JSON.readTree(storedText.first()) == JSON.readTree(JSON.writeValueAsString(texts))

        when:
        wiring.assure(SUPPORT, started, RESEARCH, 1, ALAN, ['body'] as Set)
        wiring.drained()

        then:
        positions(started) == ['Done', 'Done']
        snapshot(started).steps().last().tries().size() == 1
        callFacts(started).size() == 1
        store.count('select count(*) from model_calls') == otherCalls + 1
        attachments.withIndex().every { file, index -> downloaded(started, file).contentAsByteArray == bytes[index] }

        where:
        filenames << [['Demo Sample fresh.txt', 'Demo Sample packing.txt'], ['Demo Sample fresh.md', 'Demo Sample packing.md'],
                      ['Demo Sample fresh.csv', 'Demo Sample packing.csv'], ['Demo Sample fresh.json', 'Demo Sample packing.json'],
                      ['Demo Sample claim.txt', 'Demo Sample evidence.md', 'Demo Sample ledger.csv']]
    }

    def 'a valid upload containing invalid UTF-8 fails reading by filename before any search starts'() {
        given:
        def before = counts()
        def request = upload(['Demo Sample good.txt', 'Demo Sample invalid.txt'], ['Invented good note'.getBytes(UTF_8), [0xC3, 0x28] as byte[]])

        when:
        def response = start(request)
        def started = new RunId(response.body.runId())
        wiring.drained()

        then:
        positions(started) == ['Failed', 'NotStarted']
        counts().calls == before.calls
        // DB-SPECIFIC: PostgreSQL :: cast to uuid.
        def failures = store.texts("select lost_detail from productions where run_id = '" + started.value() + "'::uuid")
        failures.size() == 2
        failures.every { it.contains("Demo Sample invalid.txt isn't plain text") }
    }

    def 'a new run refuses #reason without leaving a run, file, holder or model call behind'() {
        given:
        def before = counts()
        def request = upload(mediaType, filename, new byte[length])

        when:
        start(request)

        then:
        thrown(ApiErrorException)
        counts() == before

        where:
        reason                  | mediaType         | filename                  | length
        'an unaccepted extension' | 'text/plain'    | 'Demo Sample refused.exe' | 8
        'a file over 2 MiB'      | 'text/plain'      | 'Demo Sample refused.txt' | 2097153
    }

    def 'opening the attachment-ready run again dispatches one search beyond the tries it holds, which stay as they were'() {
        given:
        def stopped = run(13)
        def held = snapshot(stopped).steps().last().tries()
        def heldCalls = callFacts(stopped)
        def before = counts()

        when:
        wiring.openAgain(store, SUPPORT, stopped, GRACE)
        wiring.drained()
        def tries = snapshot(stopped).steps().last().tries()
        def calls = callFacts(stopped)

        then:
        positions(stopped) == ['Done', 'AwaitingReview']
        tries.size() == held.size() + 1
        tries.take(held.size()) == held
        calls.size() == heldCalls.size() + 1
        calls.containsAll(heldCalls)
        counts().calls == before.calls + 1
        counts().files == before.files
        counts().contents == before.contents
        counts().holders == before.holders
    }

    def 'assuring the waiting seeded research completes it without another search or file write'() {
        given:
        def before = counts()

        when:
        wiring.assure(SUPPORT, run(14), RESEARCH, 1, ALAN, ['body'] as Set)
        wiring.drained()

        then:
        positions(run(14)) == ['Done', 'Done']
        counts() == before
        callFacts(run(14)).size() == 1
    }

    private def snapshot(RunId run) {
        store.transactions().execute { wiring.snapshots.asRead(SUPPORT, run) }
    }

    private List<String> positions(RunId run) {
        StepPositions.of(snapshot(run)).collect { it.getClass().simpleName }
    }

    private static Map<String, JsonValue> named(JsonValue.JsonObject object) {
        object.members().collectEntries { [(it.name()): it.value()] }
    }

    private static RunId run(int number) {
        new RunId(UUID.fromString('0000000f-0000-4000-8000-' + String.format('%012d', 100 + number)))
    }

    private byte[] storedBytes(UUID file) {
        store.session.sql('select content from file_contents where file_id = :file')
                .param('file', file).query(byte[].class).single()
    }

    private MockHttpServletResponse downloaded(RunId run, StoredFile file) {
        EngineWiring.downloaded(store, files, SUPPORT, run, file, GRACE)
    }

    private static MockHttpServletRequest admitted(String method) {
        def request = new MockHttpServletRequest(method, '/api/groups/' + SUPPORT.value() + '/runs')
        request.setAttribute(ActAdmission.name + '.group', SUPPORT)
        request.setAttribute(CallerAdmission.name + '.caller', GRACE)
        request
    }

    private Object start(HttpServletRequest request) {
        def response = new MockHttpServletResponse()
        def manager = WebAsyncUtils.getAsyncManager(request)
        manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(request, response))
        new com.lilradish.lite.web.RequestTrace().doFilter(request, response, { asked, answer ->
            def task = starting.start(asked, answer)
            manager.startDeferredResultProcessing(task)
        } as jakarta.servlet.FilterChain)
        try {
            new PollingConditions(timeout: 10).eventually {
                assert manager.hasConcurrentResult()
                assert uploadWorkers.activeCount == 0
            }
            def answered = manager.concurrentResult
            if (answered instanceof Throwable) throw answered
            answered
        } finally {
            request.asyncContext.complete()
        }
    }

    private static HttpServletRequest upload(String type, String filename, byte[] bytes) {
        upload([filename], [bytes], type)
    }

    private static HttpServletRequest upload(List<String> filenames, List<byte[]> contents, String type = 'text/plain') {
        def request = admitted('POST')
        request.contentType = 'multipart/form-data; boundary=DemoSample'
        String document = JSON.writeValueAsString([name: 'Demo Sample fresh research', versionId: VERSION,
                values: [topic: 'Demo Sample fresh topic', attachment: filenames.withIndex().collect { name, index -> [upload: index, name: name] }]])
        def wire = new ByteArrayOutputStream()
        wire.write(('--DemoSample\r\nContent-Disposition: form-data; name="document"\r\n\r\n' + document).getBytes(UTF_8))
        filenames.withIndex().each { name, index ->
            wire.write(('\r\n--DemoSample\r\nContent-Disposition: form-data; name="upload' + index + '"; filename="' + name +
                    '"\r\nContent-Type: ' + type + '\r\n\r\n').getBytes(UTF_8))
            wire.write(contents[index])
        }
        wire.write('\r\n--DemoSample--\r\n'.getBytes(UTF_8))
        request.content = wire.toByteArray()
        request.asyncSupported = true
        ReadyServletStreams.request(request)
    }

    private Map<String, Long> counts() {
        [runs: 'runs', files: 'stored_files', contents: 'file_contents', holders: 'run_file_holders', calls: 'model_calls']
                .collectEntries { key, table -> [(key): store.count('select count(*) from ' + table)] }
    }

    private List<Map<String, Object>> callFacts(RunId run) {
        store.session.sql("""
                select sent.payload, call.answer, call.envelope_version,
                       call.sent_count, call.came_back_count, call.counted_by_model,
                       used.tool, used.most_uses, used.uses, used.uses_said
                  from model_calls call
                  join run_step_send_attempts sent using (run_step_send_attempt_id)
                  join model_call_tool_uses used using (model_call_id)
                 where call.run_id = :run
                """).param('run', run.value()).query { result, row ->
            (1..result.metaData.columnCount).collectEntries { column ->
                [(result.metaData.getColumnLabel(column)): result.getObject(column)]
            }
        }.list()
    }
    private List<Map<String, Object>> callSearches(RunId run) {
        store.session.sql("""
                select search.searched_for as searched, search.found
                  from model_call_searches search join model_calls call using (model_call_id)
                 where call.run_id = :run order by call.created_at, call.model_call_id, search.position
                """).param('run', run.value()).query().listOfRows()
    }

    private List<Map<String, Object>> callPrices(RunId run) {
        store.session.sql("""
                select price.currency, price.per_use
                  from model_call_tool_prices price join model_calls call using (model_call_id)
                 where call.run_id = :run order by call.created_at, call.model_call_id, price.currency
                """).param('run', run.value()).query().listOfRows()
    }


}
