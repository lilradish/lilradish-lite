package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.app.filling.ValueProblemsRefusal;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.codestep.CodeOutcome;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.filling.FillOutcome;
import org.lilradish.lite.domain.filling.FillProblems;
import org.lilradish.lite.domain.filling.FilledFields;
import org.lilradish.lite.domain.filling.Filling;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.run.InputRecord;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.ReviewOutcome;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.StepAct;
import org.lilradish.lite.domain.run.StepGround;
import org.lilradish.lite.domain.run.StepInputs;
import org.lilradish.lite.domain.run.StepPosition;
import org.lilradish.lite.domain.run.StepPositions;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.workflow.StepProducer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * What a person does to a step of a run: review what waits on them, answer it, ask for its next try, or send its
 * try again. Each is one transaction as the caller's act, under the tree's lock and then the group's; each names
 * the try, or the hold or failure, it acts on, so a second press on what was read once is refused, or comes to
 * nothing, rather than made twice. Once it commits the run goes on.
 */
@Component
final class StepActs {

    private static final String IN_VIEW = "select 1 from runs run where " + RunScope.RUN_IN_VIEW;

    private final JdbcClient database;

    private final TransactionOperations transactions;

    private final GroupRoles roles;

    private final RunTree tree;

    private final RunSnapshots snapshots;

    private final EngineWrites writes;

    private final RunEngine engine;

