package org.lilradish.lite.domain.codestep;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.Demand;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.inference.ForeignProse;
import org.lilradish.lite.domain.inference.GivenBack;
import org.lilradish.lite.domain.inference.Unwrapping;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;

/**
 * What running a code step came to: every value it declares giving back, each standing as its release declares, or
 * that it went wrong, saying why. Nothing it gives back is ever a value that did not fit: that is code gone wrong.
 */
public sealed interface CodeOutcome permits CodeOutcome.Gave, CodeOutcome.Errored {

    /**
     * What code that gave {@code returned} back came to, read against the fields {@code call} declares it gives
     * back. The first thing found wrong is given as a reason with the field it is about, and what came back is kept
     * where it can be.
     */
    static CodeOutcome returned(CodeCall call, JsonValue.@Nullable JsonObject returned) {
        requireNonNull(call, "CodeOutcome call must not be null");
        if (returned == null) {
            return new Errored(new CodeError.Fault(CodeErrorReason.GAVE_NOTHING, List.of(), null, null), null);
        }
        Declaration gives = call.declared().gives();
        // Asking cannot tell a half that gives nothing, so any member at all is one it does not declare.
        if (gives.fields().isEmpty()) {
            return returned.members().isEmpty()
                    ? new Gave(List.of())
                    : misfit(
                            new CodeError.Fault(
                                    CodeErrorReason.NOT_DECLARED,
                                    List.of(),
                                    returned.members().getFirst().name(),
                                    null),
                            returned);
        }
        return switch (Unwrapping.given(Asking.told(gives, call.lists()), returned)) {
            case GivenBack.Misfit wrong ->
                misfit(new CodeError.Fault(reason(wrong.reason()), wrong.path(), wrong.undeclared(), null), returned);
            case GivenBack.Fits fits -> {
                List<Given> values = new ArrayList<>(gives.fields().size());
                for (Field field : gives.fields()) {
                    values.add(Given.of(field, requireNonNull(fits.values().get(field.name()))));
                }
                yield new Gave(values);
            }
        };
    }

    /**
     * What code that threw came to: its message cleaned and cut, saying so, or where nothing readable is left of it,
     * that it said nothing, which is this system's to say and never a sentence standing in for the code's.
     */
    static CodeOutcome thrown(@Nullable String message) {
        ForeignProse said = ForeignProse.said(message);
        return new Errored(
                said == null
                        ? new CodeError.Fault(CodeErrorReason.SAID_NOTHING, List.of(), null, null)
                        : new CodeError.Said(said),
                null);
    }

    private static CodeOutcome misfit(CodeError.Fault fault, JsonValue.JsonObject returned) {
        return new Errored(fault, kept(returned));
    }

    private static @Nullable String kept(JsonValue.JsonObject returned) {
        String written;
        try {
            written = CanonicalJson.write(returned);
        } catch (IllegalArgumentException halfAPair) {
            return null;
        }
        return Errored.unkeepable(written) == null ? written : null;
    }

    private static CodeErrorReason reason(DidNotFitReason found) {
        return switch (found) {
            case FIELD_UNKNOWN -> CodeErrorReason.NOT_DECLARED;
            case FIELD_MISSING, NOTHING_GIVEN -> CodeErrorReason.NOTHING_GIVEN;
            case NOT_ITS_KIND -> CodeErrorReason.NOT_ITS_KIND;
            case TOO_LONG -> CodeErrorReason.TOO_LONG;
            case TOO_MANY -> CodeErrorReason.TOO_MANY;
            case NOT_A_TERM -> CodeErrorReason.NOT_A_TERM;
            case UNKEEPABLE -> CodeErrorReason.UNKEEPABLE;
            case TOO_LONG_TO_KEEP -> CodeErrorReason.TOO_LONG_TO_KEEP;
            case NOT_THE_SHAPE,
                    CONFIDENCE_MISSING,
                    CONFIDENCE_UNASKED,
                    CONFIDENCE_NOT_A_PERCENT,
                    UNDECIDED,
                    WORDS_MISSING,
                    WORDS_TOO_LONG,
                    WORDS_UNKEEPABLE,
                    CUT_OFF,
                    NOT_KEPT_AS_IT_CAME ->
                throw new IllegalArgumentException(
                        "What code gave back is never found " + found + ", which only a model's answer is");
        };
    }

    /** One value per field it gives back, in declared order, nothing given being JSON null. */
    record Gave(@DoNotLog List<Given> values) implements CodeOutcome {

        public Gave {
            values = List.copyOf(requireNonNull(values, "CodeOutcome.Gave values must not be null"));
        }
    }

    /**
     * Why it went wrong, and what it gave back kept as it came back: none where it threw, which gave nothing back,
     * and none where what came back could not be kept.
     */
    record Errored(CodeError wentWrong, @DoNotLog @Nullable String returned) implements CodeOutcome {

        /* The store's bound, counted in code points as it counts characters. */
        private static final int MOST_RETURNED = 8_388_608;

        public Errored {
            requireNonNull(wentWrong, "CodeOutcome.Errored wentWrong must not be null");
            String refused = returned == null ? null : unkeepable(returned);
            if (refused != null) {
                throw new IllegalArgumentException(refused);
            }
        }

        // A driver refuses a null character, and writes half a pair as a question mark nobody gave back.
        private static @Nullable String unkeepable(String returned) {
            long characters = 0;
            for (int index = 0; index < returned.length(); index++) {
                char character = returned.charAt(index);
                if (character == 0
                        || (Character.isSurrogate(character) && CanonicalJson.halfAPairAt(returned, index))) {
                    return "CodeOutcome.Errored returned must hold no null character and no half of a pair";
                }
                if (!Character.isLowSurrogate(character)) {
                    characters++;
                }
            }
            return characters > MOST_RETURNED
                    ? "CodeOutcome.Errored returned must run to at most " + MOST_RETURNED + " characters"
                    : null;
        }
    }

    /** A value of one field it gives back, standing as that field's demand says. */
    record Given(
            FieldName name,
            @DoNotLog JsonValue value,
            FieldStanding standing,
            @Nullable Integer floor) {

        private static final int HIGHEST_FLOOR = 100;

        public Given {
            requireNonNull(name, "CodeOutcome.Given name must not be null");
            requireNonNull(value, "CodeOutcome.Given value must not be null");
            requireNonNull(standing, "CodeOutcome.Given standing must not be null");
            if ((standing == FieldStanding.ABOVE_CONFIDENCE) != (floor != null)) {
                throw new IllegalArgumentException(
                        "CodeOutcome.Given of " + name.value() + " carries a floor exactly where it stands above one");
            }
            if (floor != null && (floor < 1 || floor > HIGHEST_FLOOR)) {
                throw new IllegalArgumentException(
                        "CodeOutcome.Given of " + name.value() + " floor must be from 1 to 100: " + floor);
            }
        }

        /** Refused where the field does not say what it takes to stand, as a field no release holds. */
        public static Given of(Field field, JsonValue value) {
            requireNonNull(field, "CodeOutcome.Given field must not be null");
            if (!(field.demand() instanceof Demand.Stands stands) || stands.standing() == null) {
                throw new IllegalArgumentException("CodeOutcome.Given of "
                        + field.name().value() + " is of a field that does not say what it takes to stand");
            }
            return new Given(field.name(), value, stands.standing(), stands.floor());
        }
    }
}
