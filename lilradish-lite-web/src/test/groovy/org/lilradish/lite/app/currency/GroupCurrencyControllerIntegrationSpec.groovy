package org.lilradish.lite.app.currency

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import jakarta.servlet.http.HttpServletRequest
import java.nio.charset.StandardCharsets
import org.libprunus.core.error.ApiErrorException
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What a group's currency is answered with, read and chosen, over a real dispatcher: the members each
 * answer carries, which choice the request was turned into, and every refusal a request meets before the
 * store is asked anything.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling and
 * what they hold in the group. What a choice does to the store is its own spec's question. Every change
 * says it came from this application's own pages, which is the door's question and is asked of it
 * elsewhere.
 */
@WebMvcTest(GroupCurrencyController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class GroupCurrencyControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000b01")

    static final String CURRENCY = "/api/groups/${GROUP}/currency"

    static final List<Currency> PRICED = [currency("EUR"), currency("USD")]

    static final String BODY_REFUSED =
            "This takes a JSON object holding one currency code in three capital English letters, and nothing else."

    static final int LARGEST_BODY = 256

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private GroupCurrencies currencies

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
    }

    /** As this application's own pages send it, which a browser says of every request it makes. */
    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private MvcResult choosing(String body) {
        sending(put(CURRENCY).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static Currency currency(String code) {
        Currency.getInstance(code)
    }

    /** Every character of {@code text} written as a JSON escape, so nothing of it is sent as itself. */
    private static String escaped(String text) {
        text.collect { String.format("\\u%04x", (int) it.charAt(0)) }.join()
    }

    /** A choice of USD written wholly in escapes, padded out with spaces to {@code length} bytes. */
    private static String paddedTo(int length) {
        def document = '{"' + escaped("currency") + '":"' + escaped("USD") + '"}'
        document + " " * (length - document.length())
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void askedOnly(Closure<?> asked) {
        asked(Mockito.verify(currencies))
        Mockito.verifyNoMoreInteractions(currencies)
    }

    /** Nothing chosen and nothing offered are left out; an empty offer is one nothing is priced for, and sent. */
    def "answers the currency as the store read it, leaving out what is absent"() {
        given:
        holding(held)
        given(currencies.read(new GroupId(GROUP), READER)).willReturn(read)

        when:
        def answered = mockMvc.perform(get(CURRENCY)).andReturn()

        then:
        answered.response.status == 200
        documentOf(answered) == JSON.readTree(json)

        and:
        askedOnly { it.read(new GroupId(GROUP), READER) }

        where:
        held                            | read                                                  || json
        EnumSet.of(GroupRole.OWNER)     | new GroupCurrencies.Choice(currency("EUR"), PRICED)   || '{"chosen":"EUR","offered":["EUR","USD"]}'
        EnumSet.of(GroupRole.OWNER)     | new GroupCurrencies.Choice(null, PRICED)              || '{"offered":["EUR","USD"]}'
        EnumSet.of(GroupRole.OWNER)     | new GroupCurrencies.Choice(currency("GBP"), [])       || '{"chosen":"GBP","offered":[]}'
        EnumSet.of(GroupRole.OWNER)     | new GroupCurrencies.Choice(null, [])                  || '{"offered":[]}'
        EnumSet.of(GroupRole.OPERATOR)  | new GroupCurrencies.Choice(currency("EUR"), null)     || '{"chosen":"EUR"}'
        EnumSet.of(GroupRole.OPERATOR)  | new GroupCurrencies.Choice(null, null)                || '{}'
    }

    /** A group the caller holds nothing in is not refused for a permission: it is not there to them. */
    def "refuses the currency to anybody holding no role in the group, before the store is asked anything"() {
        given:
        holding(EnumSet.noneOf(GroupRole))

        when:
        def answered = mockMvc.perform(get(CURRENCY)).andReturn()

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "GROUP_NOT_IN_VIEW"
        documentOf(answered).get("detail").asString() == "That group is not in view."

        and:
        Mockito.verifyNoInteractions(currencies)
    }

    def "chooses the currency named, answering as a read of it does"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        given(currencies.choose(new GroupId(GROUP), "USD", READER))
                .willReturn(new GroupCurrencies.Choice(currency("USD"), PRICED))

        when:
        def answered = choosing('{"currency":"USD"}')

        then:
        answered.response.status == 200
        documentOf(answered) == JSON.readTree('{"chosen":"USD","offered":["EUR","USD"]}')

        and:
        askedOnly { it.choose(new GroupId(GROUP), "USD", READER) }
    }

    /** Any other spelling of a code, capitals outside English among them, and anything the body does not take. */
    def "refuses a body that does not hold one currency code in three capitals, before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = choosing(body)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == BODY_REFUSED

        and:
        Mockito.verifyNoInteractions(currencies)

        where:
        body << [
                '{"currency":"eur"}',
                '{"currency":"Eur"}',
                '{"currency":"EU"}',
                '{"currency":"EURO"}',
                '{"currency":""}',
                '{"currency":" EUR"}',
                '{"currency":"EUR "}',
                '{"currency":"E1R"}',
                '{"currency":"E_R"}',
                '{"currency":"USÉ"}',
                '{"currency":"ＵSD"}',
                '{"currency":"KRW"}',
                '{"currency":"İSK"}',
                '{"currency":7}',
                '{"currency":true}',
                '{"currency":null}',
                '{"currency":["EUR"]}',
                '{"currency":{"code":"EUR"}}',
                '{"currency":"EUR","group":"PAYROLL"}',
                '{"currency":"EUR","currency":"USD"}',
                '{"Currency":"EUR"}',
                '{"code":"EUR"}',
                '{}',
                '["EUR"]',
                '"EUR"',
                'EUR',
                '',
        ]
    }

    /** The limit counts bytes as sent, so a body escaped in full and padded out reaches it exactly. */
    def "takes a body escaped in full and exactly as long as the limit, reading the code it holds"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        given(currencies.choose(new GroupId(GROUP), "USD", READER))
                .willReturn(new GroupCurrencies.Choice(currency("USD"), PRICED))
        def body = paddedTo(LARGEST_BODY)

        when:
        def answered = choosing(body)

        then:
        body.getBytes(StandardCharsets.UTF_8).length == LARGEST_BODY
        answered.response.status == 200

        and:
        askedOnly { it.choose(new GroupId(GROUP), "USD", READER) }
    }

    def "refuses a body a byte longer than the limit, before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        def body = paddedTo(LARGEST_BODY + 1)

        when:
        def answered = choosing(body)

        then:
        body.getBytes(StandardCharsets.UTF_8).length == LARGEST_BODY + 1
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == BODY_REFUSED

        and:
        Mockito.verifyNoInteractions(currencies)
    }

    def "refuses a choice sent with a parameter, before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(put(CURRENCY).queryParam("currency", "EUR")
                .contentType(MediaType.APPLICATION_JSON).content('{"currency":"EUR"}'))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "PARAMETER_UNKNOWN"

        and:
        Mockito.verifyNoInteractions(currencies)
    }

    def "refuses a choice not declared as JSON, before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        def request = put(CURRENCY).content('{"currency":"EUR"}')

        when:
        def answered = sending(declared == null ? request : request.contentType(declared))

        then:
        answered.response.status == 415
        documentOf(answered).get("code").asString() == "UNSUPPORTED_MEDIA_TYPE"

        and:
        Mockito.verifyNoInteractions(currencies)

        where:
        declared << [null, MediaType.TEXT_PLAIN_VALUE]
    }

    /** Refused in the category the code declares, and in the store's own words. */
    def "a currency the store finds no model priced in is refused as the store refused it"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        given(currencies.choose(new GroupId(GROUP), "GBP", READER)).willThrow(
                new ApiErrorException(RefusalCode.CURRENCY_NOT_PRICED, "No model is priced in that currency."))

        when:
        def answered = choosing('{"currency":"GBP"}')

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "CURRENCY_NOT_PRICED"
        documentOf(answered).get("detail").asString() == "No model is priced in that currency."

        and: "asked once, and not asked to read in its place"
        askedOnly { it.choose(new GroupId(GROUP), "GBP", READER) }
    }

    /** A member who may only see the members is refused for that; somebody holding nothing there sees no group. */
    def "refuses a choice to anybody who may not change the membership, and never asks the store"() {
        given:
        holding(held)

        when:
        def answered = choosing('{"currency":"EUR"}')

        then:
        answered.response.status == status
        documentOf(answered).get("code").asString() == code
        documentOf(answered).get("detail").asString() == detail

        and:
        Mockito.verifyNoInteractions(currencies)

        where:
        held                                               || status | code                | detail
        EnumSet.of(GroupRole.OPERATOR, GroupRole.OVERSEER) || 403    | "ACT_NOT_PERMITTED" | "This caller may not do that."
        EnumSet.noneOf(GroupRole)                          || 404    | "GROUP_NOT_IN_VIEW" | "That group is not in view."
    }
}