    StepActs(
            JdbcClient database,
            TransactionOperations transactions,
            GroupRoles roles,
            RunTree tree,
            RunSnapshots snapshots,
            EngineWrites writes,
            RunEngine engine) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
        this.tree = tree;
        this.snapshots = snapshots;
        this.writes = writes;
        this.engine = engine;
    }

    /**
     * One review deciding every value of try {@code number} waiting on it, each by its field's name. Each reason
     * given was judged before this was asked.
     */
    void review(
            GroupId group,
            RunId run,
            WorkflowStepId step,
            int number,
            UserId caller,
            Map<String, ReviewedRecord> decisions) {
        requireNonNull(decisions, "StepActs decisions must not be null");
        RunId root = requireNonNull(transactions.execute(status -> {
            Acting acting = acting(group, run, step, caller, StepAct.REVIEW);
            StepPosition.AwaitingReview waiting = (StepPosition.AwaitingReview) acting.position();
            if (waiting.number() != number) {
                throw RunRefusal.STEP_MOVED_ON.raised();
            }
            if (waiting.values().size() != decisions.size()
                    || !waiting.values().stream().allMatch(value -> decisions.containsKey(value.field()))) {
                throw RunRefusal.REVIEW_INCOMPLETE.raised();
            }
            List<EngineWrites.DecidedRecord> decided =
                    new ArrayList<>(waiting.values().size());
            for (StepPosition.WaitingValue value : waiting.values()) {
                ReviewedRecord reviewed = requireNonNull(decisions.get(value.field()));
                decided.add(new EngineWrites.DecidedRecord(value.value(), reviewed.outcome(), reviewed.why()));
            }
            writes.reviewed(
                    acting.tree(),
                    acting.run(),
                    acting.step().newest().orElseThrow().id(),
                    caller,
                    decided);
            return acting.tree().root();
        }));
        engine.goOn(group, root);
    }

    /** Try {@code number}, asked of whoever the step names to produce it, as the caller's act. */
    void askAgain(GroupId group, RunId run, WorkflowStepId step, int number, UserId caller) {
        RunId root = requireNonNull(transactions.execute(status -> {
            Acting acting = acting(group, run, step, caller, StepAct.ASK_AGAIN);
            if (number != next(acting)) {
                throw RunRefusal.STEP_MOVED_ON.raised();
            }
            // Asked as the run asks a try by itself, a model's here and sent from the engine's thread. Code's try is
            // written only on the engine's thread: one dying before it loses this press, and Ask again is offered.
            engine.askAgain(acting.tree(), acting.run(), acting.step(), number, caller);
            engine.planWithin(acting.tree());
            return acting.tree().root();
        }));
        engine.goOn(group, root);
    }

    /**
     * The try the step is held back or failed on, sent again as the caller's act by the engine's thread once this
     * commits. Nothing is written here; the press names the hold or failure it was made on, so one the engine's
     * thread comes to after an earlier press acted on that hold or failure changes nothing, whatever that one wrote.
     */
    void trySending(GroupId group, RunId run, WorkflowStepId step, UserId caller) {
        transactions.executeWithoutResult(status -> {
            Acting acting = acting(group, run, step, caller, StepAct.TRY_SENDING);
            engine.trySending(acting.tree(), acting.run(), acting.step(), caller);
        });
    }

    /**
     * A person's answer filling try {@code number}: the one asked already, or the next, made here as the caller's
     * try. Every value is read against the field it fills, and a value that does not fit spends no try. The
     * reason given was judged before this was asked.
     */
    void answer(
            GroupId group, RunId run, WorkflowStepId step, int number, UserId caller, JsonValue values, String why) {
        requireNonNull(values, "StepActs values must not be null");
        requireNonNull(why, "StepActs why must not be null");
        RunId root = requireNonNull(transactions.execute(status -> {
            Acting acting = acting(group, run, step, caller, StepAct.ANSWER);
            if (number != next(acting)) {
                throw RunRefusal.STEP_MOVED_ON.raised();
            }
            StepSnapshot found = acting.step();
            switch (found.planned().runs()) {
                case StepRuns.Question question -> answerQuestion(acting, question, number, caller, values, why);
                case StepRuns.Code code -> answerCode(acting, code, number, caller, values, why);
                case StepRuns.Workflow ignored ->
                    throw new IllegalStateException("A workflow's try is never answered here");
                case StepRuns.Route ignored -> throw new IllegalStateException("A route's try is never answered here");
            }
            return acting.tree().root();
        }));
        engine.goOn(group, root);
    }

    private void answerQuestion(
            Acting acting, StepRuns.Question question, int number, UserId caller, JsonValue values, String why) {
        List<FillField> gives = FillField.of(question.gives().fields(), question.lists());
        JsonValue.JsonObject kept = filled(gives, values);
        StepSnapshot found = acting.step();
        ProductionId answered = owed(acting).open()
                ? found.newest().orElseThrow().id()
                : writes.tried(
                        acting.tree(),
                        acting.run(),
                        requireNonNull(found.runStep(), "a step owing a try is written"),
                        number,
                        StepProducer.PERSON,
                        caller,
                        null,
                        StepInputs.traced(acting.run(), found, question.takes())
                                .orElseThrow(() -> new IllegalStateException(
                                        "What step " + found.planned().id().value() + " took no longer stands")));
        List<EngineWrites.AnsweredRecord> given = new ArrayList<>(gives.size());
        for (int index = 0; index < gives.size(); index++) {
            given.add(new EngineWrites.AnsweredRecord(
                    question.givesFields().get(index), kept.members().get(index).value()));
        }
        writes.answered(acting.tree(), acting.run(), answered, caller, why, given);
    }

    /* Read against what the release declares now, which is what every step after reads it as. */
    private void answerCode(
            Acting acting, StepRuns.Code code, int number, UserId caller, JsonValue values, String why) {
        ReleasedCodeStep released =
                requireNonNull(code.released(), "a code step answered here is one the release holds");
        List<Field> declared = released.declared().gives().fields();
        JsonValue.JsonObject kept = filled(FillField.of(declared, released.lists()), values);
        StepSnapshot found = acting.step();
        ProductionId answered = owed(acting).open()
                ? found.newest().orElseThrow().id()
                : writes.tried(
                        acting.tree(),
                        acting.run(),
                        requireNonNull(found.runStep(), "a step owing a try is written"),
                        number,
                        StepProducer.PERSON,
                        caller,
                        released.mayRunAgain(),
                        StepInputs.bound(acting.run(), found)
                                .orElseThrow(() -> new IllegalStateException(
                                        "What step " + found.planned().id().value() + " took no longer stands"))
                                .stream()
                                .map(each -> new InputRecord(each.binding().id(), each.source()))
                                .toList());
        List<CodeOutcome.Given> given = new ArrayList<>(declared.size());
        for (int index = 0; index < declared.size(); index++) {
            given.add(CodeOutcome.Given.of(
                    declared.get(index), kept.members().get(index).value()));
        }
        writes.codeAnswered(acting.tree(), acting.run(), answered, caller, why, given);
    }

    private static JsonValue.JsonObject filled(List<FillField> gives, JsonValue values) {
        return switch (Filling.of(gives, values)) {
            case FilledFields filled -> filled.values();
            case FillProblems problems -> throw new ValueProblemsRefusal(problems);
            case FillOutcome.Unshaped ignored -> throw RunRefusal.VALUES_UNSHAPED.raised();
        };
    }

    private static int next(Acting acting) {
        return owed(acting).number();
    }

    private static StepPosition.Owed owed(Acting acting) {
        return requireNonNull(acting.position().nextTry(), "a step answered or asked again owes a try");
    }

    /**
     * A run the caller may not read takes the path of one nobody holds, and no lock; one they may, the tree's
     * lock, then what they hold, then the run gone on as far as it goes by itself and the step re-read, each
     * refused in that order.
     */
    private Acting acting(GroupId group, RunId run, WorkflowStepId step, UserId caller, StepAct act) {
        Set<GroupPermission> seen = GroupReach.reachedBy(roles.heldBy(caller, group));
        boolean inView = inView(group, run, caller, seen);
        Optional<LockedTree> locked = inView ? tree.lock(group, run) : Optional.empty();
        Set<GroupPermission> permitted = roles.stillReaching(caller, group, act.permission());
        LockedTree lockedTree = locked.orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
        if (!inView(group, run, caller, permitted)) {
            throw RunRefusal.RUN_NOT_IN_VIEW.raised();
        }
        // A hold whose stop was let go while nothing went on after it is released here, before the act is judged.
        engine.planWithin(lockedTree);
        RunSnapshot snapshot = snapshots.locked(lockedTree, run);
        StepSnapshot found = snapshot.step(step).orElseThrow(RunRefusal.STEP_NOT_IN_VIEW::raised);
        StepPosition position = StepPositions.of(snapshot, found);
        StepGround ground = StepGround.of(snapshot, found, position, RunScope.subjectOf(database, caller));
        RefusalCode refused = act.refusal(ground);
        if (refused == RefusalCode.CODE_STEP_GIVES_OTHERWISE) {
            throw RunRefusal.givesOtherwise(requireNonNull(ground.codeGivesOtherwise(), "a step giving otherwise"));
        }
        if (refused != null) {
            throw RunRefusal.answering(refused).raised();
        }
        return new Acting(lockedTree, snapshot, found, position);
    }

    private boolean inView(GroupId group, RunId run, UserId caller, Set<GroupPermission> permitted) {
        return RunScope.scoped(database.sql(IN_VIEW), group, run, caller, permitted)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * One value's decision as a reviewer sent it.
     *
     * @param why the words it was refused with, exactly where it was refused
     */
    record ReviewedRecord(
            ReviewOutcome outcome, @DoNotLog @Nullable String why) {

        ReviewedRecord {
            requireNonNull(outcome, "StepActs.ReviewedRecord outcome must not be null");
            if ((outcome == ReviewOutcome.REFUSED) != (why != null)) {
                throw new IllegalArgumentException("StepActs.ReviewedRecord says why exactly where it refused");
            }
        }
    }

    private record Acting(LockedTree tree, RunSnapshot run, StepSnapshot step, StepPosition position) {}
}
