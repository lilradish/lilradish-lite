package org.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.run.Ceiling;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.BindingSource;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.RouteCase;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A workflow version, read by any member of its group; what it takes and gives back, its steps and what fills
 * them, its ceiling and whether its runs may be helped, each written only as a draft, by one who may write an
 * entry, naming the revision the page read, and answered by the version as the change writing it then reads it.
 */
@RestController
final class WorkflowController {

    /* The segment is the workflow kind's own, which a spec holds level with EntryKind's. */
    static final String VERSION = ActAdmission.IN_A_GROUP + "/workflows/{entryId}/versions/{versionId}";

    static final String HALF = VERSION + "/{side:takes|gives}";

    static final String STEPS = VERSION + "/steps";

    static final String CEILING = VERSION + "/ceiling";

    static final String HELP = VERSION + "/help";

    private static final String REVISION = "revision";

    private static final String FIELDS = "fields";

    private static final String CEILING_MEMBER = "ceiling";

    private static final String KEEPS_OWN_CEILING = "keepsOwnCeiling";

    private static final String RAISE_NEEDS_APPROVAL = "raiseNeedsApproval";

    private static final String MAY_BE_HELPED = "mayBeHelped";

    private static final String HELPER = "helper";

    private static final String MODEL = "model";

    private static final String MODE = "mode";

    /** Room for steps and their bindings, constants and routes' declarations, as no one draft is written with. */
    private static final int LARGEST_STEPS = 8 * 1024 * 1024;

    private static final int LARGEST_SETTING = 1024;

    private static final String HALF_REFUSED = "This takes a JSON object holding the revision read and the fields,"
            + " each exactly the members its kind and depth take, and nothing else.";

    private static final String CEILING_REFUSED = "This takes a JSON object holding the revision read, a ceiling in"
            + " digits or null for none, and whether runs keep their own and whether a raise needs approving.";

    private static final String HELP_REFUSED = "This takes a JSON object holding the revision read, whether runs may"
            + " be helped, and the model and mode helping them or null where they may not be.";

    private final Workflows workflows;

    private final WorkflowDrafts drafts;

    WorkflowController(Workflows workflows, WorkflowDrafts drafts) {
        this.workflows = workflows;
        this.drafts = drafts;
    }

    @GetMapping(VERSION)
    @GroupMembershipRequired
    WorkflowAnswer read(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request) {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        QueryParameters.requireNone(request, VersionAddress.PARAMETER_REFUSED);
        return answer(workflows.read(addressed.group(), addressed.entry(), addressed.version(), addressed.caller()));
    }

