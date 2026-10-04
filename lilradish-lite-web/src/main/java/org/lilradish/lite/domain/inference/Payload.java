package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonBoolean;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/**
 * One JSON document sent beside the {@link Envelope}: version first, then asking, review, or search members.
 * Declared fields follow their declaration, search parts their approved order; no producer identity is sent.
 */
public final class Payload {

    static final JsonValue VERSION_WRITTEN = new JsonNumber(BigDecimal.valueOf(Envelope.VERSION));

    private Payload() {}

    public sealed interface SearchPart {

        record LiteralPart(@DoNotLog String text) implements SearchPart {
            public LiteralPart {
                requireNonNull(text, "Payload search literal must not be null");
            }
        }

        record ValuePart(
                @DoNotLog AskedField field,
                @DoNotLog @Nullable JsonValue value) implements SearchPart {
            public ValuePart {
                requireNonNull(field, "Payload search field must not be null");
            }
        }
    }

    /** The question is joined here in its approved order, then written once with the whole payload. */
    public static JsonObject toSearch(List<SearchPart> parts, List<AskedField> gives, @Nullable Refused refused) {
        return toSearch(searchQuestion(parts), gives, refused);
    }

    public static JsonObject toSearch(String question, List<AskedField> gives, @Nullable Refused refused) {
        requireNonNull(question, "Payload search question must not be null");
        requireNonNull(gives, "Payload gives must not be null");
        return written(
                PayloadMember.sentSearch(refused != null),
                null,
                List.of(),
                gives,
                Map.of(),
                refused,
                null,
                null,
                question,
                List.of(),
                null);
    }

    private static String searchQuestion(List<SearchPart> parts) {
        requireNonNull(parts, "Payload search parts must not be null");
        StringBuilder question = new StringBuilder();
        for (SearchPart part : parts) {
            switch (part) {
                case SearchPart.LiteralPart literal -> question.append(literal.text());
                case SearchPart.ValuePart from -> {
                    JsonValue value = from.value();
                    AskedField field = from.field();
                    refuseFile(List.of(field));
                    if (value == null
                            || value instanceof JsonNull
                            || (field.most() != null
                                    && value instanceof JsonArray many
                                    && many.items().isEmpty())) {
                        if (field.mustBeGiven()) {
                            throw new IllegalArgumentException("Payload search field must be given: "
                                    + field.name().value());
                        }
                        continue;
                    }
                    if (!written(field, value)) {
                        throw new IllegalArgumentException("Payload search value is not written as its field");
                    }
                    if (searchValueRaw(field)) {
                        question.append(((JsonString) value).value());
                    } else {
                        question.append(CanonicalJson.write(value));
                    }
                }
            }
        }
        return question.toString();
    }

    static boolean searchValueRaw(AskedField field) {
        refuseFile(List.of(field));
        if (field.most() != null) {
            return false;
        }
        return switch (field.kind()) {
            case TEXT, TERM -> true;
            case DATE, MOMENT, FIELDS, NUMBER, YES_NO -> false;
            case FILE -> throw new IllegalArgumentException("A file is not sent to a model");
        };
    }

    /** Refuses values not written as declared and markers not naming distinct top-level inputs in order. */
    public static JsonObject toProduce(
            Asking asking, Map<FieldName, JsonValue> taken, @Nullable Refused refused, List<FieldName> fromTheWeb) {
        requireNonNull(asking, "Payload asking must not be null");
        return written(
                PayloadMember.sent(
                        refused != null,
                        false,
                        !requireNonNull(fromTheWeb, "Payload web sources must not be null")
                                .isEmpty()),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                taken,
                refused,
                null,
                null,
                null,
                fromTheWeb,
                null);
    }

    public static JsonObject toProduce(Asking asking, Map<FieldName, JsonValue> taken, @Nullable Refused refused) {
        return toProduce(asking, taken, refused, List.of());
    }

    /** Refuses values not written as declared and decisions not naming distinct fields given back. */
    public static JsonObject toReview(
            Asking asking,
            Map<FieldName, JsonValue> taken,
            @Nullable Refused refused,
            Map<FieldName, JsonValue> answer,
            List<FieldName> deciding,
            List<FieldName> fromTheWeb) {
        requireNonNull(asking, "Payload asking must not be null");
        requireNonNull(deciding, "Payload deciding must not be null");
        return written(
                PayloadMember.sent(
                        refused != null,
                        true,
                        !requireNonNull(fromTheWeb, "Payload web sources must not be null")
                                .isEmpty()),
                asking.instruction(),
                asking.takes(),
                asking.gives(),
                taken,
                refused,
                answer,
                deciding,
                null,
                fromTheWeb,
                null);
    }

    public static JsonObject toReview(
            Asking asking,
            Map<FieldName, JsonValue> taken,
            @Nullable Refused refused,
            Map<FieldName, JsonValue> answer,
            List<FieldName> deciding) {
        return toReview(asking, taken, refused, answer, deciding, List.of());
    }

