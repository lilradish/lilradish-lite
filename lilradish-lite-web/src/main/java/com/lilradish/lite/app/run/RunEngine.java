package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.files.FileFillings;
import com.lilradish.lite.app.files.RunFiles;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.app.inference.AnswerReader;
import com.lilradish.lite.app.inference.ToolUseLimit;
import com.lilradish.lite.app.library.EntryLetGo;
import com.lilradish.lite.domain.codestep.ReleasedCodeStep;
import com.lilradish.lite.domain.declaration.AskedField;
import com.lilradish.lite.domain.declaration.Asking;
import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.file.StoredFile;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.filling.FillPath;
import com.lilradish.lite.domain.filling.FilledFields;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.inference.CallRequest;
import com.lilradish.lite.domain.inference.Envelope;
import com.lilradish.lite.domain.inference.ModelCallOutcome;
import com.lilradish.lite.domain.inference.ModelCallPurpose;
import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.DeployedTool;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.model.SentText;
import com.lilradish.lite.domain.registry.EntryId;
import com.lilradish.lite.domain.run.AttemptRecord;
import com.lilradish.lite.domain.run.CallRecord;
import com.lilradish.lite.domain.run.EngineAct;
import com.lilradish.lite.domain.run.EngineActs;
import com.lilradish.lite.domain.run.FailureRecord;
import com.lilradish.lite.domain.run.HoldRecord;
import com.lilradish.lite.domain.run.InputRecord;
import com.lilradish.lite.domain.run.ModelCallId;
import com.lilradish.lite.domain.run.PlannedStep;
import com.lilradish.lite.domain.run.ProductionId;
import com.lilradish.lite.domain.run.ReviewPayload;
import com.lilradish.lite.domain.run.ReviewSending;
import com.lilradish.lite.domain.run.ReviewUnbuiltReason;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunPayloads;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunStepFailureId;
import com.lilradish.lite.domain.run.RunStepFailureReason;
import com.lilradish.lite.domain.run.RunStepHoldReason;
import com.lilradish.lite.domain.run.RunStepId;
import com.lilradish.lite.domain.run.RunStepSendAttemptId;
import com.lilradish.lite.domain.run.StepAct;
import com.lilradish.lite.domain.run.StepGround;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepPosition;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.ValueRecord;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.domain.search.QuestionSearchShape;
import com.lilradish.lite.domain.search.SearchAnswer;
import com.lilradish.lite.domain.search.SearchWay;
import com.lilradish.lite.domain.wire.CanonicalJson;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonArray;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.wire.JsonValue.JsonNull;
import com.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.workflow.Binding;
import com.lilradish.lite.domain.workflow.BindingSource;
import com.lilradish.lite.domain.workflow.ModelChoice;
import com.lilradish.lite.domain.workflow.Producer;
import com.lilradish.lite.domain.workflow.StepProducer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Under the tree's lock, makes one act at a time until none follows.
 * Work outside the store is handed to the engine's threads after commit, so the lock never waits on it.
 */
@Component
public final class RunEngine implements EntryLetGo {

    private static final Logger logger = LoggerFactory.getLogger(RunEngine.class);

    // DB-SPECIFIC: an enum compared to a literal is PostgreSQL's.
    private static final String CHILD_TAKES_FILE = """
            select exists (select 1 from declaration_fields field
                            where field.entry_version_id = :version
                              and field.side = 'takes' and field.kind = 'file')
            """;

    // DB-SPECIFIC: PostgreSQL's native run_step_hold_reason enum is compared with 'entry_stopped'.
    /* Unlocked: which trees to drive is only a lead, each re-read under its own lock before anything is released. */
    private static final String HELD_ON_ENTRY = """
            select distinct run.group_id, run.root_run_id
              from run_step_holds hold
              join run_steps step on step.run_step_id = hold.run_step_id
              join runs run on run.run_id = hold.run_id
              left join entry_versions pinned on pinned.entry_version_id = step.pinned_version_id
             where hold.released_at is null and hold.reason = 'entry_stopped'
               and (run.entry_id = :entry or pinned.entry_id = :entry
                    or exists (select 1 from route_cases routed
                                 join entry_versions target on target.entry_version_id = routed.target_version_id
                                where routed.workflow_step_id = step.workflow_step_id and target.entry_id = :entry))
            """;

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final RunTree tree;

    private final RunSnapshots snapshots;

    private final EngineWrites writes;

    private final EngineExecutor executor;

    private final EngineCalls calls;

    private final EngineCodes codes;

    private final ModelCatalog catalog;

    private final CeilingReach ceilings;

    private final GroupRoles roles;

    private final RunDeployment deployment;

    private final RunFiles files;

    RunEngine(
            JdbcClient database,
            TransactionOperations transactions,
            RunTree tree,
            RunSnapshots snapshots,
            EngineWrites writes,
            EngineExecutor executor,
            EngineCalls calls,
            EngineCodes codes,
            ModelCatalog catalog,
            CeilingReach ceilings,
            GroupRoles roles,
            RunDeployment deployment,
            RunFiles files) {
        this.database = database;
        this.transactions = transactions;
        this.tree = tree;
        this.snapshots = snapshots;
        this.writes = writes;
        this.executor = executor;
        this.calls = calls;
        this.codes = codes;
        this.catalog = catalog;
        this.ceilings = ceilings;
        this.roles = roles;
        this.deployment = deployment;
        this.files = files;
    }

    /**
     * Inside the transaction that wrote the run at the top of {@code started}, its tree held: the run's first
     * step is planned there, as far as it goes by itself, and only what that plans outside the store is handed
     * over once the transaction commits.
     */
    public void planStarted(LockedTree started) {
        requireNonNull(started, "RunEngine started must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("A run is planned only inside the transaction that started it");
        }
        planWithin(started);
    }

    /** After a let-go has committed, off the committing thread: each run its stop held goes on. */
    @Override
    public void goesOn(EntryId entry) {
        requireNonNull(entry, "RunEngine entry must not be null");
        executor.execute(entry, () -> released(entry));
    }

