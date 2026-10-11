package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.files.FileDeployment;
import com.lilradish.lite.app.files.RunFiles;
import com.lilradish.lite.app.filling.ValueProblemsRefusal;
import com.lilradish.lite.app.group.GroupReach;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.domain.codestep.CodeOutcome;
import com.lilradish.lite.domain.codestep.ReleasedCodeStep;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.filling.FillOutcome;
import com.lilradish.lite.domain.filling.FillProblems;
import com.lilradish.lite.domain.filling.FilledFields;
import com.lilradish.lite.domain.filling.Filling;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.run.DecisionRecord;
import com.lilradish.lite.domain.run.InputRecord;
import com.lilradish.lite.domain.run.ProductionId;
import com.lilradish.lite.domain.run.ProductionValueId;
import com.lilradish.lite.domain.run.ReviewOutcome;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunPayloads;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunStepFailureReason;
import com.lilradish.lite.domain.run.RunStepHoldReason;
import com.lilradish.lite.domain.run.StepAct;
import com.lilradish.lite.domain.run.StepGround;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepPosition;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.ValueStanding;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.domain.search.SearchWay;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.workflow.StepProducer;
import com.lilradish.lite.web.MultipartFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Each caller's act locks the tree before the group in one transaction, naming the try, hold or failure it read.
 * Repeating that press is refused or does nothing; the run goes on after commit.
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

    private final RunFiles files;

    private final FileDeployment deployment;
    private final ModelCatalog catalog;

    StepActs(
            JdbcClient database,
            TransactionOperations transactions,
            GroupRoles roles,
            RunTree tree,
            RunSnapshots snapshots,
            EngineWrites writes,
            RunEngine engine,
            RunFiles files,
            FileDeployment deployment,
            ModelCatalog catalog) {
        this.database = database;
        this.transactions = transactions;
        this.roles = roles;
        this.tree = tree;
        this.snapshots = snapshots;
        this.writes = writes;
        this.engine = engine;
        this.files = files;
        this.deployment = deployment;
        this.catalog = catalog;
    }

    /** Rejects one still unused source, under the same visibility, tree and group locks as any step act. */
    void refuseForLength(
            GroupId group, RunId run, WorkflowStepId step, UserId caller, UUID hold, ProductionValueId value) {
        RunId root = requireNonNull(transactions.execute(status -> {
            Set<GroupPermission> seen = GroupReach.reachedBy(roles.heldBy(caller, group));
            boolean visible = inView(group, run, caller, seen);
            Optional<LockedTree> locked = visible ? tree.lock(group, run) : Optional.empty();
            Set<GroupPermission> permitted = roles.stillReaching(caller, group, GroupPermission.REVIEW_AT_GATE);
            LockedTree held = locked.orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
            if (!inView(group, run, caller, permitted)) {
                throw RunRefusal.RUN_NOT_IN_VIEW.raised();
            }
            RunInTree context = snapshots.inTreeLocked(held, run);
            StepSnapshot found = context.run().step(step).orElseThrow(RunRefusal.STEP_NOT_IN_VIEW::raised);
            if (context.run().stopped()) {
                throw RunRefusal.RUN_STOPPED.raised();
            }
            RunReadDetails read = new RunReadDetails(database, context);
            if (found.hold() == null
                    || found.hold().reason() != RunStepHoldReason.TOO_LONG
                    || !read.lengthHold(found).equals(hold)) {
                throw RunRefusal.STEP_NOT_HELD_FOR_LENGTH.raised();
            }
            if (!read.used(value).isEmpty()) {
                throw RunRefusal.LENGTH_VALUE_USED.raised();
            }
            StepLengthDetails.Measured measured = StepLengthDetails.question(context, found, catalog);
            if (measured == null) {
                throw RunRefusal.LENGTH_VALUE_NOT_STANDING.raised();
            }
            StepLengthDetails.Row contribution = measured.details().rows().stream()
                    .filter(row -> value.equals(row.source()))
                    .findFirst()
                    .orElseThrow(RunRefusal.LENGTH_VALUE_NOT_STANDING::raised);
            RunReadDetails.MakerRecord maker = read.maker(value);
            RunInTree madeAt = null;
            for (RunInTree at = context; at != null; at = at.above()) {
                if (at.run().run().equals(maker.run().run())) {
                    madeAt = at;
                    break;
                }
            }
            if (madeAt == null) {
                throw RunRefusal.LENGTH_VALUE_NOT_STANDING.raised();
            }
            StepSnapshot source =
                    madeAt.run().step(new WorkflowStepId(maker.stepId())).orElseThrow();
            TryRecord produced = source.tries().stream()
                    .filter(aTry ->
                            aTry.values().stream().anyMatch(each -> each.id().equals(value)))
                    .findFirst()
                    .orElseThrow();
            boolean stands = produced.values().stream()
                    .filter(each -> each.id().equals(value))
                    .anyMatch(each -> ValueStanding.of(produced, each) == ValueStanding.STANDS);
            if (!stands) {
                throw RunRefusal.LENGTH_VALUE_NOT_STANDING.raised();
            }
            DeployedModel model = measured.model();
            long length = model.unitsOf(contribution.characters());
            long excess = model.unitsPast(measured.details().sent().characters());
            if (excess <= 0) {
                throw RunRefusal.STEP_NOT_HELD_FOR_LENGTH.raised();
            }
            String why = "Rejected for its length, not for what it says: it took up about " + units(length)
                    + " of what a later step would send, and that was about " + units(excess)
                    + " more than its model takes. Give it again, shorter.";
            writes.refusedForLength(
                    held,
                    madeAt.run(),
                    produced.id(),
                    hold,
                    run,
                    caller,
                    new DecisionRecord(value, ReviewOutcome.REFUSED, why, length, excess));
            if (source.used() < source.planned().declaredTries() && madeAt.run().stoppedFor(source) == null) {
                engine.askAgain(held, madeAt, source, source.used() + 1, caller);
            }
            engine.planWithin(held);
            return held.root();
        }));
        engine.goOn(group, root);
    }

    private static String units(long quantity) {
        return quantity + (quantity == 1 ? " unit" : " units");
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
            List<String> assured = waiting.values().stream()
                    .filter(value ->
                            requireNonNull(decisions.get(value.field())).outcome() == ReviewOutcome.ASSURED)
                    .map(StepPosition.WaitingValue::field)
                    .toList();
            holdOutputs(acting, acting.step().newest().orElseThrow().id(), assured);
            return acting.tree().root();
        }));
        engine.goOn(group, root);
    }

    /** Try {@code number}, asked of whoever the step names to produce it. */
    void askAgain(GroupId group, RunId run, WorkflowStepId step, int number, UserId caller) {
        RunId root = transactions.execute(status -> {
            Acting acting = acting(group, run, step, caller, StepAct.ASK_AGAIN);
            if (searchMismatch(acting.step())) {
                return null;
            }
            if (number != next(acting)) {
                throw RunRefusal.STEP_MOVED_ON.raised();
            }
            // Asked as the run asks a try by itself, a model's here and sent from the engine's thread. Code's try is
            // written only on the engine's thread: one dying before it loses this press, and Ask again is offered.
            engine.askAgain(acting.tree(), acting.context(), acting.step(), number, caller);
            engine.planWithin(acting.tree());
            return acting.tree().root();
        });
        if (root == null) {
            throw RunRefusal.STEP_MOVED_ON.raised();
        }
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
        answered(group, run, step, number, caller, values, why, null);
    }

    /** Answer with file parts consumed only after the locked step and its declaration are rechecked. */
    void answerFiles(
            GroupId group,
            RunId run,
            WorkflowStepId step,
            int number,
            UserId caller,
            JsonValue values,
            String why,
            MultipartFiles body) {
        answered(group, run, step, number, caller, values, why, requireNonNull(body));
    }

    private void answered(
            GroupId group,
            RunId run,
            WorkflowStepId step,
            int number,
            UserId caller,
            JsonValue values,
            String why,
            @Nullable MultipartFiles body) {
        requireNonNull(values, "StepActs values must not be null");
        requireNonNull(why, "StepActs why must not be null");
        RunId root = transactions.execute(status -> {
            Acting acting = acting(group, run, step, caller, StepAct.ANSWER);
            if (searchMismatch(acting.step())) {
                return null;
            }
            if (number != next(acting)) {
                throw RunRefusal.STEP_MOVED_ON.raised();
            }
            StepSnapshot found = acting.step();
            ProductionId answered =
                    switch (found.planned().runs()) {
                        case StepRuns.Question question -> {
                            List<FillField> gives =
                                    FillField.of(question.gives().fields(), question.lists());
                            yield answerQuestion(
                                    acting, question, number, caller, filled(acting, gives, values, body), why);
                        }
                        case StepRuns.Code code -> {
                            ReleasedCodeStep released = requireNonNull(
                                    code.released(), "a code step answered here is one the release holds");
                            List<FillField> gives =
                                    FillField.of(released.declared().gives().fields(), released.lists());
                            yield answerCode(acting, code, number, caller, filled(acting, gives, values, body), why);
                        }
                        case StepRuns.Search search -> {
                            SearchWay way = requireNonNull(search.released(), "an answered search holds its way");
                            yield answerSearch(
                                    acting,
                                    search,
                                    number,
                                    caller,
                                    filled(acting, FillField.of(way.outputs(), Map.of()), values, body),
                                    why);
                        }
                        case StepRuns.Workflow ignored ->
                            throw new IllegalStateException("A workflow's try is never answered here");
                        case StepRuns.Route ignored ->
                            throw new IllegalStateException("A route's try is never answered here");
                    };
            List<String> answeredFields =
                    switch (found.planned().runs()) {
                        case StepRuns.Question question ->
                            question.gives().fields().stream()
                                    .map(field -> field.name().value())
                                    .toList();
                        case StepRuns.Code code ->
                            requireNonNull(code.released()).declared().gives().fields().stream()
                                    .map(field -> field.name().value())
                                    .toList();
                        case StepRuns.Search search ->
                            requireNonNull(search.released()).outputs().stream()
                                    .map(field -> field.name().value())
                                    .toList();
                        default -> throw new IllegalStateException("Only a producing step is answered");
                    };
            holdOutputs(acting, answered, answeredFields);
            return acting.tree().root();
        });
        if (root == null) {
            throw RunRefusal.STEP_MOVED_ON.raised();
        }
        engine.goOn(group, root);
    }

    private ProductionId answerQuestion(
            Acting acting,
            StepRuns.Question question,
            int number,
            UserId caller,
            JsonValue.JsonObject kept,
            String why) {
        List<FillField> gives = FillField.of(question.gives().fields(), question.lists());
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
                        StepInputs.traced(acting.context(), found, question.takes())
                                .orElseThrow(() -> new IllegalStateException(
                                        "What step " + found.planned().id().value() + " took no longer stands")));
        List<EngineWrites.AnsweredRecord> given = new ArrayList<>(gives.size());
        for (int index = 0; index < gives.size(); index++) {
            given.add(new EngineWrites.AnsweredRecord(
                    question.givesFields().get(index), kept.members().get(index).value()));
        }
        writes.answered(acting.tree(), acting.run(), answered, caller, why, given);
        return answered;
    }

    /* Read against what the release declares now, which is what every step after reads it as. */
    private ProductionId answerCode(
            Acting acting, StepRuns.Code code, int number, UserId caller, JsonValue.JsonObject kept, String why) {
        ReleasedCodeStep released =
                requireNonNull(code.released(), "a code step answered here is one the release holds");
        List<Field> declared = released.declared().gives().fields();
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
                        StepInputs.bound(acting.context(), found)
                                .orElseThrow(() -> new IllegalStateException(
                                        "What step " + found.planned().id().value() + " took no longer stands"))
                                .stream()
                                .map(each -> new InputRecord(each.binding().id(), each.source(), each.parts()))
                                .toList());
        List<CodeOutcome.Given> given = new ArrayList<>(declared.size());
        for (int index = 0; index < declared.size(); index++) {
            given.add(CodeOutcome.Given.of(
                    declared.get(index), kept.members().get(index).value()));
        }
        writes.codeAnswered(acting.tree(), acting.run(), answered, caller, why, given);
        return answered;
    }

    private ProductionId answerSearch(
            Acting acting, StepRuns.Search search, int number, UserId caller, JsonValue.JsonObject kept, String why) {
        StepSnapshot found = acting.step();
        ProductionId answered;
        if (owed(acting).open()) {
            TryRecord open = found.newest().orElseThrow();
            StepInputs.took(acting.context(), found, open);
            answered = open.id();
        } else {
            List<InputRecord> inputs = StepInputs.traced(
                            acting.context(), found, search.question().takes())
                    .orElseThrow(() -> new IllegalStateException("What the search takes no longer stands"));
            answered = writes.tried(
                    acting.tree(),
                    acting.run(),
                    requireNonNull(found.runStep(), "a search owing a try is written"),
                    number,
                    StepProducer.PERSON,
                    caller,
                    null,
                    inputs);
        }
        writes.searchAnswered(acting.tree(), acting.run(), answered, caller, why, search.standings(), kept);
        return answered;
    }

    private void holdOutputs(Acting acting, ProductionId answered, List<String> fields) {
        List<String> affected =
                writes.fileOutputsAffected(acting.tree(), acting.run().run(), answered, fields);
        if (!affected.isEmpty()) {
            RunEngine.holdBeneathOutputs(
                    snapshots.inTreeLocked(acting.tree(), acting.run().run()), affected, files);
        }
    }

    private JsonValue.JsonObject filled(
            Acting acting, List<FillField> gives, JsonValue values, @Nullable MultipartFiles body) {
        if (body == null) {
            return filled(gives, values);
        }
        JsonValue.JsonObject previous = RunPayloads.refusedBeforeAnswer(acting.step())
                .map(StepActs::previous)
                .orElse(null);
        FillOutcome outcome;
        try {
            outcome = body.fill(acting.run().run(), acting.tree().group(), gives, values, previous, files, deployment);
        } catch (IOException unread) {
            throw new UncheckedIOException(unread);
        }
        return switch (outcome) {
            case FilledFields filled -> filled.values();
            case FillProblems problems -> throw new ValueProblemsRefusal(problems);
            case FillOutcome.Unshaped ignored -> throw RunRefusal.VALUES_UNSHAPED.raised();
        };
    }

    private static JsonValue.JsonObject previous(TryRecord prior) {
        return new JsonValue.JsonObject(prior.values().stream()
                .map(value -> new JsonMember(value.field(), value.value()))
                .toList());
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
        RunInTree context = snapshots.inTreeLocked(lockedTree, run);
        RunSnapshot snapshot = context.run();
        StepSnapshot found = snapshot.step(step).orElseThrow(RunRefusal.STEP_NOT_IN_VIEW::raised);
        StepPosition position = StepPositions.of(snapshot, found);
        StepGround ground = StepGround.of(snapshot, found, position, RunScope.subjectOf(database, caller));
        RefusalCode refused = act.refusal(ground);
        if (refused == RefusalCode.CODE_STEP_GIVES_OTHERWISE) {
            throw RunRefusal.givesOtherwise(requireNonNull(ground.codeGivesOtherwise(), "a step giving otherwise"));
        }
        if (refused != null
                && !(refused == RefusalCode.STEP_MOVED_ON
                        && (act == StepAct.ANSWER || act == StepAct.ASK_AGAIN)
                        && searchMismatch(found))) {
            throw RunRefusal.answering(refused).raised();
        }
        return new Acting(lockedTree, context, snapshot, found, position);
    }

    private static boolean searchMismatch(StepSnapshot step) {
        return StepPositions.inForce(step)
                .filter(failure -> failure.reason() == RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES)
                .isPresent();
    }

    private boolean inView(GroupId group, RunId run, UserId caller, Set<GroupPermission> permitted) {
        return RunScope.scoped(database.sql(IN_VIEW), group, run, caller, permitted)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
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

    private record Acting(
            LockedTree tree, RunInTree context, RunSnapshot run, StepSnapshot step, StepPosition position) {}
}
