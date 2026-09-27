package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.app.library.EntryLetGo;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.inference.Envelope;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.model.SentText;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.run.CallRecord;
import org.lilradish.lite.domain.run.EngineAct;
import org.lilradish.lite.domain.run.EngineActs;
import org.lilradish.lite.domain.run.FailureRecord;
import org.lilradish.lite.domain.run.HoldRecord;
import org.lilradish.lite.domain.run.InputRecord;
import org.lilradish.lite.domain.run.ModelCallId;
import org.lilradish.lite.domain.run.PlannedStep;
import org.lilradish.lite.domain.run.ReviewPayload;
import org.lilradish.lite.domain.run.ReviewSending;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunPayloads;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunStepFailureId;
import org.lilradish.lite.domain.run.RunStepFailureReason;
import org.lilradish.lite.domain.run.RunStepHoldReason;
import org.lilradish.lite.domain.run.RunStepId;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.StepAct;
import org.lilradish.lite.domain.run.StepGround;
import org.lilradish.lite.domain.run.StepInputs;
import org.lilradish.lite.domain.run.StepPosition;
import org.lilradish.lite.domain.run.StepPositions;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.run.TryRecord;
import org.lilradish.lite.domain.run.ValueRecord;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.workflow.ModelChoice;
import org.lilradish.lite.domain.workflow.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What makes a run go on by itself: under its tree's lock it works out where every step is, and makes the one
 * act that follows, again and again until none does. It never waits on anything outside the store while it
 * holds the lock: what is to be done outside it is handed to the engine's threads once the transaction commits.
 */
@Component
public final class RunEngine implements EntryLetGo {

    private static final Logger logger = LoggerFactory.getLogger(RunEngine.class);

