package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.codestep.ReleasedCodeStep;
import com.lilradish.lite.domain.declaration.AskedField;
import com.lilradish.lite.domain.declaration.Asking;
import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.DeclarationSide;
import com.lilradish.lite.domain.declaration.Demands;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.declaration.OfferedTerms;
import com.lilradish.lite.domain.file.StoredFile;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.inference.GivenBack;
import com.lilradish.lite.domain.inference.Unwrapping;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.run.PlannedStep;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepPosition;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.workflow.Binding;
import com.lilradish.lite.domain.workflow.BindingSource;
import com.lilradish.lite.domain.workflow.Pointer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What went into a step and where each value came from, as its reader is answered. Files held for this read are
 * supplied here; shared tree details are read when needed from the reading this is part of.
 */
final class StepSources {

    private final StepReading stepReading;

    private final ShownValues shownValues;

    private Map<UUID, StoredFile> heldFiles = Map.of();

    StepSources(StepReading stepReading, ShownValues shownValues) {
        this.stepReading = stepReading;
        this.shownValues = shownValues;
    }

    void withHeldFiles(Map<UUID, StoredFile> heldFiles) {
        this.heldFiles = Map.copyOf(heldFiles);
    }

    List<StepAnswers.TakesFromAnswer> takesFrom(PlannedStep planned) {
        List<StepAnswers.TakesFromAnswer> taken =
                new ArrayList<>(planned.bindings().size());
        for (Binding binding : planned.bindings()) {
            Pointer target = binding.target();
            if (target != null) {
                taken.add(new StepAnswers.TakesFromAnswer(target.published(), from(binding)));
            }
        }
        return taken;
    }

    private StepAnswers.FromAnswer from(Binding binding) {
        return from(binding.source());
    }

