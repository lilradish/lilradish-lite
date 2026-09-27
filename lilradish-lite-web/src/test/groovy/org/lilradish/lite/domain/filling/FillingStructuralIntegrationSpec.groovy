package org.lilradish.lite.domain.filling

import java.nio.file.Files
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.wire.JsonValue.JsonArray
import org.lilradish.lite.domain.wire.JsonValue.JsonMember
import org.lilradish.lite.domain.wire.JsonValue.JsonNull
import org.lilradish.lite.domain.wire.JsonValue.JsonObject
import org.lilradish.lite.domain.wire.JsonValue.JsonString
import org.lilradish.lite.testutil.ReaderVocabulary
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * Which places of a level lack a value or hold one that does not fit, and what is kept, are read where it is
 * filled in and again here, where it arrives. Both are run over one table of whole levels, so a case the two
 * read differently fails on one side.
 */
class FillingStructuralIntegrationSpec extends Specification {

    static final Map<String, Object> TABLE = JsonMapper.builder().build().readValue(
            Files.readString(ReaderVocabulary.FRONTEND.resolve("lib/filling/filling.cases.json")), Map)

    static final List<FillField> FIELDS = (TABLE.fields as List<Map<String, Object>>).collect { fieldOf(it) }

    static final List<Map<String, Object>> CASES = TABLE.cases as List<Map<String, Object>>

    static FillField fieldOf(Map<String, Object> declared) {
        FieldKind kind = FieldKind.values().find { it.published() == declared.kind }
        def held = (declared.fields ?: []) as List<Map<String, Object>>
        new FillField(new FieldName(declared.name as String), null, null, kind, declared.longest as Integer,
                declared.most as Integer, declared.mustBeGiven as boolean, null, held.collect { fieldOf(it) })
    }

    static JsonValue sent(Object parsed) {
        switch (parsed) {
            case null:
                return new JsonNull()
            case String:
                return new JsonString(parsed as String)
            case List:
                return new JsonArray((parsed as List).collect { sent(it) })
            case Map:
                return new JsonObject((parsed as Map<String, Object>).collect { name, value ->
                    new JsonMember(name, sent(value))
                })
            default:
                throw new AssertionError("the table holds nothing a page sends: " + parsed)
        }
    }

    static List<Map<String, Object>> named(FillOutcome outcome) {
        if (!(outcome instanceof FillProblems)) {
            return []
        }
        (outcome as FillProblems).problems().collect { problem ->
            [path: problem.path().steps().collect { step ->
                step instanceof FillPath.Named ? step.name().value() : (step as FillPath.Place).index()
            }, reason: problem.reason().published()]
        }
    }

    def "the table holds cases that fit, each with what is kept, and cases that do not, an empty table agreeing with anything"() {
        expect:
        CASES.any { (it.problems as List).isEmpty() }
        CASES.any { !(it.problems as List).isEmpty() }
        CASES.every { (it.problems as List).isEmpty() == it.containsKey("kept") }
    }

    def "a level is read as the table names its places, in its order, and kept exactly where none is named"() {
        when:
        def outcome = Filling.of(FIELDS, sent(each.sent))

        then:
        !(outcome instanceof FillOutcome.Unshaped)
        named(outcome) == each.problems
        (outcome instanceof FilledFields) == (each.problems as List).isEmpty()

        where:
        each << CASES
    }

    def "a level nothing is named in is kept as the table keeps it, written as it is sent"() {
        when:
        def outcome = Filling.of(FIELDS, sent(each.sent))

        then:
        Filling.wire(FIELDS, (outcome as FilledFields).values()) == sent(each.kept)

        where:
        each << CASES.findAll { it.containsKey("kept") }
    }

    def "a level kept and sent back as the page sends it is read again into the very values kept"() {
        given:
        def keptValues = (Filling.of(FIELDS, sent(each.sent)) as FilledFields).values()

        when:
        def outcome = Filling.of(FIELDS, sent(each.kept))

        then:
        outcome == new FilledFields(keptValues)

        where:
        each << CASES.findAll { it.containsKey("kept") }
    }
}