    // DB-SPECIFIC: an enum compared to a literal is PostgreSQL's.
    /* Unlocked: which trees to drive is only a lead, each re-read under its own lock before anything is released. */
    private static final String HELD_ON_ENTRY = """
            select distinct run.group_id, run.root_run_id
              from run_step_holds hold
              join run_steps step on step.run_step_id = hold.run_step_id
              join runs run on run.run_id = hold.run_id
              left join entry_versions pinned on pinned.entry_version_id = step.pinned_version_id
             where hold.released_at is null and hold.reason = 'entry_stopped'
               and (run.entry_id = :entry or pinned.entry_id = :entry)
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
            GroupRoles roles) {
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
        executor.execute(() -> released(entry));
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
        while (true) {
            RunSnapshot run = snapshots.locked(held, held.root());
            EngineAct act = EngineActs.next(run, StepPositions.of(run));
            // Once stopping, what goes out is left as it reads, a model's try unsent, a code step's not yet made.
            if (act instanceof EngineAct.Nothing || (executor.stopping() && goesOut(act))) {
                return;
            }
            // Every act changes what the next is worked out from; one worked out again was never made.
            if (act.equals(last)) {
                throw new IllegalStateException("Run " + run.run().value() + " made no change acting on step "
                        + actedOn(act).value());
            }
            Pending pending = apply(held, run, act);
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

    private @Nullable Pending apply(LockedTree held, RunSnapshot run, EngineAct act) {
        return switch (act) {
            case EngineAct.StartTry start ->
                startTry(held, run, stepOf(run, start.step().id().value()), start.number(), null);
            case EngineAct.Send send ->
                new PendingSend(
                        run.group(),
                        run.root(),
                        run.run(),
                        send.step().id(),
                        send.number(),
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
                        review.number(),
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
                writes.held(held, run, writes.runStep(held, run, step), RunStepHoldReason.CODE_STEP_NOT_HELD);
                yield null;
            }
            case EngineAct.ReleaseHold release -> {
                StepSnapshot step = stepOf(run, release.step().id().value());
                writes.released(
                        held,
                        run,
                        requireNonNull(step.runStep(), "a hold is on a step written"),
                        requireNonNull(step.hold(), "a hold released is one standing")
                                .reason());
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
    PendingCode startTry(LockedTree held, RunSnapshot run, StepSnapshot step, int number, @Nullable UserId asker) {
        return switch (step.planned().producer()) {
            case Producer.Person person -> {
                tried(held, run, step, number, person, asker);
                yield null;
            }
            case Producer.Model model -> {
                tried(held, run, step, number, model, asker);
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

    /**
     * Inside the caller's transaction, the tree held: try {@code number} asked again of whoever {@code step} names,
     * by {@code asker}, and handed over once this commits where code is to make it.
     */
    void askAgain(LockedTree held, RunSnapshot run, StepSnapshot step, int number, UserId asker) {
        requireNonNull(asker, "RunEngine asker must not be null");
        PendingCode pending = startTry(held, run, step, number, asker);
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
                    pressed.number(),
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
            LockedTree held,
            RunSnapshot run,
            StepSnapshot step,
            int number,
            Producer producer,
            @Nullable UserId asker) {
        switch (step.planned().runs()) {
            case StepRuns.Question question -> {
                List<InputRecord> inputs =
                        StepInputs.traced(run, step, question.takes()).orElseThrow(() -> unreached(step));
                writes.tried(held, run, writes.runStep(held, run, step), number, producer.kind(), asker, null, inputs);
            }
            // Recorded as bound, not traced: what the release takes now may no longer be what the version binds.
            case StepRuns.Code code -> {
                ReleasedCodeStep released =
                        requireNonNull(code.released(), "a code step asked of a person is one the release holds");
                List<InputRecord> inputs = StepInputs.bound(run, step).orElseThrow(() -> unreached(step)).stream()
                        .map(each -> new InputRecord(each.binding().id(), each.source()))
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
        RunSnapshot run = snapshots.locked(held, pending.run());
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
            return sentAgain(held, run, step, pending, presser);
        }
        EngineAct act =
                switch (pending.purpose()) {
                    case PRODUCE -> new EngineAct.Send(step.planned(), pending.number());
                    case REVIEW -> new EngineAct.Review(step.planned(), pending.number());
                    case HELP -> throw new IllegalStateException("A run's step is never sent to its helper");
                };
        if (!EngineActs.next(run, StepPositions.of(run)).equals(act)) {
            return null;
        }
        if (ceilings.stopsAt(held, null)) {
            return new Written(null);
        }
        return new Written(
                switch (act) {
                    case EngineAct.Send send -> send(held, run, step, send.number(), null, null, null);
                    case EngineAct.Review review -> review(held, run, step, review.number(), null, null);
                    case EngineAct.StartTry ignored -> throw notSent();
                    case EngineAct.HoldOnStop ignored -> throw notSent();
                    case EngineAct.HoldNotHeld ignored -> throw notSent();
                    case EngineAct.ReleaseHold ignored -> throw notSent();
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
            LockedTree held, RunSnapshot run, StepSnapshot step, PendingSend pending, UserId presser) {
        if (!stillPermitted(presser, pending.group())) {
            return null;
        }
        TryRecord newest =
                step.newest().filter(each -> each.number() == pending.number()).orElse(null);
        StepPosition position = StepPositions.of(run, step);
        boolean offered = newest != null
                && pressedOn(step, position, newest, pending)
                && StepAct.TRY_SENDING.refusal(StepGround.of(run, step, position, null)) == null;
        if (!offered) {
            return null;
        }
        if (ceilings.stopsAt(held, null)) {
            return new Written(null);
        }
        RunStepFailureId answers = pending.failedOn();
        if (pending.purpose() == ModelCallPurpose.REVIEW) {
            return new Written(review(held, run, step, pending.number(), answers, presser));
        }
        HoldRecord hold = step.hold();
        if (hold == null) {
            return new Written(send(held, run, step, pending.number(), null, answers, presser));
        }
        writes.releasedToSend(held, run, requireNonNull(step.runStep(), "a step held back is written"), hold.reason());
        if (hold.reason() == RunStepHoldReason.TURNED_AWAY) {
            RunStepSendAttemptId turnedAway = requireNonNull(hold.attempt(), "a hold on a turnaway names its attempt");
            EngineWrites.SentRecord stored = writes.stored(held, turnedAway);
            // Ruled: one sent under an envelope this release no longer holds is built afresh, never resent as it was.
            return new Written(send(
                    held,
                    run,
                    step,
                    pending.number(),
                    stored.envelopeVersion() == Envelope.VERSION ? stored : null,
                    null,
                    presser));
        }
        // Built afresh from what the try was traced to take, which stands while nothing refuses a value for length.
        return new Written(send(held, run, step, pending.number(), null, null, presser));
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

    /**
     * The model or its mode not held fails the try, and so does what cannot be cut being too long for it; the
     * rest too long holds it. Otherwise the call is written down as sent, to be made once this commits; what was
     * {@code stored} is sent again as it was, and anything else built afresh.
     */
    private @Nullable PendingCall send(
            LockedTree held,
            RunSnapshot run,
            StepSnapshot step,
            int number,
            EngineWrites.@Nullable SentRecord stored,
            @Nullable RunStepFailureId answers,
            @Nullable UserId presser) {
        PlannedStep planned = step.planned();
        if (!(planned.runs() instanceof StepRuns.Question question)
                || !(planned.producer() instanceof Producer.Model model)) {
            throw new IllegalStateException("Only a question a model produces is sent to one");
        }
        TryRecord aTry = step.newest()
                .filter(newest -> newest.number() == number && newest.open())
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + planned.name().value() + " has no open try " + number + " to send"));
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
        String envelope = Envelope.toProduce(gives);
        TryRecord refused = RunPayloads.refusedInWords(step, aTry.number()).orElse(null);
        String payload;
        if (stored == null) {
            payload = CanonicalJson.write(RunPayloads.toProduce(planned, StepInputs.took(run, step, aTry), refused));
        } else {
            Envelope.requireHeld(stored.envelopeVersion());
            payload = stored.payload();
        }
        String kept = stored == null ? payload : null;
        RunStepSendAttemptId repeats = stored == null ? null : stored.attempt();
        SentText sent = SentText.measure(envelope, payload);
        if (!deployed.takes(sent.characters())) {
            // What it takes can be cut short, and nothing else it sends can: with none of it, it must fit.
            String uncut = CanonicalJson.write(RunPayloads.toProduce(planned, List.of(), refused));
            long uncuttable = SentText.measure(envelope, uncut).characters();
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

    /**
     * A code step's values whose review cannot be built are left to a person, nothing sent and nothing failed. The
     * model the step names to review, or its mode, not held fails the try; what it would be sent too long for it
     * leaves the values to a person, nothing held and nothing sent. Otherwise the call is written down as sent, the
     * presser's where one pressed. A code step's production is sent as the running release declares that code step,
     * with no instruction.
     */
    private @Nullable PendingCall review(
            LockedTree held,
            RunSnapshot run,
            StepSnapshot step,
            int number,
            @Nullable RunStepFailureId answers,
            @Nullable UserId presser) {
        PlannedStep planned = step.planned();
        ModelChoice choice = requireNonNull(planned.reviewer(), "a step a model reviews names it");
        TryRecord reviewed = step.newest()
                .filter(newest -> newest.number() == number && newest.yielded())
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + planned.name().value() + " has no try " + number + " to review"));
        RunStepId runStep = requireNonNull(step.runStep(), "a step with a try is written");
        ReviewPayload.Built built;
        switch (RunPayloads.toReview(run, step, reviewed)) {
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
                        Envelope.toReview(Asking.told(question.gives(), question.lists()));
                    case StepRuns.Code code -> {
                        ReleasedCodeStep released =
                                requireNonNull(code.released(), "a code step reviewed is one the release holds");
                        yield Envelope.toReviewUninstructed(
                                Asking.told(released.declared().gives(), released.lists()));
                    }
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
                .orElseThrow();
    }

    private void released(EntryId entry) {
        List<Tree> held;
        try {
            held = database.sql(HELD_ON_ENTRY)
                    .param("entry", entry.value())
                    .query((result, number) -> new Tree(
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
            case EngineAct.Nothing ignored -> throw new IllegalArgumentException("Nothing acts on no step");
        };
    }

    private record Tree(GroupId group, RunId root) {}

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
                case PendingSend send -> executor.execute(() -> sendAndEnd(send));
                case PendingCode code ->
                    executor.executeCode(() -> goOnAfter(codes.runAndEnd(code), code.group(), code.root()));
            }
        }
    }
}
