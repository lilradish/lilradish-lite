package com.lilradish.lite.app.start

import static java.nio.charset.StandardCharsets.UTF_8
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.files.DatabaseFileContentStore
import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.app.files.RunFiles
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.inference.ToolModelCalls
import com.lilradish.lite.app.run.EngineCalls
import com.lilradish.lite.app.run.EngineExecutor
import com.lilradish.lite.app.run.EngineWiring
import com.lilradish.lite.app.run.EngineWrites
import com.lilradish.lite.app.run.RunEngine
import com.lilradish.lite.app.run.RunSnapshots
import com.lilradish.lite.app.run.RunTree
import com.lilradish.lite.app.run.Runs
import com.lilradish.lite.app.run.RunsController
import com.lilradish.lite.domain.filling.Filling
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.ReadyServletStreams
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.library.TakingWorkflow
import com.lilradish.lite.testutil.run.EngineModels
import com.lilradish.lite.testutil.run.RecordedModelCalls
import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper


@WebMvcTest([StartRunsController, RunsController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@TestPropertySource(properties = ["lilradish.files.maximum-size=100B", "lilradish.files.maximum-total-size=100B"])
@Import([StartRuns, Runs, GroupRoles, RunTree, RunEngine, RunSnapshots, com.lilradish.lite.app.search.SearchWays, EngineWrites, EngineExecutor, EngineCalls,
        com.lilradish.lite.app.run.EngineCodes, com.lilradish.lite.app.run.CeilingReach,
        com.lilradish.lite.app.run.OneProcess, com.lilradish.lite.app.run.RunConfiguration,
        com.lilradish.lite.testutil.run.EngineCodeSteps, EngineModels, Store, ReadyServletStreams])
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class StartRunsControllerIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001701"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001702"


    static final String ANN = "00000002-0000-4000-8000-000000001701"

    static final UserId ANN_USER = new UserId("001701")


    static final String BEA = "00000002-0000-4000-8000-000000001702"

    static final UserId BEA_USER = new UserId("001702")

    static final String CLAIM = "00000006-0000-4000-8000-000000001701"

    static final String CLAIM_VERSION = "00000007-0000-4000-8000-000000001701"


    static final String LETTER_VERSION = "00000007-0000-4000-8000-000000001703"

    static final String FILE_VERSION = "00000007-0000-4000-8000-000000001711"

    static final String FILE_PAIR_VERSION = "00000007-0000-4000-8000-000000001712"

    static final String RETIRED_VERSION = "00000007-0000-4000-8000-000000001704"

    static final String ELSEWHERE_VERSION = "00000007-0000-4000-8000-000000001708"

    static final String LIST_VERSION = "00000007-0000-4000-8000-000000001710"

    static final String RUNS = "/api/groups/${GROUP}/runs"

    static final String FILLED = """
            {"complaint": "Kettle broken", "amount": "12.50", "category": "damaged", "tags": ["box"],
             "contact": {"email": "a@example.org", "phone": null}}"""

    static final String BODY_REFUSED = "This takes a JSON object holding a run's name, the version it runs and a" +
            " value for each field that version takes, and nothing else."

    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private LibraryStore store

    @Autowired
    private RecordedModelCalls modelCalls

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    def setup() {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(ANN_USER))
        given(grants.heldBy(ANN_USER)).willReturn(EnumSet.allOf(EstateRole))
    }

    /** No workflow here has a model's step, so no start may call one. */
    def cleanup() {
        assert modelCalls.scripted.requests.isEmpty()
    }

    private MvcResult sending(String address, String body) {
        sending(address, body.getBytes(UTF_8))
    }

    private MvcResult sending(String address, byte[] body) {
        mockMvc.perform(post(address)
                .header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn()
    }


    private static MockHttpServletRequest declaring(ServletContext servletContext, long length) {
        new MockHttpServletRequest(servletContext, "POST", RUNS) {
            @Override
            int getContentLength() {
                Math.toIntExact(length)
            }

            @Override
            long getContentLengthLong() {
                length
            }
        }
    }

    private MvcResult starting(String name, String version, String values) {
        sending(RUNS, JSON.writeValueAsString([name: name, versionId: version, values: JSON.readTree(values)]))
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    /** Everything an answer says, but the address it was sent to, which is each request's own. */
    private static List<Object> apartFromWhere(MvcResult answered) {
        def document = JSON.convertValue(documentOf(answered), Map)
        [answered.response.status, answered.response.contentType, document.findAll { it.key != "instance" }]
    }

    private long runsWritten() {
        store.count("select count(*) from runs")
    }

    def "a line break for a one-line input is refused on arrival at its field without starting a run"() {
        given:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update declaration_fields set text_lines = 'one' where entry_version_id = ?::uuid and name = 'complaint'")
                .params(CLAIM_VERSION).update()
        def before = runsWritten()
        def values = JSON.readTree(FILLED)
        values.put("complaint", Character.toString(codePoint))

        when:
        def answered = starting("Demo Sample", CLAIM_VERSION, JSON.writeValueAsString(values))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "VALUE_DOES_NOT_FIT"
        documentOf(answered).get("problems") == JSON.readTree('[{"path":["complaint"],"reason":"line_break"}]')
        documentOf(answered).get("problemsFound").asInt() == 1
        runsWritten() == before

        cleanup:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update declaration_fields set text_lines = null where entry_version_id = ?::uuid and name = 'complaint'")
                .params(CLAIM_VERSION).update()

        where:
        codePoint << [0x0D, 0x0A, 0x0B, 0x0C, 0x85, 0x2028, 0x2029]
    }

    def "a run started is answered as created, with only its address and number: #role"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(caller))
        given(grants.heldBy(caller)).willReturn(EnumSet.allOf(EstateRole))
        def before = runsWritten()

        when:
        def answered = starting("Kettle arrived broken", CLAIM_VERSION, FILLED)

        then:
        answered.response.status == 201
        def run = documentOf(answered).get("runId").asString()
        answered.response.getHeader("Location") == "/api/groups/${GROUP}/runs/${run}" as String
        documentOf(answered).propertyNames().toList() == ["runId", "number"]
        documentOf(answered).get("number").asInt() == before + 1
        def read = mockMvc.perform(get(answered.response.getHeader("Location"))).andReturn()
        read.response.status == 200
        documentOf(read).get("runId").asString() == run
        documentOf(read).get("number") == documentOf(answered).get("number")
        documentOf(read).get("workflow").get("entryId").asString() == CLAIM
        documentOf(read).get("startedWith") == JSON.readTree(FILLED)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select name from runs where run_id = ?::uuid", run) == ["Kettle arrived broken"]
        runsWritten() == before + 1

        where:
        caller   | role
        ANN_USER | "an operator, reading the runs they started"
        BEA_USER | "an overseer, reading every run"
    }

    def "a multipart start keeps the uploaded file inside its new run"() {
        given:
        String json = JSON.writeValueAsString([name: 'Picture', versionId: FILE_VERSION,
                                               values: [photo: [upload: 0, name: 'actual.png']]])
        String wire = '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n' + json +
                '\r\n--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\nabc\r\n--Aa--\r\n'
        def before = runsWritten()

        when:
        def started = mockMvc.perform(post(RUNS)
                .header('Sec-Fetch-Site', 'same-origin')
                .contentType('multipart/form-data; boundary=Aa')
                .content(wire.getBytes(UTF_8))).andReturn()
        started.getAsyncResult(5_000)
        def answered = mockMvc.perform(asyncDispatch(started)).andReturn()

        then:
        answered.response.status == 201
        runsWritten() == before + 1
        def run = documentOf(answered).get('runId').asString()
        documentOf(answered).propertyNames().toList() == ['runId', 'number']
        answered.response.getHeader('Location') == "/api/groups/${GROUP}/runs/${run}" as String
        def read = mockMvc.perform(get(answered.response.getHeader('Location'))).andReturn()
        read.response.status == 200
        documentOf(read).get('runId').asString() == run
        documentOf(read).get('number') == documentOf(answered).get('number')
        documentOf(read).get('startedWith').get('photo').get('name').asString() == 'actual.png'
        store.texts('select original_name from stored_files') == ['actual.png']
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts('select file_id::text from run_file_holders where run_id = ?::uuid', run).size() == 1
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts('select started_with::text from runs where run_id = ?::uuid', run).first().contains('actual.png')
    }

    def "an HTTP multipart start accepts exactly the total and rolls back every row one byte over"() {
        given:
        def beforeRuns = runsWritten()
        def beforeStored = store.count('select count(*) from stored_files')
        def beforeContents = store.count('select count(*) from file_contents')
        def beforeHolders = store.count('select count(*) from run_file_holders')
        String json = JSON.writeValueAsString([name: 'Two pictures', versionId: FILE_PAIR_VERSION,
                                               values: [photo: [upload: 0, name: 'one.png'],
                                                        copy : [upload: 1, name: 'two.png']]])
        Closure<MvcResult> start = { String second ->
            String wire = '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n' + json +
                    '\r\n--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="one.png"\r\n' +
                    'Content-Type: image/png\r\n\r\n' + ('a' * 50) + '\r\n' +
                    '--Aa\r\nContent-Disposition: form-data; name="upload1"; filename="two.png"\r\n' +
                    'Content-Type: image/png\r\n\r\n' + second + '\r\n--Aa--\r\n'
            def started = mockMvc.perform(post(RUNS)
                    .header('Sec-Fetch-Site', 'same-origin')
                    .contentType('multipart/form-data; boundary=Aa')
                    .content(wire.getBytes(UTF_8))).andReturn()
            started.getAsyncResult(5_000)
            mockMvc.perform(asyncDispatch(started)).andReturn()
        }

        when:
        def exact = start('b' * 50)
        def over = start('b' * 51)

        then:
        exact.response.status == 201
        over.response.status == 400
        documentOf(over).get('code').asString() == 'BODY_UNUSABLE'
        runsWritten() == beforeRuns + 1
        store.count('select count(*) from stored_files') == beforeStored + 2
        store.count('select count(*) from file_contents') == beforeContents + 2
        store.count('select count(*) from run_file_holders') == beforeHolders + 2
        def run = documentOf(exact).get('runId').asString()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts('select original_name from stored_files where file_id in ' +
                '(select file_id from run_file_holders where run_id = ?::uuid) order by original_name', run) ==
                ['one.png', 'two.png']
    }

    def "a name of 128 characters is taken whole"() {
        given:
        def before = runsWritten()
        def name = "k" * 128

        when:
        def answered = starting(name, CLAIM_VERSION, FILLED)

        then:
        answered.response.status == 201
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select name from runs where run_id = ?::uuid", documentOf(answered).get("runId").asString()) ==
                [name]
        runsWritten() == before + 1
    }


    def "every way of naming no group in view is answered alike, writing nothing"() {
        given:
        def before = runsWritten()
        def body = JSON.writeValueAsString([name: "Kettle", versionId: CLAIM_VERSION, values: JSON.readTree(FILLED)])

        when:
        def answers = [OTHER_GROUP, "00000003-0000-4000-8000-000000001709", "not-a-group"].collect {
            sending("/api/groups/${it}/runs", body)
        }

        then:
        answers*.response*.status.toSet() == [404] as Set
        answers.collect { apartFromWhere(it) }.toSet().size() == 1
        documentOf(answers.first()).get("code").asString() == "GROUP_NOT_IN_VIEW"
        runsWritten() == before
    }

    def "a body not of the shape a start takes is refused as unusable, writing nothing: #shape"() {
        given:
        def before = runsWritten()

        when:
        def answered = sending(RUNS, body)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == BODY_REFUSED
        !documentOf(answered).has("problems")
        !documentOf(answered).has("problemsFound")
        runsWritten() == before

        where:
        shape                                | body
        "no object"                          | '[]'
        "values left out"                    | """{"name": "Kettle", "versionId": "${CLAIM_VERSION}"}"""
        "a member it does not take"          | """{"name": "Kettle", "versionId": "${CLAIM_VERSION}", "values": {}, "why": null}"""
        "a name that is no string"           | """{"name": 1, "versionId": "${CLAIM_VERSION}", "values": {}}"""
        "a version that is no string"        | '{"name": "Kettle", "versionId": null, "values": {}}'
        "values not of the version's shape"  | """{"name": "Kettle", "versionId": "${CLAIM_VERSION}", "values": {}}"""
        "a number written with an exponent"  | """{"name": "Kettle", "versionId": "${CLAIM_VERSION}", "values": ${FILLED.replace('"12.50"', '1e3')}}"""
    }

    def "a start declared at the largest body taken is started, and one declared a byte past it is refused unread"() {
        given:
        def before = runsWritten()
        def body = JSON.writeValueAsString([name: "Kettle", versionId: CLAIM_VERSION, values: JSON.readTree(FILLED)])

        when:
        def answered = mockMvc.perform({ servletContext ->
            def request = declaring(servletContext, declared)
            request.addHeader("Sec-Fetch-Site", "same-origin")
            request.contentType = MediaType.APPLICATION_JSON_VALUE
            request.content = body.getBytes(UTF_8)
            request
        } as RequestBuilder).andReturn()

        then:
        answered.response.status == status
        documentOf(answered).get("code")?.asString() == code
        runsWritten() == before + written

        where:
        declared                           || status | code            | written
        Filling.LARGEST_REQUEST_BYTES      || 201    | null            | 1
        Filling.LARGEST_REQUEST_BYTES + 1  || 400    | "BODY_UNUSABLE" | 0
    }

    def "a name that is none, shows nothing, breaks a line or runs past 128 characters is refused: #name"() {
        given:
        def before = runsWritten()

        when:
        def answered = starting(sent, CLAIM_VERSION, FILLED)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "RUN_NAME_UNUSABLE"
        documentOf(answered).get("detail").asString() ==
                "A run's name is one to 128 characters on one line, with something in it that shows."
        runsWritten() == before

        where:
        name                 | sent
        "empty"              | ""
        "spaces alone"       | "   "
        "two lines"          | "Kettle\narrived broken"
        "129 characters"     | "k" * 129
    }


    def "a version the group does not offer is refused alike however it fails, writing nothing"() {
        given:
        def before = runsWritten()

        when:
        def answers = ["not-a-version", "00000007-0000-4000-8000-000000001709", ELSEWHERE_VERSION, RETIRED_VERSION]
                .collect { starting("Kettle", it, FILLED) }

        then:
        answers*.response*.status.toSet() == [409] as Set
        answers.collect { apartFromWhere(it) }.toSet().size() == 1
        documentOf(answers.first()).get("code").asString() == "WORKFLOW_NOT_OFFERED"
        runsWritten() == before
    }

    def "values that do not fit are answered with where each stands and why, writing nothing"() {
        given:
        def before = runsWritten()

        when:
        def answered = starting("Kettle", CLAIM_VERSION, """
                {"complaint": "This complaint runs past twenty", "amount": "1e3", "category": "broken",
                 "tags": ["fine", "far too long a tag"], "contact": {"email": "", "phone": "555 0100"}}""")

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "VALUE_DOES_NOT_FIT"
        documentOf(answered).get("detail").asString() == "Each value is written as its field takes it."
        documentOf(answered).get("problems") == JSON.readTree("""
                [{"path": ["complaint"], "reason": "too_long"},
                 {"path": ["amount"], "reason": "malformed"},
                 {"path": ["category"], "reason": "not_a_term"},
                 {"path": ["tags", 1], "reason": "too_long"},
                 {"path": ["contact", "email"], "reason": "missing"}]""")
        documentOf(answered).get("problemsFound").asInt() == 5
        !answered.response.contentAsString.contains("This complaint runs past twenty")
        runsWritten() == before
    }

    def "a value past 1,048,576 characters, within its field's limit, is kept whole"() {
        given:
        def before = runsWritten()
        def letter = "a" * 1_048_577

        when:
        def answered = starting("Letter", LETTER_VERSION, """{"letter": "${letter}"}""")

        then:
        answered.response.status == 201
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid; JSON ->> operator.
        store.texts("select (started_with ->> 'letter' = ?)::text from runs where run_id = ?::uuid",
                letter, documentOf(answered).get("runId").asString()) == ["true"]
        runsWritten() == before + 1
    }

    def "a parameter, which a start does not take, is refused, writing nothing"() {
        given:
        def before = runsWritten()

        when:
        def answered = sending("${RUNS}?draft=true",
                JSON.writeValueAsString([name: "Kettle", versionId: CLAIM_VERSION, values: JSON.readTree(FILLED)]))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "PARAMETER_UNKNOWN"
        runsWritten() == before
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FileDeployment)
    static class Store {

        @Bean(destroyMethod = "close")
        TestPostgres server() {
            TestPostgres.worker()
        }

        @Bean
        LibraryStore store(TestPostgres server) {
            LibraryStore.prepareBaseline(server)
            def store = LibraryStore.forCase(server, "start_answers")
            store.person(ANN, "001701", "Ann Example")
            store.group(GROUP, "SUPPORT", "Customer support")
            store.group(OTHER_GROUP, "BILLING", "Billing")
            store.member(GROUP, ANN, "operator")
            store.person(BEA, "001702", "Bea Example")
            store.member(GROUP, BEA, "overseer")
            TakingWorkflow.list(store, "00000006-0000-4000-8000-000000001710", LIST_VERSION, GROUP)
            TakingWorkflow.workflow(store, CLAIM, CLAIM_VERSION, GROUP, "Handle a claim")
            TakingWorkflow.takes(store, CLAIM_VERSION, LIST_VERSION)
            TakingWorkflow.workflow(store, "00000006-0000-4000-8000-000000001703", LETTER_VERSION, GROUP,
                    "Read a letter")
            TakingWorkflow.field(store, LETTER_VERSION, 1, "letter", "text", [textLimit: 2_000_000])
            TakingWorkflow.workflow(store, "00000006-0000-4000-8000-000000001711", FILE_VERSION, GROUP,
                    "Take a picture")
            def photo = TakingWorkflow.field(store, FILE_VERSION, 1, "photo", "file", [mustBeGiven: true])
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("update declaration_fields set file_types = array['.png'], file_maximum_bytes = 100 " +
                    "where declaration_field_id = ?::uuid").params(photo).update()
            TakingWorkflow.workflow(store, "00000006-0000-4000-8000-000000001712", FILE_PAIR_VERSION, GROUP,
                    "Take two pictures")
            ['photo', 'copy'].eachWithIndex { name, index ->
                def pairField = TakingWorkflow.field(store, FILE_PAIR_VERSION, index + 1, name, "file",
                        [mustBeGiven: true])
                // DB-SPECIFIC: PostgreSQL :: casts to uuid.
                store.session.sql("update declaration_fields set file_types = array['.png'], file_maximum_bytes = 100 " +
                        "where declaration_field_id = ?::uuid").params(pairField).update()
            }
            store.entry("00000006-0000-4000-8000-000000001704", GROUP, "workflow", "Retired")
            store.seeded(RETIRED_VERSION, "00000006-0000-4000-8000-000000001704", 1, true)
            store.content(RETIRED_VERSION, "workflow")
            TakingWorkflow.workflow(store, "00000006-0000-4000-8000-000000001708", ELSEWHERE_VERSION, OTHER_GROUP,
                    "Elsewhere")
            store
        }

        @Bean
        DataSource database(LibraryStore store) {
            store.database
        }

        @Bean
        JdbcClient session(LibraryStore store) {
            store.session
        }

        @Bean
        RunFiles runFiles(LibraryStore store, FileDeployment deployment) {
            new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        }

        @Bean
        TransactionOperations transactions(LibraryStore store) {
            store.transactions()
        }

        @Bean
        PlatformTransactionManager snapshotTransactions(LibraryStore store) {
            store.transactionManager()
        }

        @Bean
        ToolModelCalls toolModelCalls() {
            EngineWiring.withoutTools()
        }
    }
}
