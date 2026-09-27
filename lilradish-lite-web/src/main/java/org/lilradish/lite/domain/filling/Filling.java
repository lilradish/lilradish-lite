package org.lilradish.lite.domain.filling;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.text.ProseRefusal;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonBoolean;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/** Values a person filled in, read against the fields they were put as. Nothing in text is ever rewritten. */
public final class Filling {

    /* Values run to MOST_SENT code points written out, each sent at worst as an escaped surrogate pair of twelve
    bytes; 4096 holds the rest a request names, escaped alike, as a start's 1,883 at most. */
    public static final int LARGEST_REQUEST_BYTES = Math.toIntExact(12 * Declaration.MOST_SENT + 4096);

    private static final String YES = "true";

    private static final String NO = "false";

    private static final JsonValue NONE = new JsonNull();

    private static final int NOT_WITHIN = -1;

    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");

    /* Only spares parsing text no number is written in; FieldKind.writes still judges every number read. */
    private static final int LONGEST_NUMBER =
            CanonicalJson.write(FieldKind.NUMBER.longestWritten()).length();

    /* A field is one of the first level, counted with all it holds; past either most a problem is only counted. */
    private static final int MOST_NAMED_PER_FIELD = 20;

    private static final int MOST_NAMED = 200;

    /* Where reading stands, built into a FillPath only once a problem is named there: each field entered, and
    the element read within it, or NOT_WITHIN where it is read whole. */
    private final List<FieldName> entered = new ArrayList<>();

    private int[] within = new int[Declaration.MOST_LEVELS];

    private final List<FillProblem> problems = new ArrayList<>();

    /* Every problem, named or not, less what is missing within fields holding nothing, which is dropped. */
    private int found;

    private int foundBeforeField;

    private int foundOtherThanMissing;

    private Filling() {}

    /**
     * Not the shape where anything is, and otherwise the values that do not fit, the first
     * {@link #MOST_NAMED_PER_FIELD} of each field's and {@link #MOST_NAMED} in all named and every one counted, or
     * all of them as kept.
     *
     * @param fields as {@link FillField#of} puts them, whose limits each value is held to
     */
    public static FillOutcome of(List<FillField> fields, JsonValue sent) {
        requireNonNull(fields, "Filling fields must not be null");
        requireNonNull(sent, "Filling sent must not be null");
        Filling filling = new Filling();
        JsonObject kept = filling.level(fields, sent);
        if (kept == null) {
            return new FillOutcome.Unshaped();
        }
        return filling.problems.isEmpty() ? new FilledFields(kept) : new FillProblems(filling.problems, filling.found);
    }

    /**
     * Values as they are kept, written as they are sent: every field in declared order at every level, which the
     * store does not keep, and each value as its text.
     *
     * @throws IllegalStateException where what is kept is not of the fields' shape, as none where many are held is
     *     not, since {@link #of} never keeps it
     */
    public static JsonObject wire(List<FillField> fields, JsonObject kept) {
        requireNonNull(fields, "Filling fields must not be null");
        requireNonNull(kept, "Filling kept must not be null");
        return written(fields, kept, false);
    }

    /**
     * One value of {@code field} as it is kept, written as it is sent, as it is among all the fields, and none as
     * none where many are held, as a model may give back.
     *
     * @throws IllegalStateException where what is kept is not of the field's shape
     */
    public static JsonValue wire(FillField field, JsonValue kept) {
        requireNonNull(field, "Filling field must not be null");
        requireNonNull(kept, "Filling kept must not be null");
        return written(field, kept, true);
    }

