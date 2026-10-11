package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.codestep.ReleasedCodeStep;
import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.DeclarationSide;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.run.InputRecord;
import com.lilradish.lite.domain.run.PlannedStep;
import com.lilradish.lite.domain.run.ProductionValueId;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunPositions;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunState;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.ValueRecord;
import com.lilradish.lite.domain.run.ValueStanding;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.workflow.Binding;
import com.lilradish.lite.domain.workflow.Pointer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read through {@link StepReading} on each call: its {@code with} calls can populate data after construction. */
final class StepGivenBack {

    private final StepReading stepReading;

    private final ShownValues shownValues;

    private final StepSources stepSources;

    private final Map<com.lilradish.lite.domain.run.RunId, RunState> childStates = new HashMap<>();

    private final Map<com.lilradish.lite.domain.run.RunId, List<StepAnswers.CameOutAnswer>> childOutputs =
            new HashMap<>();

    StepGivenBack(StepReading stepReading, ShownValues shownValues, StepSources stepSources) {
        this.stepReading = stepReading;
        this.shownValues = shownValues;
        this.stepSources = stepSources;
    }

    @Nullable
    List<StepAnswers.ShownValueAnswer> gaveBack(StepSnapshot step, @Nullable List<StepAnswers.CameOutAnswer> cameOut) {
        if (step.beneath() != null && stepReading.details() != null) {
            RunSnapshot child = step.beneath().run();
            if (childStates.computeIfAbsent(child.run(), ignored -> RunPositions.state(child, StepPositions.of(child)))
                    != RunState.DONE) {
                return null;
            }
            List<StepAnswers.ShownValueAnswer> shown = new ArrayList<>();
            for (StepAnswers.CameOutAnswer output : cameOut == null ? cameOutBeneath(step) : cameOut) {
                StepAnswers.StandingAnswer standing = output.standing();
                if (standing != null) {
                    shown.add(new StepAnswers.ShownValueAnswer(
                            output.field(),
                            standing.value(),
                            standing.withheld(),
                            CameOutNow.STANDS.published(),
                            standing.earlierShape()));
                }
            }
            return shown.isEmpty() ? null : List.copyOf(shown);
        }
        TryRecord newest = step.newest().filter(TryRecord::yielded).orElse(null);
        ValueReading reading = reading(step.planned());
        if (newest == null || reading == null) {
            return null;
        }
        List<ValueRecord> values = reading.values(newest);
        List<StepAnswers.ShownValueAnswer> shown = new ArrayList<>(values.size());
        for (ValueRecord value : values) {
            ShownValues.Shown written = reading.shown(value);
            shown.add(new StepAnswers.ShownValueAnswer(
                    value.field(),
                    written.value(),
                    written.withheld(),
                    ValueStanding.of(newest, value).published(),
                    written.earlierShape()));
        }
        return shown;
    }

    @Nullable
    List<StepAnswers.CameOutAnswer> cameOut(StepSnapshot step) {
        if (step.beneath() != null) {
            return cameOutBeneath(step);
        }
        ValueReading reading = reading(step.planned());
        if (reading == null || step.tries().stream().noneMatch(TryRecord::yielded)) {
            return null;
        }
        TryRecord newest = step.newest().orElseThrow();
        List<String> fields = reading.fields(newest);
        List<StepAnswers.CameOutAnswer> came = new ArrayList<>(fields.size());
        for (String field : fields) {
            ValueRecord value = newest.yielded() ? valueOf(newest, field) : null;
            if (value == null) {
                came.add(new StepAnswers.CameOutAnswer(field, null, CameOutNow.NONE.published(), null, null, null));
                continue;
            }
            ValueStanding now = ValueStanding.of(newest, value);
            ShownValues.Shown shown = reading.shown(value);
            came.add(new StepAnswers.CameOutAnswer(
                    field,
                    now == ValueStanding.STANDS
                            ? new StepAnswers.StandingAnswer(
                                    requireNonNull(newest.number()),
                                    shown.value(),
                                    shown.withheld(),
                                    shown.earlierShape())
                            : null,
                    CameOutNow.of(now).published(),
                    null,
                    null,
                    null));
        }
        return came;
    }

    private List<StepAnswers.CameOutAnswer> cameOutBeneath(StepSnapshot step) {
        RunInTree child = stepReading.context.beneath(step);
        return childOutputs.computeIfAbsent(child.run().run(), ignored -> cameOutBeneath(child));
    }

