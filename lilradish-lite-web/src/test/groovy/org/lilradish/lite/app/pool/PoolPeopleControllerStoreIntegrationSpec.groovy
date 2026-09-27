package org.lilradish.lite.app.pool

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * The things about these endpoints that only a request reaching a real store can show. Whether
 * somebody is in view is decided in SQL — a stay that ended, a subject that is no person, an
 * identifier nobody holds are all the same empty answer there — so whether those answers leave the
 * system alike is a question about the whole path and not about either half of it. And a row this
 * system refuses to read back has to fail the request as a fault of this system's, which is the
 * outlet's to render and log, not the store's. A filter is spaced on one side and matched on the
 * other, so only both together say whether what a reader types finds what the pool shows.
 *
 * <p>Only who is calling is replaced, for the reason {@code StandingEndpointIntegrationSpec} gives.
 */
@WebMvcTest(PoolPeopleController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([PoolPeople, Store])
@GroupRolesStoodIn
class PoolPeopleControllerStoreIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String SYSTEM_ACTOR = SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString()

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String ENDED_STAY = "00000002-0000-4000-8000-000000000190"

    static final String NEVER_POOLED = "00000002-0000-4000-8000-000000000200"

    static final String A_GROUP = "00000003-0000-4000-8000-000000000001"

    static final String WRITTEN_BY_HAND = "00000002-0000-4000-8000-000000000999"

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private JdbcClient session

    @Autowired
    private SingleConnectionDataSource store

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    def setup() {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.of(EstateRole.STEWARD))
    }

    /**
     * Every address below is the length of an identifier, so no two answers can differ even in how
     * long they are. The one thing that does differ is the address itself, which the outlet hands
     * back as the instance the problem is about; it is the caller's own, and is replaced before the
     * answers are compared.
     */
    def "every address naming nobody in view is answered alike, however nobody came to be in view there"() {
        when:
        def answers = [
                "00000009-0000-4000-8000-000000000009",
                ENDED_STAY,
                NEVER_POOLED,
                SYSTEM_ACTOR,
                SEEDER,
                A_GROUP,
                "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz",
        ].collect { address ->
            def answered = asking("/api/pool/people/" + address)
            [
                    answered.response.status,
                    answered.response.headerNames.collectEntries { [(it): answered.response.getHeaders(it)] },
                    answered.response.contentAsString.replace(address, "{subjectId}"),
            ]
        }

        then:
        answers.toSet().size() == 1
        answers.first()[0] == 404
        JsonMapper.builder().build().readTree(answers.first()[2] as String).get("code").asString() == "PERSON_NOT_IN_VIEW"

        and: "over a store that does answer for somebody in view, so the sameness is a reading and not a silence"
        asking("/api/pool/people/" + FIRST_STEWARD).response.status == 200
    }

    def "a stored user number this system will not show fails the list as a logged fault of this system's, naming nobody"() {
        given:
        session.sql("""
                insert into subjects (subject_id, kind, user_id, display_name, created_by)
                values (?::uuid, 'person', ' 000999', 'Written By Hand', ?::uuid)
                """).params(WRITTEN_BY_HAND, SEEDER).update()
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(WRITTEN_BY_HAND, FIRST_STEWARD).update()
        def outlet = LoggerFactory.getLogger("org.lilradish.lite.web.RefusalOutlet") as Logger
        def logged = new ListAppender<ILoggingEvent>()
        logged.start()
        outlet.addAppender(logged)

        when:
        def answered = asking("/api/pool/people")

        then:
        answered.response.status == 500
        JsonMapper.builder().build().readTree(answered.response.contentAsString).get("code").asString() == "INTERNAL"

        and: "with no row of the pool in it, neither the one refused nor any around it"
        !answered.response.contentAsString.contains("000999")
        !answered.response.contentAsString.contains("000001")
        !answered.response.contentAsString.contains("Written By Hand")

        and: "and logged as an error naming whose row it was, which is where somebody can act on it"
        logged.list.any {
            it.level == Level.ERROR && it.throwableProxy?.className == IllegalStateException.name &&
                    it.throwableProxy.message == "Subject ${WRITTEN_BY_HAND} holds a user id this system will not show"
        }

        cleanup:
        outlet.detachAppender(logged)
        store.connection.rollback()
    }

    /**
     * Every name in the pool is spaced with U+0020, so a filter typed with another space is spaced alike
     * before the store is asked; a space at either end still narrows, being nothing trimmed.
     */
    def "a filter typed with any whitespace finds the pool's names spaced once, a trailing space still narrowing"() {
        given:
        [["000610", "田中 一郎"], ["000611", "Ada Lovelace"], ["000612", "Adam Smith"]].eachWithIndex { person, index ->
            def subject = "00000002-0000-4000-8000-00000000061" + index
            session.sql("""
                    insert into subjects (subject_id, kind, user_id, display_name, created_by)
                    values (?::uuid, 'person', ?, ?, ?::uuid)
                    """).params(subject, person[0], person[1], SEEDER).update()
            session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                    .params(subject, FIRST_STEWARD).update()
        }

        when:
        def answered = mockMvc.perform(get("/api/pool/people").param("filter", typed)).andReturn()

        then:
        answered.response.status == 200
        JsonMapper.builder().build().readTree(answered.response.contentAsString).get("items")
                .collect { it.get("userId").asString() } == found

        cleanup:
        store.connection.rollback()

        where:
        typed                                             || found
        "田中" + IDEOGRAPHIC_SPACE + "一郎"                   || ["000610"]
        "田中 一郎"                                          || ["000610"]
        "ADA" + IDEOGRAPHIC_SPACE                         || ["000611"]
        "ADA "                                            || ["000611"]
        "ADA"                                             || ["000611", "000612"]
    }

    private MvcResult asking(String address) {
        mockMvc.perform(get(address)).andReturn()
    }

    /**
     * The real baseline on a real server, and the store the endpoints read through it. Held as a
     * configuration of this spec's own and not scanned, so no other context starts a server for it.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Store {

        @Bean(destroyMethod = "close")
        EmbeddedPostgres server() {
            EmbeddedPostgres.builder().start()
        }

        /**
         * One connection, never committing on its own: the dispatcher runs on the spec's own thread,
         * so what a feature writes the endpoint reads, and the feature's cleanup rolls it back.
         */
        @Bean(destroyMethod = "destroy")
        SingleConnectionDataSource connection(EmbeddedPostgres server) {
            def store = new SingleConnectionDataSource(Baseline.appliedTo(server, "postgres").connection, true)
            store.connection.autoCommit = false
            store
        }

        @Bean
        JdbcClient database(SingleConnectionDataSource connection) {
            def session = JdbcClient.create(connection)
            session.sql("""
                    insert into subjects (subject_id, kind, user_id, display_name, created_by) values
                        (?::uuid, 'person', '000190', 'Removed Person', ?::uuid),
                        (?::uuid, 'person', '000200', 'Never Pooled', ?::uuid)
                    """).params(ENDED_STAY, SEEDER, NEVER_POOLED, SEEDER).update()
            session.sql("""
                    insert into pool_members (subject_id, created_by, removed_at, removed_by)
                    values (?::uuid, ?::uuid, now(), ?::uuid)
                    """).params(ENDED_STAY, FIRST_STEWARD, FIRST_STEWARD).update()
            session.sql("insert into groups (group_id, key, name, created_by) values (?::uuid, 'PAYROLL', 'Payroll', ?::uuid)")
                    .params(A_GROUP, FIRST_STEWARD).update()
            connection.connection.commit()
            session
        }
    }
}
