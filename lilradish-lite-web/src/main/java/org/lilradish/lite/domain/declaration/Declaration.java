package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.registry.ContentProblemCode;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/**
 * One half of what something declares, each field asking what its host's {@link Demands} ask at its depth.
 * What only submitting refuses is {@link #problems}'; what no version could hold is refused here.
 */
public record Declaration(DeclarationSide side, Demands demands, List<Field> fields) {

    /** The longest path of names joined by dots anything may be bound through, which the store holds too. */
    public static final int LONGEST_PATH = 1023;

    /** The deepest a field may be held, the first level counted as one. */
    public static final int MOST_LEVELS = 32;

    /** The most fields one half holds, at every depth together. */
    public static final int MOST_FIELDS = 256;

    /** The most characters one asking of a model may send, and the longest one value may be written out. */
    public static final long MOST_SENT = 8_388_608L;

    /**
     * The most characters the store's own text of one value runs to: each character written out is kept as at
     * most the six of an escaped control, a comma or a colon gaining only a space.
     */
    public static final long MOST_STORED = MOST_SENT
            * (CanonicalJson.storedLength(new JsonString(Character.toString(0x1)))
                    - CanonicalJson.storedLength(new JsonString("")));

    private static final long PAST_SENT = MOST_SENT + 1;

    private static final JsonValue NOTHING = new JsonNull();

    private static final long NONE = written(NOTHING);

    private static final long QUOTES = written(new JsonString(""));

    private static final long EMPTY_OBJECT = written(new JsonObject(List.of()));

    private static final long EMPTY_ARRAY = written(new JsonArray(List.of()));

    private static final long COMMA = written(new JsonArray(List.of(NOTHING, NOTHING))) - EMPTY_ARRAY - 2 * NONE;

    private static final long COLON =
            written(new JsonObject(List.of(new JsonMember("", NOTHING)))) - EMPTY_OBJECT - QUOTES - NONE;

    /* A field's own codes in the order they are declared, drawn once: this is asked once per field. */
    private static final ContentProblemCode[] CODES = Arrays.stream(ContentProblemCode.values())
            .filter(ContentProblemCode::ofOneField)
            .toArray(ContentProblemCode[]::new);

    public Declaration {
        requireNonNull(side, "Declaration side must not be null");
        requireNonNull(demands, "Declaration demands must not be null");
        fields = List.copyOf(requireNonNull(fields, "Declaration fields must not be null"));
        if (side == DeclarationSide.TAKES && demands.first() == Demands.Kind.STANDS) {
            throw new IllegalArgumentException("Declaration of what is taken cannot ask " + demands);
        }
        int held = requireHeld(demands, fields, 1, 0);
        if (held > MOST_FIELDS) {
            throw new IllegalArgumentException(
                    "Declaration holds " + held + " fields, more than the " + MOST_FIELDS + " a half holds");
        }
    }

    /** How long the path to a field named {@code name} runs, below a field whose own runs to {@code above}. */
    public static int pathBelow(int above, FieldName name) {
        requireNonNull(name, "Declaration name must not be null");
        return (above == 0 ? 0 : above + 1) + name.value().length();
    }

    /**
     * Every field not holding as submitting requires, in the order the half reads, each field's problems in the order
     * {@link ContentProblemCode} declares; each term counting as its list's longest, a missing list offering none.
     */
    public List<FieldProblem> problems(Map<EntryVersionId, OfferedTerms> terms) {
        requireNonNull(terms, "Declaration terms must not be null");
        List<FieldProblem> found = new ArrayList<>();
        walk(fields, new int[MOST_LEVELS], 0, terms, found);
        return List.copyOf(found);
    }

    /**
     * All it holds written out as one value, framed as {@link CanonicalJson} frames it and nothing escaped, each field
     * at its limits as {@link #problems} counts one; held at one past {@link #MOST_SENT} once past it.
     */
    public long longestWritten(Map<EntryVersionId, OfferedTerms> terms) {
        requireNonNull(terms, "Declaration terms must not be null");
        return object(fields, terms);
    }