    /** Outside any transaction: the run goes as far as it goes by itself, in one short transaction. */
    void drive(GroupId group, RunId root) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run " + root.value() + " was driven inside a transaction it would join");
        }
        transactions.executeWithoutResult(status -> tree.lock(group, root).ifPresent(this::planWithin));
    }

    /**
     * After the caller's act has committed, outside its transaction: the run goes on as {@link #drive} takes it,
     * and one that cannot is logged and read as where it stopped, the act having landed all the same.
     */
    void goOn(GroupId group, RunId root) {
        try {
            drive(group, root);
        } catch (RuntimeException failed) {
            logger.error("Run {} of group {} could not go on by itself", root.value(), group.value(), failed);
        }
    }

    void endWaitingCalls(LockedTree held) {
        writes.endWaitingCalls(held);
    }

    /** The tree stopped for {@code run}'s ceiling now in force, where what it and every run beneath spent reached it. */
    void stopAtReachedCeilingOf(LockedTree held, RunId run) {
        ceilings.stopsAtCeilingOf(held, run);
    }

    /*
     * After a hand-over's task: gone on from even where it wrote nothing, since a stop on what the step runs, made
     * meanwhile, is held only so. Only one that wrote nothing once stopping is left as it reads, for the next start.
     */
    private void goOnAfter(boolean written, GroupId group, RunId root) {
        if (written || !executor.stopping()) {
            goOn(group, root);
        }
    }

    /**
     * Inside the caller's transaction, the tree held: every act the run makes of itself, each worked out afresh
     * from the store, until it makes none. What no approved version could lead to fails the transaction, and
     * what is to be done outside it is handed over only once it commits.
     */
    void planWithin(LockedTree held) {
        EngineAct last = null;
        boolean startedBeneath = false;
        while (true) {
            RunInTree root = snapshots.inTreeLocked(held, held.root());
            EngineAct act = EngineActs.next(root, deployment.maximumDepth());
            // Once stopping, what goes out is left as it reads, a model's try unsent, a code step's not yet made.
            if (act instanceof EngineAct.Nothing || (executor.stopping() && goesOut(act))) {
                return;
            }
            // Every act changes what the next is worked out from; one worked out again was never made.
            if (act.equals(last)) {
                throw new IllegalStateException("Run " + act.run().value() + " made no change acting on step "
                        + actedOn(act).value());
            }
            RunInTree at = root.find(act.run())
                    .orElseThrow(
                            () -> new IllegalStateException("Run " + act.run().value() + " is not in the tree of "
                                    + held.root().value()));
            if (act instanceof EngineAct.StartBeneath start) {
                // One run beneath each time a tree is planned: a workflow reaching itself would keep the lock for ever.
                if (startedBeneath) {
                    if (!executor.stopping()) {
                        TransactionSynchronizationManager.registerSynchronization(
                                new HandOver(new PendingDrive(held.group(), held.root())));
                    }
                    return;
                }
                startBeneath(held, at, start);
                startedBeneath = true;
                last = act;
                continue;
            }
            Pending pending = apply(held, at, act);
            // Nothing is written for a code step's try or a model's call until the engine's thread makes it, so no
            // act follows here: the next worked out would be this one again.
            if (pending != null) {
                TransactionSynchronizationManager.registerSynchronization(new HandOver(pending));
                return;
            }
            last = act;
        }
    }

    private static boolean goesOut(EngineAct act) {
        return act instanceof EngineAct.Send
                || act instanceof EngineAct.Review
                || (act instanceof EngineAct.StartTry start && start.step().producer() instanceof Producer.Code);
    }

    /** Inside the caller's transaction, the tree held: the run beneath {@code start} names written. */
    private void startBeneath(LockedTree held, RunInTree at, EngineAct.StartBeneath start) {
        RunSnapshot run = at.run();
        StepSnapshot step = stepOf(run, start.step().id().value());
        StepInputs.BindingRecord discriminator = step.planned().runs() instanceof StepRuns.Route route
                ? StepInputs.bound(at, route.discriminator())
                        .orElseThrow(() -> new IllegalStateException("Route "
                                + step.planned().name().value() + " chose a case by a value that does not stand"))
                : null;
        RunId child = writes.beneath(held, run, step, start.version(), start.routeCase(), discriminator);
        if (!database.sql(CHILD_TAKES_FILE)
                .param("version", start.version().value())
                .query(Boolean.class)
                .single()) {
            return;
        }
        RunInTree beneath = snapshots.inTreeLocked(held, child);
        StepInputs.WorkflowInput input = StepInputs.workflowInput(beneath);
        if (!input.files().isEmpty()) {
            Map<FillPath, RunFiles.VerifiedBatch> batches = new HashMap<>();
            List<FillField> fields = FillField.of(
                    beneath.run().workflow().takes().fields(),
                    beneath.run().workflow().lists());
            JsonObject marked =
                    childMarkers(fields, input.values(), List.of(), input.files(), batches, child, run.group(), files);
            if (!(FileFillings.of(child, run.group(), fields, marked, batches) instanceof FilledFields)) {
                throw new IllegalStateException("A child run's file bindings did not fill its inputs");
            }
            holdBeneathOutputs(
                    beneath,
                    FillField.of(
                                    beneath.run().workflow().gives().fields(),
                                    beneath.run().workflow().lists())
                            .stream()
                            .filter(RunEngine::containsFile)
                            .map(field -> field.name().value())
                            .toList(),
                    files);
        }
    }

    static void holdBeneathOutputs(RunInTree changed, List<String> changedFields, RunFiles files) {
        RunInTree child = changed;
        List<String> affected = changedFields;
        while (child.above() != null && !affected.isEmpty()) {
            RunInTree parent = requireNonNull(child.above());
            List<String> promoted = new ArrayList<>();
            List<FillField> fields = FillField.of(
                    child.run().workflow().gives().fields(),
                    child.run().workflow().lists());
            for (FillField field : fields) {
                if (!affected.contains(field.name().value()) || !containsFile(field)) {
                    continue;
                }
                List<Binding> outputs = child.run().workflow().outputs().stream()
                        .filter(output -> requireNonNull(output.target())
                                .names()
                                .getFirst()
                                .equals(field.name()))
                        .toList();
                if (outputs.isEmpty()) {
                    continue;
                }
                List<StepInputs.BindingRecord> bound = new ArrayList<>();
                for (Binding output : outputs) {
                    StepInputs.bound(child, output).ifPresent(bound::add);
                }
                if (bound.size() != outputs.size()) {
                    continue;
                }
                Map<FillPath, StepInputs.FileSource> sources = StepInputs.fileSources(
                        child, bound, child.run().workflow().gives());
                if (sources.isEmpty()) {
                    continue;
                }
                Map<FillPath, RunFiles.VerifiedBatch> batches = new HashMap<>();
                JsonObject marked = childMarkers(
                        List.of(field),
                        StepInputs.filled(child.run().workflow().gives(), bound),
                        List.of(),
                        sources,
                        batches,
                        parent.run().run(),
                        parent.run().group(),
                        files);
                if (!(FileFillings.of(parent.run().run(), parent.run().group(), List.of(field), marked, batches)
                        instanceof FilledFields)) {
                    throw new IllegalStateException("A child run's file output did not fill its parent");
                }
                promoted.add(field.name().value());
            }
            List<String> next = new ArrayList<>();
            UUID step = requireNonNull(child.from()).planned().id().value();
            for (Binding output : parent.run().workflow().outputs()) {
                if (output.source() instanceof BindingSource.StepOutput source
                        && source.step().equals(step)
                        && promoted.contains(source.pointer().names().getFirst().value())) {
                    String target =
                            requireNonNull(output.target()).names().getFirst().value();
                    if (!next.contains(target)) {
                        next.add(target);
                    }
                }
            }
            child = parent;
            affected = next;
        }
    }

    private static boolean containsFile(FillField field) {
        if (field.kind() == FieldKind.FILE) {
            return true;
        }
        return field.kind() == FieldKind.FIELDS && field.fields().stream().anyMatch(RunEngine::containsFile);
    }

    private static JsonObject childMarkers(
            List<FillField> fields,
            JsonObject values,
            List<FillPath.Step> parent,
            Map<FillPath, StepInputs.FileSource> sources,
            Map<FillPath, RunFiles.VerifiedBatch> batches,
            RunId child,
            GroupId group,
            RunFiles files) {
        List<JsonMember> members = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            JsonValue value = StepInputs.within(values, List.of(field.name()));
            List<FillPath.Step> at = new ArrayList<>(parent);
            at.add(new FillPath.Named(field.name()));
            JsonValue marked = value;
            if (field.kind() == FieldKind.FILE && !(value instanceof JsonNull)) {
                FillPath path = new FillPath(at);
                RunFiles.Batch batch = files.begin(child, group, field);
                if (value instanceof JsonArray many) {
                    List<JsonValue> markers = new ArrayList<>(many.items().size());
                    for (int index = 0; index < many.items().size(); index++) {
                        List<FillPath.Step> item = new ArrayList<>(at);
                        item.add(new FillPath.Place(index));
                        keepBoundFile(
                                batch, index, new FillPath(item), many.items().get(index), sources);
                        markers.add(keptMarker(index));
                    }
                    marked = new JsonArray(markers);
                } else {
                    keepBoundFile(batch, 0, path, value, sources);
                    marked = keptMarker(0);
                }
                batches.put(path, batch.finish());
            } else if (field.kind() == FieldKind.FIELDS && value instanceof JsonObject nested) {
                marked = childMarkers(field.fields(), nested, at, sources, batches, child, group, files);
            } else if (field.kind() == FieldKind.FIELDS && value instanceof JsonArray many) {
                List<JsonValue> markers = new ArrayList<>(many.items().size());
                for (int index = 0; index < many.items().size(); index++) {
                    if (!(many.items().get(index) instanceof JsonObject nested)) {
                        throw new IllegalStateException("A nested file value was not an object");
                    }
                    List<FillPath.Step> item = new ArrayList<>(at);
                    item.add(new FillPath.Place(index));
                    markers.add(childMarkers(field.fields(), nested, item, sources, batches, child, group, files));
                }
                marked = new JsonArray(markers);
            }
            members.add(new JsonMember(field.name().value(), marked));
        }
        return new JsonObject(members);
    }

    private static void keepBoundFile(
            RunFiles.Batch batch,
            int position,
            FillPath path,
            JsonValue value,
            Map<FillPath, StepInputs.FileSource> sources) {
        StepInputs.FileSource source = sources.get(path);
        if (source == null || !StoredFile.fromKept(value).id().equals(source.file())) {
            throw new IllegalStateException("A bound file has no matching trusted source");
        }
        batch.reuse(position, position, source.run(), source.file());
    }

    private static JsonObject keptMarker(int index) {
        return new JsonObject(List.of(new JsonMember("keep", new JsonNumber(BigDecimal.valueOf(index)))));
    }

    private @Nullable Pending apply(LockedTree held, RunInTree at, EngineAct act) {
        RunSnapshot run = at.run();
        if (!act.run().equals(run.run())) {
            throw new IllegalStateException(
                    "Run " + act.run().value() + " was acted on as " + run.run().value());
        }
        return switch (act) {
            case EngineAct.StartTry start ->
                startTry(held, at, stepOf(run, start.step().id().value()), start.number(), null);
            case EngineAct.Send send ->
                new PendingSend(
                        run.group(),
                        run.root(),
                        run.run(),
                        send.step().id(),
                        send.aTry(),
                        ModelCallPurpose.PRODUCE,
                        null,
                        null,
                        null);
            case EngineAct.Review review ->
                new PendingSend(
                        run.group(),
                        run.root(),
                        run.run(),
                        review.step().id(),
                        review.aTry(),
                        ModelCallPurpose.REVIEW,
                        null,
                        null,
                        null);
            case EngineAct.HoldOnStop hold -> {
                StepSnapshot step = stepOf(run, hold.step().id().value());
                writes.held(held, run, writes.runStep(held, run, step), RunStepHoldReason.ENTRY_STOPPED);
                yield null;
            }
            case EngineAct.HoldNotHeld hold -> {
                StepSnapshot step = stepOf(run, hold.step().id().value());
                if (hold.source() != null) {
                    writes.heldOnSource(held, run, writes.runStep(held, run, step), hold.source());
                } else {
                    if (step.hold() != null && step.hold().source() != null) {
                        writes.released(
                                held,
                                run,
                                requireNonNull(step.runStep()),
                                step.hold().reason());
                    }
                    writes.held(
                            held,
                            run,
                            writes.runStep(held, run, step),
                            step.planned().runs() instanceof StepRuns.Search
                                    ? RunStepHoldReason.SEARCH_WAY_NOT_HELD
                                    : RunStepHoldReason.CODE_STEP_NOT_HELD);
                }
                yield null;
            }
            case EngineAct.ReleaseHold release -> {
                StepSnapshot step = stepOf(run, release.step().id().value());
                HoldRecord hold = requireNonNull(step.hold(), "a hold released is one standing");
                TryRecord open = step.newest().filter(TryRecord::open).orElse(null);
                if (hold.reason() != RunStepHoldReason.ENTRY_STOPPED
                        && step.planned().producer() instanceof Producer.Model
                        && open != null) {
                    yield new PendingSend(
                            run.group(),
                            run.root(),
                            run.run(),
                            step.planned().id(),
                            open.id(),
                            ModelCallPurpose.PRODUCE,
                            null,
                            null,
                            null);
                }
                writes.released(
                        held, run, requireNonNull(step.runStep(), "a hold is on a step written"), hold.reason());
                yield null;
            }
            case EngineAct.StartBeneath ignored ->
                throw new IllegalStateException("A run beneath is started only as the tree is planned, one at a time");
            case EngineAct.FailUnclaimed failure -> {
                writes.unclaimed(held, run, stepOf(run, failure.step().id().value()), failure.said());
                yield null;
            }
            case EngineAct.FailTooDeep failure -> {
                writes.tooDeep(held, run, stepOf(run, failure.step().id().value()));
                yield null;
            }
            case EngineAct.FailSearchShape failure -> {
                writes.searchQuestionMismatch(
                        held, run, stepOf(run, failure.step().id().value()));
                yield null;
            }
            case EngineAct.Nothing ignored -> null;
        };
    }

    /**
     * Inside the caller's transaction, the tree held: try {@code number} of {@code step} asked of whoever the step
     * names, by the system where {@code asker} is none and otherwise by that person. The one way a try is started,
     * whether the run asks it by itself or a person asks again. A code step's try is only planned here, and what is
     * to make it is returned, to be handed over once this commits.
     */
    @Nullable
    PendingCode startTry(LockedTree held, RunInTree at, StepSnapshot step, int number, @Nullable UserId asker) {
        RunSnapshot run = at.run();
        return switch (step.planned().producer()) {
            case Producer.Person person -> {
                tried(held, at, step, number, person, asker);
                yield null;
            }
            case Producer.Model model -> {
                tried(held, at, step, number, model, asker);
                yield null;
            }
            case Producer.Code ignored -> {
                if (!(step.planned().runs() instanceof StepRuns.Code)) {
                    throw new IllegalStateException("Only a code step's try is made by code");
                }
                yield new PendingCode(
                        run.group(), run.root(), run.run(), step.planned().id(), number, asker);
            }
            case null -> throw new IllegalStateException("Only a question's or a code step's try is started here");
        };
    }

    /** Inside the caller's transaction, the tree held: where code is to make the try, it is handed over once this commits. */
    void askAgain(LockedTree held, RunInTree at, StepSnapshot step, int number, UserId asker) {
        requireNonNull(asker, "RunEngine asker must not be null");
        PendingCode pending = startTry(held, at, step, number, asker);
        if (pending != null && !executor.stopping()) {
            TransactionSynchronizationManager.registerSynchronization(new HandOver(pending));
        }
    }

    /**
     * Inside the caller's transaction, the tree held: what {@code step} offers Try sending on handed over to be sent
     * again as {@code presser}'s act once this commits, naming what was pressed on: the hold's attempt, the failure in
     * force, or the attempt whose call to review its values was turned away. Nothing of it is written here.
     */
    void trySending(LockedTree held, RunSnapshot run, StepSnapshot step, UserId presser) {
        requireNonNull(presser, "RunEngine presser must not be null");
        held.requireHeld();
        FailureRecord current = StepPositions.inForce(step).orElse(null);
        if (current != null && current.reason() == RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES) {
            StepRuns.Search search = (StepRuns.Search) step.planned().runs();
            if (search.released() == null
                    || !search.way().equals(search.markedWay())
                    || !QuestionSearchShape.matches(
                            search.released(), search.question().gives().fields())) {
                return;
            }
            writes.searchQuestionResolved(held, run, step, current.id(), presser);
            planWithin(held);
            return;
        }
        TryRecord pressed = step.newest()
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + step.planned().name().value() + " offers Try sending on no try"));
        HoldRecord hold = step.hold();
        FailureRecord failure = hold == null ? StepPositions.inForce(step).orElse(null) : null;
        ModelCallPurpose purpose;
        RunStepSendAttemptId attemptOn = null;
        RunStepFailureId failedOn = null;
        if (hold != null) {
            purpose = ModelCallPurpose.PRODUCE;
            attemptOn = requireNonNull(hold.attempt(), "a hold Try sending is offered on names its attempt");
        } else if (failure != null) {
            purpose = requireNonNull(failure.purpose(), "a failure Try sending is offered on names what it sent for");
            failedOn = failure.id();
        } else {
            purpose = ModelCallPurpose.REVIEW;
            attemptOn = ReviewSending.newestCall(pressed)
                    .map(CallRecord::attempt)
                    .orElseThrow(() -> offeredOnNothing(step));
        }
        // A press lost with this process before the engine's thread writes it leaves the step offering it still.
        if (!executor.stopping()) {
            TransactionSynchronizationManager.registerSynchronization(new HandOver(new PendingSend(
                    run.group(),
                    run.root(),
                    run.run(),
                    step.planned().id(),
                    pressed.id(),
                    purpose,
                    presser,
                    attemptOn,
                    failedOn)));
        }
    }

    private static IllegalStateException offeredOnNothing(StepSnapshot step) {
        return new IllegalStateException("Step " + step.planned().name().value()
                + " offered Try sending on no hold, no failure and no review turned away");
    }

    private void tried(
            LockedTree held, RunInTree at, StepSnapshot step, int number, Producer producer, @Nullable UserId asker) {
        RunSnapshot run = at.run();
        if (producer instanceof Producer.Model && heldOnSource(held, at, step)) {
            return;
        }
        switch (step.planned().runs()) {
            case StepRuns.Question question -> {
                List<InputRecord> inputs =
                        StepInputs.traced(at, step, question.takes()).orElseThrow(() -> unreached(step));
                writes.tried(held, run, writes.runStep(held, run, step), number, producer.kind(), asker, null, inputs);
            }
            // Recorded as bound, not traced: what the release takes now may no longer be what the version binds.
            case StepRuns.Code code -> {
                ReleasedCodeStep released =
                        requireNonNull(code.released(), "a code step asked of a person is one the release holds");
                List<InputRecord> inputs = StepInputs.bound(at, step).orElseThrow(() -> unreached(step)).stream()
                        .map(each -> new InputRecord(each.binding().id(), each.source(), each.parts()))
                        .toList();
                writes.tried(
                        held,
                        run,
                        writes.runStep(held, run, step),
                        number,
                        producer.kind(),
                        asker,
                        released.mayRunAgain(),
                        inputs);
            }
            case StepRuns.Search search -> {
                List<InputRecord> inputs =
                        StepInputs.traced(at, step, search.question().takes()).orElseThrow(() -> unreached(step));
                writes.tried(held, run, writes.runStep(held, run, step), number, producer.kind(), asker, null, inputs);
            }
            case StepRuns.Workflow ignored -> throw new IllegalStateException("A workflow's try is never asked");
            case StepRuns.Route ignored -> throw new IllegalStateException("A route's try is never asked");
        }
    }

    private static IllegalStateException unreached(StepSnapshot step) {
        return new IllegalStateException(
                "Step " + step.planned().name().value() + " was reached before what it takes stood");
    }

    /**
     * On the engine's thread, outside any transaction: {@code pending} written down as sent in a short transaction
     * of its own, then made. Nothing is written unless it is still the run's next act and nothing new is going out;
     * where the ceiling is reached, the run is stopped instead and nothing is sent.
     */
    private void sendAndEnd(PendingSend pending) {
        requireNonNull(pending, "RunEngine pending must not be null");
        if (!(Thread.currentThread() instanceof EngineThread)) {
            throw new IllegalStateException("Step " + pending.step().value() + " was to be sent off the run engine");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Step " + pending.step().value() + " was to be sent inside a transaction it would hold open");
        }
        Written written = transactions.execute(status -> sending(pending));
        PendingCall call = written == null ? null : written.call();
        if (call != null) {
            calls.callAndEnd(call);
        }
        goOnAfter(written != null, pending.group(), pending.root());
    }

    /** What was written, none where nothing was: the call to make once this commits, if it was not held or stopped. */
    private @Nullable Written sending(PendingSend pending) {
        LockedTree held = tree.lock(pending.group(), pending.root())
                .orElseThrow(() -> new IllegalStateException("Group "
                        + pending.group().value() + " holds no run "
                        + pending.root().value() + " for step " + pending.step().value()));
        RunInTree at = snapshots.inTreeLocked(held, pending.run());
        RunSnapshot run = at.run();
        StepSnapshot step = run.step(pending.step())
                .orElseThrow(
                        () -> new IllegalStateException("Run " + pending.run().value() + " holds no step "
                                + pending.step().value()));
        // Judged once the lock is held: a stop, stopping, or this try handed over twice may have come first.
        if (executor.stopping()) {
            return null;
        }
        UserId presser = pending.presser();
        if (presser != null) {
            return sentAgain(held, at, step, pending, presser);
        }
        HoldRecord resuming = step.hold();
        TryRecord open = step.newest()
                .filter(each -> each.id().equals(pending.aTry()) && each.open())
                .orElse(null);
        if (pending.purpose() == ModelCallPurpose.PRODUCE
                && resuming != null
                && resuming.attempt() == null
                && resuming.reason() != RunStepHoldReason.ENTRY_STOPPED
                && open != null) {
            if (!EngineActs.next(at, deployment.maximumDepth())
                    .equals(new EngineAct.ReleaseHold(run.run(), step.planned()))) {
                return null;
            }
            if (ceilings.stopsAt(held, run.run(), null)) {
                return new Written(null);
            }
            return new Written(resumeProduction(held, at, step, open, resuming));
        }
        EngineAct act =
                switch (pending.purpose()) {
                    case PRODUCE -> new EngineAct.Send(run.run(), step.planned(), pending.aTry());
                    case REVIEW -> new EngineAct.Review(run.run(), step.planned(), pending.aTry());
                    case HELP -> throw new IllegalStateException("A run's step is never sent to its helper");
                };
        EngineAct next = EngineActs.next(at, deployment.maximumDepth());
        if (!next.equals(act)) {
            if (pending.purpose() == ModelCallPurpose.PRODUCE
                    && open != null
                    && (next instanceof EngineAct.HoldNotHeld || next instanceof EngineAct.FailSearchShape)) {
                apply(held, at, next);
                return new Written(null);
            }
            return null;
        }
        if (ceilings.stopsAt(held, run.run(), null)) {
            return new Written(null);
        }
        return new Written(
                switch (act) {
                    case EngineAct.Send send -> send(held, at, step, send.aTry(), null, null, null);
                    case EngineAct.Review review -> review(held, at, step, review.aTry(), null, null);
                    case EngineAct.StartTry ignored -> throw notSent();
                    case EngineAct.HoldOnStop ignored -> throw notSent();
                    case EngineAct.HoldNotHeld ignored -> throw notSent();
                    case EngineAct.ReleaseHold ignored -> throw notSent();
                    case EngineAct.StartBeneath ignored -> throw notSent();
                    case EngineAct.FailUnclaimed ignored -> throw notSent();
                    case EngineAct.FailTooDeep ignored -> throw notSent();
                    case EngineAct.FailSearchShape ignored -> throw notSent();
                    case EngineAct.Nothing ignored -> throw notSent();
                });
    }

    private static IllegalStateException notSent() {
        return new IllegalStateException("Only a try to produce or to review is sent");
    }

    /**
     * Pressed: sent only while the presser may still start a run in the group, the step still offers Try sending on
     * what was pressed, judged as the press was, and the ceiling is not reached. A hold is released first, by the
     * system, and whatever is sent is measured again; values to review are sent as the run would send them itself.
     */
    private @Nullable Written sentAgain(
            LockedTree held, RunInTree at, StepSnapshot step, PendingSend pending, UserId presser) {
        RunSnapshot run = at.run();
        if (!stillPermitted(presser, pending.group())) {
            return null;
        }
        TryRecord newest =
                step.newest().filter(each -> each.id().equals(pending.aTry())).orElse(null);
        StepPosition position = StepPositions.of(run, step);
        boolean offered = newest != null
                && pressedOn(step, position, newest, pending)
                && StepAct.TRY_SENDING.refusal(StepGround.of(run, step, position, null)) == null;
        if (!offered) {
            return null;
        }
        if (ceilings.stopsAt(held, run.run(), null)) {
            return new Written(null);
        }
        RunStepFailureId answers = pending.failedOn();
        if (pending.purpose() == ModelCallPurpose.REVIEW) {
            return new Written(review(held, at, step, pending.aTry(), answers, presser));
        }
        if (heldOnSource(held, at, step)) {
            return new Written(null);
        }
        HoldRecord hold = step.hold();
        if (hold == null) {
            return new Written(send(held, at, step, pending.aTry(), null, answers, presser));
        }
        writes.releasedToSend(held, run, requireNonNull(step.runStep(), "a step held back is written"), hold.reason());
        if (hold.reason() == RunStepHoldReason.TURNED_AWAY) {
            RunStepSendAttemptId turnedAway = requireNonNull(hold.attempt(), "a hold on a turnaway names its attempt");
            EngineWrites.SentRecord stored = writes.stored(held, turnedAway);
            // One sent under an envelope this release no longer holds is built afresh, never resent as it was.
            return new Written(send(
                    held,
                    at,
                    step,
                    pending.aTry(),
                    stored.envelopeVersion() == Envelope.VERSION ? stored : null,
                    null,
                    presser));
        }
        // Built afresh from what the try was traced to take, which stands while nothing refuses a value for length.
        return new Written(send(held, at, step, pending.aTry(), null, null, presser));
    }

    /* As the press named it and where it stands still: the hold's attempt, the failure in force, or the review turned
     * away. Anything written on the step since, a hold, a failure or an attempt of its own, is one it did not name. */
    private static boolean pressedOn(StepSnapshot step, StepPosition position, TryRecord newest, PendingSend pending) {
        HoldRecord hold = step.hold();
        RunStepFailureId failedOn = pending.failedOn();
        if (failedOn != null) {
            return hold == null
                    && StepPositions.inForce(step)
                            .filter(failure -> failure.id().equals(failedOn) && failure.purpose() == pending.purpose())
                            .isPresent();
        }
        RunStepSendAttemptId attemptOn = requireNonNull(pending.attemptOn(), "a press names what it was made on");
        if (pending.purpose() == ModelCallPurpose.REVIEW) {
            return position instanceof StepPosition.AwaitingReview waiting
                    && waiting.sending() == ReviewSending.TURNED_AWAY
                    && ReviewSending.newestCall(newest)
                            .map(CallRecord::attempt)
                            .filter(attemptOn::equals)
                            .isPresent();
        }
        return hold != null && attemptOn.equals(hold.attempt());
    }

    /* Asked again as the request asked it, the group's row held: a presser removed since, or no longer reaching it,
     * sends nothing, and the refusal the request would raise is only the answer here. */
    private boolean stillPermitted(UserId presser, GroupId group) {
        try {
            roles.stillReaching(presser, group, StepAct.TRY_SENDING.permission());
            return true;
        } catch (ApiErrorException refused) {
            return false;
        }
    }

    private boolean heldOnSource(LockedTree held, RunInTree at, StepSnapshot step) {
        HoldRecord.SourceRecord missing = StepInputs.missingCodeSource(at, step).orElse(null);
        if (missing == null) {
            return false;
        }
        HoldRecord hold = step.hold();
        if (hold != null) {
            if (missing.equals(hold.source())) {
                return true;
            }
            RunStepId runStep = requireNonNull(step.runStep(), "A hold belongs to a written step");
            if (hold.attempt() == null) {
                writes.released(held, at.run(), runStep, hold.reason());
            } else {
                writes.releasedToSend(held, at.run(), runStep, hold.reason());
            }
        }
        writes.heldOnSource(held, at.run(), writes.runStep(held, at.run(), step), missing);
        return true;
    }

    private @Nullable PendingCall resumeProduction(
            LockedTree held, RunInTree at, StepSnapshot step, TryRecord open, HoldRecord hold) {
        if (heldOnSource(held, at, step)) {
            return null;
        }
        FailureRecord failure = StepPositions.inForce(step)
                .filter(each ->
                        each.purpose() == ModelCallPurpose.PRODUCE && open.id().equals(each.onTry()))
                .orElse(null);
        AttemptRecord latest = null;
        for (AttemptRecord attempt : open.attempts()) {
            if (attempt.purpose() == ModelCallPurpose.PRODUCE) {
                latest = attempt;
            }
        }
        EngineWrites.SentRecord stored = null;
        if (latest != null && !latest.tooLong()) {
            CallRecord call = null;
            for (CallRecord produced : open.calls()) {
                if (produced.attempt().equals(latest.id())) {
                    call = produced;
                }
            }
            if (call == null || call.outcome() != ModelCallOutcome.TURNED_AWAY) {
                throw new IllegalStateException("An open production resumes only a call that was turned away");
            }
            EngineWrites.SentRecord previous = writes.stored(held, latest.id());
            if (previous.envelopeVersion() == Envelope.VERSION) {
                stored = previous;
            }
        }
        writes.released(held, at.run(), requireNonNull(step.runStep()), hold.reason());
        return send(held, at, step, open.id(), stored, failure == null ? null : failure.id(), null);
    }

    /**
     * The model or its mode not held fails the try, and so does what cannot be cut being too long for it; the
     * rest too long holds it. Otherwise the call is written down as sent, to be made once this commits; what was
     * {@code stored} is sent again as it was, and anything else built afresh.
     */
    private @Nullable PendingCall send(
            LockedTree held,
            RunInTree at,
            StepSnapshot step,
            ProductionId production,
            EngineWrites.@Nullable SentRecord stored,
            @Nullable RunStepFailureId answers,
            @Nullable UserId presser) {
        RunSnapshot run = at.run();
        PlannedStep planned = step.planned();
        if (planned.runs() instanceof StepRuns.Search search
                && search.released() != null
                && (!search.way().equals(search.markedWay())
                        || !QuestionSearchShape.matches(
                                search.released(), search.question().gives().fields()))) {
            writes.searchQuestionMismatch(held, run, step);
            return null;
        }
        if (heldOnSource(held, at, step)) {
            return null;
        }
        StepInputs.SourceFailureRecord mismatch =
                StepInputs.sourceFailure(at, step).orElse(null);
        if (mismatch != null) {
            writes.sourceFailure(held, run, requireNonNull(step.runStep()), production, mismatch);
            return null;
        }
        if (planned.runs() instanceof StepRuns.Search search) {
            return search(held, at, step, search, production, stored, answers, presser);
        }
        if (!(planned.runs() instanceof StepRuns.Question question)
                || !(planned.producer() instanceof Producer.Model model)) {
            throw new IllegalStateException("Only a question a model produces is sent to one");
        }
        TryRecord aTry = step.newest()
                .filter(newest -> newest.id().equals(production) && newest.open())
                .orElseThrow(() -> new IllegalStateException("Step "
                        + planned.name().value() + " has no open production " + production.value() + " to send"));
        RunStepId runStep = requireNonNull(step.runStep(), "a step with a try is written");
        ModelChoice choice = model.choice();
        if (choice.unheldBy(catalog) != null) {
            writes.failed(
                    held,
                    run,
                    runStep,
                    aTry.id(),
                    ModelCallPurpose.PRODUCE,
                    RunStepFailureReason.MODEL_NOT_DEPLOYED,
                    notHeld(choice));
            return null;
        }
        DeployedModel deployed = catalog.find(choice.model()).orElseThrow();
        List<AskedField> gives = Asking.told(question.gives(), question.lists());
        String envelope = Envelope.toProduce(gives, question.instruction());
        TryRecord refused = RunPayloads.refusedInWords(step, aTry.id()).orElse(null);
        String payload;
        StepLengthDetails measured = null;
        if (stored == null) {
            List<StepInputs.BindingRecord> bound = StepInputs.took(at, step, aTry);
            measured = StepLengthDetails.question(envelope, RunPayloads.toProduce(at, planned, bound, refused), bound);
            payload = measured.sent().user();
        } else {
            Envelope.requireHeld(stored.envelopeVersion());
            payload = stored.payload();
        }
        String kept = stored == null ? payload : null;
        RunStepSendAttemptId repeats = stored == null ? null : stored.attempt();
        SentText sent = measured == null ? SentText.measure(envelope, payload) : measured.sent();
        if (!deployed.takes(sent.characters())) {
            // A held call is rebuilt; a kept call already fit before the deployment could change.
            if (measured == null) {
                List<StepInputs.BindingRecord> bound = StepInputs.took(at, step, aTry);
                measured =
                        StepLengthDetails.question(envelope, RunPayloads.toProduce(at, planned, bound, refused), bound);
            }
            long uncuttable = measured.fixedCharacters();
            if (deployed.takes(uncuttable)) {
                RunStepSendAttemptId attempt = writes.attempted(
                        held,
                        run,
                        runStep,
                        aTry.id(),
                        ModelCallPurpose.PRODUCE,
                        choice,
                        true,
                        kept,
                        repeats,
                        answers,
                        presser);
                writes.heldOn(held, attempt, RunStepHoldReason.TOO_LONG);
            } else {
                writes.failed(
                        held,
                        run,
                        runStep,
                        aTry.id(),
                        ModelCallPurpose.PRODUCE,
                        RunStepFailureReason.UNCUTTABLE_LENGTH,
                        uncuttable(deployed, uncuttable));
            }
            return null;
        }
        RunStepSendAttemptId attempt = writes.attempted(
                held,
                run,
                runStep,
                aTry.id(),
                ModelCallPurpose.PRODUCE,
                choice,
                false,
                kept,
                repeats,
                answers,
                presser);
        ModelCallId call = writes.called(held, run, attempt, deployed.unitsOf(sent.characters()));
        return new PendingCall(
                run.group(),
                run.root(),
                run.run(),
                aTry.id(),
                attempt,
                call,
                new CallRequest(deployed, choice.mode(), ModelCallPurpose.PRODUCE, sent),
                new PendingCall.Producing(gives, question.givesFields()));
    }

    private @Nullable PendingCall search(
            LockedTree held,
            RunInTree at,
            StepSnapshot step,
            StepRuns.Search search,
            ProductionId production,
            EngineWrites.@Nullable SentRecord stored,
            @Nullable RunStepFailureId answers,
            @Nullable UserId presser) {
        RunSnapshot run = at.run();
        Producer.Model producer = (Producer.Model) requireNonNull(step.planned().producer());
        SearchWay way = requireNonNull(search.released(), "a way absent from the release is held before sending");
        TryRecord aTry = step.newest()
                .filter(each -> each.id().equals(production) && each.open())
                .orElseThrow(() -> new IllegalStateException("A search sends only its open production"));
        RunStepId runStep = requireNonNull(step.runStep());
        ModelChoice choice = producer.choice();
        if (choice.unheldBy(catalog) != null) {
            writes.failed(
                    held,
                    run,
                    runStep,
                    aTry.id(),
                    ModelCallPurpose.PRODUCE,
                    RunStepFailureReason.MODEL_NOT_DEPLOYED,
                    notHeld(choice));
            return null;
        }
        DeployedModel model = catalog.find(choice.model()).orElseThrow();
        DeployedTool tool = catalog.tool(choice.model(), way.requiredTool()).orElse(null);
        if (tool == null) {
            writes.toolNotOffered(held, run, runStep, aTry.id(), way.requiredTool());
            return null;
        }
        List<AskedField> gives = SearchAnswer.asked(way);
        TryRecord refused = RunPayloads.refusedInWords(step, aTry.id()).orElse(null);
        String payload = stored == null
                ? CanonicalJson.write(
                        RunPayloads.toProduce(at, step.planned(), StepInputs.took(at, step, aTry), refused))
                : stored.payload();
        if (stored != null) {
            Envelope.requireHeld(stored.envelopeVersion());
        }
        String envelope = Envelope.toSearch(gives, search.question().instruction());
        SentText sent = SentText.measure(envelope, payload);
        boolean tooLong = !model.takes(sent.characters());
        if (tooLong) {
            long uncuttable = SentText.measure(
                            envelope, CanonicalJson.write(RunPayloads.toProduce(step.planned(), List.of(), refused)))
                    .characters();
            if (!model.takes(uncuttable)) {
                writes.failed(
                        held,
                        run,
                        runStep,
                        aTry.id(),
                        ModelCallPurpose.PRODUCE,
                        RunStepFailureReason.UNCUTTABLE_LENGTH,
                        uncuttable(model, uncuttable));
                return null;
            }
        }
        RunStepSendAttemptId attempt = writes.attempted(
                held,
                run,
                runStep,
                aTry.id(),
                ModelCallPurpose.PRODUCE,
                choice,
                tooLong,
                stored == null ? payload : null,
                stored == null ? null : stored.attempt(),
                answers,
                presser);
        if (tooLong) {
            writes.heldOn(held, attempt, RunStepHoldReason.TOO_LONG);
            return null;
        }
        ModelCallId call = writes.called(held, run, attempt, model.unitsOf(sent.characters()));
        writes.searchSent(held, run, call, tool);
        return new PendingCall(
                run.group(),
                run.root(),
                run.run(),
                aTry.id(),
                attempt,
                call,
                new CallRequest(model, choice.mode(), ModelCallPurpose.PRODUCE, sent),
                new PendingCall.Searching(
                        gives, search.standings(), new ToolUseLimit(tool.name(), tool.maxUsesPerCall())));
    }

    /**
     * A code step's values whose review cannot be built are left to a person, nothing sent and nothing failed. The
     * model the step names to review, or its mode, not held fails the try; what it would be sent too long for it
     * leaves the values to a person, nothing held and nothing sent. Otherwise the call is written down as sent, the
     * presser's where one pressed. A code step's production is sent as the running release declares that code step,
     * with no instruction.
     */
    private @Nullable PendingCall review(
            LockedTree held,
            RunInTree at,
            StepSnapshot step,
            ProductionId production,
            @Nullable RunStepFailureId answers,
            @Nullable UserId presser) {
        RunSnapshot run = at.run();
        PlannedStep planned = step.planned();
        ModelChoice choice = requireNonNull(planned.reviewer(), "a step a model reviews names it");
        TryRecord reviewed = step.newest()
                .filter(newest -> newest.id().equals(production) && newest.yielded())
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + planned.name().value() + " has no production " + production.value() + " to review"));
        RunStepId runStep = requireNonNull(step.runStep(), "a step with a try is written");
        JsonValue.JsonObject searchRecord = null;
        JsonValue.JsonObject questionSent = null;
        if ((planned.runs() instanceof StepRuns.Question || planned.runs() instanceof StepRuns.Search)
                && reviewed.producer() == StepProducer.MODEL) {
            @Nullable
            JsonValue sent = AnswerReader.read(
                    writes.stored(held, producedCall(reviewed).attempt()).payload());
            if (!(sent instanceof JsonValue.JsonObject kept)) {
                writes.unbuilt(
                        held, run, runStep, reviewed.id(), choice, ReviewUnbuiltReason.NO_LONGER_DECLARED, answers);
                return null;
            }
            questionSent = kept;
        }
        if (planned.runs() instanceof StepRuns.Search search && search.released() != null) {
            if (reviewed.producer() == StepProducer.MODEL) {
                CallRecord call = producedCall(reviewed);
                searchRecord = writes.searchReviewRecord(held, call.id());
            } else {
                searchRecord = new JsonValue.JsonObject(List.of());
            }
        }
        ReviewPayload.Built built;
        switch (RunPayloads.toReview(at, step, reviewed, searchRecord, questionSent)) {
            case ReviewPayload.Unbuilt unbuilt -> {
                writes.unbuilt(held, run, runStep, reviewed.id(), choice, unbuilt.reason(), answers);
                return null;
            }
            case ReviewPayload.Built payload -> built = payload;
        }
        if (choice.unheldBy(catalog) != null) {
            writes.failed(
                    held,
                    run,
                    runStep,
                    reviewed.id(),
                    ModelCallPurpose.REVIEW,
                    RunStepFailureReason.MODEL_NOT_DEPLOYED,
                    notHeld(choice));
            return null;
        }
        DeployedModel deployed = catalog.find(choice.model()).orElseThrow();
        String envelope =
                switch (planned.runs()) {
                    case StepRuns.Question question ->
                        Envelope.toReview(Asking.told(question.gives(), question.lists()), question.instruction());
                    case StepRuns.Code code -> {
                        ReleasedCodeStep released =
                                requireNonNull(code.released(), "a code step reviewed is one the release holds");
                        yield Envelope.toReviewUninstructed(
                                Asking.told(released.declared().gives(), released.lists()));
                    }
                    case StepRuns.Search search ->
                        Envelope.toReviewSearch(
                                SearchAnswer.asked(
                                        requireNonNull(search.released(), "A search reviewed is held by the release")),
                                search.question().instruction());
                    case StepRuns.Workflow ignored -> throw notProduced(planned);
                    case StepRuns.Route ignored -> throw notProduced(planned);
                };
        String payload = CanonicalJson.write(built.payload());
        SentText sent = SentText.measure(envelope, payload);
        boolean tooLong = !deployed.takes(sent.characters());
        // Measured, never said: one too long is the system's whoever pressed, and a person reviews in its place.
        RunStepSendAttemptId attempt = writes.attempted(
                held,
                run,
                runStep,
                reviewed.id(),
                ModelCallPurpose.REVIEW,
                choice,
                tooLong,
                payload,
                null,
                answers,
                tooLong ? null : presser);
        if (tooLong) {
            return null;
        }
        ModelCallId call = writes.called(held, run, attempt, deployed.unitsOf(sent.characters()));
        List<ValueRecord> deciding = RunPayloads.deciding(reviewed);
        return new PendingCall(
                run.group(),
                run.root(),
                run.run(),
                reviewed.id(),
                attempt,
                call,
                new CallRequest(deployed, choice.mode(), ModelCallPurpose.REVIEW, sent),
                new PendingCall.Reviewing(
                        deciding.stream()
                                .map(value -> new FieldName(value.field()))
                                .toList(),
                        deciding.stream().map(ValueRecord::id).toList()));
    }

    private static CallRecord producedCall(TryRecord reviewed) {
        List<CallRecord> produced = reviewed.calls().stream()
                .filter(call -> call.outcome() == ModelCallOutcome.CAME_BACK
                        && reviewed.attempts().stream()
                                .anyMatch(attempt -> attempt.id().equals(call.attempt())
                                        && attempt.purpose() == ModelCallPurpose.PRODUCE))
                .toList();
        if (produced.size() != 1) {
            throw new IllegalStateException("A model production holds one completed production call");
        }
        return produced.getFirst();
    }

    private static IllegalStateException notProduced(PlannedStep planned) {
        return new IllegalStateException("Step " + planned.name().value() + " produces nothing to review");
    }

    private static String notHeld(ModelChoice choice) {
        return "Model " + choice.model().value()
                + (choice.mode() == null ? "" : " in mode " + choice.mode().value()) + " is not held here.";
    }

    private static String uncuttable(DeployedModel model, long characters) {
        return "With nothing it takes, what would be sent is still " + model.unitsOf(characters)
                + " units, more than the " + model.sentPerCallLimit() + " model "
                + model.name().value()
                + " takes.";
    }

    private static StepSnapshot stepOf(RunSnapshot run, UUID step) {
        return run.steps().stream()
                .filter(held -> held.planned().id().value().equals(step))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Run " + run.run().value() + " holds no step " + step));
    }

    private void released(EntryId entry) {
        List<RunTreeAddress> held;
        try {
            held = database.sql(HELD_ON_ENTRY)
                    .param("entry", entry.value())
                    .query((result, number) -> new RunTreeAddress(
                            new GroupId(result.getObject("group_id", UUID.class)),
                            new RunId(result.getObject("root_run_id", UUID.class))))
                    .list();
        } catch (RuntimeException failed) {
            logger.error("The runs held on entry {} could not be found to go on", entry.value(), failed);
            return;
        }
        held.forEach(each -> goOn(each.group(), each.root()));
    }

    private static WorkflowStepId actedOn(EngineAct act) {
        return switch (act) {
            case EngineAct.StartTry start -> start.step().id();
            case EngineAct.Send send -> send.step().id();
            case EngineAct.Review review -> review.step().id();
            case EngineAct.HoldOnStop hold -> hold.step().id();
            case EngineAct.HoldNotHeld hold -> hold.step().id();
            case EngineAct.ReleaseHold release -> release.step().id();
            case EngineAct.StartBeneath start -> start.step().id();
            case EngineAct.FailUnclaimed failure -> failure.step().id();
            case EngineAct.FailTooDeep failure -> failure.step().id();
            case EngineAct.FailSearchShape failure -> failure.step().id();
            case EngineAct.Nothing ignored -> throw new IllegalArgumentException("Nothing acts on no step");
        };
    }

    /** @param call none where what was written holds, fails or stops rather than sends */
    private record Written(@Nullable PendingCall call) {}

    /*
     * Never done where it is registered: what runs after a commit still joins the committed transaction's
     * resources. What follows an end is planned apart from it, so a planning failure never takes back what ended.
     */
    private final class HandOver implements TransactionSynchronization {

        private final Pending pending;

        HandOver(Pending pending) {
            this.pending = pending;
        }

        @Override
        public void afterCommit() {
            switch (pending) {
                case PendingSend send -> executor.execute(send, () -> sendAndEnd(send));
                case PendingCode code ->
                    executor.executeCode(
                            code,
                            () -> goOnAfter(
                                    codes.runAndEnd(code, deployment.maximumDepth()), code.group(), code.root()));
                case PendingDrive drive -> executor.execute(() -> goOn(drive.group(), drive.root()));
            }
        }
    }
}
