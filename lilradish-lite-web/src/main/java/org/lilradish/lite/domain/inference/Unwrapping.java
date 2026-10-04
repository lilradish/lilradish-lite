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
import org.lilradish.lite.domain.declaration.FieldKind;
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
 * An answer read against its declaration, once, as it arrives: every non-file field there and none other,
 * what may be absent being the value and never the field, nor the value of one that
 * must be given. What a code step gives back is read by the same rules, save that a field it leaves out is one it
 * gave none. Nothing is cleaned or cut; what the store could not keep as it came does not fit. A model's value
 * written as text that shows nothing, of any kind, or holding fields every one of which is nothing, is nothing
 * given: kept as none where the field need not be given, and otherwise, or as one of many, not fitting. An answer
 * that could not be read arrives as none, and is not the shape.
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
        DidNotFitReason wrong = level(gives, values, kept, new Reading(false));
        if (wrong != null) {
            return new DoesNotFit(wrong);
        }
        Map<FieldName, Confidence> sure = new HashMap<>();
        wrong = confidences(gives, confidences, sure);
        return wrong == null ? new ProductionAnswer.Produced(kept, sure) : new DoesNotFit(wrong);
    }

    /**
     * What a search answered, read as a model's values are, in the same order: the answer is the values themselves,
     * and nothing is asked how sure it is.
     *
     * @throws IllegalArgumentException where a field asks how sure its maker was
     */
    public static ProductionAnswer searched(List<AskedField> gives, @Nullable JsonValue answer) {
        requireNonNull(gives, "Unwrapping gives must not be null");
        for (AskedField field : gives) {
            if (field.kind() != FieldKind.FILE && field.confidenceAsked()) {
                throw new IllegalArgumentException("Unwrapping reads no confidence of a search, yet "
                        + field.name().value() + " asks one");
            }
        }
        if (!(answer instanceof JsonObject values)) {
            return new DoesNotFit(DidNotFitReason.NOT_THE_SHAPE);
        }
        Map<FieldName, JsonValue> kept = new HashMap<>();
        DidNotFitReason wrong = level(gives, values, kept, new Reading(false));
        return wrong == null ? new ProductionAnswer.Produced(kept, Map.of()) : new DoesNotFit(wrong);
    }

    /**
     * Read as a model's values are, in the same order, but a member absent for a field that need not be given is
     * that field given nothing, and kept holding none at every depth. One absent for a field that must be given
     * does not fit as {@code NOTHING_GIVEN}.
     */
    public static GivenBack given(List<AskedField> gives, JsonObject gaveBack) {
        return given(gives, gaveBack, false);
    }

    /** Read only after FileFillings replaced every file position with verified metadata. */
    public static GivenBack givenAfterFileFillings(List<AskedField> gives, JsonObject gaveBack) {
        return given(gives, gaveBack, true);
    }

    private static GivenBack given(List<AskedField> gives, JsonObject gaveBack, boolean verifiedFiles) {
        requireNonNull(gives, "Unwrapping gives must not be null");
        requireNonNull(gaveBack, "Unwrapping gaveBack must not be null");
        Map<FieldName, JsonValue> kept = new HashMap<>();
        Reading code = new Reading(true, verifiedFiles);
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
     * Below it, a model's level every one of whose fields is nothing is itself nothing given, and left as none.
     */
    private static @Nullable DidNotFitReason level(
            List<AskedField> fields, JsonObject given, @Nullable Map<FieldName, JsonValue> kept, Reading reading) {
        List<JsonMember> arrived = given.members();
        for (JsonMember member : arrived) {
            AskedField field = declared(fields, member.name());
            if (field == null || (!reading.code && field.kind() == FieldKind.FILE)) {
                if (reading.code) {
                    reading.undeclared = member.name();
                }
                return DidNotFitReason.FIELD_UNKNOWN;
            }
        }
        boolean mayBeNothing = kept == null && !reading.code;
        boolean holdsSomething = false;
        boolean gap = false;
        List<JsonMember> rebuilt = null;
        for (AskedField field : fields) {
            if (!reading.code && field.kind() == FieldKind.FILE) {
                continue;
            }
            String name = field.name().value();
            int position = position(arrived, name);
            JsonValue value;
            if (position >= 0) {
                value = arrived.get(position).value();
            } else if (reading.code) {
                value = NOTHING;
            } else {
                return gap ? DidNotFitReason.NOTHING_GIVEN : DidNotFitReason.FIELD_MISSING;
            }
            DidNotFitReason wrong = field(field, value, reading);
            JsonValue filled = reading.takeRebuilt();
            if (filled != null) {
                value = filled;
            }
            if (wrong == null && kept != null && CanonicalJson.storedLength(value) > Declaration.MOST_STORED) {
                wrong = DidNotFitReason.TOO_LONG_TO_KEEP;
            }
            boolean nothing = mayBeNothing && nothing(value);
            if (wrong != null) {
                if (!nothing || wrong != DidNotFitReason.NOTHING_GIVEN) {
                    if (reading.code) {
                        reading.under(field.name());
                    }
                    return gap ? DidNotFitReason.NOTHING_GIVEN : wrong;
                }
                if (holdsSomething) {
                    return DidNotFitReason.NOTHING_GIVEN;
                }
                gap = true;
                continue;
            }
            if (!nothing) {
                if (gap) {
                    return DidNotFitReason.NOTHING_GIVEN;
                }
                holdsSomething = true;
            }
            if (kept != null) {
                kept.put(field.name(), value);
            } else if (filled != null || position < 0) {
                if (rebuilt == null) {
                    rebuilt = asArrived(arrived, fields.size());
                }
                JsonMember member = new JsonMember(name, value);
                if (position < 0) {
                    rebuilt.add(member);
                } else {
                    rebuilt.set(position, member);
                }
            }
        }
        if (mayBeNothing && !holdsSomething) {
            reading.leave(NOTHING);
        } else if (rebuilt != null) {
            reading.leave(new JsonObject(rebuilt));
        }
        return null;
    }

    private static int position(List<JsonMember> members, String name) {
        for (int index = 0; index < members.size(); index++) {
            if (members.get(index).name().equals(name)) {
                return index;
            }
        }
        return -1;
    }

    private static List<JsonMember> asArrived(List<JsonMember> arrived, int room) {
        List<JsonMember> members = new ArrayList<>(room);
        for (JsonMember member : arrived) {
            members.add(member);
        }
        return members;
    }

    /* By ruling, a list holding nothing is nothing, as none is. */
    private static boolean nothing(JsonValue value) {
        return value instanceof JsonNull
                || (value instanceof JsonArray many && many.items().isEmpty());
    }

    private static @Nullable DidNotFitReason field(AskedField field, JsonValue value, Reading reading) {
        if (value instanceof JsonNull) {
            return field.mustBeGiven() ? DidNotFitReason.NOTHING_GIVEN : null;
        }
        if (!reading.code
                && field.most() != null
                && value instanceof JsonString said
                && (field.kind() != FieldKind.TEXT
                        || requireNonNull(field.lines()).accepts(said.value()))
                && !Legibility.holdsSomethingVisibleInProse(said.value())) {
            reading.leave(NOTHING);
            return field.mustBeGiven() ? DidNotFitReason.NOTHING_GIVEN : null;
        }
        Integer most = field.most();
        if (most == null) {
            DidNotFitReason wrong = one(field, value, reading);
            return wrong == null && field.mustBeGiven() && reading.rebuilt instanceof JsonNull
                    ? DidNotFitReason.NOTHING_GIVEN
                    : wrong;
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
            DidNotFitReason wrong = item instanceof JsonNull ? DidNotFitReason.NOT_ITS_KIND : one(field, item, reading);
            JsonValue filled = reading.takeRebuilt();
            /* By ruling, one of many is always one that must be given. */
            if (filled instanceof JsonNull) {
                wrong = DidNotFitReason.NOTHING_GIVEN;
            }
            if (wrong != null) {
                if (reading.code) {
                    reading.stopAtTheMany();
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
            reading.leave(new JsonArray(rebuilt));
        }
        return null;
    }

    /* By ruling, a model's value of any kind written as text that shows nothing is nothing given, left as
     * none before its kind is asked; a code step's is read as it came. */
    private static @Nullable DidNotFitReason one(AskedField field, JsonValue value, Reading reading) {
        if (field.kind() == FieldKind.TEXT
                && value instanceof JsonString said
                && !requireNonNull(field.lines()).accepts(said.value())) {
            return DidNotFitReason.LINE_BREAK;
        }
        if (!reading.code
                && value instanceof JsonString said
                && !Legibility.holdsSomethingVisibleInProse(said.value())) {
            reading.leave(NOTHING);
            return null;
        }
        if (!field.kind().writes(value)) {
            return DidNotFitReason.NOT_ITS_KIND;
        }
        return switch (field.kind()) {
            case TEXT -> text(((JsonString) value).value(), requireNonNull(field.longest()));
            case TERM -> term(((JsonString) value).value(), requireNonNull(field.terms()));
            case FIELDS -> level(field.fields(), (JsonObject) value, null, reading);
            case NUMBER, DATE, MOMENT, YES_NO -> null;
            case FILE -> reading.verifiedFiles ? null : DidNotFitReason.NOT_ITS_KIND;
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
            if (field == null || field.kind() == FieldKind.FILE || !field.confidenceAsked()) {
                return DidNotFitReason.CONFIDENCE_UNASKED;
            }
            if (!(member.value() instanceof JsonNumber number) || !percent(number.value())) {
                return DidNotFitReason.CONFIDENCE_NOT_A_PERCENT;
            }
            sure.put(field.name(), new Confidence(number.value().intValueExact()));
        }
        for (AskedField field : gives) {
            if (field.kind() != FieldKind.FILE && field.confidenceAsked() && !sure.containsKey(field.name())) {
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

    /**
     * A value rebuilt below, to hold every member a code step left out or none where a model gave nothing; and,
     * only of a code step's reading, where it did not fit. A level rebuilt holds its members in the order they
     * came, then those left out in declared order.
     */
    private static final class Reading {

        private final boolean code;

        private final boolean verifiedFiles;

        /* Innermost first, gathered only while unwinding from what does not fit. */
        private @Nullable List<FieldName> outwards;

        /* Left by a level, a many or one value for the one caller that reads it next, which takes it at once. */
        private @Nullable JsonValue rebuilt;

        private @Nullable String undeclared;

        private Reading(boolean code) {
            this(code, false);
        }

        private Reading(boolean code, boolean verifiedFiles) {
            this.code = code;
            this.verifiedFiles = verifiedFiles;
        }

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
