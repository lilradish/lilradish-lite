package org.lilradish.lite.app.pool

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.change.ChangeTransactions
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.GroupRolesStoodIn
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

/**
 * What only a change reaching a real store can show about the whole path. Whether somebody is in the
 * pool is decided in SQL under a lock — a stay that ended, a subject that is no person, an identifier
 * nobody holds are the same empty answer there — so whether those refusals leave the server alike is
 * a question about the path and not about either half of it. And what a change answers with has to
 * be what the address it names answers with afterwards.
 *
 * <p>Only who is calling is replaced. Each feature changes people of its own, since what it changes
 * is committed.
 */
@WebMvcTest([PoolPeopleController, PoolChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([PoolPeople, PoolChanges, ChangeTransactions, Store])
@GroupRolesStoodIn
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

    /** What a change answers with is what the address it names reads, there and then. */
    def "brings somebody in, and the address the answer names reads them exactly as the answer did"() {
        given:
        session.sql("insert into people (user_id, display_name) values ('000601', 'Ada Lovelace')").update()

        when:
        def brought = sending(post("/api/pool/people").contentType(MediaType.APPLICATION_JSON).content('{"userId":"000601"}'))
        def read = mockMvc.perform(get(brought.response.getHeader("Location"))).andReturn()

        then:
        brought.response.status == 201
        read.response.status == 200
        brought.response.contentAsString == read.response.contentAsString

        and: "the person the directory holds, under the name it holds"
        JsonMapper.builder().build().readTree(read.response.contentAsString).get("displayName").asString() == "Ada Lovelace"
    }

    def "grants a role and withdraws it again, each answer being the person as the pool reads them then"() {
        given:
        session.sql("insert into people (user_id, display_name) values ('000602', 'Grace Hopper')").update()
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
        session.sql("insert into people (user_id, display_name) values ('000603', 'Alan Turing')").update()
        def address = sending(post("/api/pool/people").contentType(MediaType.APPLICATION_JSON)
                .content('{"userId":"000603"}')).response.getHeader("Location")

        when:
        def removed = sending(delete(address))
        def read = mockMvc.perform(get(address)).andReturn()

        then:
        removed.response.status == 204
        read.response.status == 404
    }

    /**
     * Every address below is an identifier, of the same length, so no two answers can differ even in
     * how long they are; the address itself is the caller's own and is replaced before they are
     * compared. Addresses that are no identifier are the controller's spec's question.
     */
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

    /**
     * The real baseline on a real server, and the store the endpoints change through it, under the
     * transactions they would run under anywhere. Held as a configuration of this spec's own and not
     * scanned, so no other context starts a server for it.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Store {

        @Bean(destroyMethod = "close")
        EmbeddedPostgres server() {
            EmbeddedPostgres.builder().start()
        }

        @Bean
        DataSource database(EmbeddedPostgres server) {
            def database = Baseline.appliedTo(server, "postgres")
            def session = JdbcClient.create(database)
            session.sql("""
                    insert into subjects (subject_id, kind, user_id, display_name, created_by) values
                        (?::uuid, 'person', '000690', 'Removed Person', ?::uuid),
                        (?::uuid, 'person', '000700', 'Never Pooled', ?::uuid)
                    """).params(ENDED_STAY, SEEDER, NEVER_POOLED, SEEDER).update()
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
