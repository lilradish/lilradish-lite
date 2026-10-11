package com.lilradish.lite.app.pool

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import com.lilradish.lite.app.change.ChangeTransactions
import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.SystemPrincipal
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import com.lilradish.lite.testutil.GroupRolesStoodIn
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.transaction.PlatformTransactionManager
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/** Refusals for people outside the pool must be indistinguishable over the whole request path. */
@WebMvcTest([PoolPeopleController, PoolChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([PoolPeople, PoolChanges, ChangeTransactions, Store])
@GroupRolesStoodIn
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PoolChangesControllerStoreIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM_ACTOR = SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString()

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String ENDED_STAY = "00000002-0000-4000-8000-000000000690"

    static final String NEVER_POOLED = "00000002-0000-4000-8000-000000000700"

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private JdbcClient session

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    def setup() {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.of(EstateRole.STEWARD))
    }

    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }


    def "brings somebody in, and the address the answer names reads them exactly as the answer did"() {
        given:
        session.sql("insert into people (user_id, display_name) values ('000601', 'Ari Mockvale')").update()

        when:
        def brought = sending(post("/api/pool/people").contentType(MediaType.APPLICATION_JSON).content('{"userId":"000601"}'))
        def read = mockMvc.perform(get(brought.response.getHeader("Location"))).andReturn()

        then:
        brought.response.status == 201
        read.response.status == 200
        brought.response.contentAsString == read.response.contentAsString

        and: "the person the directory holds, under the name it holds"
        JsonMapper.builder().build().readTree(read.response.contentAsString).get("displayName").asString() == "Ari Mockvale"
    }

    def "grants a role and withdraws it again, each answer being the person as the pool reads them then"() {
        given:
        session.sql("insert into people (user_id, display_name) values ('000602', 'Greno Nockal')").update()
        def address = sending(post("/api/pool/people").contentType(MediaType.APPLICATION_JSON)
                .content('{"userId":"000602"}')).response.getHeader("Location")

        when:
        def granted = sending(put(address + "/estate-roles/watcher"))
        def afterGrant = mockMvc.perform(get(address)).andReturn()
        def withdrawn = sending(delete(address + "/estate-roles/watcher"))
        def afterWithdrawal = mockMvc.perform(get(address)).andReturn()

        then:
        granted.response.status == 200
        granted.response.contentAsString == afterGrant.response.contentAsString
        rolesIn(afterGrant) == ["watcher"]

        and:
        withdrawn.response.status == 200
        withdrawn.response.contentAsString == afterWithdrawal.response.contentAsString
        rolesIn(afterWithdrawal) == []
    }

    def "takes somebody out, after which the address that named them names nobody in view"() {
        given:
        session.sql("insert into people (user_id, display_name) values ('000603', 'Arlo Taffan')").update()
        def address = sending(post("/api/pool/people").contentType(MediaType.APPLICATION_JSON)
                .content('{"userId":"000603"}')).response.getHeader("Location")

        when:
        def removed = sending(delete(address))
        def read = mockMvc.perform(get(address)).andReturn()

        then:
        removed.response.status == 204
        read.response.status == 404
    }

    /** Normalize caller-supplied addresses before comparing refusal responses. */
    def "every change to an address naming nobody in view is answered alike, however nobody came to be in view there"() {
        when:
        def answers = ["00000009-0000-4000-8000-000000000009", ENDED_STAY, NEVER_POOLED, SYSTEM_ACTOR, SEEDER].collect { subject ->
            def address = "/api/pool/people/" + subject + suffix
            def answered = sending(method == "PUT" ? put(address) : delete(address))
            [answered.response.status,
             answered.response.headerNames.collectEntries { [(it): answered.response.getHeaders(it)] },
             answered.response.contentAsString.replace(subject, "{subjectId}")]
        }

        then:
        answers.toSet().size() == 1
        answers.first()[0] == 404
        JsonMapper.builder().build().readTree(answers.first()[2] as String).get("code").asString() == "PERSON_NOT_IN_VIEW"

        where:
        method   | suffix
        "DELETE" | ""
        "PUT"    | "/estate-roles/watcher"
        "DELETE" | "/estate-roles/watcher"
    }

    private static List<String> rolesIn(MvcResult read) {
        JsonMapper.builder().build().readTree(read.response.contentAsString).get("estateRoles").collect { it.asString() }
    }

    /** TestConfiguration keeps fixture components out of other test contexts. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Store {

        @Bean(destroyMethod = "close")
        TestPostgres server() {
            TestPostgres.worker()
        }

        @Bean
        DataSource database(TestPostgres server) {
            def database = Baseline.appliedTo(server, "postgres")
            def session = JdbcClient.create(database)
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            session.sql("""
                    insert into subjects (subject_id, kind, user_id, display_name, created_by) values
                        (?::uuid, 'person', '000690', 'Removed Person', ?::uuid),
                        (?::uuid, 'person', '000700', 'Never Pooled', ?::uuid)
                    """).params(ENDED_STAY, SEEDER, NEVER_POOLED, SEEDER).update()
            // DB-SPECIFIC: PostgreSQL :: casts to uuid; now().
            session.sql("""
                    insert into pool_members (subject_id, created_by, removed_at, removed_by)
                    values (?::uuid, ?::uuid, now(), ?::uuid)
                    """).params(ENDED_STAY, FIRST_STEWARD, FIRST_STEWARD).update()
            database
        }

        @Bean
        JdbcClient session(DataSource database) {
            JdbcClient.create(database)
        }

        @Bean
        PlatformTransactionManager transactions(DataSource database) {
            new DataSourceTransactionManager(database)
        }
    }
}
