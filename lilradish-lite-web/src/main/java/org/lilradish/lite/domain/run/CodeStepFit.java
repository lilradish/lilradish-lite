package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.codestep.CodeErrorReason;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldFit;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.search.SearchWay;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.BindingSource;
import org.lilradish.lite.domain.workflow.ConstantFit;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.SearchQuestion;
import org.lilradish.lite.domain.workflow.SearchQuestionPart;

/**
 * Whether what a version binds into a code step, and reads out of it, still matches what the running release
 * declares of it now: a release may change a code step under a version in service. Worked out from the run alone;
 * the first thing found wrong is given as a reason, with the field of the code step it is about and, where what it
 * gives back is read otherwise, the later step or the workflow's output reading it.
 */
public final class CodeStepFit {

    private CodeStepFit() {}

    /**
     * What no longer matches for its code to be run on {@code step}, none where all of it does: every list it pins
     * held by the group, every binding into it filling what it takes and every one it must be given, and every
     * binding reading what it gives back reaching a field it gives back that fits where it is read into, and every
     * part of a search's question reading it still reaching a field it gives back.
     */
    public static Optional<CodeError.Fault> misfit(RunSnapshot run, StepSnapshot step) {
        requireNonNull(run, "CodeStepFit run must not be null");
        Held held = held(step);
        return listMissing(
                        held,
                        held.released().declared().takes().fields(),
                        List.of(),
                        CodeErrorReason.TAKES_A_LIST_NOT_HERE)
                .or(() -> listMissing(
                        held,
                        held.released().declared().gives().fields(),
                        List.of(),
                        CodeErrorReason.GIVES_A_LIST_NOT_HERE))
                .or(() -> into(run, held))
                .or(() -> outOf(run, held));
    }

    /**
     * What no longer matches for a person to answer {@code step} here as the release now declares it, none where
     * all of it does: every list what it gives back pins held by the group, and every binding reading it matching, and
     * every part of a search's question reading it still reaching a field it gives back.
     */
    public static Optional<CodeError.Fault> givesOtherwise(RunSnapshot run, StepSnapshot step) {
        requireNonNull(run, "CodeStepFit run must not be null");
        Held held = held(step);
        return listMissing(
                        held,
                        held.released().declared().gives().fields(),
                        List.of(),
                        CodeErrorReason.GIVES_A_LIST_NOT_HERE)
                .or(() -> outOf(run, held));
    }

    private static Held held(StepSnapshot step) {
        requireNonNull(step, "CodeStepFit step must not be null");
        PlannedStep planned = step.planned();
        if (!(planned.runs() instanceof StepRuns.Code code) || code.released() == null) {
            throw new IllegalArgumentException("CodeStepFit judges only a code step the release holds, not step "
                    + planned.name().value());
        }
        return new Held(planned, code.released());
    }