    /** None where it is not the shape. */
    private @Nullable JsonObject level(List<FillField> fields, JsonValue sent) {
        if (!(sent instanceof JsonObject given) || given.members().size() != fields.size()) {
            return null;
        }
        Map<String, JsonValue> named = named(given);
        List<JsonMember> kept = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            JsonValue value = named.get(field.name().value());
            if (value == null) {
                return null;
            }
            if (entered.isEmpty()) {
                foundBeforeField = found;
            }
            enter(field.name());
            JsonValue read = field.most() == null ? one(field, value) : many(field, value);
            entered.removeLast();
            if (read == null) {
                return null;
            }
            kept.add(new JsonMember(field.name().value(), read));
        }
        return new JsonObject(kept);
    }

    private @Nullable JsonValue one(FillField field, JsonValue value) {
        if (value instanceof JsonNull || empty(field, value)) {
            return field.mustBeGiven() ? refuse(FillReason.MISSING) : NONE;
        }
        int namedBefore = problems.size();
        int foundBefore = found;
        int otherBefore = foundOtherThanMissing;
        JsonValue read = element(field, value);
        if (holdsNothing(read, namedBefore, foundBefore, otherBefore)) {
            return field.mustBeGiven() ? refuse(FillReason.MISSING) : NONE;
        }
        return read;
    }

    private @Nullable JsonValue many(FillField field, JsonValue value) {
        if (!(value instanceof JsonArray given)) {
            return null;
        }
        List<JsonValue> items = given.items();
        if (items.isEmpty() && field.mustBeGiven()) {
            refuse(FillReason.MISSING);
        } else if (items.size() > requireNonNull(field.most())) {
            /* No element read: one far past its most would otherwise be answered with a problem for each. */
            return refuse(FillReason.TOO_MANY);
        }
        List<JsonValue> kept = new ArrayList<>(items.size());
        int depth = entered.size() - 1;
        for (int index = 0; index < items.size(); index++) {
            JsonValue item = items.get(index);
            within[depth] = index;
            int namedBefore = problems.size();
            int foundBefore = found;
            int otherBefore = foundOtherThanMissing;
            JsonValue read = empty(field, item) ? refuse(FillReason.MISSING) : element(field, item);
            if (holdsNothing(read, namedBefore, foundBefore, otherBefore)) {
                read = refuse(FillReason.MISSING);
            }
            if (read == null) {
                return null;
            }
            kept.add(read);
        }
        within[depth] = NOT_WITHIN;
        return new JsonArray(kept);
    }

    private @Nullable JsonValue element(FillField field, JsonValue value) {
        if (field.kind() == FieldKind.FIELDS) {
            return level(field.fields(), value);
        }
        return value instanceof JsonString said ? scalar(field, said) : null;
    }

    /** Fields holding nothing are no value, so what is missing within them is dropped for their own demand. */
    private boolean holdsNothing(@Nullable JsonValue read, int namedBefore, int foundBefore, int otherBefore) {
        /* Counted rather than read from what is named: past the most named, what was found is not held. */
        if (!(read instanceof JsonObject object) || foundOtherThanMissing != otherBefore) {
            return false;
        }
        for (JsonMember member : object.members()) {
            if (!(member.value() instanceof JsonNull
                    || (member.value() instanceof JsonArray many && many.items().isEmpty()))) {
                return false;
            }
        }
        problems.subList(namedBefore, problems.size()).clear();
        found = foundBefore;
        return true;
    }

    private JsonValue scalar(FillField field, JsonString sent) {
        FieldKind kind = field.kind();
        String said = sent.value();
        JsonValue typed =
                switch (kind) {
                    case NUMBER -> number(said);
                    case YES_NO -> yesOrNo(said);
                    case TEXT, TERM, DATE, MOMENT -> sent;
                    case FIELDS -> throw new IllegalStateException("Filling reads fields as fields, not as text");
                };
        if (typed == null || !kind.writes(typed)) {
            return refuse(FillReason.MALFORMED);
        }
        ProseRefusal refusal = kind == FieldKind.TEXT ? refusalOf(said) : null;
        if (refusal != null) {
            return refuse(reasonOf(refusal));
        }
        if (kind == FieldKind.TEXT && !Legibility.withinMaximumLength(said, requireNonNull(field.longest()))) {
            return refuse(FillReason.TOO_LONG);
        }
        if (kind == FieldKind.TERM && !requireNonNull(field.terms()).offers(said)) {
            return refuse(FillReason.NOT_A_TERM);
        }
        return typed;
    }

    /** No value in its place: what is kept of a level where anything does not fit is never kept. */
    private JsonValue refuse(FillReason reason) {
        if (reason != FillReason.MISSING) {
            foundOtherThanMissing++;
        }
        boolean named = found - foundBeforeField < MOST_NAMED_PER_FIELD && problems.size() < MOST_NAMED;
        found++;
        if (!named) {
            return NONE;
        }
        List<FillPath.Step> steps = new ArrayList<>(2 * entered.size());
        for (int depth = 0; depth < entered.size(); depth++) {
            steps.add(new FillPath.Named(entered.get(depth)));
            if (within[depth] != NOT_WITHIN) {
                steps.add(new FillPath.Place(within[depth]));
            }
        }
        problems.add(new FillProblem(new FillPath(steps), reason));
        return NONE;
    }

    private void enter(FieldName name) {
        // A declaration stops at MOST_LEVELS; a FillField built by hand does not, so the room still grows.
        if (entered.size() == within.length) {
            within = Arrays.copyOf(within, 2 * within.length);
        }
        within[entered.size()] = NOT_WITHIN;
        entered.add(name);
    }

    /**
     * A value of any kind written as it is sent showing nothing is no value, unless it holds what text is refused
     * for: that is never dropped. Nothing is trimmed, so spacing around what shows is still how it is written.
     */
    private static boolean empty(FillField field, JsonValue value) {
        if (field.kind() == FieldKind.FIELDS || !(value instanceof JsonString said)) {
            return false;
        }
        String text = said.value();
        return text.isEmpty() || (!Legibility.holdsSomethingVisibleInProse(text) && refusalOf(text) == null);
    }

    private static @Nullable ProseRefusal refusalOf(String text) {
        return ProseRefusal.of(text, true, Filling::requireWellFormed);
    }

    private static void requireWellFormed(String text) {
        Legibility.requireWellFormedProse(text, "Filled text");
    }

    private static FillReason reasonOf(ProseRefusal refusal) {
        return switch (refusal) {
            case CRLF -> FillReason.CRLF;
            case DIRECTION_CONTROL -> FillReason.DIRECTION_CONTROL;
            case TAG -> FillReason.TAG;
            case UNUSABLE -> FillReason.UNUSABLE;
            case INVISIBLE -> throw new IllegalStateException("Filled text is not refused for what shows nothing");
        };
    }

    private static @Nullable JsonValue number(String said) {
        if (said.length() > LONGEST_NUMBER
                || !PLAIN_NUMBER.matcher(said).matches()
                || FieldKind.numberUnwritten(said) != null) {
            return null;
        }
        return new JsonNumber(new BigDecimal(said));
    }

    private static @Nullable JsonValue yesOrNo(String said) {
        return switch (said) {
            case YES -> new JsonBoolean(true);
            case NO -> new JsonBoolean(false);
            default -> null;
        };
    }

    private static JsonObject written(List<FillField> fields, JsonValue kept, boolean noneWhereMany) {
        if (!(kept instanceof JsonObject object) || object.members().size() != fields.size()) {
            throw unlike();
        }
        Map<String, JsonValue> named = named(object);
        List<JsonMember> members = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            JsonValue value = named.get(field.name().value());
            if (value == null) {
                throw unlike();
            }
            members.add(new JsonMember(field.name().value(), written(field, value, noneWhereMany)));
        }
        return new JsonObject(members);
    }

    private static JsonValue written(FillField field, JsonValue kept, boolean noneWhereMany) {
        return field.most() == null ? writtenOne(field, kept, noneWhereMany) : writtenMany(field, kept, noneWhereMany);
    }

    private static JsonValue writtenMany(FillField field, JsonValue kept, boolean noneWhereMany) {
        if (noneWhereMany && kept instanceof JsonNull) {
            return NONE;
        }
        if (!(kept instanceof JsonArray many)) {
            throw unlike();
        }
        List<JsonValue> items = new ArrayList<>(many.items().size());
        for (JsonValue item : many.items()) {
            if (item instanceof JsonNull) {
                throw unlike();
            }
            items.add(writtenOne(field, item, noneWhereMany));
        }
        return new JsonArray(items);
    }

    private static JsonValue writtenOne(FillField field, JsonValue kept, boolean noneWhereMany) {
        FieldKind kind = field.kind();
        return switch (kept) {
            case JsonNull absent -> NONE;
            case JsonObject object when kind == FieldKind.FIELDS -> written(field.fields(), object, noneWhereMany);
            case JsonNumber number
            when kind == FieldKind.NUMBER && kind.writes(number) ->
                new JsonString(number.value().toPlainString());
            case JsonBoolean flag when kind == FieldKind.YES_NO -> new JsonString(flag.value() ? YES : NO);
            case JsonString text when kind.writes(text) -> text;
            default -> throw unlike();
        };
    }

    private static Map<String, JsonValue> named(JsonObject object) {
        Map<String, JsonValue> named = HashMap.newHashMap(object.members().size());
        for (JsonMember member : object.members()) {
            named.put(member.name(), member.value());
        }
        return named;
    }

    private static IllegalStateException unlike() {
        return new IllegalStateException("Filling was handed values kept in no shape of the fields they fill");
    }
}
