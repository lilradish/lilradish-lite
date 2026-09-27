package org.lilradish.lite.testutil.inference

import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.wire.JsonValue

/** Fields as a model is told them, one of every kind, the way a spec of what is sent and read back needs them. */
final class Askings {

    static final OfferedTerms CATEGORIES = new OfferedTerms(
            [offered("Billing", "A charge is disputed."), offered("Delivery", 'It came "late".')],
            new ListNote("Choose what is asked."))

    static OfferedTerms.Offered offered(String term, String meaning) {
        new OfferedTerms.Offered(new Term(term), new TermMeaning(meaning))
    }

    /** Every kind once, a text asking how sure, a term and a text held many times, and fields holding fields. */
    static final List<AskedField> GIVES = [
            text("summary", 5, null, true),
            term("tags", CATEGORIES, 2),
            plain("amount", FieldKind.NUMBER),
            plain("due", FieldKind.DATE),
            plain("at", FieldKind.MOMENT),
            plain("urgent", FieldKind.YES_NO),
            held("details", [text("product", 3), text("codes", 2, 2)])]

    static final JsonValue NONE = new JsonValue.JsonNull()

    /** A control with no short escape, which JSON writes as long as it writes any one character. */
    static final String WORST_CHARACTER = Character.toString(0x1)

    static final String NINES = "9" * FieldKind.MOST_DIGITS

    /** Numbers of the most digits, each kept with its ", " as this many characters, the brackets taking the last. */
    private static final int KEPT_APIECE = NINES.length() + 2

    static final int NUMBERS_AT_THE_BOUND = (int) Declaration.MOST_STORED.intdiv(KEPT_APIECE)

    /** How many of them are negated, a minus apiece making up what the bound runs past the last whole number. */
    static final int MINUSES_AT_THE_BOUND = (int) (Declaration.MOST_STORED % KEPT_APIECE)

    private Askings() {}

    /** One number over and over, so its text runs to millions without this side holding each apart. */
    static JsonValue.JsonArray numbersAtTheBound(boolean onePast) {
        def nines = new BigDecimal(NINES)
        int minuses = MINUSES_AT_THE_BOUND + (onePast ? 1 : 0)
        new JsonValue.JsonArray(Collections.nCopies(minuses, new JsonValue.JsonNumber(nines.negate())) +
                Collections.nCopies(NUMBERS_AT_THE_BOUND - minuses, new JsonValue.JsonNumber(nines)))
    }

    static AskedField text(
            String name, int longest, Integer most = null, boolean confidenceAsked = false, boolean mustBeGiven = false) {
        new AskedField(new FieldName(name), FieldKind.TEXT, longest, most, null, [], mustBeGiven, confidenceAsked)
    }

    static AskedField plain(String name, FieldKind kind, Integer most = null) {
        new AskedField(new FieldName(name), kind, null, most, null, [], false, false)
    }

    static AskedField term(String name, OfferedTerms terms, Integer most = null) {
        new AskedField(new FieldName(name), FieldKind.TERM, null, most, terms, [], false, false)
    }

    static AskedField held(String name, List<AskedField> fields, Integer most = null, boolean mustBeGiven = false) {
        new AskedField(new FieldName(name), FieldKind.FIELDS, null, most, null, fields, mustBeGiven, false)
    }

    /** The field's value at its longest as JSON writes it, built rather than counted: none where none is longer. */
    static JsonValue longest(AskedField field) {
        def item = longestItem(field)
        if (item == null) {
            return NONE
        }
        longer(field.most() == null ? item : new JsonValue.JsonArray([item] * field.most()), NONE)
    }

    private static JsonValue longestItem(AskedField field) {
        switch (field.kind()) {
            case FieldKind.TEXT:
                return new JsonValue.JsonString(WORST_CHARACTER * field.longest())
            case FieldKind.TERM:
                return field.terms().terms().collect { new JsonValue.JsonString(it.term().value()) }
                        .max { codePoints(CanonicalJson.write(it)) }
            case FieldKind.FIELDS:
                return new JsonValue.JsonObject(field.fields().collect {
                    new JsonValue.JsonMember(it.name().value(), longest(it))
                })
            default:
                return field.kind().longestWritten()
        }
    }

    private static JsonValue longer(JsonValue one, JsonValue other) {
        codePoints(CanonicalJson.write(one)) >= codePoints(CanonicalJson.write(other)) ? one : other
    }

    static long codePoints(String text) {
        text.codePointCount(0, text.length())
    }
}