    /** The fields it holds, at every depth together. */
    public int fieldsHeld() {
        return counted(fields);
    }

    private static int counted(List<Field> level) {
        int held = level.size();
        for (Field field : level) {
            if (field.shape() instanceof FieldShape.Nested nested) {
                held += counted(nested.fields());
            }
        }
        return held;
    }

    /**
     * Whether {@link #problems} would name nothing, asked without naming anything and without the terms a value
     * written out too long is found by, which is then left to what measures it.
     */
    public boolean holds() {
        return !walk(fields, new int[MOST_LEVELS], 0, null, null);
    }

    /** The fields counted, this level's and every level's below. */
    private static int requireHeld(Demands demands, List<Field> level, int depth, int above) {
        int held = level.size();
        for (Field field : level) {
            if (depth > MOST_LEVELS) {
                throw new IllegalArgumentException("Declaration holds a field " + depth + " deep, past " + MOST_LEVELS);
            }
            int path = pathBelow(above, field.name());
            if (path > LONGEST_PATH) {
                throw new IllegalArgumentException(
                        "Declaration holds a field whose path runs to " + path + " characters, past " + LONGEST_PATH);
            }
            if (!demands.admits(field.demand(), depth == 1)) {
                throw new IllegalArgumentException("Declaration asking " + demands + " holds a field demanding "
                        + Demands.kindOf(field.demand()) + (depth == 1 ? " on its first level" : " below it"));
            }
            if (field.shape() instanceof FieldShape.Nested nested) {
                held += requireHeld(demands, nested.fields(), depth + 1, path);
            }
        }
        return held;
    }

    /**
     * True at the first problem where {@code found} is none; otherwise every problem is added to it. A value
     * written out too long is asked of only where {@code terms} are given.
     */
    private static boolean walk(
            List<Field> level,
            int[] at,
            int depth,
            @Nullable Map<EntryVersionId, OfferedTerms> terms,
            @Nullable List<FieldProblem> found) {
        for (int index = 0; index < level.size(); index++) {
            Field field = level.get(index);
            at[depth] = index;
            for (ContentProblemCode code : CODES) {
                if (has(code, field, level, index, terms)) {
                    if (found == null) {
                        return true;
                    }
                    List<Integer> place = new ArrayList<>(depth + 1);
                    for (int step = 0; step <= depth; step++) {
                        place.add(at[step]);
                    }
                    found.add(new FieldProblem(code, place));
                }
            }
            if (field.shape() instanceof FieldShape.Nested nested
                    && walk(nested.fields(), at, depth + 1, terms, found)) {
                return true;
            }
        }
        return false;
    }

