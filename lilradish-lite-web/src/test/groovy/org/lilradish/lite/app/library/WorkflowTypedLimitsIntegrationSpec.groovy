package org.lilradish.lite.app.library

import java.nio.file.Files
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.ConstantFit
import org.lilradish.lite.testutil.ReaderVocabulary
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * A constant and a case's term are judged twice: where they are typed, and here, where they arrive. The two are
 * written in two languages, so both are run over one table in the reader's tree and a case they decide
 * differently fails on one side.
 */
class WorkflowTypedLimitsIntegrationSpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final Map<String, List<Map<String, Object>>> CASES = JSON.readValue(
            Files.readString(ReaderVocabulary.FRONTEND.resolve("features/library/constantDrafts.cases.json")), Map)

    static final String QUESTION = "00000007-0000-4000-8000-000000000101"

    static final String WORKFLOW = "00000007-0000-4000-8000-000000000102"

    static final Map ASKING = [name: "classify", runs: [kind: "question", version: QUESTION], producer: null, tries: null,
                               reviewer: null, bindings: []]

    static String typedIn(Map<String, Object> each) {
        (each.typed as String) * ((each.times != null ? each.times : 1) as int)
    }

    static boolean arrives(Map body) {
        try {
            FlowBody.read(JSON.valueToTree([revision: 1] + body) as JsonNode)
            true
        } catch (ApiErrorException ignored) {
            false
        }
    }

    static boolean constantArrives(String written) {
        arrives([steps: [], outputs: [[target: "amount", source: [constant: written]]]])
    }

    static boolean termArrives(String term) {
        arrives([steps: [ASKING, [name: "route",
                                  runs: [kind: "route", discriminator: [step: 0, path: "category"], gives: [],
                                         cases: [[term: term, workflow: WORKFLOW, bindings: []]]],
                                  bindings: []]],
                 outputs: []])
    }

    static boolean fits(String written, FieldKind filled, HowMany howMany, boolean mustBeGiven) {
        JsonValue constant
        try {
            constant = ConstantJson.sent(written)
        } catch (IllegalArgumentException ignored) {
            return false
        }
        constantArrives(written) && ConstantFit.fits(constant, new Field(new FieldName("amount"), null, null,
                new FieldShape.Plain(filled), howMany, new Demand.Given(mustBeGiven)), [:])
    }

    /**
     * Sent as the page sends one value of the kind typed: a date or a moment as text, but null as null where it need
     * not be given, anything else as written.
     */
    static boolean fitsOne(Map<String, Object> each) {
        FieldKind filled = FieldKind.values().find { it.published() == each.kind }
        String typed = typedIn(each)
        // Not `?: true`: Groovy truth would turn a declared false into true.
        boolean mustBeGiven = each.mustBeGiven != false
        boolean quoted = (filled == FieldKind.DATE || filled == FieldKind.MOMENT) && (mustBeGiven || typed != "null")
        fits(quoted ? JSON.writeValueAsString(typed) : typed, filled, new HowMany.One(), mustBeGiven)
    }

    def "the table holds cases for every rule, an empty table agreeing with anything"() {
        expect:
        !CASES.written.isEmpty()
        !CASES.text.isEmpty()
        !CASES.one.isEmpty()
        !CASES.many.isEmpty()
        !CASES.term.isEmpty()
    }

    def "a constant written out arrives exactly where the table says it is taken"() {
        expect:
        constantArrives(typedIn(each)) == each.accepted

        where:
        each << CASES.written
    }

    def "a constant of text arrives exactly where the table says it is taken"() {
        expect:
        constantArrives(JSON.writeValueAsString(typedIn(each))) == each.accepted

        where:
        each << CASES.text
    }

    def "one value of a kind typed arrives and fits what it fills exactly where the table says it is taken"() {
        expect:
        fitsOne(each) == each.accepted

        where:
        each << CASES.one
    }

    def "many numbers written out arrive and fit what they fill exactly where the table says they are taken"() {
        expect:
        fits(typedIn(each), FieldKind.NUMBER, new HowMany.Many(null), each.mustBeGiven != false) == each.accepted

        where:
        each << CASES.many
    }

    def "a case's term arrives exactly where the table says it is taken"() {
        expect:
        termArrives(typedIn(each)) == each.accepted

        where:
        each << CASES.term
    }
}
