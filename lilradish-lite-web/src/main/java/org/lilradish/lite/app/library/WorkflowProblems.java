package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.files.FileDeployment;
import org.lilradish.lite.domain.declaration.AskedField;
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
import org.lilradish.lite.domain.inference.Payload;
import org.lilradish.lite.domain.inference.SendMeasure;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.registry.ContentPart;
import org.lilradish.lite.domain.registry.ContentPlace;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.ContentProblemCode;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.search.SearchAnswer;
import org.lilradish.lite.domain.search.SearchWay;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.BindingSource;
import org.lilradish.lite.domain.workflow.ConstantFit;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Pointer;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.RouteCase;
import org.lilradish.lite.domain.workflow.SearchQuestion;
import org.lilradish.lite.domain.workflow.SearchQuestionPart;
import org.lilradish.lite.domain.workflow.StepId;

/**
 * Every place a workflow version's stored content does not hold, counted in the order the content reads: what it takes,
 * each step in the order it runs with its bindings and cases, what it gives back and what fills it, and who
 * helps. Worked out only from what the version stores, what it pins and what this release declares of the code
 * steps and ways to search it names, so it is the same judgement at submitting and at any reading since, until a
 * release changes one.
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

    private final FileDeployment deployment;

    private final boolean hideCurrentCodeProblems;

    private final Map<UUID, Integer> positions;

    private final ContentProblemReport found = new ContentProblemReport();

    private final Map<String, StoredDeclarations.Half> wayGives = new HashMap<>();

    private final Map<UUID, Long> searchesSent = new HashMap<>();

    private final Map<UUID, Long> searchesReviewed = new HashMap<>();

    private final Map<String, Long> codeReviewsSent = new HashMap<>();

    private WorkflowProblems(
            StoredWorkflow workflow,
            Resolved resolved,
            ModelCatalog models,
            FileDeployment deployment,
            boolean hideCurrentCodeProblems) {
        this.workflow = workflow;
        this.resolved = resolved;
        this.models = models;
        this.deployment = deployment;
        this.hideCurrentCodeProblems = hideCurrentCodeProblems;
        this.positions = workflow.positions();
    }

    /** A version pinned that {@code resolved} holds no declaration of is one the group does not hold. */
    static ContentProblemReport.Result report(
            StoredWorkflow workflow, Resolved resolved, ModelCatalog models, FileDeployment deployment) {
        return judged(workflow, resolved, models, deployment, false).problems();
    }

    static Judged judged(
            StoredWorkflow workflow,
            Resolved resolved,
            ModelCatalog models,
            FileDeployment deployment,
            boolean hideCurrentCodeProblems) {
        WorkflowProblems problems = new WorkflowProblems(
                requireNonNull(workflow, "WorkflowProblems workflow must not be null"),
                requireNonNull(resolved, "WorkflowProblems resolved must not be null"),
                requireNonNull(models, "WorkflowProblems models must not be null"),
                requireNonNull(deployment, "WorkflowProblems deployment must not be null"),
                hideCurrentCodeProblems);
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
        if (workflow.gives()
                .declaration()
                .filesPastTotal(deployment.maximumTotalSize().toBytes())) {
            problems.found.add(
                    ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                    new ContentPlace.Whole(ContentPart.GIVES),
                    null,
                    problems.fileLimits());
        }
        problems.outputs();
        problems.helper();
        return new Judged(
                problems.found.result(), problems.codeReviewsSent, problems.searchesSent, problems.searchesReviewed);
    }

    /** A run keeps what it was started with as one value, so all a workflow takes is held to what one may be. */
    private void takenWhole() {
        Declaration takes = workflow.takes().declaration();
        if (takes.filesPastTotal(deployment.maximumTotalSize().toBytes())) {
            found.add(
                    ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                    new ContentPlace.Whole(ContentPart.TAKES),
                    null,
                    fileLimits());
        }
        if (takes.longestWritten(resolved.terms()) > Declaration.MOST_SENT) {
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
        SearchWay way = null;
        boolean produces = false;
        boolean codeDeclaration = step.runs() instanceof StoredWorkflow.Runs.Code;
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
                    add(ContentProblemCode.CODE_STEP_NOT_DECLARED, here, true);
                }
                StoredDeclarations.Halves declared = named == null ? null : declaredHere(named);
                if (declared != null) {
                    takes = declared.takes();
                    gives = declared.gives();
                    if (takes.declaration().fields().isEmpty()
                            && gives.declaration().fields().isEmpty()) {
                        add(ContentProblemCode.CODE_STEP_TAKES_AND_GIVES_NOTHING, here, true);
                    }
                    resolved.listsUnserved().getOrDefault(named, List.of()).forEach(reason -> add(reason, here, true));
                }
            }
            case StoredWorkflow.Runs.Route route -> {
                takes = NOTHING_TAKEN;
                gives = route.gives();
            }
            case StoredWorkflow.Runs.Search search -> {
                produces = true;
                if (step.producer() == null) {
                    add(ContentProblemCode.PRODUCER_MISSING, here);
                }
                way = wayOf(search);
                if (search.way() == null) {
                    add(ContentProblemCode.RUNS_MISSING, here);
                } else if (way == null) {
                    add(ContentProblemCode.SEARCH_WAY_NOT_HELD, here);
                }
                gives = way == null ? null : givesOf(way);
            }
        }
        boolean givesFile = gives != null && gives.declaration().hasFile();
        if (givesFile && step.producer() instanceof Producer.Model) {
            add(ContentProblemCode.STEP_FILE_MADE_BY_MODEL, here, codeDeclaration);
        }
        if (step.reviewer() != null
                && (givesFile || takes != null && takes.declaration().hasFile())) {
            add(ContentProblemCode.STEP_FILE_REVIEWED_BY_MODEL, here, codeDeclaration);
        }
        if (step.producer() instanceof Producer.Model model) {
            unheld(
                    model.choice(),
                    here,
                    ContentProblemCode.PRODUCER_NOT_HELD,
                    ContentProblemCode.PRODUCER_MODE_NOT_OFFERED);
            if (gives != null && gives.declaration().fields().isEmpty()) {
                add(ContentProblemCode.MODEL_GIVES_NOTHING, here, codeDeclaration);
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
        if (step.runs() instanceof StoredWorkflow.Runs.Search search) {
            search(position, step, search, way, here);
        }
        bindings(
                step.bindings(),
                takes,
                position,
                ContentPart.STEPS,
                input -> new ContentPlace.AtInput(step.id(), null, input),
                codeDeclaration);
    }

    /**
     * A review of the step's question, or of what its code step gives back, is held to the most one asking may send,
     * as the question itself is. A code step is measured only where every list it pins serves and both its halves
     * could be told, anything else being refused already.
     */
    private void reviewSent(StoredWorkflow.Step step, ContentPlace here) {
        if (step.runs() instanceof StoredWorkflow.Runs.Code code && code.codeStep() != null) {
            Long sent = codeReviewSent(code.codeStep());
            if (sent == null) {
                return;
            }
            long past = sent - Declaration.MOST_SENT;
            if (past > 0) {
                add(ContentProblemCode.CODE_STEP_REVIEW_PAST_LARGEST, here, past, true);
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
            found.add(ContentProblemCode.ASKING_PAST_LARGEST, here, past);
        }
    }

    private @Nullable Long codeReviewSent(String codeStep) {
        return codeReviewsSent.computeIfAbsent(codeStep, named -> {
            StoredDeclarations.Halves declared = declaredHere(named);
            if (declared == null
                    || !resolved.listsUnserved().getOrDefault(named, List.of()).isEmpty()
                    || !Asking.tellable(declared.takes().declaration())
                    || !Asking.tellable(declared.gives().declaration())) {
                return null;
            }
            return SendMeasure.mostSentToReview(
                    Asking.told(declared.takes().declaration(), resolved.terms()),
                    Asking.told(declared.gives().declaration(), resolved.terms()));
        });
    }

    /**
     * How each field its way gives back stands, the tool its model must offer, what each part of its question reads,
     * that it holds something, and the most one asking could send, measured only where every value read is told.
     */
    private void search(
            int position,
            StoredWorkflow.Step step,
            StoredWorkflow.Runs.Search search,
            @Nullable SearchWay way,
            ContentPlace here) {
        List<String> unchosen = new ArrayList<>();
        for (FieldName field : SearchWay.FIELDS) {
            if (!search.standings().containsKey(field)) {
                unchosen.add(field.value());
            }
        }
        if (!unchosen.isEmpty()) {
            add(ContentProblemCode.SEARCH_STANDING_MISSING, new ContentPlace.AtStepFields(step.id(), unchosen));
        }
        if (way != null
                && step.producer() instanceof Producer.Model model
                && models.find(model.choice().model()).isPresent()
                && models.tool(model.choice().model(), way.requiredTool()).isEmpty()) {
            add(ContentProblemCode.SEARCH_TOOL_NOT_OFFERED, here);
        }
        Map<BindingSource, PartRead> reads = new HashMap<>();
        Set<UUID> given = new HashSet<>();
        Set<UUID> currentCodeReads = new HashSet<>();
        List<Payload.SearchPart> sent =
                way == null ? null : new ArrayList<>(search.question().size());
        List<Payload.SearchPart> sentWithoutCode =
                way == null ? null : new ArrayList<>(search.question().size());
        for (SearchQuestionPart part : search.question()) {
            switch (part) {
                case SearchQuestionPart.LiteralPart literal -> {
                    if (sent != null) {
                        sent.add(new Payload.SearchPart.LiteralPart(literal.text()));
                        requireNonNull(sentWithoutCode).add(new Payload.SearchPart.LiteralPart(literal.text()));
                    }
                }
                case SearchQuestionPart.Read read -> {
                    PartRead partRead = reads.computeIfAbsent(read.source(), source -> partRead(source, position));
                    Pointer.Reach.At at =
                            said(partRead.sourced(), new ContentPlace.AtBinding(ContentPart.STEPS, read.id()));
                    boolean takesFile = at != null && SearchQuestion.takesFile(at);
                    if (partRead.sourced().currentCode()) {
                        currentCodeReads.add(read.id());
                    }
                    if (takesFile) {
                        add(
                                ContentProblemCode.QUESTION_TAKES_FILE,
                                new ContentPlace.AtBinding(ContentPart.STEPS, read.id()),
                                partRead.sourced().currentCode());
                    }
                    if (at != null && at.given() && !takesFile) {
                        given.add(read.id());
                    }
                    if (takesFile || !(partRead.told() instanceof AskedField asked)) {
                        sent = null;
                        sentWithoutCode = null;
                    } else if (sent != null) {
                        sent.add(new Payload.SearchPart.ValuePart(asked, null));
                        requireNonNull(sentWithoutCode)
                                .add(
                                        partRead.sourced().currentCode()
                                                ? new Payload.SearchPart.LiteralPart("")
                                                : new Payload.SearchPart.ValuePart(asked, null));
                    }
                }
            }
        }
        if (SearchQuestion.couldHoldNothing(search.question(), read -> given.contains(read.id()))) {
            boolean independentlyEmpty = SearchQuestion.couldHoldNothing(
                    search.question(), read -> given.contains(read.id()) || currentCodeReads.contains(read.id()));
            add(ContentProblemCode.SEARCH_QUESTION_MAY_BE_EMPTY, here, !independentlyEmpty);
        }
        if (sent != null) {
            long most = SendMeasure.mostSentToSearch(
                    sent,
                    SearchAnswer.asked(requireNonNull(way)),
                    step.producer() instanceof Producer.Model model && model.toldWhatHappened());
            searchesSent.put(step.id(), most);
            if (most > Declaration.MOST_SENT) {
                long withoutCode = SendMeasure.mostSentToSearch(
                        requireNonNull(sentWithoutCode),
                        SearchAnswer.asked(requireNonNull(way)),
                        step.producer() instanceof Producer.Model model && model.toldWhatHappened());
                add(
                        ContentProblemCode.ASKING_PAST_LARGEST,
                        here,
                        most - Declaration.MOST_SENT,
                        withoutCode <= Declaration.MOST_SENT);
            }
            if (step.reviewer() != null) {
                long reviewMost = SendMeasure.mostSentToReviewSearch(sent, SearchAnswer.asked(way));
                searchesReviewed.put(step.id(), reviewMost);
                if (reviewMost > Declaration.MOST_SENT) {
                    long withoutCode = SendMeasure.mostSentToReviewSearch(
                            requireNonNull(sentWithoutCode), SearchAnswer.asked(way));
                    add(
                            ContentProblemCode.SEARCH_REVIEW_PAST_LARGEST,
                            here,
                            reviewMost - Declaration.MOST_SENT,
                            withoutCode <= Declaration.MOST_SENT);
                }
            }
        }
    }

    /**
     * What a part of a question reads and how it is told, worked out once for every part reading alike. A list a
     * code step pins missing leaves its value untold, that step being named for it already.
     */
    private PartRead partRead(BindingSource source, int position) {
        Sourced sourced = sourced(source, position);
        Pointer.Reach.At at = sourced.at();
        return new PartRead(
                sourced, at == null || listMissingBehind(source) ? null : SearchQuestion.told(at, resolved.terms()));
    }

    private boolean listMissingBehind(BindingSource source) {
        return source instanceof BindingSource.StepOutput output
                && workflow.steps()
                                .get(requireNonNull(positions.get(output.step())))
                                .runs()
                        instanceof StoredWorkflow.Runs.Code code
                && code.codeStep() != null
                && resolved.listsUnserved()
                        .getOrDefault(code.codeStep(), List.of())
                        .contains(ContentProblemCode.CODE_STEP_LIST_MISSING);
    }

    private void route(int position, UUID step, StoredWorkflow.Runs.Route route) {
        OfferedTerms offered = null;
        boolean discriminatorFromCurrentCode = false;
        Binding discriminator = route.discriminator();
        if (discriminator == null) {
            add(ContentProblemCode.DISCRIMINATOR_MISSING, new ContentPlace.AtStep(step));
        } else if (discriminator.source() instanceof BindingSource.Written) {
            add(
                    ContentProblemCode.DISCRIMINATOR_NOT_TERM,
                    new ContentPlace.AtBinding(ContentPart.STEPS, discriminator.id()));
        } else {
            Sourced source = sourced(discriminator.source(), position);
            discriminatorFromCurrentCode = source.currentCode();
            Pointer.Reach.At chosenBy = said(source, new ContentPlace.AtBinding(ContentPart.STEPS, discriminator.id()));
            if (chosenBy != null
                    && chosenBy.field().shape() instanceof FieldShape.Term term
                    && chosenBy.field().howMany() instanceof HowMany.One) {
                EntryVersionId list = term.list();
                offered = list == null ? null : resolved.terms().getOrDefault(list, OFFERS_NOTHING);
            } else if (chosenBy != null) {
                add(
                        ContentProblemCode.DISCRIMINATOR_NOT_TERM,
                        new ContentPlace.AtBinding(ContentPart.STEPS, discriminator.id()),
                        discriminatorFromCurrentCode);
            }
        }
        fields(route.gives(), ContentPart.STEPS);
        if (route.gives()
                .declaration()
                .filesPastTotal(deployment.maximumTotalSize().toBytes())) {
            found.add(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT, new ContentPlace.AtStep(step), null, fileLimits());
        }
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
                add(ContentProblemCode.CASE_NOT_OFFERED, here, discriminatorFromCurrentCode);
            }
            StoredDeclarations.Halves led =
                    target == null ? null : resolved.pinned().get(target);
            if (target != null && led == null) {
                add(ContentProblemCode.PIN_ELSEWHERE, here);
            }
            if (led != null) {
                FieldFit.visitCaseDifferences(
                        led.gives().declaration().fields(),
                        route.gives().declaration().fields(),
                        (kind, inCase, path, depth) -> {
                            if (!found.reserveCase(routeCase.id())) {
                                return;
                            }
                            List<Integer> at = new ArrayList<>(depth);
                            for (int index = 0; index < depth; index++) {
                                at.add(path[index]);
                            }
                            UUID field = inCase
                                    ? led.gives().keyAt(at)
                                    : route.gives().keyAt(at);
                            ContentProblemCode code =
                                    switch (kind) {
                                        case UNDECLARED -> ContentProblemCode.CASE_GIVES_UNDECLARED;
                                        case OTHER_KIND -> ContentProblemCode.CASE_GIVES_OTHER_KIND;
                                        case PAST_LIMIT -> ContentProblemCode.CASE_GIVES_PAST_LIMIT;
                                        case MAY_GIVE_NOTHING -> ContentProblemCode.CASE_MAY_GIVE_NOTHING;
                                    };
                            found.addReserved(code, new ContentPlace.AtCaseField(step, routeCase.id(), field));
                        });
            }
            bindings(
                    routeCase.bindings(),
                    led == null ? null : led.takes(),
                    position,
                    ContentPart.STEPS,
                    input -> new ContentPlace.AtInput(step, routeCase.id(), input),
                    false);
        }
    }

    private void outputs() {
        Bound bound = new Bound();
        StoredDeclarations.Half gives = workflow.gives();
        for (Binding binding : workflow.outputs()) {
            ContentPlace here = new ContentPlace.AtBinding(ContentPart.GIVES, binding.id());
            Field filled = target(binding, gives, bound, here, false);
            if (!(binding.source() instanceof BindingSource.StepOutput)) {
                add(ContentProblemCode.OUTPUT_NOT_FROM_STEP, here);
                continue;
            }
            read(binding, filled, workflow.steps().size(), here, false);
        }
        unbound(
                gives.declaration().fields(),
                gives.keys(),
                List.of(),
                bound,
                field -> new ContentPlace.AtField(ContentPart.GIVES, field),
                ContentProblemCode.OUTPUT_UNBOUND,
                false);
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
            InputPlace inputPlace,
            boolean currentCodeTarget) {
        Bound bound = new Bound();
        for (Binding binding : bindings) {
            ContentPlace here = new ContentPlace.AtBinding(part, binding.id());
            Field filled = target(binding, takes, bound, here, currentCodeTarget);
            if (binding.source() instanceof BindingSource.Written written) {
                constant(written.constant(), filled, here, currentCodeTarget);
            } else {
                read(binding, filled, position, here, currentCodeTarget);
            }
        }
        if (takes != null) {
            unbound(
                    takes.declaration().fields(),
                    takes.keys(),
                    List.of(),
                    bound,
                    inputPlace,
                    ContentProblemCode.INPUT_UNBOUND,
                    currentCodeTarget);
        }
    }

    /** What a binding reads, against what it fills: that it fits, and that it is always there where it must be. */
    private void read(
            Binding binding, @Nullable Field filled, int position, ContentPlace here, boolean currentCodeTarget) {
        Sourced sourced = sourced(binding.source(), position);
        Pointer.Reach.At source = said(sourced, here);
        if (source == null || filled == null) {
            return;
        }
        if (!FieldFit.fits(source.field(), filled)) {
            add(ContentProblemCode.SOURCE_DOES_NOT_FIT, here, currentCodeTarget || sourced.currentCode());
        }
        if ((filled.demand().mustBe() && !source.given()) || !FieldFit.givenWithin(source.field(), filled)) {
            add(ContentProblemCode.SOURCE_MAY_BE_EMPTY, here, currentCodeTarget || sourced.currentCode());
        }
    }

    /** The field a binding fills, or none where that is not known or not one; its pointer counted as bound. */
    private @Nullable Field target(
            Binding binding,
            StoredDeclarations.@Nullable Half into,
            Bound bound,
            ContentPlace here,
            boolean currentCodeTarget) {
        Pointer target = requireNonNull(binding.target(), "an input filled is named");
        Field filled = null;
        if (into != null) {
            Pointer.Reach.At reached = reached(
                    target.reach(into.declaration().fields()),
                    ContentProblemCode.TARGET_UNKNOWN,
                    here,
                    currentCodeTarget);
            filled = reached == null ? null : reached.field();
        }
        if (bound.clashes(target)) {
            add(ContentProblemCode.TARGET_BOUND_TWICE, here);
        }
        bound.add(target);
        return filled;
    }

    private void constant(JsonValue constant, @Nullable Field filled, ContentPlace here, boolean currentCodeTarget) {
        if (ConstantFit.concealing(constant) != null) {
            add(ContentProblemCode.CONSTANT_CONCEALS, here);
        }
        long length = ConstantFit.textLength(constant);
        if (length > ConstantFit.MOST_TEXT) {
            found.add(ContentProblemCode.CONSTANT_TOO_LONG, here, length - ConstantFit.MOST_TEXT);
        }
        if (filled != null && !ConstantFit.fits(constant, filled, resolved.terms())) {
            add(ContentProblemCode.CONSTANT_DOES_NOT_FIT, here, currentCodeTarget);
        }
    }

    private Sourced sourced(BindingSource source, int position) {
        return switch (source) {
            case BindingSource.WorkflowInput input ->
                reached(
                        input.pointer().reach(workflow.takes().declaration().fields()),
                        ContentProblemCode.SOURCE_UNKNOWN,
                        false);
            case BindingSource.StepOutput output -> {
                Integer at = positions.get(output.step());
                if (at == null) {
                    throw new IllegalStateException("A binding reads a step its version does not hold");
                }
                if (at >= position) {
                    yield new Sourced(null, ContentProblemCode.SOURCE_NOT_EARLIER, false);
                }
                StoredWorkflow.Step earlier = workflow.steps().get(at);
                boolean currentCode = earlier.runs() instanceof StoredWorkflow.Runs.Code;
                StoredDeclarations.Half gives = givesOf(earlier);
                yield gives == null
                        ? new Sourced(null, ContentProblemCode.SOURCE_UNKNOWN, currentCode)
                        : reached(
                                output.pointer().reach(gives.declaration().fields()),
                                ContentProblemCode.SOURCE_UNKNOWN,
                                currentCode);
            }
            case BindingSource.Written ignored -> new Sourced(null, null, false);
        };
    }

    private Pointer.Reach.@Nullable At reached(
            Pointer.Reach reach, ContentProblemCode nowhere, ContentPlace here, boolean currentCode) {
        return said(reached(reach, nowhere, currentCode), here);
    }

    private Pointer.Reach.@Nullable At said(Sourced sourced, ContentPlace here) {
        if (sourced.problem() instanceof ContentProblemCode problem) {
            add(problem, here, sourced.currentCode());
        }
        return sourced.at();
    }

    private static Sourced reached(Pointer.Reach reach, ContentProblemCode nowhere, boolean currentCode) {
        return switch (reach) {
            case Pointer.Reach.At at -> new Sourced(at, null, currentCode);
            case Pointer.Reach.IntoMany ignored -> new Sourced(null, ContentProblemCode.POINTER_INTO_MANY, currentCode);
            case Pointer.Reach.Nowhere ignored -> new Sourced(null, nowhere, currentCode);
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
            case StoredWorkflow.Runs.Search search -> {
                SearchWay way = wayOf(search);
                yield way == null ? null : givesOf(way);
            }
        };
    }

    /** The way a searching step runs, where one is chosen and this release holds it. */
    private @Nullable SearchWay wayOf(StoredWorkflow.Runs.Search search) {
        return search.way() == null ? null : resolved.ways().get(search.way());
    }

    private StoredDeclarations.Half givesOf(SearchWay way) {
        return wayGives.computeIfAbsent(way.name(), ignored -> StoredDeclarations.ofSearchWay(way));
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
            ContentProblemCode code,
            boolean currentCode) {
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
                unbound(nested.fields(), keys.get(index).fields(), names, bound, place, code, currentCode);
            } else {
                add(code, place.at(keys.get(index).id()), currentCode);
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
        for (FieldProblem problem : half.declaration()
                .problems(resolved.terms(), deployment.maximumSize().toBytes(), deployment.maximumPerField())) {
            ContentPlace place = new ContentPlace.AtField(part, half.keyAt(problem.at()));
            if (problem.code() == ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT) {
                found.add(problem.code(), place, null, fileLimits());
            } else {
                add(problem.code(), place);
            }
        }
    }

    private ContentProblem.FileLimits fileLimits() {
        return new ContentProblem.FileLimits(
                deployment.maximumSize().toBytes(),
                deployment.maximumPerField(),
                deployment.maximumTotalSize().toBytes());
    }

    private void add(ContentProblemCode code, ContentPlace place) {
        add(code, place, false);
    }

    private void add(ContentProblemCode code, ContentPlace place, boolean currentCode) {
        if (!hideCurrentCodeProblems || !currentCode) {
            found.add(code, place);
        }
    }

    private void add(ContentProblemCode code, ContentPlace place, long excess, boolean currentCode) {
        if (!hideCurrentCodeProblems || !currentCode) {
            found.add(code, place, excess);
        }
    }

    /**
     * What submitting would refuse, and the most each searching step's one asking could send, by the step's key.
     *
     * @param codeReviewsSent only of a code step named with a reviewer, where both released halves could be told
     * @param searchesSent only of a step whose way this release holds and whose every value read could be told
     * @param searchesReviewed only of such a step that names a reviewer
     */
    record Judged(
            ContentProblemReport.Result problems,
            Map<String, Long> codeReviewsSent,
            Map<UUID, Long> searchesSent,
            Map<UUID, Long> searchesReviewed) {

        Judged {
            requireNonNull(problems, "WorkflowProblems.Judged problems must not be null");
            codeReviewsSent = Map.copyOf(
                    requireNonNull(codeReviewsSent, "WorkflowProblems.Judged codeReviewsSent must not be null"));
            searchesSent =
                    Map.copyOf(requireNonNull(searchesSent, "WorkflowProblems.Judged searchesSent must not be null"));
            searchesReviewed = Map.copyOf(
                    requireNonNull(searchesReviewed, "WorkflowProblems.Judged searchesReviewed must not be null"));
        }
    }

    /** What a source reaches, or the one problem keeping it from a field known to be there; neither for a constant. */
    private record Sourced(
            Pointer.Reach.@Nullable At at, @Nullable ContentProblemCode problem, boolean currentCode) {}

    /** @param told none where it could not be told, or a list behind it is missing */
    private record PartRead(Sourced sourced, @Nullable AskedField told) {}

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
     * @param ways each way to search its steps run that this release holds, by its name
     */
    record Resolved(
            Map<EntryVersionId, StoredDeclarations.Halves> pinned,
            Map<EntryVersionId, OfferedTerms> terms,
            Set<String> nameable,
            Map<String, StoredDeclarations.Halves> released,
            Map<String, List<ContentProblemCode>> listsUnserved,
            Map<EntryVersionId, Asking> asked,
            Map<String, SearchWay> ways) {

        Resolved {
            pinned = Map.copyOf(requireNonNull(pinned, "WorkflowProblems.Resolved pinned must not be null"));
            terms = Map.copyOf(requireNonNull(terms, "WorkflowProblems.Resolved terms must not be null"));
            nameable = Set.copyOf(requireNonNull(nameable, "WorkflowProblems.Resolved nameable must not be null"));
            released = Map.copyOf(requireNonNull(released, "WorkflowProblems.Resolved released must not be null"));
            listsUnserved = Map.copyOf(
                    requireNonNull(listsUnserved, "WorkflowProblems.Resolved listsUnserved must not be null"));
            asked = Map.copyOf(requireNonNull(asked, "WorkflowProblems.Resolved asked must not be null"));
            ways = Map.copyOf(requireNonNull(ways, "WorkflowProblems.Resolved ways must not be null"));
        }
    }
}
