package com.lilradish.lite.app.files;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.filling.ValueProblemsRefusal;
import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.filling.FillOutcome;
import com.lilradish.lite.domain.filling.FillPath;
import com.lilradish.lite.domain.filling.FillProblems;
import com.lilradish.lite.domain.filling.FilledFields;
import com.lilradish.lite.domain.filling.Filling;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonArray;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.wire.JsonValue.JsonNull;
import com.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.wire.JsonValue.JsonString;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;

public final class FileFillings {

    private static final JsonString PRESENT = new JsonString("x");

    private FileFillings() {}

    /** The only application path that can turn file upload or keep markers into stored filling values. */
    public static FillOutcome of(
            RunId run,
            GroupId group,
            List<FillField> fields,
            JsonValue sent,
            Map<FillPath, RunFiles.VerifiedBatch> batches) {
        requireNonNull(run, "FileFillings run must not be null");
        requireNonNull(group, "FileFillings group must not be null");
        requireNonNull(fields, "FileFillings fields must not be null");
        requireNonNull(sent, "FileFillings sent must not be null");
        requireNonNull(batches, "FileFillings batches must not be null");
        Set<FillPath> used = new HashSet<>();
        Set<Integer> uploads = new HashSet<>();
        JsonObject safe = level(fields, sent, List.of(), batches, used, uploads, run, group);
        if (safe == null || used.size() != batches.size()) {
            return declined(batches, new FillOutcome.Unshaped());
        }
        FillOutcome checked = Filling.of(shadow(fields), safe);
        if (!(checked instanceof FilledFields filled)) {
            return declined(batches, checked);
        }
        JsonObject kept = restore(fields, filled.values(), List.of(), batches);
        try {
            Filling.wire(fields, kept);
        } catch (IllegalStateException refused) {
            return declined(batches, new FillOutcome.Unshaped());
        }
        for (RunFiles.VerifiedBatch batch : batches.values()) {
            batch.accept();
        }
        return new FilledFields(kept);
    }