    private static boolean has(
            ContentProblemCode code,
            Field field,
            List<Field> level,
            int index,
            @Nullable Map<EntryVersionId, OfferedTerms> terms) {
        return switch (code) {
            case NAME_REPEATED -> named(level, index);
            case LONGEST_MISSING -> field.shape() instanceof FieldShape.Text text && text.longest() == null;
            case LIST_MISSING -> field.shape() instanceof FieldShape.Term term && term.list() == null;
            case MOST_MISSING -> field.howMany() instanceof HowMany.Many many && many.most() == null;
            case LIMIT_PAST_LARGEST -> terms != null && value(field, terms) > MOST_SENT;
            case NO_FIELDS_HELD ->
                field.shape() instanceof FieldShape.Nested nested
                        && nested.fields().isEmpty();
            case STANDING_MISSING -> field.demand() instanceof Demand.Stands stands && stands.standing() == null;
            case FLOOR_MISSING ->
                field.demand() instanceof Demand.Stands stands
                        && stands.standing() == FieldStanding.ABOVE_CONFIDENCE
                        && stands.floor() == null;
            case INSTRUCTION_MISSING,
                    NOTHING_GIVEN_BACK,
                    NO_STEPS,
                    STEP_NAME_REPEATED,
                    RUNS_MISSING,
                    PIN_ELSEWHERE,
                    CODE_STEP_NOT_PUBLISHED,
                    CODE_STEP_NOT_DECLARED,
                    CODE_STEP_TAKES_AND_GIVES_NOTHING,
                    CODE_STEP_LIST_MISSING,
                    CODE_STEP_LIST_NOT_YET_IN_SERVICE,
                    CODE_STEP_LIST_RETIRED,
                    PRODUCER_MISSING,
                    PRODUCER_NOT_HELD,
                    PRODUCER_MODE_NOT_OFFERED,
                    TRIES_MISSING,
                    REVIEWER_NOT_HELD,
                    REVIEWER_MODE_NOT_OFFERED,
                    MODEL_GIVES_NOTHING,
                    DISCRIMINATOR_MISSING,
                    DISCRIMINATOR_NOT_TERM,
                    NO_CASES,
                    CASE_TARGET_MISSING,
                    CASE_NOT_OFFERED,
                    CASE_REPEATED,
                    CASE_GIVES_OTHERWISE,
                    INPUT_UNBOUND,
                    TARGET_UNKNOWN,
                    TARGET_BOUND_TWICE,
                    POINTER_INTO_MANY,
                    SOURCE_UNKNOWN,
                    SOURCE_NOT_EARLIER,
                    SOURCE_DOES_NOT_FIT,
                    SOURCE_MAY_BE_EMPTY,
                    CONSTANT_DOES_NOT_FIT,
                    CONSTANT_TOO_LONG,
                    CONSTANT_CONCEALS,
                    OUTPUT_UNBOUND,
                    OUTPUT_NOT_FROM_STEP,
                    HELPER_MISSING,
                    HELPER_NOT_HELD,
                    HELPER_MODE_NOT_OFFERED,
                    NO_TERMS,
                    TERM_REPEATED,
                    ASKING_PAST_LARGEST,
                    TAKES_PAST_LARGEST,
                    CODE_STEP_REVIEW_PAST_LARGEST ->
                throw new IllegalStateException(code + " is not a field's own problem");
        };
    }

    private static boolean named(List<Field> level, int index) {
        FieldName name = level.get(index).name();
        for (int earlier = 0; earlier < index; earlier++) {
            if (level.get(earlier).name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The longest one value of the field is written out in, none included; held at one past {@link #MOST_SENT}
     * once past it. A limit not chosen yet leaves text and a term only none, and counts many as one.
     */
    private static long value(Field field, Map<EntryVersionId, OfferedTerms> terms) {
        long item =
                switch (field.shape()) {
                    case FieldShape.Text text -> text.longest() == null ? 0 : QUOTES + text.longest();
                    case FieldShape.Term term -> term.list() == null ? 0 : longestTerm(terms.get(term.list()));
                    case FieldShape.Plain plain -> written(plain.kind().longestWritten());
                    case FieldShape.Nested nested -> object(nested.fields(), terms);
                };
        if (!(field.howMany() instanceof HowMany.Many many)) {
            return Math.max(NONE, item);
        }
        long most = many.most() == null ? 1 : many.most();
        // An item held within one past the most and a most within an int keep the product within a long.
        return item == 0 ? NONE : Math.min(PAST_SENT, EMPTY_ARRAY + most * (item + COMMA) - COMMA);
    }

    private static long object(List<Field> level, Map<EntryVersionId, OfferedTerms> terms) {
        long length = EMPTY_OBJECT + Math.max(0, level.size() - 1) * COMMA;
        for (Field field : level) {
            length = Math.min(PAST_SENT, length + QUOTES + field.name().value().length() + COLON + value(field, terms));
        }
        return length;
    }

    /** Where the list offers no term, or none is read of it, nothing but none could be given. */
    private static long longestTerm(@Nullable OfferedTerms offered) {
        long longest = 0;
        if (offered == null) {
            return longest;
        }
        for (OfferedTerms.Offered each : offered.terms()) {
            String term = each.term().value();
            longest = Math.max(longest, QUOTES + term.codePointCount(0, term.length()));
        }
        return longest;
    }

    private static long written(JsonValue value) {
        String text = CanonicalJson.write(value);
        return text.codePointCount(0, text.length());
    }
}
