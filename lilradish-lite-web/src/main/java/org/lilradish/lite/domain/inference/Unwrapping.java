package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/**
 * An answer read against what the {@link Envelope} of the same version asked for, once, as it arrives: every
 * field there and none other, what may be absent being the value and never the field, nor the value of one that
 * must be given. What a code step gives back is read by the same rules, save that a field it leaves out is one it
 * gave none. Nothing is cleaned or cut; what the store could not keep as it came does not fit. An answer that
 * could not be read arrives as none, and is not the shape.
 */
public final class Unwrapping {

    static final String VALUES = "values";

    static final String CONFIDENCES = "confidences";

    static final String DECISIONS = "decisions";

    static final String OUTCOME = "outcome";

    static final String WORDS = "words";

    static final String ASSURED = "assured";

    static final String REFUSED = "refused";

    private static final BigDecimal HIGHEST_CONFIDENCE = BigDecimal.valueOf(Confidence.HIGHEST);

    private static final JsonNull NOTHING = new JsonNull();

    private Unwrapping() {}

    /**
     * The first thing that does not fit, reading the values before the confidences: in each object, a member
     * nothing declares first, in the order they came, then each declared field in declared order.
     */
    public static ProductionAnswer production(List<AskedField> gives, @Nullable JsonValue answer) {
        requireNonNull(gives, "Unwrapping gives must not be null");
        if (!(answer instanceof JsonObject whole)
                || whole.members().size() != 2
                || !(member(whole, VALUES) instanceof JsonObject values)
                || !(member(whole, CONFIDENCES) instanceof JsonObject confidences)) {
            return new DoesNotFit(DidNotFitReason.NOT_THE_SHAPE);
        }
        Map<FieldName, JsonValue> kept = new HashMap<>();
        DidNotFitReason wrong = level(gives, values, kept, null);
        if (wrong != null) {
            return new DoesNotFit(wrong);
        }
        Map<FieldName, Confidence> sure = new HashMap<>();
        wrong = confidences(gives, confidences, sure);
        return wrong == null ? new ProductionAnswer.Produced(kept, sure) : new DoesNotFit(wrong);
    }

    /**
     * Read as a model's values are, in the same order, but a member absent for a field that need not be given is
     * that field given nothing, and kept holding none at every depth. One absent for a field that must be given
     * does not fit as {@code NOTHING_GIVEN}.
     */
    public static GivenBack given(List<AskedField> gives, JsonObject gaveBack) {
        requireNonNull(gives, "Unwrapping gives must not be null");
        requireNonNull(gaveBack, "Unwrapping gaveBack must not be null");
        Map<FieldName, JsonValue> kept = new HashMap<>();
        CodeReading code = new CodeReading();
        DidNotFitReason wrong = level(gives, gaveBack, kept, code);
        return wrong == null ? new GivenBack.Fits(kept) : new GivenBack.Misfit(wrong, code.path(), code.undeclared);
    }

    /** The first thing that does not fit, reading the decisions in the order they came. */
    public static ReviewAnswer review(List<FieldName> deciding, @Nullable JsonValue answer) {
        requireNonNull(deciding, "Unwrapping deciding must not be null");
        if (!(answer instanceof JsonObject whole)
                || whole.members().size() != 1
                || !(member(whole, DECISIONS) instanceof JsonObject decisions)) {
            return new DoesNotFit(DidNotFitReason.NOT_THE_SHAPE);
        }
        Map<String, FieldName> asked = new HashMap<>();
        for (FieldName name : deciding) {
            asked.put(name.value(), name);
        }
        Map<FieldName, ReviewAnswer.Decision> decided = new HashMap<>();
        for (JsonMember member : decisions.members()) {
            FieldName name = asked.get(member.name());
            if (name == null) {
                return new DoesNotFit(DidNotFitReason.FIELD_UNKNOWN);
            }
            DidNotFitReason wrong = decision(member.value(), name, decided);
            if (wrong != null) {
                return new DoesNotFit(wrong);
            }
        }
        return decided.size() == asked.size()
                ? new ReviewAnswer.Reviewed(decided)
                : new DoesNotFit(DidNotFitReason.UNDECIDED);
    }

    static @Nullable AskedField declared(List<AskedField> fields, String name) {
        for (AskedField field : fields) {
            if (field.name().value().equals(name)) {
                return field;
            }
        }
        return null;
    }