    private StepAnswers.FromAnswer from(BindingSource bindingSource) {
        return switch (bindingSource) {
            case BindingSource.WorkflowInput input ->
                new StepAnswers.FromAnswer(
                        FromKind.RUN_INPUT.published(),
                        input.pointer().published(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
            case BindingSource.StepOutput output -> {
                PlannedStep source = stepReading
                        .run
                        .workflow()
                        .step(new WorkflowStepId(output.step()))
                        .orElseThrow();
                yield new StepAnswers.FromAnswer(
                        FromKind.STEP.published(),
                        output.pointer().published(),
                        output.step(),
                        source.name().value(),
                        source.label() == null ? null : source.label().value(),
                        versionOf(source.runs()),
                        source.runs() instanceof StepRuns.Code code ? code.codeStep() : null,
                        null,
                        null,
                        source.runs() instanceof StepRuns.Search search ? search.way() : null);
            }
            case BindingSource.Written ignored ->
                new StepAnswers.FromAnswer(
                        FromKind.CONSTANT.published(), null, null, null, null, null, null, null, null, null);
        };
    }

    StepAnswers.FromAnswer from(RunReadDetails.OriginRecord origin) {
        return from(requireNonNull(stepReading.details(), "A source needs its read details"), origin);
    }

    static StepAnswers.FromAnswer from(RunReadDetails read, RunReadDetails.OriginRecord origin) {
        RunReadDetails.NumberedRunRecord numbered = origin.run();
        RunReadDetails.MakerRecord maker = origin.maker();
        if (maker == null) {
            return new StepAnswers.FromAnswer(
                    origin.kind(),
                    origin.path(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    numbered == null ? null : numbered.run().value(),
                    numbered == null ? null : numbered.number(),
                    null);
        }
        PlannedStep source = read.planned(maker);
        return new StepAnswers.FromAnswer(
                FromKind.STEP.published(),
                maker.field(),
                maker.stepId(),
                maker.name(),
                source.label() == null ? null : source.label().value(),
                versionOf(source.runs()),
                source.runs() instanceof StepRuns.Code code ? code.codeStep() : null,
                maker.run().run().value(),
                maker.run().number(),
                source.runs() instanceof StepRuns.Search search ? search.way() : null);
    }

    private static @Nullable UUID versionOf(StepRuns runs) {
        return switch (runs) {
            case StepRuns.Question question -> question.pinned().version().value();
            case StepRuns.Workflow workflow -> workflow.pinned().version().value();
            case StepRuns.Code ignored -> null;
            case StepRuns.Search search -> search.question().pinned().version().value();
            case StepRuns.Route ignored -> null;
        };
    }

    /** For a reached step, what its newest try took, or what would go in now where it has none. */
    @Nullable
    WentIn wentIn(int index, StepSnapshot step) {
        if (stepReading.positions.get(index) instanceof StepPosition.NotStarted) {
            return null;
        }
        StepRuns runs = step.planned().runs();
        if (!(runs instanceof StepRuns.Question || runs instanceof StepRuns.Search || runs instanceof StepRuns.Code)) {
            return null;
        }
        TryRecord newest = step.newest().orElse(null);
        if (newest != null
                && (newest.yielded()
                        || newest.attempts().stream()
                                .anyMatch(attempt -> attempt.purpose()
                                                == com.lilradish.lite.domain.inference.ModelCallPurpose.PRODUCE
                                        && newest.calls().stream()
                                                .anyMatch(call -> call.attempt().equals(attempt.id()))))) {
            List<Optional<StepInputs.BindingRecord>> taken =
                    StepInputs.tookForReading(stepReading.context, step, newest);
            List<StepAnswers.WentInAnswer> answers = new ArrayList<>(taken.size());
            for (int at = 0; at < taken.size(); at++) {
                Optional<StepInputs.BindingRecord> read = taken.get(at);
                if (read.isPresent()) {
                    answers.add(wentIn(runs, List.of(read.get())).getFirst());
                } else {
                    Binding binding = step.planned().bindings().get(at);
                    answers.add(new StepAnswers.WentInAnswer(
                            requireNonNull(binding.target()).published(),
                            from(binding),
                            null,
                            Boolean.TRUE,
                            null,
                            null));
                }
            }
            return new WentIn(List.copyOf(answers), WentInFrom.TRY);
        }
        Optional<List<StepInputs.BindingRecord>> live = StepInputs.bound(stepReading.context, step);
        if (live.isEmpty()) {
            return null;
        }
        return new WentIn(wentIn(runs, live.get()), WentInFrom.NOT_YET_SENT);
    }

    private List<StepAnswers.WentInAnswer> wentIn(StepRuns runs, List<StepInputs.BindingRecord> read) {
        Declaration declared = runs instanceof StepRuns.Question question
                ? question.takes()
                : runs instanceof StepRuns.Search search
                        ? search.question().takes()
                        : runs instanceof StepRuns.Code code && code.released() != null
                                ? code.released().declared().takes()
                                : null;
        List<FillField> takes = runs instanceof StepRuns.Question question
                ? FillField.of(question.takes().fields(), question.lists())
                : runs instanceof StepRuns.Search search
                        ? FillField.of(
                                search.question().takes().fields(),
                                search.question().lists())
                        : null;
        List<StepAnswers.WentInAnswer> inputs = new ArrayList<>(read.size());
        for (StepInputs.BindingRecord bound : read) {
            Pointer target = bound.binding().target();
            if (target != null) {
                ShownValues.Shown shown = bound.parts().isEmpty() && shownValues.withheld(bound.sources())
                        ? new ShownValues.Shown(null, Boolean.TRUE, null)
                        : runs instanceof StepRuns.Code code
                                ? shownValues.kept(
                                        fitting(code, DeclarationSide.TAKES, target.names(), bound.value()),
                                        bound.value(),
                                        bound.sources(),
                                        bound.parts())
                                : shownValues.shown(
                                        StepReading.fieldAt(requireNonNull(takes), target.names()),
                                        bound.value(),
                                        bound.sources(),
                                        bound.parts());
                List<StepAnswers.SourceAnswer> sources = bound.parts().isEmpty() ? null : sources(bound, declared);
                inputs.add(new StepAnswers.WentInAnswer(
                        target.published(),
                        sources == null ? actualFrom(bound) : null,
                        shown.value(),
                        shown.withheld(),
                        shown.earlierShape(),
                        sources));
            }
        }
        return inputs;
    }

    private StepAnswers.FromAnswer actualFrom(StepInputs.BindingRecord bound) {
        RunReadDetails read = stepReading.details();
        if (read != null) {
            return from(read.origin(stepReading.context, bound));
        }
        return actualFrom(from(bound.binding()));
    }

    private StepAnswers.FromAnswer actualFrom(StepAnswers.FromAnswer declared) {
        if (FromKind.CONSTANT.published().equals(declared.kind())) {
            return declared;
        }
        return new StepAnswers.FromAnswer(
                declared.kind(),
                declared.path(),
                declared.stepId(),
                declared.name(),
                declared.label(),
                declared.versionId(),
                declared.codeStep(),
                stepReading.run.run().value(),
                stepReading.header.number(),
                declared.searchWay());
    }

    private List<StepAnswers.SourceAnswer> sources(StepInputs.BindingRecord bound, @Nullable Declaration declared) {
        return requireNonNull(stepReading.details(), "A composite input needs its run details")
                .parts(stepReading.context, declared, bound.binding(), bound.parts())
                .stream()
                .map(part -> new StepAnswers.SourceAnswer(part.targetPath(), from(part.from())))
                .toList();
    }

    /**
     * The field at {@code names} as the release declares it now, exactly where {@code value} fits it as code giving it
     * back now would be read: none where none is declared, its list is not here, or the value no longer fits.
     */
    @Nullable
    FillField fitting(StepRuns.Code code, DeclarationSide side, List<FieldName> names, JsonValue value) {
        ReleasedCodeStep released = code.released();
        if (released == null) {
            return null;
        }
        Declaration half = side == DeclarationSide.TAKES
                ? released.declared().takes()
                : released.declared().gives();
        Field first = Field.named(half.fields(), names.getFirst());
        if (first == null || !listsHeld(first, released.lists())) {
            return null;
        }
        List<Field> alone = List.of(first);
        AskedField asked = Asking.told(new Declaration(side, Demands.ofQuestion(side), alone), released.lists())
                .getFirst();
        for (FieldName name : names.subList(1, names.size())) {
            asked = asked.fields().stream()
                    .filter(inner -> inner.name().equals(name))
                    .findFirst()
                    .orElse(null);
            if (asked == null) {
                return null;
            }
        }
        JsonValue.JsonObject given = new JsonValue.JsonObject(
                List.of(new JsonValue.JsonMember(asked.name().value(), value)));
        if (!(Unwrapping.givenAfterFileFillings(List.of(asked), given) instanceof GivenBack.Fits)) {
            return null;
        }
        FillField current = WrittenValues.at(FillField.of(alone, released.lists()), names);
        return current != null && storedFilesFit(current, value) ? current : null;
    }

    private boolean storedFilesFit(FillField field, JsonValue value) {
        if (value instanceof JsonValue.JsonArray many) {
            return many.items().stream().allMatch(item -> storedFilesFit(field, item));
        }
        if (field.kind() == FieldKind.FILE && value instanceof JsonValue.JsonObject) {
            StoredFile file = StoredFile.fromKept(value);
            return file.equals(heldFiles.get(file.id()))
                    && requireNonNull(field.fileTypes()).stream().anyMatch(extension -> extension.accepts(file.name()))
                    && file.size() <= requireNonNull(field.maximumBytes());
        }
        if (field.kind() == FieldKind.FIELDS && value instanceof JsonValue.JsonObject object) {
            for (JsonValue.JsonMember member : object.members()) {
                FillField inner = field.fields().stream()
                        .filter(candidate -> candidate.name().value().equals(member.name()))
                        .findFirst()
                        .orElse(null);
                if (inner != null && !storedFilesFit(inner, member.value())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean listsHeld(Field field, Map<EntryVersionId, OfferedTerms> lists) {
        return switch (field.shape()) {
            case FieldShape.Term term -> term.list() == null || lists.containsKey(term.list());
            case FieldShape.Nested nested -> nested.fields().stream().allMatch(inner -> listsHeld(inner, lists));
            case FieldShape.Text ignored -> true;
            case FieldShape.File ignored -> true;
            case FieldShape.Plain ignored -> true;
        };
    }

    record WentIn(List<StepAnswers.WentInAnswer> inputs, WentInFrom from) {}
}
