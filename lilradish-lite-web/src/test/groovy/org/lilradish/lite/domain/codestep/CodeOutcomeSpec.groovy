package org.lilradish.lite.domain.codestep

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.inference.ForeignProse
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification

class CodeOutcomeSpec extends Specification {

    static final EntryVersionId TIERS = new EntryVersionId(UUID.fromString("00000000-0000-4000-8000-00000000f001"))

    static final OfferedTerms TIER_TERMS = new OfferedTerms([
        new OfferedTerms.Offered(new Term("gold"), new TermMeaning("Gold")),
        new OfferedTerms.Offered(new Term("silver"), new TermMeaning("Silver"))], null)

    static final Field RECEIPT = stands("receipt", new FieldShape.Text(8), new HowMany.One(), true, FieldStanding.ALWAYS, null)

    static final Field NOTE = stands("note", new FieldShape.Text(16), new HowMany.One(), false, FieldStanding.NEVER, null)

    static final Field TIER = stands("tier", new FieldShape.Term(TIERS), new HowMany.One(), false, FieldStanding.ABOVE_CONFIDENCE, 80)

    static final Field LINES = stands("lines", new FieldShape.Nested([
        new Field(new FieldName("sku"), null, null, new FieldShape.Text(4), new HowMany.One(), new Demand.Given(true)),
        new Field(new FieldName("colour"), null, null, new FieldShape.Text(8), new HowMany.One(), new Demand.Given(false))]),
            new HowMany.Many(2), false, FieldStanding.NEVER, null)

    /** Files a claim for a ticket, giving back a receipt it must give, and a note, a tier and lines it may. */
    static final CodeCall FILE_CLAIM = new CodeCall(new CodeStepName("file_claim"), declaring([RECEIPT, NOTE, TIER, LINES]),
            [(TIERS): TIER_TERMS], json([ticket: "T-1"]))

    /** Takes a ticket and gives back nothing, which is all it declares. */
    static final CodeCall ARCHIVE = new CodeCall(new CodeStepName("archive"), declaring([]), [:], json([ticket: "T-1"]))

    /** The store's bound on what came back, less what {"receipt":"R-1","extra":""} takes around the text. */
    static final int LONGEST_KEPT_EXTRA = 8_388_608 - 28

    /** One character the store counts as one, and Java as two. */
    static final String PAIR = Character.toString(0x1F600)

    static final String BELL = Character.toString(7)

    def "gives back a value for every field it declares, in declared order, each standing as its field says"() {
        when:
        def outcome = CodeOutcome.returned(FILE_CLAIM, json(returned))

        then:
        outcome instanceof CodeOutcome.Gave
        (outcome as CodeOutcome.Gave).values() == [
            new CodeOutcome.Given(RECEIPT.name(), value(returned.receipt), FieldStanding.ALWAYS, null),
            new CodeOutcome.Given(NOTE.name(), value(returned.note), FieldStanding.NEVER, null),
            new CodeOutcome.Given(TIER.name(), value(returned.tier), FieldStanding.ABOVE_CONFIDENCE, 80),
            new CodeOutcome.Given(LINES.name(), value(returned.lines), FieldStanding.NEVER, null)]

        where:
        returned << [
            [lines: [[sku: "A1", colour: "red"]], tier: "gold", note: "Filed.", receipt: "R-1"],
            [receipt: "R-1", note: null, tier: "silver", lines: []],
            [receipt: "R-1"]]
    }

    /** As at the first level, a member left out that need not be given is that member given nothing. */
    def "a member left out below the first level that need not be given is given nothing, in declared order"() {
        when:
        def outcome = CodeOutcome.returned(FILE_CLAIM, json([receipt: "R-1", lines: [[sku: "A1"]]]))

        then:
        outcome instanceof CodeOutcome.Gave
        (outcome as CodeOutcome.Gave).values()*.value() ==
                [value("R-1"), value(null), value(null), value([[sku: "A1", colour: null]])]
    }

    def "code that gave back nothing at all went wrong for that reason, about no field, with nothing to keep"() {
        expect:
        CodeOutcome.returned(FILE_CLAIM, null) == erred(CodeErrorReason.GAVE_NOTHING, [], null, null)
    }