    /**
     * Where {@code kept} is given, the level is what is given back, each of whose values the store keeps whole.
     * Where {@code code} is given, a code step gave it back; a model's reading passes none.
     */
    private static @Nullable DidNotFitReason level(
            List<AskedField> fields,
            JsonObject given,
            @Nullable Map<FieldName, JsonValue> kept,
            @Nullable CodeReading code) {
        for (JsonMember member : given.members()) {
            if (declared(fields, member.name()) == null) {
                if (code != null) {
                    code.undeclared = member.name();
                }
                return DidNotFitReason.FIELD_UNKNOWN;
            }
        }
        boolean whole = given.members().size() == fields.size();
        List<JsonMember> rebuilt = null;
        for (int index = 0; index < fields.size(); index++) {
            AskedField field = fields.get(index);
            JsonValue value = member(given, field.name().value());
            if (value == null) {
                if (code == null) {
                    return DidNotFitReason.FIELD_MISSING;
                }
                value = NOTHING;
            }
            DidNotFitReason wrong = field(field, value, code);
            JsonValue filled = code == null ? null : code.takeRebuilt();
            if (filled != null) {
                value = filled;
            }
            if (wrong == null && kept != null && CanonicalJson.storedLength(value) > Declaration.MOST_STORED) {
                wrong = DidNotFitReason.TOO_LONG_TO_KEEP;
            }
            if (wrong != null) {
                if (code != null) {
                    code.under(field.name());
                }
                return wrong;
            }
            if (kept != null) {
                kept.put(field.name(), value);
            } else if (code != null) {
                if (rebuilt == null && (!whole || filled != null)) {
                    rebuilt = membersBefore(fields, index, given);
                }
                if (rebuilt != null) {
                    rebuilt.add(new JsonMember(field.name().value(), value));
                }
            }
        }
        if (rebuilt != null) {
            requireNonNull(code).leave(new JsonObject(rebuilt));
        }
        return null;
    }

    /* Reached only while every member before index is present and kept as it came. */
    private static List<JsonMember> membersBefore(List<AskedField> fields, int index, JsonObject given) {
        List<JsonMember> members = new ArrayList<>(fields.size());
        for (AskedField field : fields.subList(0, index)) {
            String name = field.name().value();
            members.add(new JsonMember(name, requireNonNull(member(given, name))));
        }
        return members;
    }

    private static @Nullable DidNotFitReason field(AskedField field, JsonValue value, @Nullable CodeReading code) {
        if (value instanceof JsonNull) {
            return field.mustBeGiven() ? DidNotFitReason.NOTHING_GIVEN : null;
        }
        Integer most = field.most();
        if (most == null) {
            return one(field, value, code);
        }
        if (!(value instanceof JsonArray many)) {
            return DidNotFitReason.NOT_ITS_KIND;
        }
        List<JsonValue> items = many.items();
        if (items.size() > most) {
            return DidNotFitReason.TOO_MANY;
        }
        if (field.mustBeGiven() && items.isEmpty()) {
            return DidNotFitReason.NOTHING_GIVEN;
        }
        List<JsonValue> rebuilt = null;
        for (int index = 0; index < items.size(); index++) {
            JsonValue item = items.get(index);
            DidNotFitReason wrong = item instanceof JsonNull ? DidNotFitReason.NOT_ITS_KIND : one(field, item, code);
            JsonValue filled = code == null ? null : code.takeRebuilt();
            if (wrong != null) {
                if (code != null) {
                    code.stopAtTheMany();
                }
                return wrong;
            }
            if (rebuilt == null && filled != null) {
                rebuilt = new ArrayList<>(items.subList(0, index));
            }
            if (rebuilt != null) {
                rebuilt.add(filled == null ? item : filled);
            }
        }
        if (rebuilt != null) {
            requireNonNull(code).leave(new JsonArray(rebuilt));
        }
        return null;
    }

    private static @Nullable DidNotFitReason one(AskedField field, JsonValue value, @Nullable CodeReading code) {
        if (!field.kind().writes(value)) {
            return DidNotFitReason.NOT_ITS_KIND;
        }
        return switch (field.kind()) {
            case TEXT -> text(((JsonString) value).value(), requireNonNull(field.longest()));
            case TERM -> term(((JsonString) value).value(), requireNonNull(field.terms()));
            case FIELDS -> level(field.fields(), (JsonObject) value, null, code);
            case NUMBER, DATE, MOMENT, YES_NO -> null;
        };
    }

    private static @Nullable DidNotFitReason text(String said, int longest) {
        if (!keepable(said)) {
            return DidNotFitReason.UNKEEPABLE;
        }
        return Legibility.withinMaximumLength(said, longest) ? null : DidNotFitReason.TOO_LONG;
    }

    private static @Nullable DidNotFitReason term(String said, OfferedTerms offered) {
        if (!keepable(said)) {
            return DidNotFitReason.UNKEEPABLE;
        }
        return offered.offers(said) ? null : DidNotFitReason.NOT_A_TERM;
    }

