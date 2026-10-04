package org.lilradish.lite.app.start

import static java.nio.charset.StandardCharsets.UTF_8

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.regex.Pattern
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.codestep.development.StampReference
import org.lilradish.lite.app.files.FileDeployment
import org.lilradish.lite.app.files.RunFiles
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.inference.ToolModelCalls
import org.lilradish.lite.app.inference.development.DevelopmentCallConfiguration
import org.lilradish.lite.app.inference.development.DevelopmentModelCalls
import org.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import org.lilradish.lite.app.inference.development.DevelopmentToolModelCalls
import org.lilradish.lite.app.library.ContentChecks
import org.lilradish.lite.app.library.OffersHeld
import org.lilradish.lite.app.run.Deployments
import org.lilradish.lite.app.run.EngineWiring
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.app.search.development.DevelopmentSearchWays
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.TextLines
import org.lilradish.lite.domain.file.StoredFile
import org.lilradish.lite.domain.filling.FillField
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.DevelopmentSeed
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.web.ActAdmission
import org.lilradish.lite.web.CallerAdmission
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner
import org.springframework.core.type.filter.RegexPatternTypeFilter
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class DevelopmentFileSearchSeedIntegrationSpec extends Specification {

    static final GroupId SUPPORT = new GroupId(UUID.fromString('00000002-0000-4000-8000-000000000101'))
    static final UserId GRACE = new UserId('000102')
    static final UserId ALAN = new UserId('000103')
    static final String VERSION = '00000007-0000-4000-8000-000000000109'
    static final WorkflowStepId RESEARCH = new WorkflowStepId(UUID.fromString('0000000c-0000-4000-8000-000000000105'))
    static final CodeSteps RELEASE = new CodeSteps([new StampReference()])
    static final JsonMapper JSON = JsonMapper.builder().build()

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

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
    RunFiles files

    def setupSpec() {
        LibraryStore.template(server)
        DevelopmentSeed.catalogBinding()
                .withPropertyValues('spring.profiles.active=dev')
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
        store = LibraryStore.copied(server, 'development_file_search_' + (++databasesMade))
        DevelopmentSeed.appliedTo(store.database)
        wiring = new EngineWiring(store, new DevelopmentModelCalls(), RELEASE, catalog, RELEASE,
                Deployments.DEEP_ENOUGH, EngineWiring.fileDeployment(), ways, tools)
        files = EngineWiring.files(store)
        def roles = new GroupRoles(store.session)
        starting = new StartRunsController(new StartRuns(store.session, store.transactions(), roles,
                wiring.tree, wiring.engine, files, EngineWiring.fileDeployment()))
    }

    def cleanup() {
        wiring?.close()
    }

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
        fields[1].shape().types()*.value() == ['application/pdf', 'image/png']
        fields[1].shape().maximumBytes() == 2097152L
        fields.every { it.demand().mustBe() && it.howMany() instanceof HowMany.One }
        offered.present
        offered.get().entry().value().toString() == '00000006-0000-4000-8000-000000000106'
        offered.get().takes() == FillField.of(fields, [:])
        workflow.gives().fields()*.name()*.value() == ['title', 'body', 'references']
        workflow.gives().fields()*.shape() == ways.all()['sample_web_search'].outputs().take(3)*.shape()
        workflow.gives().fields()*.demand() == ways.all()['sample_web_search'].outputs().take(3)*.demand()
        store.texts('select source_path from search_question_parts where source_path is not null') == ['topic']
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
                    'org[.]lilradish[.]lite[.]app[.]library[.](ContentChecks|WorkflowContentCheck|' +
                            'QuestionContentCheck|ReferenceListContentCheck|ReleasedCodeSteps)')))
            scanner.scan('org.lilradish.lite.app.library')
            context.refresh()
            def reading = new TransactionTemplate(store.transactionManager())
            reading.readOnly = true
            reading.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            result = reading.execute {
                context.getBean(ContentChecks).recheck(SUPPORT, new EntryVersionId(UUID.fromString(VERSION)))
            }
        }

        then:
        result.total() == 0L
        result.problems().empty
    }

    def 'each seeded attachment downloads its stored bytes and exact metadata for run #number'() {
        when:
        def snapshot = snapshot(run(number))
        def values = named(snapshot.startedWith())
        def file = StoredFile.fromKept(values.attachment)
        def response = downloaded(run(number), file)

        then:
        values.keySet() == ['topic', 'attachment'] as Set
        values.topic instanceof JsonValue.JsonString
        file.name().value().startsWith('Demo Sample ')
        file.type().value() == 'application/pdf'
        response.contentAsByteArray == storedBytes(file.id())
        response.contentAsByteArray.length == file.size()
        response.contentAsString.startsWith('%PDF-1.4\n')
        response.contentAsString.endsWith('%%EOF\n')
        response.contentAsString.contains('(Demo Sample attachment) Tj')
        response.getHeader('Content-Disposition').startsWith('attachment;')
        response.getHeader('Content-Type') == file.type().value()
        response.getHeader('Content-Length') == file.size().toString()
        response.getHeader('X-Content-Type-Options') == 'nosniff'
        store.count('select count(*) from stored_files') == 3
        store.count('select count(*) from file_contents') == 3
        store.count('select count(*) from run_file_holders') == 3

        where:
        number << [13, 14, 15]
    }

    def 'a new run accepts #mediaType and genuinely searches its topic before a person assures the body'() {
        given:
        byte[] bytes = mediaType == 'application/pdf' ? storedBytes(UUID.fromString('00000018-0000-4000-8000-000000000113')) :
                Base64.decoder.decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4l8AAAAASUVORK5CYII=')
        def request = upload(mediaType, filename, bytes)
        def otherCalls = store.count('select count(*) from model_calls')

        when:
        def response = starting.start(request)
        def started = new RunId(response.body.runId())
        wiring.drained()
        def beforeReview = snapshot(started)
        def file = StoredFile.fromKept(named(beforeReview.startedWith()).attachment)

        then:
        response.statusCode.value() == 201
        response.body.number() == 16
        response.body.readable()
        positions(started) == ['AwaitingReview']
        downloaded(started, file).contentAsByteArray == bytes
        file.type().value() == mediaType
        file.name().value() == filename
        callFacts(started) == callFacts(run(14)).collect { it + [question: 'Find information about Demo Sample fresh topic',
                payload: '{"envelope_version":5,"question":"Find information about Demo Sample fresh topic"}',
                searched: '{"envelope_version":5,"question":"Find information about Demo Sample fresh topic"}'] }
        store.count('select count(*) from model_calls') == otherCalls + 1
        beforeReview.steps().first().tries().size() == 1

        when:
        wiring.assure(SUPPORT, started, RESEARCH, 1, ALAN, ['body'] as Set)
        wiring.drained()

        then:
        positions(started) == ['Done']
        snapshot(started).steps().first().tries().size() == 1
        callFacts(started).size() == 1
        store.count('select count(*) from model_calls') == otherCalls + 1
        downloaded(started, file).contentAsByteArray == bytes

        where:
        mediaType         || filename
        'application/pdf' || 'Demo Sample fresh.pdf'
        'image/png'       || 'Demo Sample fresh.png'
    }

    def 'a new run refuses #reason without leaving a run, file, holder or model call behind'() {
        given:
        def before = counts()
        def request = upload(mediaType, 'Demo Sample refused.pdf', new byte[length])

        when:
        starting.start(request)

        then:
        thrown(ApiErrorException)
        counts() == before

        where:
        reason             | mediaType         | length
        'a different type' | 'text/plain'      | 8
        'a file over 2 MiB'| 'application/pdf' | 2097153
    }

    def 'opening the attachment-ready run again dispatches its first real search once'() {
        given:
        def stopped = run(13)
        def before = counts()

        when:
        wiring.openAgain(store, SUPPORT, stopped, GRACE)
        wiring.drained()

        then:
        positions(stopped) == ['AwaitingReview']
        snapshot(stopped).steps().first().tries().size() == 1
        callFacts(stopped).size() == 1
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
        positions(run(14)) == ['Done']
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

    private static MockHttpServletRequest upload(String type, String filename, byte[] bytes) {
        def request = admitted('POST')
        request.contentType = 'multipart/form-data; boundary=DemoSample'
        String document = JSON.writeValueAsString([name: 'Demo Sample fresh research', versionId: VERSION,
                                                   values: [topic: 'Demo Sample fresh topic',
                                                            attachment: [upload: 0, name: filename]]])
        def wire = new ByteArrayOutputStream()
        wire.write(('--DemoSample\r\nContent-Disposition: form-data; name="document"\r\n\r\n' + document +
                '\r\n--DemoSample\r\nContent-Disposition: form-data; name="upload0"; filename="' + filename +
                '"\r\nContent-Type: ' + type + '\r\n\r\n').getBytes(UTF_8))
        wire.write(bytes)
        wire.write('\r\n--DemoSample--\r\n'.getBytes(UTF_8))
        request.content = wire.toByteArray()
        request
    }

    private Map<String, Long> counts() {
        [runs: 'runs', files: 'stored_files', contents: 'file_contents', holders: 'run_file_holders', calls: 'model_calls']
                .collectEntries { key, table -> [(key): store.count('select count(*) from ' + table)] }
    }

    private List<Map<String, Object>> callFacts(RunId run) {
        store.session.sql("""
                select sent.search_question as question, sent.payload, call.answer, call.envelope_version,
                       call.sent_count, call.came_back_count, call.counted_by_model,
                       used.tool, used.most_uses, used.uses, used.uses_said,
                       price.currency, price.per_use, search.searched_for as searched, search.found
                  from model_calls call
                  join run_step_send_attempts sent using (run_step_send_attempt_id)
                  join model_call_tool_uses used using (model_call_id)
                  join model_call_tool_prices price using (model_call_id)
                  join model_call_searches search using (model_call_id)
                 where call.run_id = :run
                """).param('run', run.value()).query { result, row ->
            (1..result.metaData.columnCount).collectEntries { column ->
                [(result.metaData.getColumnLabel(column)): result.getObject(column)]
            }
        }.list()
    }

}
