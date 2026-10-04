package org.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.change.ChangeTransactions
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.files.FileDeployment
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.TextLines
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.transaction.PlatformTransactionManager
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

@WebMvcTest(WorkflowController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([GroupRoles, ChangeTransactions, Store, Workflows, WorkflowDrafts, Drafts, ReleasedCodeSteps])
class WorkflowConstantArrivalIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000eb1"

    static final String ANN = "00000002-0000-4000-8000-000000000eb1"

    static final String BOB = "00000002-0000-4000-8000-000000000eb2"

    static final UserId ANN_USER = new UserId("000eb1")

    static final UserId BOB_USER = new UserId("000eb2")

    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private LibraryStore store

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    def setup() {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(ANN_USER))
        given(grants.heldBy(any(UserId))).willReturn(EnumSet.noneOf(EstateRole))
    }

    def "a real draft write refuses each break at one-line scalar, many and nested targets without changing any row"() {
        given:
        def draft = draft()
        def target = target("question", "one", shape)
        def beforeView = oldFlow(draft, target)
        def before = store.contents()
        def typed = shaped(shape, Character.toString(codePoint))

        when:
        def refused = write(draft.address, flow(2, target, typed))
        def read = document(sendingGet(draft.address))

        then:
        refused.response.status == 400
        document(refused).code == "CONSTANT_DOES_NOT_FIT"
        document(refused).detail.endsWith("Text for a field of one line holds no line break.")
        store.contents() == before
        read == beforeView
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_version_writers where entry_version_id = ?::uuid and created_by = ?::uuid",
                draft.version, ANN) == 0

        where:
        [codePoint, shape] << [[0x0D, 0x0A, 0x0B, 0x0C, 0x85, 0x2028, 0x2029], ["one", "many", "nested"]].combinations()
    }

    def "a known one-line target refuses a trailing break on every stored binding consumer"() {
        given:
        def draft = draft()
        def target = target(consumer, "one", shape, draft)
        def beforeView = oldFlow(draft, target)
        def before = store.contents()

        when:
        def refused = write(draft.address, flow(2, target, shaped(shape, "Demo Sample\n")))

        then:
        refused.response.status == 400
        document(refused).code == "CONSTANT_DOES_NOT_FIT"
        store.contents() == before
        document(sendingGet(draft.address)) == beforeView

        where:
        [consumer, shape] << [["workflow", "code", "output", "case", "fallback"], ["one", "many", "nested"]].combinations()
    }

    def "accepted constants are saved exactly at one, several and legacy targets on every binding consumer"() {
        given:
        def draft = draft()
        def target = target(consumer, lines, shape, draft)
        def beforeView = oldFlow(draft, target)
        def beforeVersion = store.digestOf("entry_versions")
        def typed = shaped(shape, lines == "one" ? "  Demo Sample  " : " Demo Sample\n ")
        def sent = flow(2, target, typed)

        when:
        def accepted = write(draft.address, sent)
        def read = document(sendingGet(draft.address))

        then:
        accepted.response.status == 200
        read == document(accepted)
        read.revision == beforeView.revision + 1
        savedConstant(read, target) == typed
        store.digestOf("entry_versions") != beforeVersion
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_version_writers where entry_version_id = ?::uuid and created_by = ?::uuid",
                draft.version, ANN) == 1

        where:
        [consumer, lines, shape] << [["question", "workflow", "output", "case", "fallback"],
                                    ["one", "several", null], ["one", "many", "nested"]].combinations()
    }

    def "nested many constants are checked at their declared children even with undeclared members"() {
        given:
        def draft = draft()
        def target = target("question", "one", "nested_many")
        def beforeView = oldFlow(draft, target)
        def before = store.contents()
        def typed = [[titles: ["Demo Sample", "last\n"], extra: true]]

        when:
        def refused = write(draft.address, flow(2, target, typed))

        then:
        refused.response.status == 400
        document(refused).code == "CONSTANT_DOES_NOT_FIT"
        store.contents() == before
        document(sendingGet(draft.address)) == beforeView
    }

    def "unfinished and mismatched drafts remain writable where no one-line string is reached"() {
        given:
        def draft = draft()
        def target = target("question", "one", "one")
        def sent = flow(1, target, "Demo Sample")
        switch (caseName) {
            case "unchosen":
                sent.steps[0] = [name: "ask", runs: null, bindings: [[target: "value", source: [constant: JSON.writeValueAsString("last\n")]]]]
                break
            case "unknown_target":
                sent.steps[0].bindings[0].target = "absent"
                sent.steps[0].bindings[0].source.constant = JSON.writeValueAsString("last\n")
                break
            case "missing_binding":
                sent.steps[0].bindings = []
                break
            case "wrong_shape":
                sent.steps[0].bindings[0].source.constant = JSON.writeValueAsString(["last\n"])
                break
            case "past_length":
                sent.steps[0].bindings[0].source.constant = JSON.writeValueAsString("a" * 101)
                break
            case "source_mismatch":
                fields(draft.version, "workflow", "takes", "several", "one")
                sent.steps[0].bindings[0].source = [input: "value"]
                break
        }

        when:
        def accepted = write(draft.address, sent)
        def read = document(sendingGet(draft.address))

        then:
        accepted.response.status == 200
        read == document(accepted)
        read.revision == 2
        read.problems*.code.contains(problem)
        read.steps.size() == 1
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_version_writers where entry_version_id = ?::uuid and created_by = ?::uuid",
                draft.version, ANN) == 1

        where:
        caseName          || problem
        "unchosen"        || "runs_missing"
        "unknown_target"  || "target_unknown"
        "missing_binding" || "input_unbound"
        "wrong_shape"     || "constant_does_not_fit"
        "past_length"     || "constant_does_not_fit"
        "source_mismatch" || "source_does_not_fit"
    }

    def "a draft keeps its retired pin while saving a valid constant and still refuses a break for that pin's field"() {
        given:
        def draft = draft()
        def target = target("question", "one", "one")
        def old = oldFlow(draft, target)
        store.retired(target.version, LibraryStore.FIRST_STEWARD)
        def valid = flow(2, target, "Demo Sample")
        valid.steps[0].stepId = old.steps[0].stepId

        when:
        def accepted = write(draft.address, valid)
        def read = document(accepted)
        def before = store.contents()
        def invalid = flow(3, target, "last\n")
        invalid.steps[0].stepId = read.steps[0].stepId
        def refused = write(draft.address, invalid)

        then:
        accepted.response.status == 200
        read.revision == 3
        read.steps[0].runs.version.versionId == target.version
        read.steps[0].runs.version.standing == "retired"
        savedConstant(read, target) == "Demo Sample"
        refused.response.status == 400
        document(refused).code == "CONSTANT_DOES_NOT_FIT"
        store.contents() == before
        document(sendingGet(draft.address)) == read
    }

    private Map draft() {
        def entry = key("00000006")
        def version = key("00000007")
        store.entry(entry, GROUP, "workflow", "Demo Sample " + entry)
        store.version(version, entry, 1, BOB)
        store.content(version, "workflow")
        [version: version, address: "/api/groups/${GROUP}/workflows/${entry}/versions/${version}" as String]
    }

    private Map target(String consumer, String lines, String shape, Map draft = null) {
        if (consumer == "code") {
            return [consumer: consumer, shape: shape, name: shape == "one" ? "value" : shape == "many" ? "values" : "record"]
        }
        def kind = consumer == "question" ? "question" : "workflow"
        def version = draft?.version
        if (consumer != "output") {
            def entry = key("00000006")
            version = key("00000007")
            store.entry(entry, GROUP, kind, "Demo Sample " + entry)
            store.seeded(version, entry, 1)
            if (kind == "question") {
                store.wholeQuestion(version)
            } else {
                store.content(version, "workflow")
            }
        }
        fields(version, kind, consumer == "output" ? "gives" : "takes", lines, shape)
        [consumer: consumer, version: version, shape: shape, name: "value"]
    }

    private void fields(String version, String kind, String side, String lines, String shape) {
        def parent = key("0000000b")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, entry_kind, declaration_side, field_kind.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position,
                                                name, kind, holds_many, text_limit, text_lines, many_limit,
                                                must_be_given, created_by)
                values (?::uuid, ?::uuid, ?::entry_kind, ?::declaration_side, 1, 'value', ?::field_kind, ?, ?, ?, ?, false, ?::uuid)
                """).params(parent, version, kind, side, shape.startsWith("nested") ? "fields" : "text",
                shape in ["many", "nested_many"], shape.startsWith("nested") ? null : 100,
                shape.startsWith("nested") ? null : lines, shape in ["many", "nested_many"] ? 3 : null,
                LibraryStore.SEEDER).update()
        if (shape.startsWith("nested")) {
            // DB-SPECIFIC: PostgreSQL :: casts to uuid, entry_kind, declaration_side.
            store.session.sql("""
                    insert into declaration_fields (entry_version_id, entry_kind, side, parent_field_id, position,
                                                    name, kind, holds_many, text_limit, text_lines, many_limit,
                                                    must_be_given, created_by)
                    values (?::uuid, ?::entry_kind, ?::declaration_side, ?::uuid, 1, ?, 'text', ?, 100, ?, ?, false, ?::uuid)
                    """).params(version, kind, side, parent, shape == "nested_many" ? "titles" : "title",
                    shape == "nested_many", lines, shape == "nested_many" ? 3 : null, LibraryStore.SEEDER).update()
        }
    }

    private Map oldFlow(Map draft, Map target) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(BOB_USER))
        def saved = write(draft.address, flow(1, target, shaped(target.shape, "old")))
        assert saved.response.status == 200: saved.response.contentAsString
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(ANN_USER))
        document(sendingGet(draft.address))
    }

    private static Object shaped(String shape, String text) {
        switch (shape) {
            case "many": return ["Demo Sample", text]
            case "nested": return [title: text]
            case "nested_many": return [[titles: ["Demo Sample", text]]]
            default: return text
        }
    }

    private static Map flow(int revision, Map target, Object value) {
        def binding = [target: target.name, source: [constant: JSON.writeValueAsString(value)]]
        def step
        if (target.consumer in ["case", "fallback"]) {
            step = [name: "choose", runs: [kind: "route", discriminator: null,
                                          gives: [[name: "receipt", label: null, help: null, kind: "text",
                                                    longest: 30, lines: "several", many: false, most: null, mustBeGiven: false]],
                                          cases: [[term: target.consumer == "case" ? "Demo Sample" : null,
                                                   workflow: target.version, bindings: [binding]]]], bindings: []]
        } else if (target.consumer == "output") {
            step = [name: "unfinished", runs: null, bindings: []]
        } else {
            def runs = target.consumer == "code" ? [kind: "code_step", codeStep: "send_reply"] :
                    [kind: target.consumer, version: target.version]
            step = [name: "ask", runs: runs, bindings: [binding]]
            if (target.consumer in ["question", "code"]) {
                step += [producer: [kind: "person"], tries: 1, reviewer: null]
            }
        }
        [revision: revision, steps: [step], outputs: target.consumer == "output" ? [binding] : []]
    }

    private static Object savedConstant(Map read, Map target) {
        def binding = target.consumer == "output" ? read.outputs[0] : target.consumer in ["case", "fallback"] ?
                read.steps[0].runs.cases[0].bindings[0] : read.steps[0].bindings[0]
        JSON.readValue(binding.source.constant as String, Object)
    }

    private MvcResult write(String address, Map body) {
        mockMvc.perform(put(address + "/steps").header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))).andReturn()
    }

    private MvcResult sendingGet(String address) {
        mockMvc.perform(get(address).header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private static Map document(MvcResult answered) {
        JSON.readValue(answered.response.contentAsString, Map)
    }

    private static String key(String prefix) {
        prefix + UUID.randomUUID().toString().substring(8)
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FileDeployment)
    static class Store {

        @Bean(destroyMethod = "close")
        EmbeddedPostgres server() {
            EmbeddedPostgres.builder().start()
        }

        @Bean
        LibraryStore store(EmbeddedPostgres server) {
            LibraryStore.template(server)
            def store = LibraryStore.copied(server, "workflow_constant_arrival")
            store.person(ANN, "000eb1", "Demo Sample Ann")
            store.person(BOB, "000eb2", "Demo Sample Bob")
            store.group(GROUP, "SAMPLE", "Demo Sample")
            store.member(GROUP, ANN, "operator")
            store.member(GROUP, BOB, "operator")
            // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
            store.session.sql("alter type code_step add value 'send_reply'").update()
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    insert into code_step_publications (code_step, group_key, every_group, created_by)
                    values ('send_reply', 'SAMPLE', false, ?::uuid)
                    """).param(LibraryStore.SEEDER).update()
            store
        }

        @Bean
        DataSource database(LibraryStore store) {
            store.database
        }

        @Bean
        ModelCatalog models() {
            LibraryStore.MODELS
        }

        @Bean
        SearchWays ways() {
            new SearchWays([])
        }

        @Bean
        CodeSteps codeSteps() {
            def text = new FieldShape.Text(100, TextLines.ONE)
            new CodeSteps([new SpecCodeStep("send_reply", [
                    new Field(new FieldName("value"), null, null, text, new HowMany.One(), new Demand.Given(false)),
                    new Field(new FieldName("values"), null, null, text, new HowMany.Many(3), new Demand.Given(false)),
                    new Field(new FieldName("record"), null, null, new FieldShape.Nested([
                            SpecCodeStep.given("title", text)]), new HowMany.One(), new Demand.Given(false))],
                    SpecCodeStep.SEND_REPLY.declaration().gives().fields(), false)])
        }

        @Bean
        JdbcClient session(LibraryStore store) {
            store.session
        }

        @Bean
        PlatformTransactionManager transactions(LibraryStore store) {
            store.transactionManager()
        }
    }
}