    private static @Nullable DidNotFitReason confidences(
            List<AskedField> gives, JsonObject given, Map<FieldName, Confidence> sure) {
        for (JsonMember member : given.members()) {
            AskedField field = declared(gives, member.name());
            if (field == null || !field.confidenceAsked()) {
                return DidNotFitReason.CONFIDENCE_UNASKED;
            }
            if (!(member.value() instanceof JsonNumber number) || !percent(number.value())) {
                return DidNotFitReason.CONFIDENCE_NOT_A_PERCENT;
            }
            sure.put(field.name(), new Confidence(number.value().intValueExact()));
        }
        for (AskedField field : gives) {
            if (field.confidenceAsked() && !sure.containsKey(field.name())) {
                return DidNotFitReason.CONFIDENCE_MISSING;
            }
        }
        return null;
    }

    /* A whole number is written with no point, as a person writes a floor: 90.0 is not one. */
    private static boolean percent(BigDecimal figure) {
        return figure.scale() == 0 && figure.signum() >= 0 && figure.compareTo(HIGHEST_CONFIDENCE) <= 0;
    }

    private static @Nullable DidNotFitReason decision(
            JsonValue value, FieldName name, Map<FieldName, ReviewAnswer.Decision> decided) {
        if (!(value instanceof JsonObject said) || !(member(said, OUTCOME) instanceof JsonString outcome)) {
            return DidNotFitReason.NOT_THE_SHAPE;
        }
        JsonValue words = member(said, WORDS);
        if (said.members().size() != (words == null ? 1 : 2)) {
            return DidNotFitReason.NOT_THE_SHAPE;
        }
        if (outcome.value().equals(ASSURED) && words == null) {
            decided.put(name, new ReviewAnswer.Assured());
            return null;
        }
        if (!outcome.value().equals(REFUSED)) {
            return DidNotFitReason.NOT_THE_SHAPE;
        }
        if (words == null || words instanceof JsonNull) {
            return DidNotFitReason.WORDS_MISSING;
        }
        if (!(words instanceof JsonString why)) {
            return DidNotFitReason.NOT_THE_SHAPE;
        }
        if (!Legibility.holdsSomethingVisibleInProse(why.value())) {
            return DidNotFitReason.WORDS_MISSING;
        }
        if (!keepable(why.value()) || !visible(why.value())) {
            return DidNotFitReason.WORDS_UNKEEPABLE;
        }
        if (!Legibility.withinMaximumLength(why.value(), Envelope.MOST_REFUSAL_WORDS)) {
            return DidNotFitReason.WORDS_TOO_LONG;
        }
        decided.put(name, new ReviewAnswer.Refused(why.value()));
        return null;
    }

    private static @Nullable JsonValue member(JsonObject object, String name) {
        for (JsonMember member : object.members()) {
            if (member.name().equals(name)) {
                return member.value();
            }
        }
        return null;
    }

    /** Neither a null character nor half a pair, which no text the store holds may be. */
    private static boolean keepable(String said) {
        for (int index = 0; index < said.length(); ) {
            int codePoint = said.codePointAt(index);
            if (KeptAnswer.unkeepable(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    /** What only a code step's reading carries: where it did not fit, and a value rebuilt below to hold every member. */
    private static final class CodeReading {

        /* Innermost first, gathered only while unwinding from what does not fit. */
        private @Nullable List<FieldName> outwards;

        /* Left by a level or a many for the one caller that reads it next, which takes it at once. */
        private @Nullable JsonValue rebuilt;

        private @Nullable String undeclared;

        private void under(FieldName name) {
            if (outwards == null) {
                outwards = new ArrayList<>();
            }
            outwards.add(name);
        }

        /** By ruling, an item of a many being wrong is named as that many, nothing inside the item. */
        private void stopAtTheMany() {
            outwards = null;
        }

        private void leave(JsonValue value) {
            rebuilt = value;
        }

        private @Nullable JsonValue takeRebuilt() {
            JsonValue value = rebuilt;
            rebuilt = null;
            return value;
        }

        private List<FieldName> path() {
            return outwards == null ? List.of() : outwards.reversed();
        }
    }

    /* The store's own check on refusal words, not the prose rule, by ruling: words the store keeps are kept as
     * they came. No control but a tab and a line feed, C1 included. */
    private static boolean visible(String said) {
        for (int index = 0; index < said.length(); index++) {
            char character = said.charAt(index);
            boolean control = character < ' ' || (character >= 0x7f && character <= 0x9f);
            if (control && character != '\t' && character != '\n') {
                return false;
            }
        }
        return true;
    }
}
