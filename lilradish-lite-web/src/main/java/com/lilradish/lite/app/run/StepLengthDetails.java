package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.Asking;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.inference.Envelope;
import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.model.SentText;
import com.lilradish.lite.domain.run.InputRecord;
import com.lilradish.lite.domain.run.ProductionValueId;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunPayloads;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepInputs.BindingRecord;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.wire.CanonicalJson;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.workflow.Producer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The actual sent spelling, with every input occurrence counted once and its surrounding text left fixed. */
record StepLengthDetails(SentText sent, long fixedCharacters, List<Row> rows) {

    StepLengthDetails {
        requireNonNull(sent, "StepLengthDetails sent must not be null");
        rows = List.copyOf(rows);
        long accounted = fixedCharacters;
        if (fixedCharacters < 0) {
            throw new IllegalArgumentException("Fixed sent text cannot have a negative length");
        }
        for (Row row : rows) {
            accounted = Math.addExact(accounted, row.characters());
        }
        if (accounted != sent.characters()) {
            throw new IllegalArgumentException("Input contributions and fixed text must equal the sent text");
        }
    }

    /** What this step would send now, shared by its read and a locked refusal of one of its sources. */
    static @Nullable Measured question(RunInTree context, StepSnapshot step, ModelCatalog catalog) {
        if (!(step.planned().runs() instanceof StepRuns.Question question)
                || !(step.planned().producer() instanceof Producer.Model producer)) {
            return null;
        }
        DeployedModel model = catalog.find(producer.choice().model()).orElse(null);
        List<BindingRecord> bound = StepInputs.bound(context, step).orElse(null);
        if (model == null || bound == null) {
            return null;
        }
        StepLengthDetails details = question(
                Envelope.toProduce(Asking.told(question.gives(), question.lists()), question.instruction()),
                RunPayloads.toProduce(
                        context,
                        step.planned(),
                        bound,
                        RunPayloads.refusedInWords(
                                        step, step.newest().orElseThrow().id())
                                .orElse(null)),
                bound);
        return new Measured(model, details);
    }

    record Measured(DeployedModel model, StepLengthDetails details) {}

    static StepLengthDetails question(String envelope, JsonObject payload, List<BindingRecord> bound) {
        SentText sent = SentText.measure(envelope, CanonicalJson.write(payload));
        JsonValue takes = member(payload, "takes");
        List<Occurrence> occurrences = new ArrayList<>();
        for (BindingRecord input : bound) {
            List<FieldName> target = requireNonNull(input.binding().target(), "A question input fills a position")
                    .names();
            if (input.parts().isEmpty()) {
                occurrence(occurrences, takes, input, null, target, input.source());
            } else {
                for (InputRecord.Part part : input.parts()) {
                    List<FieldName> position = new ArrayList<>(target);
                    position.addAll(part.targetPath());
                    occurrence(occurrences, takes, input, part, position, part.source());
                }
            }
        }
        Map<ProductionValueId, List<Occurrence>> produced = new LinkedHashMap<>();
        List<List<Occurrence>> grouped = new ArrayList<>();
        long contributions = 0;
        for (Occurrence occurrence : occurrences) {
            contributions = Math.addExact(contributions, occurrence.characters());
            ProductionValueId source = occurrence.source();
            if (source == null) {
                grouped.add(List.of(occurrence));
            } else {
                List<Occurrence> group = produced.get(source);
                if (group == null) {
                    group = new ArrayList<>();
                    produced.put(source, group);
                    grouped.add(group);
                }
                group.add(occurrence);
            }
        }
        List<Row> rows = new ArrayList<>(grouped.size());
        for (List<Occurrence> group : grouped) {
            long characters = 0;
            for (Occurrence occurrence : group) {
                characters = Math.addExact(characters, occurrence.characters());
            }
            rows.add(new Row(group.getFirst().source(), characters, group));
        }
        rows.sort(Comparator.comparingLong(Row::characters).reversed());
        return new StepLengthDetails(sent, Math.subtractExact(sent.characters(), contributions), rows);
    }

    private static void occurrence(
            List<Occurrence> occurrences,
            JsonValue takes,
            BindingRecord input,
            InputRecord.@Nullable Part part,
            List<FieldName> position,
            @Nullable ProductionValueId source) {
        if (position.isEmpty()) {
            throw new IllegalStateException("An input contribution names its actual position");
        }
        for (Occurrence earlier : occurrences) {
            int common = Math.min(position.size(), earlier.position().size());
            if (position.subList(0, common).equals(earlier.position().subList(0, common))) {
                throw new IllegalStateException("Input contributions must not overlap");
            }
        }
        JsonValue value = takes;
        for (FieldName name : position) {
            if (!(value instanceof JsonObject object)) {
                throw new IllegalStateException("An input position traverses actual object fields");
            }
            value = member(object, name.value());
        }
        long characters = SentText.measure("", CanonicalJson.write(value)).characters();
        occurrences.add(new Occurrence(input, part, source, position, characters));
    }

    private static JsonValue member(JsonObject object, String name) {
        for (JsonValue.JsonMember member : object.members()) {
            if (member.name().equals(name)) {
                return member.value();
            }
        }
        throw new IllegalStateException("The actual sent payload has no field " + name);
    }

    /** One actual source block; its position is structural, never parsed back from a display label. */
    record Occurrence(
            BindingRecord input,
            InputRecord.@Nullable Part part,
            @Nullable ProductionValueId source,
            List<FieldName> position,
            long characters) {
        Occurrence {
            position = List.copyOf(position);
        }
    }

    /** All the positions one producer fills, or one constant or starting input without a producer. */
    record Row(@Nullable ProductionValueId source, long characters, List<Occurrence> occurrences) {
        Row {
            occurrences = List.copyOf(occurrences);
        }
    }
}
