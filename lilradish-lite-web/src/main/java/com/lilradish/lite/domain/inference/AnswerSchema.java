package com.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.AskedField;
import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.declaration.OfferedTerms;
import com.lilradish.lite.domain.declaration.TextLines;
import com.lilradish.lite.domain.wire.CanonicalJson;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonArray;
import com.lilradish.lite.domain.wire.JsonValue.JsonBoolean;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.wire.JsonValue.JsonString;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class AnswerSchema {

    private static final String DATE = "(?!0000)[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])";
    private static final String MOMENT = DATE
            + "T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,6})?"
            + "(?!-00:00)[+-]((0[0-9]|1[0-3]):[0-5][0-9]|14:00)";

    private AnswerSchema() {}

    static String production(List<AskedField> gives) {
        requireNonNull(gives, "Envelope gives must not be null");
        List<JsonMember> confidences = new ArrayList<>();
        for (AskedField field : gives) {
            if (field.kind() != FieldKind.FILE && field.confidenceAsked()) {
                confidences.add(new JsonMember(
                        field.name().value(),
                        new JsonObject(List.of(
                                text("type", "integer"),
                                number("minimum", 0),
                                number("maximum", Confidence.HIGHEST)))));
            }
        }
        return document(object(List.of(
                new JsonMember(Unwrapping.VALUES, fields(gives)),
                new JsonMember(Unwrapping.CONFIDENCES, object(confidences)))));
    }

    static String search(List<AskedField> gives) {
        requireNonNull(gives, "Envelope gives must not be null");
        return document(fields(gives));
    }

    private static String document(JsonObject schema) {
        List<JsonMember> members = new ArrayList<>(schema.members().size() + 1);
        members.add(text("$schema", "https://json-schema.org/draft/2020-12/schema"));
        members.addAll(schema.members());
        return CanonicalJson.write(new JsonObject(members));
    }

    private static JsonObject fields(List<AskedField> fields) {
        return object(fields.stream()
                .filter(field -> field.kind() != FieldKind.FILE)
                .map(field -> new JsonMember(field.name().value(), field(field)))
                .toList());
    }

    private static JsonObject object(List<JsonMember> properties) {
        return new JsonObject(List.of(
                text("type", "object"),
                new JsonMember("properties", new JsonObject(properties)),
                new JsonMember(
                        "required",
                        new JsonArray(properties.stream()
                                .map(property -> (JsonValue) new JsonString(property.name()))
                                .toList())),
                new JsonMember("additionalProperties", new JsonBoolean(false))));
    }

    private static JsonObject field(AskedField field) {
        JsonObject value =
                switch (field.kind()) {
                    case TEXT -> textField(field);
                    case NUMBER -> new JsonObject(List.of(text("type", "number")));
                    case DATE ->
                        new JsonObject(List.of(
                                text("type", "string"), text("format", "date"), text("pattern", "^" + DATE + "$")));
                    case MOMENT ->
                        new JsonObject(List.of(
                                text("type", "string"),
                                text("format", "date-time"),
                                text("pattern", "^" + MOMENT + "$")));
                    case YES_NO -> new JsonObject(List.of(text("type", "boolean")));
                    case TERM -> terms(requireNonNull(field.terms()));
                    case FIELDS -> fields(field.fields());
                    case FILE -> throw new IllegalArgumentException("A file is not sent to a model");
                };
        if (field.most() != null) {
            List<JsonMember> members = new ArrayList<>(
                    List.of(text("type", "array"), new JsonMember("items", value), number("maxItems", field.most())));
            if (field.mustBeGiven()) {
                members.add(number("minItems", 1));
            }
            value = new JsonObject(members);
        }
        return field.mustBeGiven()
                ? value
                : new JsonObject(List.of(new JsonMember(
                        "anyOf", new JsonArray(List.of(value, new JsonObject(List.of(text("type", "null"))))))));
    }

    private static JsonObject textField(AskedField field) {
        List<JsonMember> members =
                new ArrayList<>(List.of(text("type", "string"), number("maxLength", requireNonNull(field.longest()))));
        if (field.lines() == TextLines.ONE) {
            members.add(text("description", "Text of one line."));
            members.add(text("pattern", "^[^\\r\\n\\u000b\\f\\u0085\\u2028\\u2029]*$"));
        } else {
            members.add(text("description", "Text of several lines."));
        }
        return new JsonObject(members);
    }

    private static JsonObject terms(OfferedTerms offered) {
        StringBuilder meaning = new StringBuilder();
        for (OfferedTerms.Offered term : offered.terms()) {
            if (!meaning.isEmpty()) {
                meaning.append('\n');
            }
            meaning.append(CanonicalJson.write(new JsonString(term.term().value())))
                    .append(": ")
                    .append(term.meaning().value());
        }
        if (offered.note() != null) {
            meaning.append("\nOn choosing: ").append(offered.note().value());
        }
        return new JsonObject(List.of(
                text("type", "string"),
                new JsonMember(
                        "enum",
                        new JsonArray(offered.terms().stream()
                                .map(term ->
                                        (JsonValue) new JsonString(term.term().value()))
                                .toList())),
                text("description", meaning.toString())));
    }

    private static JsonMember text(String name, String value) {
        return new JsonMember(name, new JsonString(value));
    }

    private static JsonMember number(String name, int value) {
        return new JsonMember(name, new JsonNumber(BigDecimal.valueOf(value)));
    }
}
