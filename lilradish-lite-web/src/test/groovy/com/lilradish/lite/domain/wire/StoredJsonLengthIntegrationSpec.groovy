package com.lilradish.lite.domain.wire

import com.lilradish.lite.domain.wire.JsonValue.JsonArray
import com.lilradish.lite.domain.wire.JsonValue.JsonBoolean
import com.lilradish.lite.domain.wire.JsonValue.JsonMember
import com.lilradish.lite.domain.wire.JsonValue.JsonNull
import com.lilradish.lite.domain.wire.JsonValue.JsonNumber
import com.lilradish.lite.domain.wire.JsonValue.JsonObject
import com.lilradish.lite.domain.wire.JsonValue.JsonString
import com.lilradish.lite.testsupport.TestPostgres
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What {@link CanonicalJson#storedLength} counts, held against a real server's own text of the same document:
 * the bound on a stored constant is counted there, so a count made here that runs short lets past what it refuses.
 */
class StoredJsonLengthIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    def "a document's stored length is the length of the text the server writes for it"() {
        given:
        def written = CanonicalJson.write(value)

        expect:
        CanonicalJson.storedLength(value) == serverLength(written)

        where:
        value << [
                new JsonObject([new JsonMember("b", new JsonArray([new JsonBoolean(true), new JsonNull()])),
                                new JsonMember("a", new JsonNumber(new BigDecimal("1.50")))]),
                new JsonArray([new JsonNumber(new BigDecimal("1E+2")), new JsonNumber(new BigDecimal("-0.001"))]),
                new JsonString("line\nbreak\t" + Character.toString(0x01) + " \"quoted\" " + Character.toString(0x5C)),
                new JsonString(Character.toString(0x1F600) + Character.toString(0xE9) + Character.toString(0x7F)),
                new JsonObject([new JsonMember("k" + Character.toString(0x1F), new JsonObject([]))]),
        ]
    }

    private int serverLength(String written) {
        server.postgresDatabase.connection.withCloseable { connection ->
            // DB-SPECIFIC: PostgreSQL :: casts to text; jsonb type.
            connection.prepareStatement("select length(cast(? as jsonb)::text)").withCloseable { statement ->
                statement.setString(1, written)
                statement.executeQuery().withCloseable { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }
    }
}
