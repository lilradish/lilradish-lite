package org.lilradish.lite.domain.filling

import java.nio.file.Files
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.wire.JsonValue.JsonBoolean
import org.lilradish.lite.domain.wire.JsonValue.JsonMember
import org.lilradish.lite.domain.wire.JsonValue.JsonNull
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber
import org.lilradish.lite.domain.wire.JsonValue.JsonObject
import org.lilradish.lite.domain.wire.JsonValue.JsonString
import org.lilradish.lite.testutil.ReaderVocabulary
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * How one value is written is judged where it is typed and again here, where it arrives, in two languages
 * neither compiler sees the other of. Both are run over one table of what is typed and why it is refused, each
 * row as the one field of a level, so a case the two decide differently fails on one side.
 */
class FillingTypedIntegrationSpec extends Specification {

    static final Map<String, List<Map<String, Object>>> CASES = JsonMapper.builder().build().readValue(
            Files.readString(ReaderVocabulary.FRONTEND.resolve("lib/filling/writing.cases.json")), Map)

    static final List<Map<String, Object>> ROWS = CASES.collectMany { kind, rows -> rows.collect { it + [kind: kind] } }

    static final OfferedTerms CATEGORIES = new OfferedTerms(
            [new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge is disputed.")),
             new OfferedTerms.Offered(new Term("Delivery"), new TermMeaning("It came late."))], null)

    static FieldKind kindOf(Map<String, Object> row) {
        FieldKind.values().find { it.published() == row.kind }
    }

    static String typedIn(Map<String, Object> row) {
        (row.typed as String) * ((row.times ?: 1) as int)
    }

    static FillOutcome filled(Map<String, Object> row, boolean mustBeGiven) {
        FieldKind kind = kindOf(row)
        def field = new FillField(new FieldName("value"), null, null, kind, row.longest as Integer, null, mustBeGiven,
                kind == FieldKind.TERM ? CATEGORIES : null, [])
        Filling.of([field], new JsonObject([new JsonMember("value", new JsonString(typedIn(row)))]))
    }

    static JsonValue readAs(FieldKind kind, String typed) {
        switch (kind) {
            case FieldKind.NUMBER:
                return new JsonNumber(new BigDecimal(typed))
            case FieldKind.YES_NO:
                return new JsonBoolean(Boolean.parseBoolean(typed))
            default:
                return new JsonString(typed)
        }
    }

    /** The reason at the one field's place, or none where it is kept; anything else is no verdict the table names. */
    static String refusedAs(FillOutcome outcome) {
        switch (outcome) {
            case FilledFields:
                return null
            case FillProblems:
                def problems = (outcome as FillProblems).problems()
                assert problems*.path() == [new FillPath([new FillPath.Named(new FieldName("value"))])]
                return problems.first().reason().published()
            default:
                throw new AssertionError("a row of the table was not the shape of its field: " + outcome)
        }
    }

    static JsonObject keptAs(FillOutcome outcome) {
        (outcome as FilledFields).values()
    }

    def "the table holds cases of every kind one value is written as, each verdict among them, an empty table agreeing with anything"() {
        expect:
        CASES.keySet() == (FieldKind.values() - FieldKind.FIELDS)*.published() as Set
        CASES.values().every { !it.isEmpty() }
        !ROWS.findAll { it.refused == null }.isEmpty()
        !ROWS.findAll { it.refused == "missing" }.isEmpty()
        !ROWS.findAll { !(it.refused in [null, "missing"]) }.isEmpty()
    }

    def "a value is refused, where it must be given, for exactly the reason the table names"() {
        expect:
        refusedAs(filled(each, true)) == each.refused

        where:
        each << ROWS
    }

    def "a value found missing where it must be given is kept as no value where it need not be"() {
        when:
        def outcome = filled(each, false)

        then:
        outcome instanceof FilledFields
        keptAs(outcome) == new JsonObject([new JsonMember("value", new JsonNull())])

        where:
        each << ROWS.findAll { it.refused == "missing" }
    }

    def "a value the table takes is kept as typed, read as its kind and nothing rewritten, whether or not it must be given"() {
        when:
        def outcome = filled(each, mustBeGiven)

        then:
        outcome instanceof FilledFields
        keptAs(outcome) == new JsonObject([new JsonMember("value", readAs(kindOf(each), typedIn(each)))])

        where:
        [each, mustBeGiven] << [ROWS.findAll { it.refused == null }, [true, false]].combinations()
    }

    def "a value refused for how it is written is refused alike where it need not be given"() {
        expect:
        refusedAs(filled(each, false)) == each.refused

        where:
        each << ROWS.findAll { !(it.refused in [null, "missing"]) }
    }
}