    /** Uses the inputs and web markers actually sent, leaving an unbuildable review to a person. */
    public static Optional<JsonObject> toReviewKept(
            Asking asking,
            JsonObject sent,
            @Nullable Refused refused,
            Map<FieldName, JsonValue> answer,
            List<FieldName> deciding) {
        requireNonNull(asking, "Payload asking must not be null");
        requireNonNull(sent, "Payload sent inputs must not be null");
        JsonValue instruction = null;
        JsonValue takes = null;
        JsonValue marked = null;
        for (JsonMember member : sent.members()) {
            if (member.name().equals(PayloadMember.INSTRUCTION.written())) {
                instruction = member.value();
            } else if (member.name().equals(PayloadMember.TAKES.written())) {
                takes = member.value();
            } else if (member.name().equals(PayloadMember.FOUND_ON_THE_WEB.written())) {
                marked = member.value();
            }
        }
        if (!(instruction instanceof JsonString text)
                || !text.value().equals(asking.instruction().value())
                || !(takes instanceof JsonObject inputs)
                || marked != null && !(marked instanceof JsonArray)) {
            return Optional.empty();
        }
        try {
            Map<FieldName, JsonValue> taken = new HashMap<>();
            for (JsonMember member : inputs.members()) {
                taken.put(new FieldName(member.name()), member.value());
            }
            List<FieldName> fromTheWeb = new ArrayList<>();
            if (marked instanceof JsonArray names) {
                for (JsonValue name : names.items()) {
                    if (!(name instanceof JsonString string)) {
                        return Optional.empty();
                    }
                    fromTheWeb.add(new FieldName(string.value()));
                }
            }
            return Optional.of(toReview(asking, taken, refused, answer, deciding, fromTheWeb));
        } catch (IllegalArgumentException unbuildable) {
            return Optional.empty();
        }
    }

    /** A review of a production no instruction asked for. */
    public static JsonObject toReviewUninstructed(
            List<AskedField> takes,
            List<AskedField> gives,
            Map<FieldName, JsonValue> taken,
            @Nullable Refused refused,
            Map<FieldName, JsonValue> answer,
            List<FieldName> deciding,
            List<FieldName> fromTheWeb) {
        requireNonNull(takes, "Payload takes must not be null");
        requireNonNull(gives, "Payload gives must not be null");
        requireNonNull(deciding, "Payload deciding must not be null");
        return written(
                PayloadMember.sentUninstructed(
                        refused != null,
                        !requireNonNull(fromTheWeb, "Payload web sources must not be null")
                                .isEmpty()),
                null,
                takes,
                gives,
                taken,
                refused,
                answer,
                deciding,
                null,
                fromTheWeb,
                null);
    }

    public static JsonObject toReviewUninstructed(
            List<AskedField> takes,
            List<AskedField> gives,
            Map<FieldName, JsonValue> taken,
            @Nullable Refused refused,
            Map<FieldName, JsonValue> answer,
            List<FieldName> deciding) {
        return toReviewUninstructed(takes, gives, taken, refused, answer, deciding, List.of());
    }

    /** The question and answer a search reviewer sees, with only searches actually kept. */
    public static JsonObject toReviewSearch(
            String question,
            List<AskedField> gives,
            @Nullable Refused refused,
            Map<FieldName, JsonValue> answer,
            List<FieldName> deciding,
            JsonObject searchRecord) {
        requireNonNull(question, "Payload search question must not be null");
        requireNonNull(gives, "Payload gives must not be null");
        requireNonNull(searchRecord, "Payload search record must not be null");
        JsonObject kept = keptSearchRecord(searchRecord);
        return written(
                PayloadMember.sentReviewSearch(refused != null, kept != null),
                null,
                List.of(),
                gives,
                Map.of(),
                refused,
                answer,
                deciding,
                question,
                List.of(),
                kept);
    }

