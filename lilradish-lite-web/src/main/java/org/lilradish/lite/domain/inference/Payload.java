package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/**
 * What a model is sent beside the {@link Envelope}, as the one JSON document kept of it: the envelope's version
 * first, the instruction, and each field it takes with its value or none; then what was refused where the step
 * tells the next asking, and for a review, the answer under review and the fields to decide. Every half is
 * written in declared order, whatever order its values were handed in. Who or what produced it is never sent.
 */
public final class Payload {

    static final JsonValue VERSION_WRITTEN = new JsonNumber(BigDecimal.valueOf(Envelope.VERSION));

    private Payload() {}

    /**
     * Refused where a value is handed for a field the half does not declare, none for one it does, or one not
     * written as its field's kind writes it, at any depth.
     */
    public static JsonObject toProduce(Asking asking, Map<FieldName, JsonValue> taken, @Nullable Refused refused) {
        requireNonNull(asking, "Payload asking must not be null");
        return written(
                PayloadMember.sent(refused != null, false),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                taken,
                refused,
                null,
                null);
    }

    /** Refused as {@link #toProduce} is, and where what is decided is nothing, twice, or no value given back. */
    public static JsonObject toReview(
            Asking asking,
            Map<FieldName, JsonValue> taken,
            @Nullable Refused refused,
            Map<FieldName, JsonValue> answer,
            List<FieldName> deciding) {
        requireNonNull(asking, "Payload asking must not be null");
        requireNonNull(deciding, "Payload deciding must not be null");
        return written(
                PayloadMember.sent(refused != null, true),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                taken,
                refused,
                answer,
                deciding);
    }

    /**
     * As {@link #toReview}, of a production no instruction asked for: what it took and gave back declared by
     * {@code takes} and {@code gives}, and nothing said of what was to be done or of what did it.
     */
    public static JsonObject toReviewUninstructed(
            List<AskedField> takes,
            List<AskedField> gives,
            Map<FieldName, JsonValue> taken,
            @Nullable Refused refused,
            Map<FieldName, JsonValue> answer,
            List<FieldName> deciding) {
        requireNonNull(takes, "Payload takes must not be null");
        requireNonNull(gives, "Payload gives must not be null");
        requireNonNull(deciding, "Payload deciding must not be null");
        return written(
                PayloadMember.sentUninstructed(refused != null), null, takes, gives, taken, refused, answer, deciding);
    }

    private static JsonObject written(
            List<PayloadMember> sent,
            @Nullable Instruction instruction,
            List<AskedField> takes,
            List<AskedField> gives,
            Map<FieldName, JsonValue> taken,
            @Nullable Refused refused,
            @Nullable Map<FieldName, JsonValue> answer,
            @Nullable List<FieldName> deciding) {
        List<JsonMember> members = new ArrayList<>(sent.size());
        for (PayloadMember member : sent) {
            JsonValue value =
                    switch (member) {
                        case VERSION -> VERSION_WRITTEN;
                        case INSTRUCTION ->
                            new JsonString(requireNonNull(instruction).value());
                        case TAKES -> half(takes, taken);
                        case REFUSED -> refused(gives, requireNonNull(refused));
                        // A reviewer is told what went in and what came out, never who produced it: no confidence goes.
                        case ANSWER -> half(gives, requireNonNull(answer, "Payload values must not be null"));
                        case DECIDING -> deciding(gives, requireNonNull(deciding));
                    };
            members.add(new JsonMember(member.written(), value));
        }
        return new JsonObject(members);
    }

    private static JsonObject refused(List<AskedField> gives, Refused refused) {
        List<JsonMember> words = new ArrayList<>(refused.words().size());
        for (AskedField field : gives) {
            String said = refused.words().get(field.name());
            if (said != null) {
                words.add(new JsonMember(field.name().value(), new JsonString(said)));
            }
        }
        if (words.size() != refused.words().size()) {
            throw new IllegalArgumentException("Payload holds words refusing a field nothing gives back");
        }
        return new JsonObject(List.of(
                new JsonMember(PayloadMember.REFUSED_VALUES, half(gives, refused.values())),
                new JsonMember(PayloadMember.REFUSED_WORDS, new JsonObject(words))));
    }