    /** Found as the reading finds it, in declared order, after any member it does not declare. */
    def "code that gave nothing for a field that must be given went wrong for that reason, about the field, and what it gave is kept"() {
        expect:
        CodeOutcome.returned(FILE_CLAIM, json(returned)) == erred(CodeErrorReason.NOTHING_GIVEN, ["receipt"], null, kept)

        where:
        returned                          || kept
        [:]                               || '{}'
        [receipt: null, note: "Filed."]   || '{"receipt":null,"note":"Filed."}'
        [tier: "iron", receipt: null]     || '{"tier":"iron","receipt":null}'
    }

    /** An item of a many found wrong is said to be within that many, never where inside the item. */
    def "code that gave back what does not fit went wrong for the first thing wrong, about the field it is in, and what it gave is kept"() {
        expect:
        CodeOutcome.returned(FILE_CLAIM, json(returned)) == erred(reason, path, null, kept)

        where:
        returned                                                          || reason                        | path        | kept
        [receipt: "R-123456789"]                                          || CodeErrorReason.TOO_LONG      | ["receipt"] | '{"receipt":"R-123456789"}'
        [receipt: 5]                                                      || CodeErrorReason.NOT_ITS_KIND  | ["receipt"] | '{"receipt":5}'
        [receipt: "R-1", tier: "iron"]                                    || CodeErrorReason.NOT_A_TERM    | ["tier"]    | '{"receipt":"R-1","tier":"iron"}'
        [receipt: "R-1", lines: [[sku: "A1"], [sku: "A2"], [sku: "A3"]]]  || CodeErrorReason.TOO_MANY      | ["lines"]   | '{"receipt":"R-1","lines":[{"sku":"A1"},{"sku":"A2"},{"sku":"A3"}]}'
        [receipt: "R-1", lines: [[:]]]                                    || CodeErrorReason.NOTHING_GIVEN | ["lines"]   | '{"receipt":"R-1","lines":[{}]}'
        [receipt: "R-1", lines: [sku: "A1"]]                              || CodeErrorReason.NOT_ITS_KIND  | ["lines"]   | '{"receipt":"R-1","lines":{"sku":"A1"}}'
    }

    /** The member named is the one the reading found first: each level's own members, then the fields it declares. */
    def "code that gave back a field it does not declare went wrong for that reason, naming that member and the field holding it"() {
        expect:
        CodeOutcome.returned(FILE_CLAIM, json(returned)) == erred(CodeErrorReason.NOT_DECLARED, path, member, kept)

        where:
        returned                                                    || path      | member  | kept
        [receipt: "R-1", extra: true]                               || []        | "extra" | '{"receipt":"R-1","extra":true}'
        [extra: 1]                                                  || []        | "extra" | '{"extra":1}'
        [receipt: "R-1", lines: [[sku: "A1", size: 2]]]             || ["lines"] | "size"  | '{"receipt":"R-1","lines":[{"sku":"A1","size":2}]}'
        [receipt: "R-1", lines: [[sku: "A1"], [sku: "A2", bin: 3]]] || ["lines"] | "bin"   | '{"receipt":"R-1","lines":[{"sku":"A1"},{"sku":"A2","bin":3}]}'
    }

    /** A member's name is whatever the code wrote, so it is named as one line shows it, and cut alone. */
    def "names a member it does not declare cleaned for one line, cut alone past 64 characters, where it was found still named"() {
        when:
        def outcome = CodeOutcome.returned(FILE_CLAIM, json(returned)) as CodeOutcome.Errored

        then:
        outcome.wentWrong() ==
                new CodeError.Fault(CodeErrorReason.NOT_DECLARED, path.collect { new FieldName(it) }, member, null)

        where:
        returned                                                  || path      | member
        [receipt: "R-1", ("bad" + BELL + "bin"): true]            || []        | "badbin"
        [receipt: "R-1", ("n" * 65): true]                        || []        | "n" * 64 + "…"
        [receipt: "R-1", lines: [[sku: "A1", ("b" * 3000): 1]]]   || ["lines"] | "b" * 64 + "…"
        [receipt: "R-1", (PAIR * 65): true]                       || []        | PAIR * 64 + "…"
    }

