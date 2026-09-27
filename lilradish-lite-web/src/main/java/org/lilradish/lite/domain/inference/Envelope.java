package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.referencelist.ListNote;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/**
 * What is added to what a model is sent: the system text saying how to answer, worked out from the declared
 * answer alone. It never says the figure a value stands above, which {@link AskedField} does not carry.
 *
 * <p>{@link #VERSION} goes first in every {@link Payload}, and {@link Unwrapping} reads the answer this
 * version asks for: a release changing what is asked changes both, and the version with them.
 */
public final class Envelope {

    public static final int VERSION = 2;

    /** The most characters the words a production is refused with may run to, whoever wrote them. */
    static final int MOST_REFUSAL_WORDS = 2048;

    private static final String MUST_BE_GIVEN = "must be given";

    private static final String NOTHING_AS_NULL = "null where there is nothing to give";

    private static final String NOTHING_AS_NULL_OR_EMPTY = "null or an empty array where there is nothing to give";

    private static final String SENT = """
            You are sent a JSON object. %s says what is to be done. %s holds what it is done \
            with, a member for each field it takes, null where nothing was given. %s, where it is there, \
            holds an answer given before and refused: %s as they were given, and %s saying why each \
            refused value was refused.
            """.formatted(
                    quoted(PayloadMember.INSTRUCTION.written()),
                    quoted(PayloadMember.TAKES.written()),
                    quoted(PayloadMember.REFUSED.written()),
                    quoted(PayloadMember.REFUSED_VALUES),
                    quoted(PayloadMember.REFUSED_WORDS));

    private static final String TO_PRODUCE = SENT
            + """
            Answer with one JSON object and nothing else: no words around it and no code fence. It has two \
            members. %s holds a member for every field below and no other, each as its line says. %s holds \
            a member for every field below that asks how sure you are and no other, each a whole number from \
            0 to %d.
            A field holding many is an array of at most as many values as it says.
            A field that %s is never null, and where it holds many, never empty.
            The fields:
            """.formatted(
                    quoted(Unwrapping.VALUES), quoted(Unwrapping.CONFIDENCES), Confidence.HIGHEST, MUST_BE_GIVEN);

    /* Nothing stands in the instruction's place, so nothing sent says what was to be done, nor what did it. */
    private static final String SENT_UNINSTRUCTED = """
            You are sent a JSON object. %s holds what was taken in producing the answer, a member for each \
            field taken, null where nothing was given. %s, where it is there, holds an answer given before and \
            refused: %s as they were given, and %s saying why each refused value was refused.
            """.formatted(
                    quoted(PayloadMember.TAKES.written()),
                    quoted(PayloadMember.REFUSED.written()),
                    quoted(PayloadMember.REFUSED_VALUES),
                    quoted(PayloadMember.REFUSED_WORDS));

    private static final String REVIEWING = """
            %s holds the values given back for it, a member for each field below, null where there was \
            nothing. %s names the fields whose values are to be decided.
            Answer with one JSON object and nothing else: no words around it and no code fence. It has one \
            member, %s, holding a member for every field %s names and no other: \
            %s where its value holds, or %s where it does \
            not, the words saying why in at most %d characters.
            A field holding many is an array of at most as many values as it says.
            The fields as declared:
            """.formatted(
                    quoted(PayloadMember.ANSWER.written()),
                    quoted(PayloadMember.DECIDING.written()),
                    quoted(Unwrapping.DECISIONS),
                    quoted(PayloadMember.DECIDING.written()),
                    decided(Unwrapping.ASSURED, List.of()),
                    decided(Unwrapping.REFUSED, List.of(new JsonMember(Unwrapping.WORDS, new JsonString("...")))),
                    MOST_REFUSAL_WORDS);

    private static final String TO_REVIEW = SENT + REVIEWING;

    private static final String TO_REVIEW_UNINSTRUCTED = SENT_UNINSTRUCTED + REVIEWING;

    private static final String INDENT = "  ";

    private Envelope() {}

    /**
     * What a model producing is told: each field's name, kind and limit, whether it must be given, and where it is
     * asked how sure it is.
     */
    public static String toProduce(List<AskedField> gives) {
        return told(TO_PRODUCE, gives, true);
    }

