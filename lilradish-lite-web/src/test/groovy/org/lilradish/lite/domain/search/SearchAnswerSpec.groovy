package org.lilradish.lite.domain.search

import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.inference.DoesNotFit
import org.lilradish.lite.domain.inference.ProductionAnswer
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.inference.Json
import spock.lang.Specification

class SearchAnswerSpec extends Specification {

    static final SearchWay WAY = new SearchWay("web_search", new ModelToolName("search"), 5, 6, 7, 8)

    static final Map ANSWERED = [title: "Title", body: "Body", references: "Refs", other: "Other"]

    def "a way's outputs are asked in their order, each a text at its limit given as the way demands and asking no confidence"() {
        when:
        def asked = SearchAnswer.asked(WAY)

        then:
        asked*.name() == [SearchWay.TITLE, SearchWay.BODY, SearchWay.REFERENCES, SearchWay.OTHER]
        asked*.longest() == [5, 6, 7, 8]
        asked*.mustBeGiven() == WAY.outputs()*.demand()*.mustBe()
        asked*.mustBeGiven() == [true, true, false, false]
        asked.every { it.kind() == FieldKind.TEXT }

        and: "nothing a text does not hold, and no confidence"
        asked.every { !it.confidenceAsked() && it.most() == null && it.terms() == null && it.fields().isEmpty() }
    }

    def "an answer holding each output as text within its limit is read as it came, in any order, with no confidence"() {
        when:
        def read = SearchAnswer.read(SearchAnswer.asked(WAY), Json.of(answered))

        then:
        read == new ProductionAnswer.Produced(keptOf(ANSWERED), [:])

        where:
        answered << [ANSWERED, reversed()]
    }

    def "an output that need not be given, given none or text that shows nothing, fits as none beside the rest as given"() {
        when:
        def read = SearchAnswer.read(SearchAnswer.asked(WAY), Json.of(ANSWERED + [(output): blank]))

        then:
        read == new ProductionAnswer.Produced(keptOf(ANSWERED + [(output): null]), [:])

        where:
        [output, blank] << [["references", "other"], [null, "", " \t\n"]].combinations()
    }

    def "each output at exactly its limit fits, and one character more does not, as too long"() {
        given:
        def asked = SearchAnswer.asked(WAY)
        def limit = WAY.outputs().find { it.name() == output }.shape().longest()

        when:
        def atLimit = SearchAnswer.read(asked, Json.of(ANSWERED + [(output.value()): "a" * limit]))
        def past = SearchAnswer.read(asked, Json.of(ANSWERED + [(output.value()): "a" * (limit + 1)]))

        then:
        atLimit == new ProductionAnswer.Produced(keptOf(ANSWERED + [(output.value()): "a" * limit]), [:])
        past == new DoesNotFit(DidNotFitReason.TOO_LONG)

        where:
        output << [SearchWay.TITLE, SearchWay.BODY, SearchWay.REFERENCES, SearchWay.OTHER]
    }

    def "an answer that is not the four outputs does not fit, naming the first thing found wrong"() {
        expect:
        SearchAnswer.read(SearchAnswer.asked(WAY), Json.of(answered)) == new DoesNotFit(reason)

        where:
        answered                                           || reason
        null                                               || DidNotFitReason.NOT_THE_SHAPE
        "Title"                                            || DidNotFitReason.NOT_THE_SHAPE
        [ANSWERED]                                         || DidNotFitReason.NOT_THE_SHAPE
        [values: ANSWERED, confidences: [:]]               || DidNotFitReason.FIELD_UNKNOWN
        ANSWERED + [note: "x"]                             || DidNotFitReason.FIELD_UNKNOWN
        without("body")                                    || DidNotFitReason.FIELD_MISSING
        without("other")                                   || DidNotFitReason.FIELD_MISSING
        ANSWERED + [title: null]                           || DidNotFitReason.NOTHING_GIVEN
        ANSWERED + [body: ""]                              || DidNotFitReason.NOTHING_GIVEN
        ANSWERED + [body: " \t\n"]                         || DidNotFitReason.NOTHING_GIVEN
        ANSWERED + [title: 5]                              || DidNotFitReason.NOT_ITS_KIND
        ANSWERED + [title: true]                           || DidNotFitReason.NOT_ITS_KIND
        ANSWERED + [title: ["a"]]                          || DidNotFitReason.NOT_ITS_KIND
        ANSWERED + [title: [a: "b"]]                       || DidNotFitReason.NOT_ITS_KIND
        ANSWERED + [other: ["a"]]                          || DidNotFitReason.NOT_ITS_KIND
        ANSWERED + [title: "a" + Character.toString(0x0)]  || DidNotFitReason.UNKEEPABLE
        ANSWERED + [title: Character.toString(0xD800)]     || DidNotFitReason.UNKEEPABLE
        ANSWERED + [title: Character.toString(0xDC00)]     || DidNotFitReason.UNKEEPABLE
        ANSWERED + [title: "a" * 6, body: null]            || DidNotFitReason.TOO_LONG
    }

    def "an answer that could not be read, arriving as none, is not the shape"() {
        expect:
        SearchAnswer.read(SearchAnswer.asked(WAY), null) == new DoesNotFit(DidNotFitReason.NOT_THE_SHAPE)
    }

    private static Map without(String name) {
        ANSWERED.findAll { it.key != name }
    }

    /** Every output as answered, in the reverse of the way's order. */
    private static Map reversed() {
        ANSWERED.entrySet().toList().reverse().collectEntries { [(it.key): it.value] }
    }

    private static Map<FieldName, JsonValue> keptOf(Map answered) {
        answered.collectEntries { name, said -> [(new FieldName(name as String)): Json.of(said)] }
    }
}