    private List<StepAnswers.CameOutAnswer> cameOutBeneath(RunInTree child) {
        Declaration gives = child.run().workflow().gives();
        List<Binding> outputs = child.run().workflow().outputs();
        List<StepInputs.BindingRecord> bound = new ArrayList<>();
        Map<FieldName, List<Binding>> outputsByField = new HashMap<>();
        Map<FieldName, List<StepInputs.BindingRecord>> boundByField = new HashMap<>();
        Set<UUID> boundIds = new HashSet<>();
        for (Binding output : outputs) {
            FieldName first = requireNonNull(output.target()).names().getFirst();
            outputsByField.computeIfAbsent(first, ignored -> new ArrayList<>()).add(output);
            StepInputs.bound(child, output).ifPresent(read -> {
                bound.add(read);
                boundIds.add(output.id());
                boundByField
                        .computeIfAbsent(first, ignored -> new ArrayList<>())
                        .add(read);
            });
        }
        JsonValue.JsonObject values = StepInputs.filled(gives, bound);
        List<FillField> fields =
                FillField.of(gives.fields(), child.run().workflow().lists());
        List<StepAnswers.CameOutAnswer> came = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            List<Binding> forField = outputsByField.getOrDefault(field.name(), List.of());
            if (forField.isEmpty() || forField.stream().anyMatch(output -> !boundIds.contains(output.id()))) {
                came.add(new StepAnswers.CameOutAnswer(
                        field.name().value(), null, CameOutNow.NONE.published(), null, null, null));
                continue;
            }
            List<StepInputs.BindingRecord> fieldBound = boundByField.getOrDefault(field.name(), List.of());
            List<ProductionValueId> sources = new ArrayList<>();
            for (StepInputs.BindingRecord read : fieldBound) {
                sources.addAll(read.sources());
            }
            JsonValue value = StepInputs.within(values, List.of(field.name()));
            ShownValues.Shown shown = shownValues.shown(field, value, sources, partsOf(fieldBound));
            StepInputs.BindingRecord single = fieldBound.size() == 1
                            && requireNonNull(fieldBound.getFirst().binding().target())
                                            .names()
                                            .size()
                                    == 1
                            && fieldBound.getFirst().parts().isEmpty()
                    ? fieldBound.getFirst()
                    : null;
            RunReadDetails read = requireNonNull(stepReading.details(), "A child output needs its read details");
            ProductionValueId source = single == null ? null : single.source();
            RunReadDetails.MakerRecord maker = source == null ? null : read.maker(source);
            Integer number =
                    maker == null ? null : requireNonNull(maker.tryNumber(), "A standing value has a numbered try");
            came.add(new StepAnswers.CameOutAnswer(
                    field.name().value(),
                    new StepAnswers.StandingAnswer(number, shown.value(), shown.withheld(), null),
                    CameOutNow.STANDS.published(),
                    maker == null
                            ? null
                            : new RunsController.NumberedRunAnswer(
                                    maker.run().run().value(), maker.run().number()),
                    single == null ? null : stepSources.from(read.origin(child, single)),
                    single == null ? outputSources(child, gives, field, fieldBound) : null));
        }
        return List.copyOf(came);
    }

    private List<StepAnswers.SourceAnswer> outputSources(
            RunInTree child, Declaration gives, FillField field, List<StepInputs.BindingRecord> bound) {
        RunReadDetails read = requireNonNull(stepReading.details(), "A child output needs its read details");
        List<RunReadDetails.PartSourceRecord> parts = new ArrayList<>();
        for (StepInputs.BindingRecord each : bound) {
            List<FieldName> target = requireNonNull(each.binding().target()).names();
            String prefix = target.size() == 1 ? "" : new Pointer(target.subList(1, target.size())).published();
            if (each.parts().isEmpty()) {
                parts.add(new RunReadDetails.PartSourceRecord(prefix, read.origin(child, each)));
            } else {
                for (RunReadDetails.PartSourceRecord part : read.parts(child, gives, each.binding(), each.parts())) {
                    String path = prefix.isEmpty() ? part.targetPath() : prefix + "." + part.targetPath();
                    parts.add(new RunReadDetails.PartSourceRecord(path, part.from()));
                }
            }
        }
        return RunReadDetails.orderedParts(gives, field.name().value(), parts).stream()
                .map(part -> new StepAnswers.SourceAnswer(part.targetPath(), stepSources.from(part.from())))
                .toList();
    }

    private static List<InputRecord.Part> partsOf(List<StepInputs.BindingRecord> bound) {
        List<InputRecord.Part> parts = new ArrayList<>();
        for (StepInputs.BindingRecord input : bound) {
            List<FieldName> path = requireNonNull(input.binding().target())
                    .names()
                    .subList(1, requireNonNull(input.binding().target()).names().size());
            if (input.parts().isEmpty()) {
                if (!path.isEmpty()) {
                    parts.add(new InputRecord.Part(path, input.source()));
                }
            } else {
                for (InputRecord.Part nested : input.parts()) {
                    List<FieldName> at = new ArrayList<>(path);
                    at.addAll(nested.targetPath());
                    parts.add(new InputRecord.Part(at, nested.source()));
                }
            }
        }
        return parts;
    }

    /* A question's values are read as the version it pins declares them; a code step's by the name each was kept
     * under, since its release may have changed what it declares since they were given. */
    @Nullable
    ValueReading reading(PlannedStep planned) {
        return switch (planned.runs()) {
            case StepRuns.Question question ->
                new QuestionValues(FillField.of(question.gives().fields(), question.lists()));
            case StepRuns.Code code -> new CodeValues(code);
            case StepRuns.Search search -> new SearchValues(search);
            case StepRuns.Workflow ignored -> null;
            case StepRuns.Route ignored -> null;
        };
    }

    private static List<ValueRecord> inDeclaredOrder(TryRecord aTry, List<FillField> fields) {
        List<ValueRecord> ordered = new ArrayList<>(aTry.values().size());
        for (FillField field : fields) {
            ValueRecord value = valueOf(aTry, field.name().value());
            if (value != null) {
                ordered.add(value);
            }
        }
        return ordered;
    }

    private static @Nullable ValueRecord valueOf(TryRecord aTry, String field) {
        return aTry.values().stream()
                .filter(value -> value.field().equals(field))
                .findFirst()
                .orElse(null);
    }

    interface ValueReading {

        /** Each field what came out is said of, a newest try that gave nothing back giving each as none. */
        List<String> fields(TryRecord newest);

        /** What {@code aTry} gave back, in the order read. */
        List<ValueRecord> values(TryRecord aTry);

        ShownValues.Shown shown(ValueRecord value);
    }

    private final class QuestionValues implements ValueReading {

        private final List<FillField> gives;

        QuestionValues(List<FillField> gives) {
            this.gives = gives;
        }

        @Override
        public List<String> fields(TryRecord newest) {
            return gives.stream().map(field -> field.name().value()).toList();
        }

        @Override
        public List<ValueRecord> values(TryRecord aTry) {
            return inDeclaredOrder(aTry, gives);
        }

        @Override
        public ShownValues.Shown shown(ValueRecord value) {
            return shownValues.shown(
                    StepReading.fieldAt(gives, List.of(new FieldName(value.field()))), value.value(), value.id());
        }
    }

    /** A code step's values by the name each was kept under, which the snapshot puts in the release's order. */
    private final class CodeValues implements ValueReading {

        private final StepRuns.Code code;

        CodeValues(StepRuns.Code code) {
            this.code = code;
        }

        @Override
        public List<String> fields(TryRecord newest) {
            if (newest.yielded()) {
                return newest.values().stream().map(ValueRecord::field).toList();
            }
            ReleasedCodeStep released = code.released();
            return released == null
                    ? List.of()
                    : released.declared().gives().fields().stream()
                            .map(field -> field.name().value())
                            .toList();
        }

        @Override
        public List<ValueRecord> values(TryRecord aTry) {
            return aTry.values();
        }

        @Override
        public ShownValues.Shown shown(ValueRecord value) {
            return shownValues.kept(
                    stepSources.fitting(
                            code, DeclarationSide.GIVES, List.of(new FieldName(value.field())), value.value()),
                    value.value(),
                    value.id());
        }
    }

    private final class SearchValues implements ValueReading {
        private final StepRuns.Search search;

        SearchValues(StepRuns.Search search) {
            this.search = search;
        }

        @Override
        public List<String> fields(TryRecord newest) {
            return com.lilradish.lite.domain.search.SearchWay.FIELDS.stream()
                    .map(FieldName::value)
                    .toList();
        }

        @Override
        public List<ValueRecord> values(TryRecord aTry) {
            List<ValueRecord> ordered = new ArrayList<>();
            for (String name : fields(aTry)) {
                ValueRecord value = valueOf(aTry, name);
                if (value != null) {
                    ordered.add(value);
                }
            }
            return ordered;
        }

        @Override
        public ShownValues.Shown shown(ValueRecord value) {
            List<FillField> fields = search.released() == null
                    ? List.of()
                    : FillField.of(search.released().outputs(), Map.of());
            FillField field = WrittenValues.at(fields, List.of(new FieldName(value.field())));
            return shownValues.kept(field, value.value(), value.id());
        }
    }
}
