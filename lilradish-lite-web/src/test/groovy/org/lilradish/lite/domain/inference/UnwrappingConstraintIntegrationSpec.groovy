package org.lilradish.lite.domain.inference

import static org.lilradish.lite.testutil.inference.Askings.MINUSES_AT_THE_BOUND
import static org.lilradish.lite.testutil.inference.Askings.NINES
import static org.lilradish.lite.testutil.inference.Askings.NUMBERS_AT_THE_BOUND
import static org.lilradish.lite.testutil.inference.Askings.numbersAtTheBound
import static org.lilradish.lite.testutil.inference.Askings.plain
import static org.lilradish.lite.testutil.inference.Askings.text

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.Baseline
import org.lilradish.lite.testutil.inference.Json
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What a model gives back is kept as it came, so what unwrapping lets through is judged by the schema's own
 * checks on the columns it is kept in, and counted as the server writes it back, read off a real server.
 */
class UnwrappingConstraintIntegrationSpec extends Specification {

    static final String GRINNING = Character.toString(0x1F600)

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    JdbcClient session

    def setupSpec() {
        session = JdbcClient.create(Baseline.appliedTo(server, "postgres"))
    }

    /** A null character and half a pair never reach the store at all, and are refused by unwrapping alone. */
    def "words are refused by unwrapping exactly where the store would refuse to keep them, character by character"() {
        given:
        def condition = conditionOf("review_decisions_explanation_visible")

        when:
        def refusedByStore = characters().collate(1000).collectMany { refusedAmong(condition, it) }
        def refusedByUnwrapping = characters().findAll { !(reviewed("a" + it) instanceof ReviewAnswer.Reviewed) }

        then:
        refusedByUnwrapping == refusedByStore
    }

    def "the store refuses some characters in words and keeps others, so agreeing with it says something"() {
        when:
        def refusedByStore = refusedAmong(conditionOf("review_decisions_explanation_visible"),
                [Character.toString(0x01), "\r", Character.toString(0x7F), Character.toString(0x9F), "\t", "\n", "a"])

        then:
        refusedByStore == [Character.toString(0x01), "\r", Character.toString(0x7F), Character.toString(0x9F)]
    }

    def "words as long as the store keeps fit, and one character more does not, counted as the store counts"() {
        expect:
        passes(conditionOf("review_decisions_explanation_bounded"), "explanation", "text", words) == kept
        (reviewed(words) instanceof ReviewAnswer.Reviewed) == kept

        where:
        words            || kept
        "w" * 2048       || true
        GRINNING * 2048  || true
        "w" * 2049       || false
        GRINNING * 2049  || false
    }

    /** A value is kept as jsonb, which writes it back with a space after each comma and colon. */
    def "a value is counted as long as the store writes it back"() {
        given:
        def value = Json.of(literal)

        expect:
        CanonicalJson.storedLength(value) == keptLength(value)

        where:
        literal << [
                "a\tb" + GRINNING + '"' + Character.toString(0x1),
                new BigDecimal("-12.500"),
                true,
                ["a", 1, null, [], [:]],
                [b: [c: ["x", "y"], d: null], a: 2.0],
                [[[]]],
        ]
    }

    /** A control is kept as its six-character escape, the most any one character is kept as. */
    def "a value as long as the store keeps any one fits, and one character more does not"() {
        given:
        def value = new JsonValue.JsonString(Character.toString(0x1) * 8_388_607 + "a" * letters)

        when:
        def unwrapped = Unwrapping.production([text("letter", 8_388_612)], Json.of([values: [letter: value], confidences: [:]]))

        then:
        passes(conditionOf("production_values_value_bounded"), "value", "jsonb", CanonicalJson.write(value)) == kept
        (unwrapped instanceof ProductionAnswer.Produced) == kept
        (unwrapped == new DoesNotFit(DidNotFitReason.TOO_LONG_TO_KEEP)) == !kept

        where:
        letters || kept
        4       || true
        5       || false
    }

    /** Built again where it is kept, and counted alike there. */
    def "numbers as long as the store keeps any one value fit, their marks counted, and one character more does not"() {
        given:
        def value = numbersAtTheBound(onePast)
        def field = plain("amounts", FieldKind.NUMBER, NUMBERS_AT_THE_BOUND)

        when:
        def unwrapped = Unwrapping.production([field], Json.of([values: [amounts: value], confidences: [:]]))

        then:
        keptOnServer(onePast) == "${CanonicalJson.storedLength(value)} ${kept}"
        (unwrapped instanceof ProductionAnswer.Produced) == kept
        (unwrapped == new DoesNotFit(DidNotFitReason.TOO_LONG_TO_KEEP)) == !kept

        where:
        onePast || kept
        false   || true
        true    || false
    }

    def "what a run is started with is kept as long as one value is, and no longer"() {
        given:
        def startedWith = new JsonValue.JsonObject([new JsonValue.JsonMember("complaint",
                new JsonValue.JsonString(Character.toString(0x1) * 8_388_605 + "a" * letters))])

        expect:
        passes(conditionOf("runs_started_with_bounded"), "started_with", "jsonb", CanonicalJson.write(startedWith)) == kept
        (CanonicalJson.storedLength(startedWith) <= Declaration.MOST_STORED) == kept

        where:
        letters || kept
        1       || true
        2       || false
    }

    private static List<String> characters() {
        (1..0xFFFF).findAll { !Character.isSurrogate(it as char) }.collect { Character.toString(it) } + [GRINNING]
    }

    private String conditionOf(String constraint) {
        session.sql("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conname = ? and connamespace = 'app'::regnamespace and contype = 'c'
                """).param(constraint).query(String).single().replaceFirst(/^CHECK /, "")
    }

    private boolean passes(String condition, String column, String type, String candidate) {
        session.sql("select ${condition} from (select cast(? as ${type}) as ${column}) as candidate".toString())
                .param(candidate).query(Boolean).single()
    }

    /** The numbers `numbersAtTheBound` holds, as long as the server writes them back and whether it keeps them. */
    private String keptOnServer(boolean onePast) {
        session.sql("""
                select length(value::text) || ' ' || (${conditionOf("production_values_value_bounded")})
                  from (select jsonb_agg(case when place <= ? then -cast(? as numeric) else cast(? as numeric) end
                                         order by place) as value
                          from generate_series(1, ?) place) as candidate
                """.toString()).params(MINUSES_AT_THE_BOUND + (onePast ? 1 : 0), NINES, NINES, NUMBERS_AT_THE_BOUND)
                .query(String).single()
    }

    private long keptLength(JsonValue value) {
        session.sql("select length(cast(? as jsonb)::text)").param(CanonicalJson.write(value)).query(Long).single()
    }

    /** Each character of the chunk the check refuses as words of its own, in the order they stand. */
    private List<String> refusedAmong(String condition, List<String> chunk) {
        session.sql("""
                select explanation from regexp_split_to_table(?, '') with ordinality as candidate(explanation, place)
                where not (${condition}) order by place
                """.toString()).param(chunk.join()).query(String).list()
    }

    private static ReviewAnswer reviewed(String words) {
        Unwrapping.review([new FieldName("summary")],
                Json.of([decisions: [summary: [outcome: "refused", words: words]]]))
    }
}
