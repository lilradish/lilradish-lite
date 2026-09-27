package org.lilradish.lite.web

import static java.nio.charset.StandardCharsets.UTF_8

import jakarta.servlet.http.HttpServletRequestWrapper
import java.util.function.LongSupplier
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.failure.RefusalCode
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.ErrorResponseException
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class JsonBodySpec extends Specification {

    static final String REFUSED_AS = "This takes one thing."

    static final String NAMING = '{"userId":"000501"}'

    static final int ONE_MEBIBYTE = 1024 * 1024

    private static MockHttpServletRequest sent(String contentType, byte[] body) {
        def request = new MockHttpServletRequest("POST", "/api/pool/people")
        if (contentType != null) {
            request.contentType = contentType
        }
        request.content = body
        request
    }

    /** Text as UTF-8 and a list of numbers as the raw bytes they are, one after another. */
    private static byte[] spelt(Object... parts) {
        def joined = new ByteArrayOutputStream()
        parts.each { part ->
            joined.write(part instanceof String ? part.getBytes(UTF_8) : part.collect { value -> (byte) value } as byte[])
        }
        joined.toByteArray()
    }

    def "reads the document a body declared as JSON holds, up to and at the bound it is read to"() {
        given:
        def request = sent(contentType, body.getBytes(UTF_8))
        request.addHeader("Content-Encoding", coding)

        when:
        def document = JsonBody.read(request, NAMING.length() + 2, REFUSED_AS)

        then:
        document == JsonMapper.builder().build().readTree(body)

        where:
        contentType                         | body               | coding
        "application/json"                  | NAMING             | "identity"
        "application/json;charset=US-ASCII" | " " + NAMING + " " | "IDENTITY"
    }

    /** Read through a double, a number with a fraction would be rounded to the nearest one a double holds. */
    def "reads a number with a fraction as the decimal written, and a whole number as the whole number it is"() {
        given:
        def body = '{"amount":0.1000000000000000055511151231257827,"count":3}'

        when:
        def document = JsonBody.read(sent("application/json", body.getBytes(UTF_8)), body.length(), REFUSED_AS)

        then:
        document.get("amount").decimalValue() == new BigDecimal("0.1000000000000000055511151231257827")
        !document.get("amount").isDouble()
        document.get("count").isInt()
    }

    /** Judged by the declaration alone: what is wrong is what it was declared as, so no byte is read. */
    def "refuses a body declared as anything but JSON as a type it does not take, reading none of it"() {
        given:
        def request = sent(contentType, NAMING.getBytes(UTF_8))

        when:
        JsonBody.read(request, 4096, REFUSED_AS)

        then:
        def refused = thrown(ErrorResponseException)
        refused.statusCode == HttpStatus.UNSUPPORTED_MEDIA_TYPE
        refused.body.detail == "This takes a body declared as application/json, in UTF-8."
        refused.headers.getAccept() == [MediaType.APPLICATION_JSON]

        and: "with every byte of the body still unread, and no coding named as the fault"
        request.inputStream.readAllBytes() == NAMING.getBytes(UTF_8)
        refused.headers.getFirst("Accept-Encoding") == null

        where:
        contentType << [null, "text/plain"]
    }

    def "refuses a body declared as JSON that is not one document of UTF-8 JSON within the bound"() {
        given:
        def request = sent("application/json", body)

        when:
        JsonBody.read(request, NAMING.length(), REFUSED_AS)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        refused.message == REFUSED_AS

        where:
        body << [spelt(NAMING + " "),
                 spelt(""),
                 spelt("   "),
                 spelt([0xEF, 0xBB, 0xBF], '{"userId":1}'),
                 spelt('{"userId":"', [0xC3, 0x28], '"}'),
                 spelt('{"userId":1,"userId":2}'),
                 spelt('{"userId":1} {}'),
                 spelt('{"userId":')]
    }

    private static HttpServletRequestWrapper declaring(long length, MockHttpServletRequest request) {
        new HttpServletRequestWrapper(request) {
            @Override
            long getContentLengthLong() {
                length
            }
        }
    }

    def "refuses a body declared longer than the bound without reading a byte of it, whatever it holds"() {
        given:
        def request = declaring(NAMING.length() + 1, sent("application/json", NAMING.getBytes(UTF_8)))

        when:
        JsonBody.read(request, NAMING.length(), REFUSED_AS)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        refused.message == REFUSED_AS
        request.inputStream.readAllBytes() == NAMING.getBytes(UTF_8)
    }

    def "refuses a body sent with no declared length once one byte past the bound is read, reading no further"() {
        given:
        def request = declaring(-1, sent("application/json", (NAMING + "    ").getBytes(UTF_8)))

        when:
        JsonBody.read(request, NAMING.length() + 1, REFUSED_AS)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        refused.message == REFUSED_AS
        request.inputStream.readAllBytes() == "  ".getBytes(UTF_8)
    }

    def "reads a body sent with no declared length that reaches the bound exactly"() {
        given:
        def body = " " + NAMING + " "
        def request = declaring(-1, sent("application/json", body.getBytes(UTF_8)))

        when:
        def document = JsonBody.read(request, body.length(), REFUSED_AS)

        then:
        document == JsonMapper.builder().build().readTree(body)
        request.inputStream.readAllBytes().length == 0
    }

    private static MockHttpServletRequest holdingTheTurn(MockHttpServletRequest request) {
        readBy(1L, 0L, request)
    }

    /** A fault rather than a refusal: every handler reading one that large stands behind the turn. */
    def "refuses to read a body that may run past a mebibyte where the turn is not held, reading none of it"() {
        given:
        def request = declaring(declared, sent("application/json", NAMING.getBytes(UTF_8)))

        when:
        JsonBody.read(request, ONE_MEBIBYTE + 1, REFUSED_AS)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "A body that may run past a mebibyte is read without the turn held for it"
        request.inputStream.readAllBytes() == NAMING.getBytes(UTF_8)

        where:
        declared << [-1L, ONE_MEBIBYTE + 1L]
    }

    def "reads a body bound past a mebibyte where the turn is held, or where no more than a mebibyte may come: #why"() {
        given:
        def sending = sent("application/json", NAMING.getBytes(UTF_8))
        def request = declaring(declared, held ? holdingTheTurn(sending) : sending)

        when:
        def document = JsonBody.read(request, largest, REFUSED_AS)

        then:
        document == JsonMapper.builder().build().readTree(NAMING)
        request.inputStream.readAllBytes().length == 0

        where:
        why                                               | held  | declared          | largest
        "the turn held, no length declared"               | true  | -1L               | ONE_MEBIBYTE + 1
        "the turn held, a length past a mebibyte"         | true  | ONE_MEBIBYTE + 1L | 2 * ONE_MEBIBYTE
        "no turn, a length of exactly a mebibyte"         | false | ONE_MEBIBYTE      | ONE_MEBIBYTE + 1
        "no turn, no length, bound at exactly a mebibyte" | false | -1L               | ONE_MEBIBYTE
    }

    private static MockHttpServletRequest readBy(long endsAtNanos, long nowNanos, MockHttpServletRequest request) {
        request.setAttribute(
                LargeBodyAdmission.HOLDING,
                new LargeBodyAdmission.ReadDeadline({ nowNanos } as LongSupplier, endsAtNanos))
        request
    }

    def "reads a body held for its turn whose reads land by the read deadline, the clock read across its wrap: #why"() {
        given:
        def request = readBy(endsAt, now, sent("application/json", NAMING.getBytes(UTF_8)))

        when:
        def document = JsonBody.read(request, 4096, REFUSED_AS)

        then:
        document == JsonMapper.builder().build().readTree(NAMING)
        request.inputStream.readAllBytes().length == 0

        where:
        why                                    | endsAt                  | now
        "landing at the deadline exactly"      | 100L                    | 100L
        "landing before it"                    | 101L                    | 100L
        "a deadline set past the clock's wrap" | Long.MIN_VALUE + 5      | Long.MAX_VALUE
    }

    def "refuses a body held for its turn once a read lands past the read deadline, as sent too slowly, reading no further: #why"() {
        given:
        def body = (NAMING + " " * JsonBody.CHUNK_BYTES).getBytes(UTF_8)
        def request = readBy(endsAt, now, sent("application/json", body))

        when:
        JsonBody.read(request, body.length, REFUSED_AS)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_SENT_TOO_SLOWLY
        refused.message == "What was sent arrived too slowly to be read; send it again."

        and: "what came after the one chunk read still unread"
        request.inputStream.readAllBytes().length == body.length - JsonBody.CHUNK_BYTES

        where:
        why                                  | endsAt             | now
        "one nanosecond past"                | 99L                | 100L
        "past, the clock read across a wrap" | Long.MAX_VALUE - 5 | Long.MIN_VALUE
    }

    /** Declared twice, recipients disagree on which declaration counts, so neither is taken. */
    def "refuses a body declared more than once, even as JSON each time"() {
        given: "both values held, as a container holds a field sent twice and the mock request cannot"
        def request = new HttpServletRequestWrapper(sent("application/json", NAMING.getBytes(UTF_8))) {
            @Override
            Enumeration<String> getHeaders(String name) {
                name.equalsIgnoreCase("Content-Type")
                        ? Collections.enumeration(["application/json", "application/json"])
                        : super.getHeaders(name)
            }
        }

        when:
        JsonBody.read(request, 4096, REFUSED_AS)

        then:
        def refused = thrown(ErrorResponseException)
        refused.statusCode == HttpStatus.UNSUPPORTED_MEDIA_TYPE
        request.inputStream.readAllBytes() == NAMING.getBytes(UTF_8)
    }

    /** Case is no part of a type, a parameter JSON does not define is ignored, and a charset must be UTF-8's. */
    def "takes a declaration as JSON only where it names JSON in UTF-8 or its ASCII subset"() {
        expect:
        JsonBody.declaredJson(contentType) == taken

        where:
        contentType                              || taken
        "application/json"                       || true
        "APPLICATION/Json"                       || true
        "application/json;charset=UTF-8"         || true
        "application/json; charset=utf-8"        || true
        'application/json;charset="utf-8"'       || true
        "application/json;charset=utf8"          || true
        "application/json;charset=US-ASCII"      || true
        "application/json;version=2"             || true
        null                                     || false
        ""                                       || false
        "text/plain"                             || false
        "text/json"                              || false
        "application/xml"                        || false
        "application/jsonl"                      || false
        "application/problem+json"               || false
        "application/merge-patch+json"           || false
        "application/x-www-form-urlencoded"      || false
        "application/*"                          || false
        "*/*"                                    || false
        "application/json, text/plain"           || false
        "application/json;charset=ISO-8859-1"    || false
        "application/json;charset=windows-1252"  || false
        "application/json;charset=UTF-16"        || false
        "application/json;charset=no-such"       || false
        "application json"                       || false
        "not a media type"                       || false
    }

    def "refuses a body sent with a content coding, naming the one coding it takes"() {
        given:
        def request = sent("application/json", NAMING.getBytes(UTF_8))
        codings.each { request.addHeader("Content-Encoding", it) }

        when:
        JsonBody.read(request, 4096, REFUSED_AS)

        then:
        def refused = thrown(ErrorResponseException)
        refused.statusCode == HttpStatus.UNSUPPORTED_MEDIA_TYPE
        refused.body.detail == "This takes a body sent without a content coding."
        refused.headers.getFirst("Accept-Encoding") == "identity"

        and: "the media type not named as the fault"
        refused.headers.getAccept() == []
        request.inputStream.readAllBytes() == NAMING.getBytes(UTF_8)

        where:
        codings << [["gzip"], ["identity", "br"], ["gzip, identity"]]
    }
}
