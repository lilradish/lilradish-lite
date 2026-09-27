package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldFit;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldProblem;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.inference.SendMeasure;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.registry.ContentPart;
import org.lilradish.lite.domain.registry.ContentPlace;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.ContentProblemCode;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.BindingSource;
import org.lilradish.lite.domain.workflow.ConstantFit;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.RouteCase;
import org.lilradish.lite.domain.workflow.StepId;

/**
 * Every place a workflow version's stored content does not hold, in the order the content reads: what it takes,
 * each step in the order it runs with its bindings and cases, what it gives back and what fills it, and who
 * helps. Worked out only from what the version stores, what it pins and what this release declares of the code
 * steps it names, so it is the same judgement at submitting and at any reading since, until a release changes one.
 */
final class WorkflowProblems {

    /* What a route takes, which is nothing: its cases take, each what the workflow it leads to takes. */
    private static final StoredDeclarations.Half NOTHING_TAKEN = new StoredDeclarations.Half(
            new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), List.of()), List.of());

    /* What a list the group does not hold offers, which is no term at all. */
    private static final OfferedTerms OFFERS_NOTHING = new OfferedTerms(List.of(), null);

    private final StoredWorkflow workflow;

    private final Resolved resolved;

    private final ModelCatalog models;

    private final Map<UUID, Integer> positions;

    private final List<ContentProblem> found = new ArrayList<>();

    private WorkflowProblems(StoredWorkflow workflow, Resolved resolved, ModelCatalog models) {
        this.workflow = workflow;
        this.resolved = resolved;
        this.models = models;
        this.positions = workflow.positions();
    }

    /** A version pinned that {@code resolved} holds no declaration of is one the group does not hold. */
    static List<ContentProblem> of(StoredWorkflow workflow, Resolved resolved, ModelCatalog models) {
        WorkflowProblems problems = new WorkflowProblems(
                requireNonNull(workflow, "WorkflowProblems workflow must not be null"),
                requireNonNull(resolved, "WorkflowProblems resolved must not be null"),
                requireNonNull(models, "WorkflowProblems models must not be null"));
        problems.fields(workflow.takes(), ContentPart.TAKES);
        problems.takenWhole();
        if (workflow.steps().isEmpty()) {
            problems.add(ContentProblemCode.NO_STEPS, new ContentPlace.Whole(ContentPart.STEPS));
        }
        Set<StepId> named = new HashSet<>();
        for (int position = 0; position < workflow.steps().size(); position++) {
            problems.step(position, !named.add(workflow.steps().get(position).name()));
        }
        problems.fields(workflow.gives(), ContentPart.GIVES);
        problems.outputs();
        problems.helper();
        return List.copyOf(problems.found);
    }

    /** A run keeps what it was started with as one value, so all a workflow takes is held to what one may be. */
    private void takenWhole() {
        if (workflow.takes().declaration().longestWritten(resolved.terms()) > Declaration.MOST_SENT) {
            add(ContentProblemCode.TAKES_PAST_LARGEST, new ContentPlace.Whole(ContentPart.TAKES));
        }
    }

    private void step(int position, boolean nameRepeated) {
        StoredWorkflow.Step step = workflow.steps().get(position);
        ContentPlace here = new ContentPlace.AtStep(step.id());
        if (nameRepeated) {
            add(ContentProblemCode.STEP_NAME_REPEATED, here);
        }
        StoredDeclarations.Half takes = null;
        StoredDeclarations.Half gives = null;
        boolean produces = false;
        switch (step.runs()) {
            case StoredWorkflow.Runs.Unchosen ignored -> add(ContentProblemCode.RUNS_MISSING, here);
            case StoredWorkflow.Runs.Pinned pinned -> {
                StoredDeclarations.Halves declared = resolved.pinned().get(pinned.version());
                produces = pinned.kind() == EntryKind.QUESTION;
                if (declared == null) {
                    add(ContentProblemCode.PIN_ELSEWHERE, here);
                } else {
                    takes = declared.takes();
                    gives = declared.gives();
                }
                if (produces && step.producer() == null) {
                    add(ContentProblemCode.PRODUCER_MISSING, here);
                }
            }
            case StoredWorkflow.Runs.Code code -> {
                produces = true;
                if (step.producer() == null) {
                    add(ContentProblemCode.PRODUCER_MISSING, here);
                }
                String named = code.codeStep();
                if (named == null) {
                    add(ContentProblemCode.RUNS_MISSING, here);
                } else if (!resolved.nameable().contains(named)) {
                    add(ContentProblemCode.CODE_STEP_NOT_PUBLISHED, here);
                }
                if (named != null && !resolved.released().containsKey(named)) {
                    add(ContentProblemCode.CODE_STEP_NOT_DECLARED, here);
                }
                StoredDeclarations.Halves declared = named == null ? null : declaredHere(named);
                if (declared != null) {
                    takes = declared.takes();
                    gives = declared.gives();
                    if (takes.declaration().fields().isEmpty()
                            && gives.declaration().fields().isEmpty()) {
                        add(ContentProblemCode.CODE_STEP_TAKES_AND_GIVES_NOTHING, here);
                    }
                    resolved.listsUnserved().getOrDefault(named, List.of()).forEach(reason -> add(reason, here));
                }
            }
            case StoredWorkflow.Runs.Route route -> {
                takes = NOTHING_TAKEN;
                gives = route.gives();
            }
        }
        if (step.producer() instanceof Producer.Model model) {
            unheld(
                    model.choice(),
                    here,
                    ContentProblemCode.PRODUCER_NOT_HELD,
                    ContentProblemCode.PRODUCER_MODE_NOT_OFFERED);
            if (gives != null && gives.declaration().fields().isEmpty()) {
                add(ContentProblemCode.MODEL_GIVES_NOTHING, here);
            }
        }
        if (produces && step.tries() == null) {
            add(ContentProblemCode.TRIES_MISSING, here);
        }
        if (step.reviewer() != null) {
            unheld(
                    step.reviewer(),
                    here,
                    ContentProblemCode.REVIEWER_NOT_HELD,
                    ContentProblemCode.REVIEWER_MODE_NOT_OFFERED);
            reviewSent(step, here);
        }
        if (step.runs() instanceof StoredWorkflow.Runs.Route route) {
            route(position, step.id(), route);
        }
        bindings(
                step.bindings(),
                takes,
                position,
                ContentPart.STEPS,
                input -> new ContentPlace.AtInput(step.id(), null, input));
    }

    /**
     * A review of the step's question, or of what its code step gives back, is held to the most one asking may send,
     * as the question itself is. A code step is measured only where every list it pins serves and both its halves
     * could be told, anything else being refused already.
     */
    private void reviewSent(StoredWorkflow.Step step, ContentPlace here) {
        if (step.runs() instanceof StoredWorkflow.Runs.Code code && code.codeStep() != null) {
            StoredDeclarations.Halves declared = declaredHere(code.codeStep());
            if (declared == null
                    || !resolved.listsUnserved()
                            .getOrDefault(code.codeStep(), List.of())
                            .isEmpty()
                    || !Asking.tellable(declared.takes().declaration())
                    || !Asking.tellable(declared.gives().declaration())) {
                return;
            }
            long past = SendMeasure.mostSentToReview(
                            Asking.told(declared.takes().declaration(), resolved.terms()),
                            Asking.told(declared.gives().declaration(), resolved.terms()))
                    - Declaration.MOST_SENT;
            if (past > 0) {
                found.add(new ContentProblem(ContentProblemCode.CODE_STEP_REVIEW_PAST_LARGEST, here, past));
            }
            return;
        }
        Asking asking = step.runs() instanceof StoredWorkflow.Runs.Pinned pinned && pinned.kind() == EntryKind.QUESTION
                ? resolved.asked().get(pinned.version())
                : null;
        if (asking == null) {
            return;
        }
        long past = SendMeasure.mostSentToReview(asking) - Declaration.MOST_SENT;
        if (past > 0) {
            found.add(new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST, here, past));
        }
    }

    private void route(int position, UUID step, StoredWorkflow.Runs.Route route) {
        OfferedTerms offered = null;
        Binding discriminator = route.discriminator();
        if (discriminator == null) {
            add(ContentProblemCode.DISCRIMINATOR_MISSING, new ContentPlace.AtStep(step));
        } else if (discriminator.source() instanceof BindingSource.Written) {
            add(
                    ContentProblemCode.DISCRIMINATOR_NOT_TERM,
                    new ContentPlace.AtBinding(ContentPart.STEPS, discriminator.id()));
        } else {
            Pointer.Reach.At chosenBy = sourceField(discriminator, position, ContentPart.STEPS);
            if (chosenBy != null
                    && chosenBy.field().shape() instanceof FieldShape.Term term
                    && chosenBy.field().howMany() instanceof HowMany.One) {
                EntryVersionId list = term.list();
                offered = list == null ? null : resolved.terms().getOrDefault(list, OFFERS_NOTHING);
            } else if (chosenBy != null) {
                add(
                        ContentProblemCode.DISCRIMINATOR_NOT_TERM,
                        new ContentPlace.AtBinding(ContentPart.STEPS, discriminator.id()));
            }
        }
        fields(route.gives(), ContentPart.STEPS);
        if (route.cases().stream().noneMatch(routeCase -> routeCase.term() != null)) {
            add(ContentProblemCode.NO_CASES, new ContentPlace.AtStep(step));
        }
        Set<String> seen = new HashSet<>();
        for (RouteCase routeCase : route.cases()) {
            ContentPlace here = new ContentPlace.AtCase(step, routeCase.id());
            EntryVersionId target = routeCase.target();
            if (target == null) {
                add(ContentProblemCode.CASE_TARGET_MISSING, here);
            }
            String term = routeCase.term();
            if (term != null && !seen.add(term)) {
                add(ContentProblemCode.CASE_REPEATED, here);
            } else if (term != null && offered != null && !offered.offers(term)) {
                add(ContentProblemCode.CASE_NOT_OFFERED, here);
            }
            StoredDeclarations.Halves led =
                    target == null ? null : resolved.pinned().get(target);
            if (target != null && led == null) {
                add(ContentProblemCode.PIN_ELSEWHERE, here);
            }
            if (led != null
                    && !FieldFit.alike(
                            led.gives().declaration().fields(),
                            route.gives().declaration().fields())) {
                add(ContentProblemCode.CASE_GIVES_OTHERWISE, here);
            }
            bindings(
                    routeCase.bindings(),
                    led == null ? null : led.takes(),
                    position,
                    ContentPart.STEPS,
                    input -> new ContentPlace.AtInput(step, routeCase.id(), input));
        }
    }

    private void outputs() {
        Bound bound = new Bound();
        StoredDeclarations.Half gives = workflow.gives();
        for (Binding binding : workflow.outputs()) {
            ContentPlace here = new ContentPlace.AtBinding(ContentPart.GIVES, binding.id());
            Field filled = target(binding, gives, bound, here);
            if (!(binding.source() instanceof BindingSource.StepOutput)) {
                add(ContentProblemCode.OUTPUT_NOT_FROM_STEP, here);
                continue;
            }
            read(binding, filled, workflow.steps().size(), ContentPart.GIVES, here);
        }
        unbound(
                gives.declaration().fields(),
                gives.keys(),
                List.of(),
                bound,
                field -> new ContentPlace.AtField(ContentPart.GIVES, field),
                ContentProblemCode.OUTPUT_UNBOUND);
    }

    private void helper() {
        ContentPlace here = new ContentPlace.Whole(ContentPart.HELPER);
        ModelChoice helper = workflow.helper();
        if (workflow.mayBeHelped() && helper == null) {
            add(ContentProblemCode.HELPER_MISSING, here);
        }
        if (helper != null) {
            unheld(helper, here, ContentProblemCode.HELPER_NOT_HELD, ContentProblemCode.HELPER_MODE_NOT_OFFERED);
        }
    }

    /** Every binding filling what {@code takes} declares, none of it judged against a declaration not known. */
    private void bindings(
            List<Binding> bindings,
            StoredDeclarations.@Nullable Half takes,
            int position,
            ContentPart part,
            InputPlace inputPlace) {
        Bound bound = new Bound();
        for (Binding binding : bindings) {
            ContentPlace here = new ContentPlace.AtBinding(part, binding.id());
            Field filled = target(binding, takes, bound, here);
            if (binding.source() instanceof BindingSource.Written written) {
                constant(written.constant(), filled, here);
            } else {
                read(binding, filled, position, part, here);
            }
        }
        if (takes != null) {
            unbound(
                    takes.declaration().fields(),
                    takes.keys(),
                    List.of(),
                    bound,
                    inputPlace,
                    ContentProblemCode.INPUT_UNBOUND);
        }
    }

    /** What a binding reads, against what it fills: that it fits, and that it is always there where it must be. */
    private void read(Binding binding, @Nullable Field filled, int position, ContentPart part, ContentPlace here) {
        Pointer.Reach.At source = sourceField(binding, position, part);
        if (source == null || filled == null) {
            return;
        }
        if (!FieldFit.fits(source.field(), filled)) {
            add(ContentProblemCode.SOURCE_DOES_NOT_FIT, here);
        }
        if ((filled.demand().mustBe() && !source.given()) || !FieldFit.givenWithin(source.field(), filled)) {
            add(ContentProblemCode.SOURCE_MAY_BE_EMPTY, here);
        }
    }

    /** The field a binding fills, or none where that is not known or not one; its pointer counted as bound. */
    private @Nullable Field target(
            Binding binding, StoredDeclarations.@Nullable Half into, Bound bound, ContentPlace here) {
        Pointer target = requireNonNull(binding.target(), "an input filled is named");
        Field filled = null;
        if (into != null) {
            Pointer.Reach.At reached =
                    reached(target.reach(into.declaration().fields()), ContentProblemCode.TARGET_UNKNOWN, here);
            filled = reached == null ? null : reached.field();
        }
        if (bound.clashes(target)) {
            add(ContentProblemCode.TARGET_BOUND_TWICE, here);
        }
        bound.add(target);
        return filled;
    }

    private void constant(JsonValue constant, @Nullable Field filled, ContentPlace here) {
        if (ConstantFit.concealing(constant) != null) {
            add(ContentProblemCode.CONSTANT_CONCEALS, here);
        }
        long length = ConstantFit.textLength(constant);
        if (length > ConstantFit.MOST_TEXT) {
            found.add(new ContentProblem(ContentProblemCode.CONSTANT_TOO_LONG, here, length - ConstantFit.MOST_TEXT));
        }
        if (filled != null && !ConstantFit.fits(constant, filled, resolved.terms())) {
            add(ContentProblemCode.CONSTANT_DOES_NOT_FIT, here);
        }
    }

    /** What a binding reads, or none where that is not a field known to be there, each such said at it. */
    private Pointer.Reach.@Nullable At sourceField(Binding binding, int position, ContentPart part) {
        ContentPlace here = new ContentPlace.AtBinding(part, binding.id());
        return switch (binding.source()) {
            case BindingSource.WorkflowInput input ->
                reached(
                        input.pointer().reach(workflow.takes().declaration().fields()),
                        ContentProblemCode.SOURCE_UNKNOWN,
                        here);
            case BindingSource.StepOutput output -> {
                Integer at = positions.get(output.step());
                if (at == null) {
                    throw new IllegalStateException("A binding reads a step its version does not hold");
                }
                if (at >= position) {
                    add(ContentProblemCode.SOURCE_NOT_EARLIER, here);
                    yield null;
                }
                StoredDeclarations.Half gives = givesOf(workflow.steps().get(at));
                if (gives == null) {
                    add(ContentProblemCode.SOURCE_UNKNOWN, here);
                    yield null;
                }
                yield reached(
                        output.pointer().reach(gives.declaration().fields()), ContentProblemCode.SOURCE_UNKNOWN, here);
            }
            case BindingSource.Written ignored -> null;
        };
    }

    private Pointer.Reach.@Nullable At reached(Pointer.Reach reach, ContentProblemCode nowhere, ContentPlace here) {
        return switch (reach) {
            case Pointer.Reach.At at -> at;
            case Pointer.Reach.IntoMany ignored -> {
                add(ContentProblemCode.POINTER_INTO_MANY, here);
                yield null;
            }
            case Pointer.Reach.Nowhere ignored -> {
                add(nowhere, here);
                yield null;
            }
        };
    }

    /** What a step gives back, as what it runs declares it; none where that is not known. */
    private StoredDeclarations.@Nullable Half givesOf(StoredWorkflow.Step step) {
        return switch (step.runs()) {
            case StoredWorkflow.Runs.Pinned pinned -> {
                StoredDeclarations.Halves declared = resolved.pinned().get(pinned.version());
                yield declared == null ? null : declared.gives();
            }
            case StoredWorkflow.Runs.Route route -> route.gives();
            case StoredWorkflow.Runs.Code code -> {
                StoredDeclarations.Halves declared = code.codeStep() == null ? null : declaredHere(code.codeStep());
                yield declared == null ? null : declared.gives();
            }
            case StoredWorkflow.Runs.Unchosen ignored -> null;
        };
    }

    /** What the release declares of a code step the group may name; none of one it may not, as though unknown. */
    private StoredDeclarations.@Nullable Halves declaredHere(String codeStep) {
        return resolved.nameable().contains(codeStep) ? resolved.released().get(codeStep) : null;
    }

    /**
     * Each field at {@code level} nothing fills; one filled only in part is judged field by field below it, and
     * one holding many is never filled that way.
     */
    private void unbound(
            List<Field> level,
            List<StoredDeclarations.Keyed> keys,
            List<FieldName> above,
            Bound bound,
            InputPlace place,
            ContentProblemCode code) {
        for (int index = 0; index < level.size(); index++) {
            Field field = level.get(index);
            List<FieldName> names = new ArrayList<>(above);
            names.add(field.name());
            Pointer here = new Pointer(names);
            if (bound.whole(here)) {
                continue;
            }
            if (field.shape() instanceof FieldShape.Nested nested
                    && field.howMany() instanceof HowMany.One
                    && bound.within(here)) {
                unbound(nested.fields(), keys.get(index).fields(), names, bound, place, code);
            } else {
                add(code, place.at(keys.get(index).id()));
            }
        }
    }

    private void unheld(ModelChoice choice, ContentPlace here, ContentProblemCode model, ContentProblemCode mode) {
        ModelChoice.Unheld unheld = choice.unheldBy(models);
        if (unheld == ModelChoice.Unheld.MODEL) {
            add(model, here);
        } else if (unheld == ModelChoice.Unheld.MODE) {
            add(mode, here);
        }
    }

    private void fields(StoredDeclarations.Half half, ContentPart part) {
        for (FieldProblem problem : half.declaration().problems(resolved.terms())) {
            add(problem.code(), new ContentPlace.AtField(part, half.keyAt(problem.at())));
        }
    }

    private void add(ContentProblemCode code, ContentPlace place) {
        found.add(new ContentProblem(code, place, null));
    }

    /** Where an input or an output left unfilled is, by the key its declaration stores it under. */
    @FunctionalInterface
    private interface InputPlace {

        ContentPlace at(UUID field);
    }

    /** The pointers bound so far, and every pointer one of them lies within, so each is asked in its own depth. */
    private static final class Bound {

        private final Set<Pointer> whole = new HashSet<>();

        private final Set<Pointer> held = new HashSet<>();

        /** Whether it is, holds or is held by a pointer bound already. */
        boolean clashes(Pointer target) {
            if (held.contains(target)) {
                return true;
            }
            for (int length = 1; length <= target.names().size(); length++) {
                if (whole.contains(new Pointer(target.names().subList(0, length)))) {
                    return true;
                }
            }
            return false;
        }

        void add(Pointer target) {
            whole.add(target);
            for (int length = 1; length <= target.names().size(); length++) {
                held.add(new Pointer(target.names().subList(0, length)));
            }
        }

        boolean whole(Pointer pointer) {
            return whole.contains(pointer);
        }

        /** Whether some pointer bound is it or lies within it. */
        boolean within(Pointer pointer) {
            return held.contains(pointer);
        }
    }

    /**
     * What a workflow version reaches beyond itself, read for it once.
     *
     * @param pinned what each version of the group's its steps and cases pin declares
     * @param terms the terms of each reference list any of those declarations, or its own, pins, in order
     * @param nameable every code step the group owning it may name: published to it, and taking no term from
     *     another group's list
     * @param released what this release declares of each code step its steps name, where it holds one
     * @param listsUnserved why a list each of those the group may name pins is not in service, as
     *     {@link CodeStepsHere} says
     * @param asked what a model is told of each of those questions a step asks a model of, where it could be told
     */
    record Resolved(
            Map<EntryVersionId, StoredDeclarations.Halves> pinned,
            Map<EntryVersionId, OfferedTerms> terms,
            Set<String> nameable,
            Map<String, StoredDeclarations.Halves> released,
            Map<String, List<ContentProblemCode>> listsUnserved,
            Map<EntryVersionId, Asking> asked) {

        Resolved {
            pinned = Map.copyOf(requireNonNull(pinned, "WorkflowProblems.Resolved pinned must not be null"));
            terms = Map.copyOf(requireNonNull(terms, "WorkflowProblems.Resolved terms must not be null"));
            nameable = Set.copyOf(requireNonNull(nameable, "WorkflowProblems.Resolved nameable must not be null"));
            released = Map.copyOf(requireNonNull(released, "WorkflowProblems.Resolved released must not be null"));
            listsUnserved = Map.copyOf(
                    requireNonNull(listsUnserved, "WorkflowProblems.Resolved listsUnserved must not be null"));
            asked = Map.copyOf(requireNonNull(asked, "WorkflowProblems.Resolved asked must not be null"));
        }
    }
}