    def "what came back is kept up to the store's bound and not past it, the reason the same either way"() {
        when:
        def outcome = CodeOutcome.returned(FILE_CLAIM, json([receipt: "R-1", extra: "a" * extraLength])) as CodeOutcome.Errored

        then:
        outcome.wentWrong() == new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [], "extra", null)
        outcome.returned()?.length() == keptLength

        where:
        extraLength            || keptLength
        LONGEST_KEPT_EXTRA     || 8_388_608
        LONGEST_KEPT_EXTRA + 1 || null
    }

    /** Half a pair written out would be a character nobody gave back, so none of it is kept. */
    def "what came back holding half a pair went wrong for that reason, about the field holding it, and is not kept"() {
        expect:
        CodeOutcome.returned(FILE_CLAIM, json([receipt: "R" + Character.toString(0xD800)])) ==
                erred(CodeErrorReason.UNKEEPABLE, ["receipt"], null, null)
    }

    def "code declaring it gives back nothing gave back nothing where it gave back no member, and went wrong for any"() {
        expect:
        CodeOutcome.returned(ARCHIVE, json([:])) == new CodeOutcome.Gave([])

        and:
        CodeOutcome.returned(ARCHIVE, json([receipt: "R-1", extra: 2])) ==
                erred(CodeErrorReason.NOT_DECLARED, [], "receipt", '{"receipt":"R-1","extra":2}')
    }

    def "code that threw went wrong in what its message said, cleaned and cut, with no reason of this system's and nothing kept"() {
        expect:
        CodeOutcome.thrown(message) == new CodeOutcome.Errored(new CodeError.Said(ForeignProse.errorDetail(detail)), null)

        where:
        message                          || detail
        "The mail server refused it."    || "The mail server refused it."
        "Refused\nat the gate."          || "Refused\nat the gate."
        "bell" + BELL + "rang"           || "bellrang"
    }

    /** Nothing readable is left to keep in the code's words, so this system says that, and writes nothing for it. */
    def "code that threw saying nothing readable went wrong for that reason, about no field, keeping nothing"() {
        expect:
        CodeOutcome.thrown(message) == erred(CodeErrorReason.SAID_NOTHING, [], null, null)

        where:
        message << [null, "", " \t\n ", BELL * 3, Character.toString(0x202E)]
    }

    def "code that threw a message past what the store holds went wrong saying all it holds, and that it was cut"() {
        when:
        def outcome = CodeOutcome.thrown("a" * messageLength) as CodeOutcome.Errored

        then:
        def said = outcome.wentWrong() as CodeError.Said
        said.detail().text() == "a" * keptLength
        said.detail().truncated() == cut
        outcome.returned() == null

        where:
        messageLength || keptLength | cut
        2048          || 2048       | false
        2049          || 2048       | true
        3000          || 2048       | true
    }

    def "every reason reading what code gave back can find is the reason code went wrong for"() {
        expect:
        CodeOutcome.reason(found) == reason

        where:
        found                            || reason
        DidNotFitReason.FIELD_UNKNOWN    || CodeErrorReason.NOT_DECLARED
        DidNotFitReason.FIELD_MISSING    || CodeErrorReason.NOTHING_GIVEN
        DidNotFitReason.NOTHING_GIVEN    || CodeErrorReason.NOTHING_GIVEN
        DidNotFitReason.NOT_ITS_KIND     || CodeErrorReason.NOT_ITS_KIND
        DidNotFitReason.TOO_LONG         || CodeErrorReason.TOO_LONG
        DidNotFitReason.TOO_MANY         || CodeErrorReason.TOO_MANY
        DidNotFitReason.NOT_A_TERM       || CodeErrorReason.NOT_A_TERM
        DidNotFitReason.UNKEEPABLE       || CodeErrorReason.UNKEEPABLE
        DidNotFitReason.TOO_LONG_TO_KEEP || CodeErrorReason.TOO_LONG_TO_KEEP
    }

    /** Only a model's answer carries these, so reading what code gave back finding one is this system gone wrong. */
    def "refuses as the reason code went wrong for any that only a model's answer has"() {
        when:
        CodeOutcome.reason(found)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "What code gave back is never found " + found + ", which only a model's answer is"

        where:
        found << [DidNotFitReason.NOT_THE_SHAPE, DidNotFitReason.CONFIDENCE_MISSING, DidNotFitReason.CONFIDENCE_UNASKED,
                  DidNotFitReason.CONFIDENCE_NOT_A_PERCENT, DidNotFitReason.UNDECIDED, DidNotFitReason.WORDS_MISSING,
                  DidNotFitReason.WORDS_TOO_LONG, DidNotFitReason.WORDS_UNKEEPABLE, DidNotFitReason.CUT_OFF,
                  DidNotFitReason.NOT_KEPT_AS_IT_CAME]
    }

    def "keeps the values it gave as they were handed over, whatever is done to the list they came in afterwards"() {
        given:
        def given = new CodeOutcome.Given(RECEIPT.name(), value("R-1"), FieldStanding.ALWAYS, null)
        def handed = [given]
        def gave = new CodeOutcome.Gave(handed)

        when:
        handed << new CodeOutcome.Given(NOTE.name(), value("Filed."), FieldStanding.NEVER, null)

        then:
        gave.values() == [given]
    }

    /*
     * Built from lengths inside the feature: a data variable holding the text itself would be rendered into every
     * iteration's name, megabytes of it.
     */
    def "keeps what came back that the store can hold, a pair counting as one character, beside why it went wrong"() {
        given:
        def wentWrong = new CodeError.Fault(CodeErrorReason.TOO_LONG, [RECEIPT.name()], null, null)
        def returned = count == null ? null : written * count

        when:
        def errored = new CodeOutcome.Errored(wentWrong, returned)

        then:
        errored.wentWrong().is(wentWrong)
        errored.returned().is(returned)

        where:
        written               | count
        "x"                   | null
        "a"                   | 8_388_608
        PAIR                  | 8_388_608
        '{"face":"' + PAIR    | 1
    }

    /** A driver refuses a null character, and writes half a pair as a question mark nobody gave back. */
    def "refuses code gone wrong it is not told why, and what came back that the store could not hold as it is"() {
        when:
        new CodeOutcome.Errored(withWhy ? new CodeError.Said(ForeignProse.errorDetail("It broke.")) : null,
                count == null ? null : written * count + tail)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        withWhy | written                     | count     | tail                                   || expected                 | message
        false   | "a"                         | null      | ""                                     || NullPointerException     | "CodeOutcome.Errored wentWrong must not be null"
        true    | "a"                         | 8_388_609 | ""                                     || IllegalArgumentException | "CodeOutcome.Errored returned must run to at most 8388608 characters"
        true    | PAIR                        | 8_388_609 | ""                                     || IllegalArgumentException | "CodeOutcome.Errored returned must run to at most 8388608 characters"
        true    | '{"a":"'                    | 1         | Character.toString(0) + '"}'           || IllegalArgumentException | "CodeOutcome.Errored returned must hold no null character and no half of a pair"
        true    | '{"a":"'                    | 1         | Character.toString(0xD83D) + '"}'      || IllegalArgumentException | "CodeOutcome.Errored returned must hold no null character and no half of a pair"
        true    | '{"a":"'                    | 1         | Character.toString(0xDE00) + '"}'      || IllegalArgumentException | "CodeOutcome.Errored returned must hold no null character and no half of a pair"
        true    | '{"a":"'                    | 1         | Character.toString(0xDE00) + Character.toString(0xD83D) + '"}' || IllegalArgumentException | "CodeOutcome.Errored returned must hold no null character and no half of a pair"
        true    | Character.toString(0xD83D)  | 1         | ""                                     || IllegalArgumentException | "CodeOutcome.Errored returned must hold no null character and no half of a pair"
    }

    def "keeps a value standing as it is given, with a floor exactly where it stands above one"() {
        when:
        def given = new CodeOutcome.Given(RECEIPT.name(), value("R-1"), standing, floor)

        then:
        given.standing() == standing
        given.floor() == floor

        where:
        standing                        | floor
        FieldStanding.ALWAYS            | null
        FieldStanding.NEVER             | null
        FieldStanding.ABOVE_CONFIDENCE  | 1
        FieldStanding.ABOVE_CONFIDENCE  | 100
    }

    def "refuses a value missing a part, or with a floor where it stands otherwise or none where it stands above one"() {
        when:
        new CodeOutcome.Given(name, givenValue, standing, floor)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        name          | givenValue   | standing                       | floor || expected                 | message
        null          | value("R-1") | FieldStanding.ALWAYS           | null  || NullPointerException     | "CodeOutcome.Given name must not be null"
        RECEIPT.name()| null         | FieldStanding.ALWAYS           | null  || NullPointerException     | "CodeOutcome.Given value must not be null"
        RECEIPT.name()| value("R-1") | null                           | null  || NullPointerException     | "CodeOutcome.Given standing must not be null"
        RECEIPT.name()| value("R-1") | FieldStanding.ALWAYS           | 80    || IllegalArgumentException | "CodeOutcome.Given of receipt carries a floor exactly where it stands above one"
        RECEIPT.name()| value("R-1") | FieldStanding.NEVER            | 80    || IllegalArgumentException | "CodeOutcome.Given of receipt carries a floor exactly where it stands above one"
        RECEIPT.name()| value("R-1") | FieldStanding.ABOVE_CONFIDENCE | null  || IllegalArgumentException | "CodeOutcome.Given of receipt carries a floor exactly where it stands above one"
        RECEIPT.name()| value("R-1") | FieldStanding.ABOVE_CONFIDENCE | 0     || IllegalArgumentException | "CodeOutcome.Given of receipt floor must be from 1 to 100: 0"
        RECEIPT.name()| value("R-1") | FieldStanding.ABOVE_CONFIDENCE | 101   || IllegalArgumentException | "CodeOutcome.Given of receipt floor must be from 1 to 100: 101"
    }

    def "a value of a field stands as the field's demand says"() {
        expect:
        CodeOutcome.Given.of(field, value("gold")) == new CodeOutcome.Given(field.name(), value("gold"), standing, floor)

        where:
        field   || standing                       | floor
        RECEIPT || FieldStanding.ALWAYS           | null
        NOTE    || FieldStanding.NEVER            | null
        TIER    || FieldStanding.ABOVE_CONFIDENCE | 80
    }

    def "refuses a value of a field that does not say what it takes to stand"() {
        when:
        CodeOutcome.Given.of(field, value("R-1"))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "CodeOutcome.Given of receipt is of a field that does not say what it takes to stand"

        where:
        field << [
            new Field(new FieldName("receipt"), null, null, new FieldShape.Text(8), new HowMany.One(), new Demand.Given(true)),
            stands("receipt", new FieldShape.Text(8), new HowMany.One(), true, null, null)]
    }

    /** Code gone wrong for {@code reason}, about the field at {@code path}, keeping {@code kept}. */
    private static CodeOutcome.Errored erred(CodeErrorReason reason, List<String> path, String member, String kept) {
        new CodeOutcome.Errored(new CodeError.Fault(reason, path.collect { new FieldName(it) }, member, null), kept)
    }

    private static Field stands(String name, FieldShape shape, HowMany howMany, boolean mustBe, FieldStanding standing,
                                Integer floor) {
        new Field(new FieldName(name), null, null, shape, howMany, new Demand.Stands(mustBe, standing, floor))
    }

    private static CodeStepDeclaration declaring(List<Field> gives) {
        new CodeStepDeclaration(
                new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES),
                        [new Field(new FieldName("ticket"), null, null, new FieldShape.Text(64), new HowMany.One(),
                                new Demand.Given(true))]),
                new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), gives),
                false)
    }

    /** Members in the order the map holds them, which is the order they are written in. */
    private static JsonValue.JsonObject json(Map<String, ?> members) {
        new JsonValue.JsonObject(members.collect { new JsonValue.JsonMember(it.key, value(it.value)) })
    }

    private static JsonValue value(Object written) {
        switch (written) {
            case null: return new JsonValue.JsonNull()
            case String: return new JsonValue.JsonString(written as String)
            case Boolean: return new JsonValue.JsonBoolean(written as boolean)
            case Number: return new JsonValue.JsonNumber(new BigDecimal(written.toString()))
            case Map: return json(written as Map<String, ?>)
            case List: return new JsonValue.JsonArray((written as List).collect { value(it) })
            default: throw new IllegalArgumentException("No JSON is written as ${written.class.name}")
        }
    }
}
