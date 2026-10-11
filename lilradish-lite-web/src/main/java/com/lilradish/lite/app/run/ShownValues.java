package com.lilradish.lite.app.run;

import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.run.InputRecord;
import com.lilradish.lite.domain.run.ProductionValueId;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.ValueRecord;
import com.lilradish.lite.domain.wire.CanonicalJson;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.workflow.StepProducer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A value of a run's tree as one reader is shown it: whole, or withheld wherever what a model made reaches it and
 * the reader may not read what a model said, saying that it was.
 */
final class ShownValues {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final JsonValue.JsonObject WITHHELD_HISTORICAL =
            new JsonValue.JsonObject(List.of(new JsonValue.JsonMember("withheld", new JsonValue.JsonBoolean(true))));

    private final RunInTree context;

    private final boolean readsModels;

    private final Map<ProductionValueId, Produced> produced = new HashMap<>();

    private boolean indexedTree;

    ShownValues(RunInTree context, boolean readsModels) {
        this.context = context;
        this.readsModels = readsModels;
        if (!readsModels) {
            rememberOwn(context.run());
        }
    }

    private void rememberOwn(RunSnapshot current) {
        for (StepSnapshot step : current.steps()) {
            for (TryRecord aTry : step.tries()) {
                for (ValueRecord value : aTry.values()) {
                    produced.put(value.id(), new Produced(aTry, value));
                }
            }
        }
    }

    private void rememberTree(RunSnapshot current) {
        rememberOwn(current);
        for (StepSnapshot step : current.steps()) {
            if (step.beneath() != null) {
                rememberTree(step.beneath().run());
            }
        }
    }

    Shown shown(FillField field, JsonValue value, @Nullable ProductionValueId source) {
        return shown(field, value, source == null ? List.of() : List.of(source));
    }

    private Shown shown(FillField field, JsonValue value, List<ProductionValueId> sources) {
        return shown(field, value, sources, List.of());
    }

    Shown shown(FillField field, JsonValue value, List<ProductionValueId> sources, List<InputRecord.Part> parts) {
        if ((parts.isEmpty() || field.kind() == FieldKind.FILE) && withheld(sources)) {
            return new Shown(null, Boolean.TRUE, null);
        }
        if (!parts.isEmpty()) {
            return new Shown(withheldParts(field, WrittenValues.of(field, value), parts), null, null);
        }
        return new Shown(WrittenValues.of(field, value), null, null);
    }

    /* Pass null for an absent or non-fitting field to select the kept-value form; a non-null field must fit. */
    Shown kept(@Nullable FillField field, JsonValue value, @Nullable ProductionValueId source) {
        return kept(field, value, source == null ? List.of() : List.of(source));
    }

    private Shown kept(@Nullable FillField field, JsonValue value, List<ProductionValueId> sources) {
        return kept(field, value, sources, List.of());
    }

    Shown kept(
            @Nullable FillField field, JsonValue value, List<ProductionValueId> sources, List<InputRecord.Part> parts) {
        if ((parts.isEmpty() || (field != null && field.kind() == FieldKind.FILE)) && withheld(sources)) {
            return new Shown(null, Boolean.TRUE, null);
        }
        if (field != null && !parts.isEmpty()) {
            return new Shown(withheldParts(field, WrittenValues.of(field, value), parts), null, null);
        }
        if (field != null) {
            return new Shown(WrittenValues.of(field, value), null, null);
        }
        Set<ProductionValueId> partSources = new HashSet<>();
        parts.stream()
                .map(InputRecord.Part::source)
                .filter(java.util.Objects::nonNull)
                .forEach(partSources::add);
        boolean unknownPart = false;
        for (ProductionValueId source : sources) {
            if (withheld(List.of(source)) && !partSources.contains(source)) {
                unknownPart = true;
            }
        }
        HistoricalMask mask = new HistoricalMask();
        for (InputRecord.Part part : parts) {
            if (part.source() != null && withheld(List.of(part.source()))) {
                mask.add(part.targetPath());
            }
        }
        if (unknownPart) {
            return new Shown(null, Boolean.TRUE, null);
        }
        JsonValue kept = value;
        try {
            kept = withheldHistorical(kept, mask);
        } catch (IllegalStateException ignored) {
            return new Shown(null, Boolean.TRUE, null);
        }
        return new Shown(
                kept instanceof JsonValue.JsonNull ? NODES.nullNode() : NODES.stringNode(CanonicalJson.write(kept)),
                null,
                Boolean.TRUE);
    }

    private static final class HistoricalMask {

        private boolean hide;
        private final Map<String, HistoricalMask> children = new HashMap<>();

        private void add(List<FieldName> path) {
            HistoricalMask here = this;
            for (FieldName name : path) {
                here = here.children.computeIfAbsent(name.value(), ignored -> new HistoricalMask());
            }
            here.hide = true;
        }
    }