    private static @Nullable JsonObject keptSearchRecord(JsonObject record) {
        JsonArray searches = null;
        JsonBoolean cut = null;
        JsonBoolean changed = null;
        JsonNumber leftOut = null;
        for (JsonMember member : record.members()) {
            switch (member.name()) {
                case PayloadMember.SEARCHES -> {
                    if (!(member.value() instanceof JsonArray many)) {
                        throw new IllegalArgumentException("Payload search record has a wrong value kind");
                    }
                    searches = many;
                }
                case PayloadMember.SEARCH_CUT -> {
                    if (!(member.value() instanceof JsonBoolean value)) {
                        throw new IllegalArgumentException("Payload search record has a wrong value kind");
                    }
                    cut = value;
                }
                case PayloadMember.SEARCH_NOT_AS_IT_CAME -> {
                    if (!(member.value() instanceof JsonBoolean value)) {
                        throw new IllegalArgumentException("Payload search record has a wrong value kind");
                    }
                    changed = value;
                }
                case PayloadMember.SEARCH_LEFT_OUT -> {
                    if (!(member.value() instanceof JsonNumber value)) {
                        throw new IllegalArgumentException("Payload search record has a wrong value kind");
                    }
                    leftOut = value;
                }
                default -> throw new IllegalArgumentException("Payload search record has an unknown member");
            }
        }
        if (searches == null || searches.items().isEmpty()) {
            return null;
        }
        if (cut == null
                || changed == null
                || leftOut == null
                || leftOut.value().scale() > 0
                || leftOut.value().signum() < 0) {
            throw new IllegalArgumentException("Payload search record has incomplete facts");
        }
        List<JsonValue> entries = new ArrayList<>(searches.items().size());
        for (JsonValue search : searches.items()) {
            if (!(search instanceof JsonObject entry)) {
                throw new IllegalArgumentException("Payload search entry must be an object");
            }
            JsonString sought = null;
            JsonString found = null;
            for (JsonMember member : entry.members()) {
                switch (member.name()) {
                    case PayloadMember.SEARCHED_FOR -> {
                        if (!(member.value() instanceof JsonString value)) {
                            throw new IllegalArgumentException("Payload search entry has a wrong value kind");
                        }
                        sought = value;
                    }
                    case PayloadMember.FOUND -> {
                        if (!(member.value() instanceof JsonString value)) {
                            throw new IllegalArgumentException("Payload search entry has a wrong value kind");
                        }
                        found = value;
                    }
                    default -> throw new IllegalArgumentException("Payload search entry has an unknown member");
                }
            }
            if (sought == null && found == null) {
                throw new IllegalArgumentException("Payload search entry must hold text");
            }
            List<JsonMember> fields = new ArrayList<>(2);
            if (sought != null) {
                fields.add(new JsonMember(PayloadMember.SEARCHED_FOR, sought));
            }
            if (found != null) {
                fields.add(new JsonMember(PayloadMember.FOUND, found));
            }
            entries.add(new JsonObject(fields));
        }
        return new JsonObject(List.of(
                new JsonMember(PayloadMember.SEARCHES, new JsonArray(entries)),
                new JsonMember(PayloadMember.SEARCH_CUT, cut),
                new JsonMember(PayloadMember.SEARCH_NOT_AS_IT_CAME, changed),
                new JsonMember(PayloadMember.SEARCH_LEFT_OUT, leftOut)));
    }

    private static JsonObject written(
            List<PayloadMember> sent,
            @Nullable Instruction instruction,
            List<AskedField> takes,
            List<AskedField> gives,
            Map<FieldName, JsonValue> taken,
            @Nullable Refused refused,
            @Nullable Map<FieldName, JsonValue> answer,
            @Nullable List<FieldName> deciding,
            @Nullable String question,
            List<FieldName> fromTheWeb,
            @Nullable JsonObject searchRecord) {
        JsonArray marked = fromTheWeb(takes, fromTheWeb);
        List<JsonMember> members = new ArrayList<>(sent.size());
        for (PayloadMember member : sent) {
            JsonValue value =
                    switch (member) {
                        case VERSION -> VERSION_WRITTEN;
                        case INSTRUCTION ->
                            new JsonString(requireNonNull(instruction).value());
                        case QUESTION -> new JsonString(requireNonNull(question));
                        case TAKES -> half(takes, taken);
                        case FOUND_ON_THE_WEB -> marked;
                        case REFUSED -> refused(gives, requireNonNull(refused));
                        case SEARCH_RECORD -> requireNonNull(searchRecord);
                        // A reviewer is told what went in and what came out, never who produced it: no confidence goes.
                        case ANSWER -> half(gives, requireNonNull(answer, "Payload values must not be null"));
                        case DECIDING -> deciding(gives, requireNonNull(deciding));
                    };
            members.add(new JsonMember(member.written(), value));
        }
        return new JsonObject(members);
    }

    private static JsonArray fromTheWeb(List<AskedField> takes, List<FieldName> names) {
        requireNonNull(names, "Payload web sources must not be null");
        List<JsonValue> written = new ArrayList<>(names.size());
        int next = 0;
        for (AskedField field : takes) {
            if (next < names.size() && field.name().equals(names.get(next))) {
                written.add(new JsonString(field.name().value()));
                next++;
            }
        }
        if (next != names.size()) {
            throw new IllegalArgumentException("Payload web sources must name top-level inputs once in declared order");
        }
        return new JsonArray(written);
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
        refuseFile(fields);
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

    private static void refuseFile(List<AskedField> fields) {
        for (AskedField field : fields) {
            if (field.kind() == FieldKind.FILE) {
                throw new IllegalArgumentException("A file is not sent to a model");
            }
            refuseFile(field.fields());
        }
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