    private static Optional<CodeError.Fault> listMissing(
            Held held, List<Field> level, List<FieldName> above, CodeErrorReason reason) {
        Set<EntryVersionId> missing = held.released().listsMissing();
        for (Field field : level) {
            if (missing.isEmpty()) {
                return Optional.empty();
            }
            List<FieldName> names = named(above, field);
            if (field.shape() instanceof FieldShape.Term term && missing.contains(term.list())) {
                return Optional.of(new CodeError.Fault(reason, names, null, null));
            }
            if (field.shape() instanceof FieldShape.Nested nested) {
                Optional<CodeError.Fault> within = listMissing(held, nested.fields(), names, reason);
                if (within.isPresent()) {
                    return within;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CodeError.Fault> into(RunSnapshot run, Held held) {
        List<Field> takes = held.released().declared().takes().fields();
        List<Pointer> bound = new ArrayList<>(held.planned().bindings().size());
        for (Binding binding : held.planned().bindings()) {
            Pointer target = requireNonNull(binding.target(), "a binding into a step fills an input");
            if (!(target.reach(takes) instanceof Pointer.Reach.At reached)) {
                return takesOtherwiseAt(target);
            }
            for (Pointer earlier : bound) {
                if (target.within(earlier) || earlier.within(target)) {
                    return takesOtherwiseAt(target);
                }
            }
            bound.add(target);
            Field filled = reached.field();
            if (binding.source() instanceof BindingSource.Written written) {
                if (!ConstantFit.fits(
                        written.constant(), filled, held.released().lists())) {
                    return takesOtherwiseAt(target);
                }
                continue;
            }
            Pointer.Reach.At source = source(run, binding);
            if (source == null) {
                continue;
            }
            if (!FieldFit.fits(source.field(), filled)
                    || (filled.demand().mustBe() && !source.given())
                    || !FieldFit.givenWithin(source.field(), filled)) {
                return takesOtherwiseAt(target);
            }
        }
        return unbound(takes, List.of(), bound);
    }

    /** A field that must be given and that nothing fills, whole or field by field below it. */
    private static Optional<CodeError.Fault> unbound(List<Field> level, List<FieldName> above, List<Pointer> bound) {
        for (Field field : level) {
            Pointer here = new Pointer(named(above, field));
            if (bound.contains(here)) {
                continue;
            }
            boolean within = bound.stream().anyMatch(target -> target.within(here));
            if (within && field.shape() instanceof FieldShape.Nested nested && field.howMany() instanceof HowMany.One) {
                Optional<CodeError.Fault> below = unbound(nested.fields(), here.names(), bound);
                if (below.isPresent()) {
                    return below;
                }
            } else if (field.demand().mustBe()) {
                return takesOtherwiseAt(here);
            }
        }
        return Optional.empty();
    }

    private static Optional<CodeError.Fault> outOf(RunSnapshot run, Held held) {
        List<Field> gives = held.released().declared().gives().fields();
        for (PlannedStep later : run.workflow().steps()) {
            for (Binding binding : later.bindings()) {
                Pointer misread = misread(held, gives, binding.source(), binding.target(), takes(later));
                if (misread != null) {
                    return givesOtherwiseAt(
                            misread, new CodeError.StepReads(later.id().value()));
                }
            }
            if (later.runs() instanceof StepRuns.Search search) {
                Pointer affected = null;
                for (SearchQuestionPart part : search.question()) {
                    if (part instanceof SearchQuestionPart.Read read) {
                        Pointer misread = misread(held, gives, read.source(), null, null);
                        if (misread != null) {
                            return givesOtherwiseAt(
                                    misread, new CodeError.StepReads(later.id().value()));
                        }
                        if (read.source() instanceof BindingSource.StepOutput output
                                && output.step().equals(held.planned().id().value())) {
                            affected = output.pointer();
                            Pointer.Reach reached = output.pointer().reach(gives);
                            if (reached instanceof Pointer.Reach.At at && SearchQuestion.takesFile(at)) {
                                return givesOtherwiseAt(
                                        output.pointer(),
                                        new CodeError.StepReads(later.id().value()));
                            }
                        }
                    }
                }
                if (affected != null
                        && !searchAwaitsCodeDeclaration(run, search.question())
                        && SearchQuestion.couldHoldNothing(search.question(), read -> searchSourceGiven(run, read))) {
                    return givesOtherwiseAt(
                            affected, new CodeError.StepReads(later.id().value()));
                }
            }
        }
        for (Binding output : run.workflow().outputs()) {
            Pointer misread = misread(
                    held,
                    gives,
                    output.source(),
                    output.target(),
                    run.workflow().gives().fields());
            if (misread != null) {
                Pointer filled = requireNonNull(output.target(), "an output fills a field");
                return givesOtherwiseAt(misread, new CodeError.OutputReads(filled.names()));
            }
        }
        return Optional.empty();
    }

    /**
     * The field of what the code step gives back that {@code read} reads and no longer can; none where it can. Read
     * into no {@code target}, it need only still be given back.
     */
    private static @Nullable Pointer misread(
            Held held, List<Field> gives, BindingSource read, @Nullable Pointer target, @Nullable List<Field> into) {
        if (!(read instanceof BindingSource.StepOutput output)
                || !output.step().equals(held.planned().id().value())) {
            return null;
        }
        if (!(output.pointer().reach(gives) instanceof Pointer.Reach.At source)) {
            return output.pointer();
        }
        Pointer.Reach filled = target == null || into == null ? null : target.reach(into);
        if (filled instanceof Pointer.Reach.At reached
                && (!FieldFit.fits(source.field(), reached.field())
                        || (reached.field().demand().mustBe() && !source.given())
                        || !FieldFit.givenWithin(source.field(), reached.field()))) {
            return output.pointer();
        }
        return null;
    }

    private static Optional<CodeError.Fault> takesOtherwiseAt(Pointer at) {
        return Optional.of(new CodeError.Fault(CodeErrorReason.TAKES_OTHERWISE, at.names(), null, null));
    }

    private static Optional<CodeError.Fault> givesOtherwiseAt(Pointer at, CodeError.ReadBy reader) {
        return Optional.of(new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE, at.names(), null, reader));
    }

    /** What a binding reads, none where that is not known here: what a step not a question or a held code gives. */
    private static Pointer.Reach.@Nullable At source(RunSnapshot run, Binding binding) {
        return source(run, binding.source());
    }

    private static Pointer.Reach.@Nullable At source(RunSnapshot run, BindingSource source) {
        return switch (source) {
            case BindingSource.WorkflowInput input ->
                input.pointer().reach(run.workflow().takes().fields()) instanceof Pointer.Reach.At at ? at : null;
            case BindingSource.StepOutput output -> {
                List<Field> gives = run.workflow()
                        .step(new WorkflowStepId(output.step()))
                        .map(CodeStepFit::gives)
                        .orElse(null);
                yield gives != null && output.pointer().reach(gives) instanceof Pointer.Reach.At at ? at : null;
            }
            case BindingSource.Written ignored -> null;
        };
    }

    private static boolean searchAwaitsCodeDeclaration(RunSnapshot run, List<SearchQuestionPart> question) {
        for (SearchQuestionPart part : question) {
            if (part instanceof SearchQuestionPart.Read read
                    && read.source() instanceof BindingSource.StepOutput output) {
                PlannedStep source = run.workflow()
                        .step(new WorkflowStepId(output.step()))
                        .orElseThrow(() -> new IllegalStateException("A search reads an actual earlier step"));
                if (source.runs() instanceof StepRuns.Code && source(run, output) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean searchSourceGiven(RunSnapshot run, SearchQuestionPart.Read read) {
        if (read.source() instanceof BindingSource.StepOutput output) {
            PlannedStep step = run.workflow()
                    .step(new WorkflowStepId(output.step()))
                    .orElseThrow(() -> new IllegalStateException("A search reads an actual earlier step"));
            if (step.runs() instanceof StepRuns.Search search && search.released() == null) {
                if (output.pointer().names().size() != 1) {
                    throw new IllegalStateException("A search output is one standard field");
                }
                return SearchWay.demand(output.pointer().names().getFirst()).mustBe();
            }
        }
        Pointer.Reach.At reached = searchSource(run, read.source());
        if (reached == null) {
            throw new IllegalStateException("A search reads a field its source declares");
        }
        return reached.given();
    }

    private static Pointer.Reach.@Nullable At searchSource(RunSnapshot run, BindingSource source) {
        if (source instanceof BindingSource.StepOutput output) {
            PlannedStep step = run.workflow()
                    .step(new WorkflowStepId(output.step()))
                    .orElseThrow(() -> new IllegalStateException("A search reads an actual earlier step"));
            List<Field> fields =
                    switch (step.runs()) {
                        case StepRuns.Workflow workflow -> workflow.gives().fields();
                        case StepRuns.Route route -> route.gives().fields();
                        default -> null;
                    };
            if (fields != null) {
                return output.pointer().reach(fields) instanceof Pointer.Reach.At at ? at : null;
            }
        }
        return source(run, source);
    }

    private static @Nullable List<Field> gives(PlannedStep step) {
        return switch (step.runs()) {
            case StepRuns.Question question -> question.gives().fields();
            case StepRuns.Code code ->
                code.released() == null
                        ? null
                        : code.released().declared().gives().fields();
            case StepRuns.Search search ->
                search.released() == null ? null : search.released().outputs();
            case StepRuns.Workflow ignored -> null;
            case StepRuns.Route ignored -> null;
        };
    }

    private static @Nullable List<Field> takes(PlannedStep step) {
        return switch (step.runs()) {
            case StepRuns.Question question -> question.takes().fields();
            case StepRuns.Code code ->
                code.released() == null
                        ? null
                        : code.released().declared().takes().fields();
            case StepRuns.Search ignored -> null;
            case StepRuns.Workflow ignored -> null;
            case StepRuns.Route ignored -> null;
        };
    }

    private static List<FieldName> named(List<FieldName> above, Field field) {
        List<FieldName> names = new ArrayList<>(above.size() + 1);
        names.addAll(above);
        names.add(field.name());
        return names;
    }

    /** A code step the release holds, as the step naming it runs it. */
    private record Held(PlannedStep planned, ReleasedCodeStep released) {}
}