    /** Refuse values known to be invalid before an upload stream is read. */
    public static void beforeFileStreams(List<FillField> fields, JsonValue sent) {
        requireNonNull(fields, "FileFillings fields must not be null");
        requireNonNull(sent, "FileFillings sent must not be null");
        JsonObject safe = preflightLevel(fields, sent);
        if (safe == null) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, "A file value was not accepted.");
        }
        FillOutcome checked = Filling.of(shadow(fields), safe);
        if (checked instanceof FillProblems problems) {
            throw new ValueProblemsRefusal(problems);
        }
        if (!(checked instanceof FilledFields)) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, "A file value was not accepted.");
        }
    }

    private static @Nullable JsonObject preflightLevel(List<FillField> fields, JsonValue sent) {
        if (!(sent instanceof JsonObject object) || object.members().size() != fields.size()) {
            return null;
        }
        Map<String, JsonValue> named = new HashMap<>();
        for (JsonMember member : object.members()) {
            if (named.putIfAbsent(member.name(), member.value()) != null) {
                return null;
            }
        }
        List<JsonMember> safe = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            JsonValue given = named.get(field.name().value());
            if (given == null) {
                return null;
            }
            JsonValue value = preflightValue(field, given);
            if (value == null) {
                return null;
            }
            safe.add(new JsonMember(field.name().value(), value));
        }
        return new JsonObject(safe);
    }

    private static @Nullable JsonValue preflightValue(FillField field, JsonValue given) {
        if (field.kind() == FieldKind.FILE) {
            if (field.most() == null) {
                return given instanceof JsonNull ? given : preflightMarker(given);
            }
            if (!(given instanceof JsonArray array)) {
                return null;
            }
            List<JsonValue> safe = new ArrayList<>(array.items().size());
            for (JsonValue item : array.items()) {
                JsonValue marker = preflightMarker(item);
                if (marker == null) {
                    return null;
                }
                safe.add(marker);
            }
            return new JsonArray(safe);
        }
        if (field.kind() != FieldKind.FIELDS || given instanceof JsonNull) {
            return given;
        }
        if (field.most() == null) {
            return preflightLevel(field.fields(), given);
        }
        if (!(given instanceof JsonArray array)) {
            return null;
        }
        List<JsonValue> safe = new ArrayList<>(array.items().size());
        for (JsonValue item : array.items()) {
            JsonObject nested = preflightLevel(field.fields(), item);
            if (nested == null) {
                return null;
            }
            safe.add(nested);
        }
        return new JsonArray(safe);
    }

    private static @Nullable JsonValue preflightMarker(JsonValue given) {
        if (!(given instanceof JsonObject object)) {
            return null;
        }
        Map<String, JsonValue> named = new HashMap<>();
        for (JsonMember member : object.members()) {
            if (named.putIfAbsent(member.name(), member.value()) != null) {
                return null;
            }
        }
        if (named.size() == 2
                && named.get("upload") instanceof JsonNumber number
                && named.get("name") instanceof JsonString
                && markerIndex(number)) {
            return PRESENT;
        }
        if (named.size() == 1 && named.get("keep") instanceof JsonNumber number && markerIndex(number)) {
            return PRESENT;
        }
        return null;
    }

    private static boolean markerIndex(JsonNumber number) {
        try {
            int index = number.value().intValueExact();
            return index >= 0 && BigDecimal.valueOf(index).equals(number.value());
        } catch (ArithmeticException invalid) {
            return false;
        }
    }

    private static FillOutcome declined(Map<FillPath, RunFiles.VerifiedBatch> batches, FillOutcome outcome) {
        if (batches.isEmpty()) {
            return outcome;
        }
        batches.values().forEach(RunFiles.VerifiedBatch::fail);
        if (outcome instanceof FillProblems problems) {
            throw new ValueProblemsRefusal(problems);
        }
        throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, "A file value was not accepted.");
    }

    private static List<FillField> shadow(List<FillField> fields) {
        return fields.stream().map(FileFillings::shadow).toList();
    }

    private static FillField shadow(FillField field) {
        if (field.kind() == FieldKind.FILE) {
            return new FillField(
                    field.name(),
                    field.label(),
                    field.help(),
                    FieldKind.TEXT,
                    1,
                    field.most(),
                    field.mustBeGiven(),
                    null,
                    List.of());
        }
        if (field.kind() == FieldKind.FIELDS) {
            return new FillField(
                    field.name(),
                    field.label(),
                    field.help(),
                    FieldKind.FIELDS,
                    null,
                    field.most(),
                    field.mustBeGiven(),
                    null,
                    shadow(field.fields()));
        }
        return field;
    }

    private static @Nullable JsonObject level(
            List<FillField> fields,
            JsonValue sent,
            List<FillPath.Step> parent,
            Map<FillPath, RunFiles.VerifiedBatch> batches,
            Set<FillPath> used,
            Set<Integer> uploads,
            RunId run,
            GroupId group) {
        if (!(sent instanceof JsonObject object) || object.members().size() != fields.size()) {
            return null;
        }
        Map<String, JsonValue> named = new HashMap<>();
        for (JsonMember member : object.members()) {
            named.put(member.name(), member.value());
        }
        List<JsonMember> kept = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            JsonValue given = named.get(field.name().value());
            if (given == null) {
                return null;
            }
            List<FillPath.Step> steps = append(parent, new FillPath.Named(field.name()));
            JsonValue read = value(field, given, steps, batches, used, uploads, run, group);
            if (read == null) {
                return null;
            }
            kept.add(new JsonMember(field.name().value(), read));
        }
        return new JsonObject(kept);
    }

    private static @Nullable JsonValue value(
            FillField field,
            JsonValue given,
            List<FillPath.Step> steps,
            Map<FillPath, RunFiles.VerifiedBatch> batches,
            Set<FillPath> used,
            Set<Integer> uploads,
            RunId run,
            GroupId group) {
        if (field.kind() == FieldKind.FILE) {
            FillPath path = new FillPath(steps);
            RunFiles.VerifiedBatch batch = batches.get(path);
            if (batch != null) {
                used.add(path);
                batch.check();
                if (!batch.run().equals(run)
                        || !batch.group().equals(group)
                        || !batch.field().equals(field)) {
                    return null;
                }
            }
            return file(field, given, batch, uploads);
        }
        if (field.kind() != FieldKind.FIELDS) {
            return given;
        }
        if (given instanceof JsonNull) {
            return given;
        }
        if (field.most() == null) {
            return level(field.fields(), given, steps, batches, used, uploads, run, group);
        }
        if (!(given instanceof JsonArray array)) {
            return null;
        }
        List<JsonValue> items = new ArrayList<>(array.items().size());
        for (int index = 0; index < array.items().size(); index++) {
            JsonObject nested = level(
                    field.fields(),
                    array.items().get(index),
                    append(steps, new FillPath.Place(index)),
                    batches,
                    used,
                    uploads,
                    run,
                    group);
            if (nested == null) {
                return null;
            }
            items.add(nested);
        }
        return new JsonArray(items);
    }

    private static @Nullable JsonValue file(
            FillField field, JsonValue given, RunFiles.@Nullable VerifiedBatch batch, Set<Integer> uploads) {
        Set<Integer> keeps = new HashSet<>();
        List<RunFiles.VerifiedItem> items = batch == null ? List.of() : batch.items();
        if (field.most() == null) {
            if (given instanceof JsonNull) {
                return items.isEmpty() ? given : null;
            }
            return items.size() == 1 && marker(given, items.getFirst(), uploads, keeps) ? PRESENT : null;
        }
        if (!(given instanceof JsonArray array) || array.items().size() != items.size()) {
            return null;
        }
        for (int index = 0; index < items.size(); index++) {
            if (!marker(array.items().get(index), items.get(index), uploads, keeps)) {
                return null;
            }
        }
        return new JsonArray(items.stream().map(ignored -> (JsonValue) PRESENT).toList());
    }

    private static boolean marker(
            JsonValue given, RunFiles.VerifiedItem item, Set<Integer> uploads, Set<Integer> keeps) {
        if (!(given instanceof JsonObject object)) {
            return false;
        }
        Map<String, JsonValue> named = new HashMap<>();
        for (JsonMember member : object.members()) {
            named.put(member.name(), member.value());
        }
        String markerName = item.upload() ? "upload" : "keep";
        int expectedMembers = item.upload() ? 2 : 1;
        if (object.members().size() != expectedMembers
                || named.size() != expectedMembers
                || !(named.get(markerName) instanceof JsonNumber number)
                || !BigDecimal.valueOf(item.markerIndex()).equals(number.value())) {
            return false;
        }
        if (item.upload()) {
            return named.get("name") instanceof JsonString name
                    && name.value().equals(item.file().name().value())
                    && uploads.add(item.markerIndex());
        }
        return keeps.add(item.markerIndex());
    }

    private static JsonObject restore(
            List<FillField> fields,
            JsonObject checked,
            List<FillPath.Step> parent,
            Map<FillPath, RunFiles.VerifiedBatch> batches) {
        Map<String, JsonValue> named = new HashMap<>();
        for (JsonMember member : checked.members()) {
            named.put(member.name(), member.value());
        }
        List<JsonMember> kept = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            List<FillPath.Step> steps = append(parent, new FillPath.Named(field.name()));
            JsonValue value = requireNonNull(named.get(field.name().value()));
            if (field.kind() == FieldKind.FILE) {
                RunFiles.VerifiedBatch batch = batches.get(new FillPath(steps));
                List<RunFiles.VerifiedItem> items = batch == null ? List.of() : batch.items();
                value = field.most() == null
                        ? items.isEmpty()
                                ? new JsonNull()
                                : items.getFirst().file().kept()
                        : new JsonArray(items.stream()
                                .map(item -> item.file().kept())
                                .map(JsonValue.class::cast)
                                .toList());
            } else if (field.kind() == FieldKind.FIELDS && value instanceof JsonObject object) {
                value = restore(field.fields(), object, steps, batches);
            } else if (field.kind() == FieldKind.FIELDS && value instanceof JsonArray array) {
                List<JsonValue> children = new ArrayList<>(array.items().size());
                for (int index = 0; index < array.items().size(); index++) {
                    children.add(restore(
                            field.fields(),
                            (JsonObject) array.items().get(index),
                            append(steps, new FillPath.Place(index)),
                            batches));
                }
                value = new JsonArray(children);
            }
            kept.add(new JsonMember(field.name().value(), value));
        }
        return new JsonObject(kept);
    }

    private static List<FillPath.Step> append(List<FillPath.Step> parent, FillPath.Step next) {
        List<FillPath.Step> result = new ArrayList<>(parent.size() + 1);
        result.addAll(parent);
        result.add(next);
        return result;
    }
}