    private static JsonArray deciding(List<AskedField> gives, List<FieldName> deciding) {
        Set<FieldName> decided = new HashSet<>(deciding);
        List<JsonValue> names = new ArrayList<>(deciding.size());
        for (AskedField field : gives) {
            if (decided.contains(field.name())) {
                names.add(new JsonString(field.name().value()));
            }
        }
        if (names.isEmpty() || names.size() != deciding.size()) {
            throw new IllegalArgumentException("Payload decides values given back, each once, and one at least");
        }
        return new JsonArray(names);
    }

    /**
     * Whether {@code values} could be sent as a half declared by {@code fields}, as every half sent is judged: a
     * value, or none, for each field and no other, each written as its field's kind writes it, at any depth.
     */
    public static boolean holds(List<AskedField> fields, Map<FieldName, JsonValue> values) {
        requireNonNull(fields, "Payload fields must not be null");
        requireNonNull(values, "Payload values must not be null");
        return misfit(fields, values) == null;
    }

    private static JsonObject half(List<AskedField> fields, Map<FieldName, JsonValue> values) {
        requireNonNull(values, "Payload values must not be null");
        String misfit = misfit(fields, values);
        if (misfit != null) {
            throw new IllegalArgumentException(misfit);
        }
        List<JsonMember> members = new ArrayList<>(fields.size());
        for (AskedField field : fields) {
            members.add(new JsonMember(field.name().value(), requireNonNull(values.get(field.name()))));
        }
        return new JsonObject(members);
    }

    /** Why {@code values} are no half {@code fields} declare, none where they are one. */
    private static @Nullable String misfit(List<AskedField> fields, Map<FieldName, JsonValue> values) {
        if (values.size() != fields.size()) {
            return "Payload holds a value, or none, for each field declared and no other";
        }
        for (AskedField field : fields) {
            JsonValue value = values.get(field.name());
            if (value == null) {
                return "Payload holds nothing for " + field.name().value();
            }
            if (!written(field, value)) {
                return "Payload holds for " + field.name().value() + " what its field does not write";
            }
        }
        return null;
    }

    /** None, or one value or many as the field holds, each as its kind writes it, and what fields hold likewise. */
    private static boolean written(AskedField field, JsonValue value) {
        if (value instanceof JsonNull) {
            return true;
        }
        if (field.most() == null) {
            return one(field, value);
        }
        if (!(value instanceof JsonArray many)) {
            return false;
        }
        for (JsonValue item : many.items()) {
            if (!one(field, item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean one(AskedField field, JsonValue value) {
        if (!field.kind().writes(value)) {
            return false;
        }
        if (!(value instanceof JsonObject held)) {
            return true;
        }
        for (JsonMember member : held.members()) {
            AskedField inner = Unwrapping.declared(field.fields(), member.name());
            if (inner == null || !written(inner, member.value())) {
                return false;
            }
        }
        return held.members().size() == field.fields().size();
    }

    /**
     * A production refused before, as the next asking is told it: every value it gave back, none being
     * {@link JsonValue.JsonNull}, and the words each refused value was refused with.
     */
    public record Refused(
            @DoNotLog Map<FieldName, JsonValue> values,
            @DoNotLog Map<FieldName, String> words) {

        public Refused {
            values = Map.copyOf(requireNonNull(values, "Payload.Refused values must not be null"));
            words = Map.copyOf(requireNonNull(words, "Payload.Refused words must not be null"));
            if (words.isEmpty()) {
                throw new IllegalArgumentException("Payload.Refused refused something, and says why");
            }
        }
    }
}
