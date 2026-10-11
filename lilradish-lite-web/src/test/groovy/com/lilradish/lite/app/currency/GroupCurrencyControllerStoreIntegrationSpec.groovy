package com.lilradish.lite.app.currency

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelPrice
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionOperations
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/** Real roles and currency rows cross MVC; only identification and estate grants are stood in for. */
@WebMvcTest(GroupCurrencyController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([GroupCurrencies, GroupRoles, Store])
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class GroupCurrencyControllerStoreIntegrationSpec extends Specification {
    static final String GROUP = "00000003-0000-4000-8000-000000006061"
    static final String OWNER = "00000002-0000-4000-8000-000000006061"
    static final String OPERATOR = "00000002-0000-4000-8000-000000006062"
    static final UserId OWNER_USER = new UserId("006061")
    static final UserId OPERATOR_USER = new UserId("006062")
    static final String ADDRESS = "/api/groups/${GROUP}/currency"
    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired private MockMvc mockMvc
    @Autowired private LibraryStore store
    @MockitoBean private UserIdentification identification
    @MockitoBean private EstateRoleGrants grants

    private void calling(UserId caller) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(caller))
        given(grants.heldBy(caller)).willReturn(EnumSet.noneOf(EstateRole))
    }

    private def reading(UserId caller) {
        calling(caller)
        def answer = mockMvc.perform(get(ADDRESS)).andReturn()
        assert answer.response.status == 200
        JSON.readTree(answer.response.contentAsString)
    }

    private def choosing(UserId caller, String code) {
        calling(caller)
        mockMvc.perform(put(ADDRESS).header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString([currency: code]))).andReturn()
    }

    def "all membership readers receive one catalog while only the real changer can write it"() {
        given:
        def before = store.contents()

        expect:
        reading(OWNER_USER) == JSON.readTree('{"offered":["EUR","USD"]}')
        reading(OPERATOR_USER) == reading(OWNER_USER)
        store.contents() == before

        when:
        def chosen = choosing(OWNER_USER, "EUR")

        then:
        chosen.response.status == 200
        JSON.readTree(chosen.response.contentAsString) == reading(OWNER_USER)
        reading(OPERATOR_USER) == JSON.readTree('{"chosen":"EUR","offered":["EUR","USD"]}')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.texts("select currency from group_currencies where group_id = ?::uuid", GROUP) == ["EUR"]

        when:
        def held = store.contents()
        def denied = choosing(OPERATOR_USER, "USD")

        then:
        denied.response.status == 403
        JSON.readTree(denied.response.contentAsString).get("code").asString() == "ACT_NOT_PERMITTED"
        store.contents() == held

        when:
        def same = choosing(OWNER_USER, "EUR")

        then:
        same.response.status == 200
        store.contents() == held
        JSON.readTree(same.response.contentAsString) == reading(OPERATOR_USER)

        when:
        def unpriced = choosing(OWNER_USER, "GBP")

        then:
        unpriced.response.status == 400
        JSON.readTree(unpriced.response.contentAsString).get("code").asString() == "CURRENCY_NOT_PRICED"
        store.contents() == held

        when:
        def changed = choosing(OWNER_USER, "USD")

        then:
        changed.response.status == 200
        JSON.readTree(changed.response.contentAsString) == reading(OWNER_USER)
        reading(OPERATOR_USER) == JSON.readTree('{"chosen":"USD","offered":["EUR","USD"]}')
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select updated_by::text from group_currencies where group_id = ?::uuid", GROUP) == [OWNER]
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Store {
        @Bean(destroyMethod = "close")
        TestPostgres server() { TestPostgres.worker() }

        @Bean
        LibraryStore store(TestPostgres server) {
            LibraryStore.prepareBaseline(server)
            def store = LibraryStore.forCase(server, "currency_state_answers")
            store.person(OWNER, "006061", "Ari Currency")
            store.person(OPERATOR, "006062", "Greno Reader")
            store.group(GROUP, "CURRENCY", "Imaginary invoices")
            store.member(GROUP, OWNER, "owner")
            store.member(GROUP, OPERATOR, "operator")
            store
        }

        @Bean DataSource database(LibraryStore store) { store.database }
        @Bean JdbcClient session(LibraryStore store) { store.session }
        @Bean TransactionOperations transactions(LibraryStore store) { store.transactions() }
        @Bean ModelCatalog models() {
            new ModelCatalog([
                    new DeployedModel(new ModelName("imaginary_currency_one"), [], 200000, 4.0, 8192,
                            [new ModelPrice(Currency.getInstance("USD"), 1.0, 2.0), new ModelPrice(Currency.getInstance("EUR"), 1.0, 2.0)], []),
                    new DeployedModel(new ModelName("imaginary_currency_two"), [], 200000, 4.0, 8192,
                            [new ModelPrice(Currency.getInstance("EUR"), 1.0, 2.0)], []),
            ])
        }
    }
}
