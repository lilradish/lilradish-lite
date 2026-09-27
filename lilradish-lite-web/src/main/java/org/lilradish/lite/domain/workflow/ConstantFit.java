package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.text.ConcealingCharacter;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;

/**
 * What a constant written into a workflow version is held to. A constant is written as JSON writes a value: a
 * date and a moment as text, a term as the term's own words, and a field holding fields as an object of them. A
 * number, a date, a moment and yes or no fit only written as {@link FieldKind#writes} writes their kind.
 */
public final class ConstantFit {

    /** The most characters of text one constant holds, at every depth together. */
    public static final int MOST_TEXT = 8192;

    /** The most characters of the store's own text of one constant, level with the column's bound. */
    public static final int MOST_STORED = 1_048_576;

    private ConstantFit() {}

    /**
     * Whether it is no constant a version may be written with: a number past the digits a value is written in, a
     * NUL character, which no stored text holds, more text than a constant holds, or more than the store keeps.
     */
    public static boolean unwritable(JsonValue constant) {
        requireNonNull(constant, "ConstantFit constant must not be null");
        // The digits are judged first: a number's plain form is only built once it is known to be short.
        return holdsUnwritten(constant)
                || textLength(constant) > MOST_TEXT
                || CanonicalJson.storedLength(constant) > MOST_STORED;
    }

    private static boolean holdsUnwritten(JsonValue constant) {
        return switch (constant) {
            case JsonValue.JsonNumber number -> {
                // In long: near the int bound, precision less scale overflows to a negative that would pass.
                long scale = number.value().scale();
                yield number.value().precision() - scale > FieldKind.MOST_DIGITS || scale > FieldKind.MOST_DIGITS;
            }
            case JsonValue.JsonString text -> text.value().indexOf('\0') >= 0;
            case JsonValue.JsonArray items -> items.items().stream().anyMatch(ConstantFit::holdsUnwritten);
            case JsonValue.JsonObject members ->
                members.members().stream()
                        .anyMatch(member -> member.name().indexOf('\0') >= 0 || holdsUnwritten(member.value()));
            case JsonValue.JsonBoolean ignored -> false;
            case JsonValue.JsonNull ignored -> false;
        };
    }

    /** Characters of text it holds, member names aside, each counted as a code point as every bound on text is. */
    public static long textLength(JsonValue constant) {
        requireNonNull(constant, "ConstantFit constant must not be null");
        return switch (constant) {
            case JsonValue.JsonString text ->
                text.value().codePointCount(0, text.value().length());
            case JsonValue.JsonArray items ->
                items.items().stream().mapToLong(ConstantFit::textLength).sum();
            case JsonValue.JsonObject members ->
                members.members().stream()
                        .mapToLong(member -> textLength(member.value()))
                        .sum();
            case JsonValue.JsonNumber ignored -> 0;
            case JsonValue.JsonBoolean ignored -> 0;
            case JsonValue.JsonNull ignored -> 0;
        };
    }

    /** The first character a model may not be sent, in the order the constant is written; none where it holds none. */
    public static @Nullable ConcealingCharacter concealing(JsonValue constant) {
        requireNonNull(constant, "ConstantFit constant must not be null");
        return switch (constant) {
            case JsonValue.JsonString text -> ConcealingCharacter.firstIn(text.value());
            case JsonValue.JsonArray items -> first(items.items());
            case JsonValue.JsonObject members ->
                first(members.members().stream()
                        .map(JsonValue.JsonMember::value)
                        .toList());
            case JsonValue.JsonNumber ignored -> null;
            case JsonValue.JsonBoolean ignored -> null;
            case JsonValue.JsonNull ignored -> null;
        };
    }

    /**
     * Whether {@code constant} is a value {@code field} takes, the terms of each list a term may come from in
     * {@code terms}; a limit or a list the field has not chosen yet refuses nothing, that being its own problem.
     */
    public static boolean fits(JsonValue constant, Field field, Map<EntryVersionId, OfferedTerms> terms) {
        requireNonNull(constant, "ConstantFit constant must not be null");
        requireNonNull(field, "ConstantFit field must not be null");
        requireNonNull(terms, "ConstantFit terms must not be null");
        boolean mustBe = field.demand().mustBe();
        if (constant instanceof JsonValue.JsonNull) {
            return !mustBe;
        }
        return switch (field.howMany()) {
            case HowMany.One ignored -> fitsOne(constant, field.shape(), terms);
            case HowMany.Many many ->
                constant instanceof JsonValue.JsonArray items
                        && (many.most() == null || items.items().size() <= many.most())
                        && !(mustBe && items.items().isEmpty())
                        && items.items().stream().allMatch(item -> fitsOne(item, field.shape(), terms));
        };
    }

    private static boolean fitsOne(JsonValue value, FieldShape shape, Map<EntryVersionId, OfferedTerms> terms) {
        return switch (shape) {
            case FieldShape.Text text ->
                value instanceof JsonValue.JsonString written
                        && (text.longest() == null || Legibility.withinMaximumLength(written.value(), text.longest()));
            case FieldShape.Term term ->
                value instanceof JsonValue.JsonString written
                        && (term.list() == null
                                || (terms.get(term.list()) instanceof OfferedTerms offered
                                        && offered.offers(written.value())));
            case FieldShape.Nested nested ->
                value instanceof JsonValue.JsonObject written && members(written, nested, terms);
            case FieldShape.Plain plain -> plain.kind().writes(value);
        };
    }

    /** Exactly the fields held, by name, each fitting its own field. */
    private static boolean members(
            JsonValue.JsonObject written, FieldShape.Nested nested, Map<EntryVersionId, OfferedTerms> terms) {
        if (written.members().size() != nested.fields().size()) {
            return false;
        }
        Map<String, JsonValue> byName = HashMap.newHashMap(written.members().size());
        written.members().forEach(member -> byName.put(member.name(), member.value()));
        return nested.fields().stream().allMatch(held -> {
            JsonValue member = byName.get(held.name().value());
            return member != null && fits(member, held, terms);
        });
    }

    private static @Nullable ConcealingCharacter first(List<JsonValue> values) {
        for (JsonValue value : values) {
            ConcealingCharacter found = concealing(value);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