    private static JsonValue withheldHistorical(JsonValue value, HistoricalMask mask) {
        if (mask.hide) {
            return WITHHELD_HISTORICAL;
        }
        if (mask.children.isEmpty()) {
            return value;
        }
        if (value instanceof JsonValue.JsonArray array) {
            if (array.items().isEmpty()) {
                throw new IllegalStateException("A historical component source names no stored part");
            }
            return new JsonValue.JsonArray(array.items().stream()
                    .map(item -> withheldHistorical(item, mask))
                    .toList());
        }
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw new IllegalStateException("A historical component source names no stored value");
        }
        List<JsonValue.JsonMember> members = new ArrayList<>(object.members().size());
        Set<String> missing = new HashSet<>(mask.children.keySet());
        for (JsonValue.JsonMember member : object.members()) {
            HistoricalMask beneath = mask.children.get(member.name());
            members.add(
                    beneath == null
                            ? member
                            : new JsonValue.JsonMember(member.name(), withheldHistorical(member.value(), beneath)));
            missing.remove(member.name());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("A historical component source names no stored part");
        }
        return new JsonValue.JsonObject(members);
    }

    private JsonNode withheldParts(FillField field, JsonNode value, List<InputRecord.Part> parts) {
        JsonNode shown = value;
        for (InputRecord.Part part : parts) {
            if (part.source() != null && withheld(List.of(part.source()))) {
                shown = withheldAt(field, shown, part.targetPath(), 0);
            }
        }
        return shown;
    }

    private static boolean withheldSource(boolean readsModels, StepProducer producer) {
        return !readsModels && producer == StepProducer.MODEL;
    }

    private static JsonNode withheldAt(FillField field, JsonNode value, List<FieldName> path, int depth) {
        if (value instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, withheldAt(field, array.get(index), path, depth));
            }
            return array;
        }
        if (depth == path.size() || field.kind() == FieldKind.FILE) {
            return withheldLeaves(field, value);
        }
        if (field.kind() != FieldKind.FIELDS || !(value instanceof ObjectNode object)) {
            throw new IllegalStateException("A component source names no nested field");
        }
        FieldName name = path.get(depth);
        FillField child = field.fields().stream()
                .filter(each -> each.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A component source names no declared field"));
        object.set(name.value(), withheldAt(child, object.get(name.value()), path, depth + 1));
        return object;
    }

    private static JsonNode withheldLeaves(FillField field, JsonNode value) {
        if (value instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, withheldLeaves(field, array.get(index)));
            }
            return array;
        }
        if (value.isNull()) {
            return NODES.objectNode().put("withheld", true);
        }
        if (field.kind() == FieldKind.FIELDS) {
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("A nested value holds no declared fields");
            }
            for (FillField child : field.fields()) {
                object.set(
                        child.name().value(),
                        withheldLeaves(child, object.get(child.name().value())));
            }
            return object;
        }
        return NODES.objectNode().put("withheld", true);
    }

    boolean withheld(List<ProductionValueId> sources) {
        boolean hide = false;
        for (ProductionValueId source : sources) {
            Produced from = produced.get(source);
            if (from == null && !indexedTree) {
                RunInTree root = context;
                while (root.above() != null) {
                    root = root.above();
                }
                rememberTree(root.run());
                indexedTree = true;
                from = produced.get(source);
            }
            if (from == null) {
                throw new IllegalStateException("A bound value names no production in its run tree");
            }
            if (withheldSource(readsModels, from.aTry().producer())) {
                hide = true;
            }
        }
        return hide;
    }

    static ObjectNode withheldParts(
            List<FillField> fields,
            ObjectNode value,
            List<InputRecord.Part> parts,
            Set<GroupPermission> permitted,
            Map<ProductionValueId, StepProducer> producers) {
        boolean readsModels = permitted.contains(GroupPermission.READ_INFERENCE_CONTENT);
        for (InputRecord.Part part : parts) {
            ProductionValueId source = part.source();
            if (source == null) {
                continue;
            }
            StepProducer producer = producers.get(source);
            if (producer == null) {
                throw new IllegalStateException("A bound value names no production in its run tree");
            }
            if (!withheldSource(readsModels, producer)) {
                continue;
            }
            List<FieldName> path = part.targetPath();
            FieldName first = path.getFirst();
            FillField field = fields.stream()
                    .filter(each -> each.name().equals(first))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("A component source names no declared field"));
            value.set(first.value(), withheldAt(field, value.get(first.value()), path, 1));
        }
        return value;
    }

    static Map<ProductionValueId, StepProducer> producersIn(RunInTree context) {
        RunInTree root = context;
        while (root.above() != null) {
            root = root.above();
        }
        Map<ProductionValueId, StepProducer> found = new HashMap<>();
        rememberProducers(root.run(), found);
        return Map.copyOf(found);
    }

    private static void rememberProducers(RunSnapshot run, Map<ProductionValueId, StepProducer> found) {
        for (StepSnapshot step : run.steps()) {
            for (TryRecord aTry : step.tries()) {
                for (ValueRecord value : aTry.values()) {
                    if (found.putIfAbsent(value.id(), aTry.producer()) != null) {
                        throw new IllegalStateException("A run tree repeats a produced value");
                    }
                }
            }
            if (step.beneath() != null) {
                rememberProducers(step.beneath().run(), found);
            }
        }
    }

    private record Produced(TryRecord aTry, ValueRecord value) {}

    /**
     * @param value none where it is withheld
     * @param earlierShape present where it is given as kept, being of a shape its field does not declare
     */
    record Shown(
            @Nullable JsonNode value,
            @Nullable Boolean withheld,
            @Nullable Boolean earlierShape) {}
}