    /** What a model reviewing is told: the answer it gives, and each field as it was declared. */
    public static String toReview(List<AskedField> gives) {
        return told(TO_REVIEW, gives, false);
    }

    /** As {@link #toReview}, to a model reviewing a production no instruction asked for. */
    public static String toReviewUninstructed(List<AskedField> gives) {
        return told(TO_REVIEW_UNINSTRUCTED, gives, false);
    }

    /** Refused where a stored payload about to be sent again was written under a version this release does not hold. */
    public static void requireHeld(int version) {
        if (version != VERSION) {
            throw new IllegalStateException("Envelope version " + version + " is not one this release holds");
        }
    }

    private static String told(String head, List<AskedField> gives, boolean producing) {
        requireNonNull(gives, "Envelope gives must not be null");
        StringBuilder text = new StringBuilder(head);
        fields(gives, 0, producing, text);
        return text.toString();
    }

    /**
     * Only a model producing gives values, so only it is told of each field whether it must be given, or how it
     * says there is nothing, and asked how sure it is.
     */
    private static void fields(List<AskedField> level, int depth, boolean producing, StringBuilder text) {
        for (AskedField field : level) {
            text.append(INDENT.repeat(depth))
                    .append("- ")
                    .append(quoted(field.name().value()))
                    .append(": ");
            if (producing) {
                text.append(
                                field.mustBeGiven()
                                        ? MUST_BE_GIVEN
                                        : field.most() == null ? NOTHING_AS_NULL : NOTHING_AS_NULL_OR_EMPTY)
                        .append("; ");
            }
            if (field.most() != null) {
                text.append("many, at most ").append(field.most()).append(", each ");
            }
            text.append(kind(field));
            if (producing && depth == 0 && field.confidenceAsked()) {
                text.append("; say how sure you are");
            }
            text.append('\n');
            OfferedTerms offered = field.terms();
            if (offered != null) {
                for (OfferedTerms.Offered term : offered.terms()) {
                    text.append(INDENT.repeat(depth + 1))
                            .append("- ")
                            .append(quoted(term.term().value()))
                            .append(", meaning ")
                            .append(quoted(term.meaning().value()))
                            .append('\n');
                }
                ListNote note = offered.note();
                if (note != null) {
                    text.append(INDENT.repeat(depth + 1))
                            .append("On choosing: ")
                            .append(quoted(note.value()))
                            .append('\n');
                }
            }
            fields(field.fields(), depth + 1, producing, text);
        }
    }

    private static String kind(AskedField field) {
        return switch (field.kind()) {
            case TEXT -> "text of at most " + field.longest() + " characters, as a JSON string";
            case NUMBER ->
                "a number, as a JSON number written plainly with no exponent, of at most " + FieldKind.MOST_DIGITS
                        + " digits in all";
            case DATE ->
                "a date, as a JSON string written year-month-day such as \"2024-03-31\", the year from "
                        + String.format(Locale.ROOT, "%04d", FieldKind.FIRST_YEAR) + " to " + FieldKind.LAST_YEAR;
            case MOMENT ->
                "a moment, as a JSON string written as a date, \"T\", a time to the second with at most "
                        + FieldKind.MOST_FRACTION_DIGITS + " digits of its fraction, and its offset as a sign, hours"
                        + " and minutes no further than " + FieldKind.FURTHEST_OFFSET_HOURS
                        + ":00 either way, such as \"2024-03-31T09:30:00.5+02:00\"; never \"Z\" or \"-00:00\"";
            case YES_NO -> "yes or no, as JSON true or false";
            case TERM -> "a term, as a JSON string, one of these:";
            case FIELDS -> "fields, as a JSON object holding a member for each field below it and no other:";
        };
    }

    private static String decided(String outcome, List<JsonMember> besides) {
        List<JsonMember> members = new ArrayList<>(besides.size() + 1);
        members.add(new JsonMember(Unwrapping.OUTCOME, new JsonString(outcome)));
        members.addAll(besides);
        return CanonicalJson.write(new JsonObject(members));
    }

    private static String quoted(String said) {
        return CanonicalJson.write(new JsonString(said));
    }
}