    @PutMapping(HALF)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    WorkflowAnswer declare(
            @PathVariable String entryId,
            @PathVariable String versionId,
            @PathVariable String side,
            HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        DeclarationSide declared = VersionAddress.sideAt(side);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, VersionAddress.LARGEST_HALF, HALF_REFUSED);
        if (!body.isObject() || body.size() != 2 || !body.has(REVISION) || !body.has(FIELDS)) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, HALF_REFUSED);
        }
        int revision = SeenRevision.in(body, HALF_REFUSED);
        DeclarationBody.Sent sent = DeclarationBody.read(body.get(FIELDS), declared, Demands.ofWorkflow(declared));
        return answer(drafts.declare(
                addressed.group(),
                addressed.entry(),
                addressed.version(),
                addressed.caller(),
                revision,
                sent.half(),
                sent.readAs()));
    }

    @PutMapping(STEPS)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    WorkflowAnswer flow(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        FlowBody.Sent sent = FlowBody.read(JsonBody.read(request, LARGEST_STEPS, FlowBody.REFUSED));
        return answer(drafts.flow(addressed.group(), addressed.entry(), addressed.version(), addressed.caller(), sent));
    }

    @PutMapping(CEILING)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    WorkflowAnswer ceiling(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, LARGEST_SETTING, CEILING_REFUSED);
        JsonNode ceiling = body.path(CEILING_MEMBER);
        JsonNode keeps = body.path(KEEPS_OWN_CEILING);
        JsonNode raise = body.path(RAISE_NEEDS_APPROVAL);
        if (!body.isObject()
                || body.size() != 4
                || !(ceiling.isString() || ceiling.isNull())
                || !keeps.isBoolean()
                || !raise.isBoolean()) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, CEILING_REFUSED);
        }
        int revision = SeenRevision.in(body, CEILING_REFUSED);
        return answer(drafts.ceiling(
                addressed.group(),
                addressed.entry(),
                addressed.version(),
                addressed.caller(),
                revision,
                ceiling.isNull() ? null : ceilingOf(ceiling.asString()),
                keeps.booleanValue(),
                raise.booleanValue()));
    }

    @PutMapping(HELP)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    WorkflowAnswer help(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, LARGEST_SETTING, HELP_REFUSED);
        JsonNode may = body.path(MAY_BE_HELPED);
        JsonNode helper = body.path(HELPER);
        if (!body.isObject()
                || body.size() != 3
                || !may.isBoolean()
                || !(helper.isObject() && helper.size() == 2 && helper.has(MODEL) && helper.has(MODE)
                        || helper.isNull())
                || (helper.isObject() && !may.booleanValue())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, HELP_REFUSED);
        }
        int revision = SeenRevision.in(body, HELP_REFUSED);
        ModelChoice chosen;
        try {
            chosen = helper.isNull() ? null : FlowBody.chosen(helper);
        } catch (ApiErrorException refused) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, HELP_REFUSED);
        }
        return answer(drafts.help(
                addressed.group(),
                addressed.entry(),
                addressed.version(),
                addressed.caller(),
                revision,
                may.booleanValue(),
                chosen));
    }

    private static Ceiling ceilingOf(String typed) {
        try {
            return Ceiling.typed(typed);
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.CEILING_UNUSABLE.raised(refused);
        }
    }

    static WorkflowAnswer answer(Workflows.WorkflowView view) {
        StoredWorkflow stored = view.stored();
        Map<EntryVersionId, PinnedVersions.PinnedVersion> pins = view.pins();
        Map<UUID, Integer> positions = stored.positions();
        Ceiling ceiling = stored.ceiling();
        ModelChoice helper = stored.helper();
        Workflows.Offers offers = view.offers();
        Map<UUID, List<String>> terms = HashMap.newHashMap(view.terms().size());
        view.terms()
                .forEach((list, offered) -> terms.put(
                        list.value(),
                        offered.terms().stream()
                                .map(each -> each.term().value())
                                .toList()));
        return new WorkflowAnswer(
                stored.revision(),
                DeclarationAnswers.of(stored.takes(), pins),
                DeclarationAnswers.of(stored.gives(), pins),
                stored.steps().stream()
                        .map(step -> stepAnswer(step, view, positions))
                        .toList(),
                stored.outputs().stream()
                        .map(binding -> bindingAnswer(binding, positions))
                        .toList(),
                ceiling == null ? null : Long.toString(ceiling.value()),
                stored.keepsOwnCeiling(),
                stored.raiseNeedsApproval(),
                stored.mayBeHelped(),
                helper == null ? null : choiceAnswer(helper),
                view.problems().stream()
                        .map(ContentProblemsOutlet::problemAnswer)
                        .toList(),
                view.sendsPast().stream()
                        .map(past -> new SendPastAnswer(
                                past.step(),
                                past.role().published(),
                                past.model().value(),
                                past.past()))
                        .toList(),
                terms,
                new OffersAnswer(
                        offers.lists().stream().map(DeclarationAnswers::offered).toList(),
                        offers.questions().stream()
                                .map(offered -> declaredAnswer(offered, pins))
                                .toList(),
                        offers.workflows().stream()
                                .map(offered -> declaredAnswer(offered, pins))
                                .toList(),
                        offers.codeSteps().stream()
                                .map(offered -> new CodeStepAnswer(
                                        offered.name(),
                                        DeclarationAnswers.of(offered.declared().takes(), pins),
                                        DeclarationAnswers.of(offered.declared().gives(), pins)))
                                .toList(),
                        offers.models().stream()
                                .map(WorkflowController::modelAnswer)
                                .toList()));
    }

    private static StepAnswer stepAnswer(
            StoredWorkflow.Step step, Workflows.WorkflowView view, Map<UUID, Integer> positions) {
        Map<EntryVersionId, PinnedVersions.PinnedVersion> pins = view.pins();
        RunsAnswer runs =
                switch (step.runs()) {
                    case StoredWorkflow.Runs.Unchosen ignored -> null;
                    case StoredWorkflow.Runs.Pinned pinned -> {
                        StoredDeclarations.Halves declared = declared(view, pinned.version());
                        yield new RunsAnswer(
                                pinned.kind() == EntryKind.QUESTION
                                        ? RunsKind.QUESTION.published()
                                        : RunsKind.WORKFLOW.published(),
                                pinnedAnswer(pins, pinned.version()),
                                DeclarationAnswers.of(declared.takes(), pins),
                                DeclarationAnswers.of(declared.gives(), pins),
                                null,
                                null,
                                null);
                    }
                    case StoredWorkflow.Runs.Code code -> {
                        StoredDeclarations.Halves declared = code.codeStep() == null
                                ? null
                                : view.codeSteps().get(code.codeStep());
                        yield new RunsAnswer(
                                RunsKind.CODE_STEP.published(),
                                null,
                                declared == null ? null : DeclarationAnswers.of(declared.takes(), pins),
                                declared == null ? null : DeclarationAnswers.of(declared.gives(), pins),
                                code.codeStep(),
                                null,
                                null);
                    }
                    case StoredWorkflow.Runs.Route route -> {
                        Binding chosenBy = route.discriminator();
                        yield new RunsAnswer(
                                RunsKind.ROUTE.published(),
                                null,
                                null,
                                DeclarationAnswers.of(route.gives(), pins),
                                null,
                                chosenBy == null ? null : bindingAnswer(chosenBy, positions),
                                route.cases().stream()
                                        .map(routeCase -> caseAnswer(routeCase, view, positions))
                                        .toList());
                    }
                };
        ModelChoice reviewer = step.reviewer();
        return new StepAnswer(
                step.id(),
                step.name().value(),
                runs,
                producerAnswer(step.producer()),
                step.tries(),
                reviewer == null ? null : choiceAnswer(reviewer),
                step.bindings().stream()
                        .map(binding -> bindingAnswer(binding, positions))
                        .toList());
    }

    private static CaseAnswer caseAnswer(
            RouteCase routeCase, Workflows.WorkflowView view, Map<UUID, Integer> positions) {
        EntryVersionId target = routeCase.target();
        StoredDeclarations.Halves declared = target == null ? null : declared(view, target);
        return new CaseAnswer(
                routeCase.id(),
                routeCase.term(),
                target == null ? null : pinnedAnswer(view.pins(), target),
                declared == null ? null : DeclarationAnswers.of(declared.takes(), view.pins()),
                declared == null ? null : DeclarationAnswers.of(declared.gives(), view.pins()),
                routeCase.bindings().stream()
                        .map(binding -> bindingAnswer(binding, positions))
                        .toList());
    }

    private static StoredDeclarations.Halves declared(Workflows.WorkflowView view, EntryVersionId version) {
        StoredDeclarations.Halves declared = view.declared().get(version);
        if (declared == null) {
            throw new IllegalStateException("A step pins version " + version.value() + ", which no reader declared");
        }
        return declared;
    }

    private static DeclarationAnswers.PinnedAnswer pinnedAnswer(
            Map<EntryVersionId, PinnedVersions.PinnedVersion> pins, EntryVersionId version) {
        PinnedVersions.PinnedVersion pinned = pins.get(version);
        if (pinned == null) {
            throw new IllegalStateException("A step pins version " + version.value() + ", which no reader named");
        }
        return DeclarationAnswers.pinned(pinned);
    }

    private static BindingAnswer bindingAnswer(Binding binding, Map<UUID, Integer> positions) {
        SourceAnswer source =
                switch (binding.source()) {
                    case BindingSource.WorkflowInput input ->
                        new InputSource(input.pointer().published());
                    case BindingSource.StepOutput output -> {
                        Integer at = positions.get(output.step());
                        if (at == null) {
                            throw new IllegalStateException("A binding reads a step its version does not hold");
                        }
                        yield new StepSource(at, output.pointer().published());
                    }
                    case BindingSource.Written written -> new ConstantSource(ConstantJson.written(written.constant()));
                };
        return new BindingAnswer(
                binding.id(), binding.target() == null ? null : binding.target().published(), source);
    }

    private static @Nullable ProducerAnswer producerAnswer(@Nullable Producer producer) {
        return switch (producer) {
            case null -> null;
            case Producer.Model model -> {
                ChoiceAnswer chosen = choiceAnswer(model.choice());
                yield new ProducerAnswer(
                        model.kind().published(), chosen.model(), chosen.mode(), model.toldWhatHappened());
            }
            case Producer.Person person -> new ProducerAnswer(person.kind().published(), null, null, null);
            case Producer.Code code -> new ProducerAnswer(code.kind().published(), null, null, null);
        };
    }

    private static ChoiceAnswer choiceAnswer(ModelChoice choice) {
        ModelMode mode = choice.mode();
        return new ChoiceAnswer(choice.model().value(), mode == null ? null : mode.value());
    }

    private static DeclaredAnswer declaredAnswer(
            Workflows.Offered offered, Map<EntryVersionId, PinnedVersions.PinnedVersion> pins) {
        return new DeclaredAnswer(
                offered.version().name().value(),
                offered.version().version().value(),
                offered.version().number(),
                DeclarationAnswers.of(offered.declared().takes(), pins),
                DeclarationAnswers.of(offered.declared().gives(), pins));
    }

    private static ModelAnswer modelAnswer(DeployedModel model) {
        return new ModelAnswer(
                model.name().value(),
                model.modes().stream().map(ModelMode::value).toList());
    }

    /**
     * @param revision how many times the draft's content has been written, which a write names as the one read
     * @param steps in the order they run
     * @param outputs what fills each value it gives back, in the order written
     * @param ceiling in digits, absent where runs of it have none
     * @param helper absent where no model is named to help
     * @param problems what submitting would refuse of it now, each placed as a refusal places it
     * @param sendsPast what its steps could send past what their models take now, which refuses nothing
     * @param terms the terms of each list any field it names pins, by the list version's key, each in its order
     * @param offered what a draft of it may choose from now
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WorkflowAnswer(
            int revision,
            List<DeclarationAnswers.FieldAnswer> takes,
            List<DeclarationAnswers.FieldAnswer> gives,
            List<StepAnswer> steps,
            List<BindingAnswer> outputs,
            @Nullable String ceiling,
            boolean keepsOwnCeiling,
            boolean raiseNeedsApproval,
            boolean mayBeHelped,
            @Nullable ChoiceAnswer helper,
            List<ContentProblemsOutlet.ProblemAnswer> problems,
            List<SendPastAnswer> sendsPast,
            Map<UUID, List<String>> terms,
            OffersAnswer offered) {}

    /**
     * @param role what the step asks the model to do, as {@link SendingRole} spells it
     * @param past about how many of the model's own units more than it takes
     */
    record SendPastAnswer(UUID stepId, String role, String model, long past) {}

    /**
     * @param runs absent where nothing is chosen yet
     * @param producer absent where nothing is chosen yet, and where what it runs has none
     * @param tries absent where nothing is chosen yet, and where what it runs makes none
     * @param reviewer the model reviewing its productions, absent where a person does or nobody does
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StepAnswer(
            UUID stepId,
            String name,
            @Nullable RunsAnswer runs,
            @Nullable ProducerAnswer producer,
            @Nullable Integer tries,
            @Nullable ChoiceAnswer reviewer,
            List<BindingAnswer> bindings) {}

    /**
     * Only the members its kind takes: a version and what it declares for a question or a workflow, a code
     * step's name for one and what the release declares of it where the group may name it, and for a route what it
     * chooses by, what it gives back and its cases.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunsAnswer(
            String kind,
            DeclarationAnswers.@Nullable PinnedAnswer version,
            @Nullable List<DeclarationAnswers.FieldAnswer> takes,
            @Nullable List<DeclarationAnswers.FieldAnswer> gives,
            @Nullable String codeStep,
            @Nullable BindingAnswer discriminator,
            @Nullable List<CaseAnswer> cases) {}

    /** @param term absent for the fallback */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CaseAnswer(
            UUID caseId,
            @Nullable String term,
            DeclarationAnswers.@Nullable PinnedAnswer workflow,
            @Nullable List<DeclarationAnswers.FieldAnswer> takes,
            @Nullable List<DeclarationAnswers.FieldAnswer> gives,
            List<BindingAnswer> bindings) {}

    /** @param target absent only on what a route chooses by */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BindingAnswer(UUID bindingId, @Nullable String target, SourceAnswer source) {}

    /** Where a bound value comes from, each answered by its own member alone. */
    sealed interface SourceAnswer permits InputSource, StepSource, ConstantSource {}

    record InputSource(String input) implements SourceAnswer {}

    /** @param step the place of the step read among the steps, counted from zero */
    record StepSource(int step, String path) implements SourceAnswer {}

    /** @param constant its canonical JSON text, so no number reaches a page as a double */
    record ConstantSource(String constant) implements SourceAnswer {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProducerAnswer(
            String kind,
            @Nullable String model,
            @Nullable String mode,
            @Nullable Boolean toldWhatHappened) {}

    /** @param mode absent where the model runs as it is */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChoiceAnswer(String model, @Nullable String mode) {}

    record OffersAnswer(
            List<DeclarationAnswers.OfferedAnswer> lists,
            List<DeclaredAnswer> questions,
            List<DeclaredAnswer> workflows,
            List<CodeStepAnswer> codeSteps,
            List<ModelAnswer> models) {}

    record DeclaredAnswer(
            String name,
            UUID versionId,
            int number,
            List<DeclarationAnswers.FieldAnswer> takes,
            List<DeclarationAnswers.FieldAnswer> gives) {}

    /** What this release declares of a code step, each field under a key that holds while the release does. */
    record CodeStepAnswer(
            String name, List<DeclarationAnswers.FieldAnswer> takes, List<DeclarationAnswers.FieldAnswer> gives) {}

    /** @param modes those it offers beyond running as it is, in the order the deployment lists them */
    record ModelAnswer(String name, List<String> modes) {}
}
