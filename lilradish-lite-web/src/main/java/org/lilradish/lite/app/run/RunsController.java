package org.lilradish.lite.app.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.pool.PersonAnswer;
import org.lilradish.lite.app.pool.PersonRows;
import org.lilradish.lite.domain.run.Ceiling;
import org.lilradish.lite.domain.run.RunAct;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunName;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.MintedIdentifiers;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * One run, answered to a member of the group the gate admitted them into who may read it, with what they may
 * do to it now. Counts go out as digits in a string: one past what a double holds exactly would arrive rounded.
 */
@RestController
final class RunsController {

    static final String RUN = ActAdmission.IN_A_GROUP + "/runs/{runId}";

    private static final String PARAMETER_REFUSED = "This run takes no parameter.";

    private final Runs runs;

    RunsController(Runs runs) {
        this.runs = runs;
    }

    @GetMapping(RUN)
    @GroupMembershipRequired
    RunAnswer run(@PathVariable String runId, HttpServletRequest request) {
        RunId run = runAt(runId);
        QueryParameters.requireNone(request, PARAMETER_REFUSED);
        return answer(runs.run(ActAdmission.admittedGroup(request), run, CallerAdmission.callerOf(request)));
    }

    static RunId runAt(String spelled) {
        return MintedIdentifiers.read(spelled).map(RunId::new).orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
    }

    /** The run as it is read now, which is what every act on it answers with too. */
    static RunAnswer answer(Runs.RunView run) {
        RunName name = run.name();
        RunId above = run.above();
        Runs.From from = run.from();
        PersonRows.Person startedBy = run.startedBy();
        Runs.Stop stop = run.stop();
        Runs.At at = run.at();
        RunBudget.Spend spend = run.spend();
        return new RunAnswer(
                run.runId().value(),
                run.number(),
                name == null ? null : name.value(),
                above == null ? null : above.value(),
                from == null
                        ? null
                        : new FromAnswer(
                                from.run().run().value(),
                                from.run().number(),
                                from.step().value(),
                                from.name().value()),
                run.runsAbove().stream().map(RunsController::aboveAnswer).toList(),
                new WorkflowAnswer(
                        run.workflow().entryId().value(),
                        run.workflow().name().value(),
                        run.workflow().version()),
                startedBy == null ? null : PersonAnswer.of(startedBy),
                run.startedAt().toString(),
                run.startedWith(),
                run.startedWithFrom() == null
                        ? null
                        : run.startedWithFrom().stream()
                                .map(source -> new StartedSourceAnswer(source.input(), source.from(), source.sources()))
                                .toList(),
                run.state().published(),
                at == null ? null : new AtAnswer(at.step().value(), at.name().value()),
                stop == null ? null : stopAnswer(stop),
                new SpendAnswer(
                        Long.toString(spend.sent()),
                        Long.toString(spend.cameBack()),
                        Long.toString(spend.spent()),
                        spend.cameBackUnknown(),
                        spend.measuredHere() == null
                                ? null
                                : spend.measuredHere().published()),
                ceilingAnswer(run.ceiling()),
                run.acts().stream().map(RunAct::published).toList(),
                run.conversationEvents());
    }

    private static RunAboveAnswer aboveAnswer(Runs.RunAbove above) {
        RunName name = above.name();
        return new RunAboveAnswer(above.run().value(), above.number(), name == null ? null : name.value());
    }

    private static StopAnswer stopAnswer(Runs.Stop stop) {
        return switch (stop.by()) {
            case Runs.Stopper.ByPerson byPerson ->
                new StopAnswer(stop.at().toString(), PersonAnswer.of(byPerson.person()), null, null);
            case Runs.Stopper.ByCeiling byCeiling ->
                new StopAnswer(
                        stop.at().toString(),
                        null,
                        numbered(byCeiling.reached().getFirst()),
                        byCeiling.reached().stream()
                                .map(RunsController::numbered)
                                .toList());
        };
    }

    private static CeilingAnswer ceilingAnswer(Runs.CeilingHeld ceiling) {
        return switch (ceiling) {
            case Runs.CeilingHeld.AtTop atTop -> new CeilingAnswer(null, null, null, numbered(atTop.top()));
            case Runs.CeilingHeld.Own own -> {
                CeilingRaises.Waiting waiting = own.waiting();
                yield new CeilingAnswer(
                        digitsOf(own.inForce().ceiling()),
                        own.inForce().raiseNeedsApproval(),
                        waiting == null
                                ? null
                                : new RaiseAnswer(
                                        waiting.change().value(),
                                        digitsOf(waiting.to()),
                                        PersonAnswer.of(waiting.askedBy()),
                                        waiting.askedAt().toString()),
                        null);
            }
        };
    }

    private static NumberedRunAnswer numbered(Runs.NumberedRun run) {
        return new NumberedRunAnswer(run.run().value(), run.number());
    }

    private static @Nullable String digitsOf(@Nullable Ceiling ceiling) {
        return ceiling == null ? null : Long.toString(ceiling.value());
    }

    /**
     * @param name absent beneath another run, which the run above started rather than anybody
     * @param runsAbove every run above it, from the top down; empty for a run at the top
     * @param startedWith each value as the start request or parent binding sends it
     * @param state where it is: running, stopped, failed or done
     * @param at the step it is on, only while it is running
     * @param acts what the caller may do to it now, by their published spellings
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunAnswer(
            UUID runId,
            int number,
            @Nullable String name,
            @Nullable UUID above,
            @Nullable FromAnswer from,
            List<RunAboveAnswer> runsAbove,
            WorkflowAnswer workflow,
            @Nullable PersonAnswer startedBy,
            String startedAt,
            @Nullable JsonNode startedWith,
            @Nullable List<StartedSourceAnswer> startedWithFrom,
            String state,
            @Nullable AtAnswer at,
            @Nullable StopAnswer stopped,
            SpendAnswer spend,
            CeilingAnswer ceiling,
            List<String> acts,
            List<RunActs.Event> conversationEvents) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StartedSourceAnswer(
            String input,
            StepAnswers.@Nullable FromAnswer from,
            @Nullable List<StepAnswers.SourceAnswer> sources) {}

    /** @param version the number of the version that ran */
    record WorkflowAnswer(UUID entryId, String name, int version) {}

    record FromAnswer(UUID runId, int number, UUID stepId, String stepName) {}

    /** @param name absent beneath the top of the tree, where nobody names a run */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunAboveAnswer(UUID runId, int number, @Nullable String name) {}

    /** @param name what the workflow calls the step */
    record AtAnswer(UUID stepId, String name) {}

    /**
     * @param ceilingOf absent where somebody stopped it, whom {@code by} names; otherwise the first of {@code ceilings}
     * @param ceilings every run whose ceiling stopped it, from the top down and by number at one depth; absent where
     *     somebody stopped it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StopAnswer(
            String at,
            @Nullable PersonAnswer by,
            @Nullable NumberedRunAnswer ceilingOf,
            @Nullable List<NumberedRunAnswer> ceilings) {}

    record NumberedRunAnswer(UUID runId, int number) {}

    /**
     * @param cameBackUnknown whether a call counted came back saying nothing, or is not back yet
     * @param measuredHere wholly or partly measured here; absent where no call was measured here
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SpendAnswer(
            String sent,
            String cameBack,
            String spent,
            boolean cameBackUnknown,
            @Nullable String measuredHere) {}

    /**
     * @param inForce absent where the run may spend without limit, or is held to the ceiling at the top
     * @param heldBy the run at the top whose ceiling a run beneath keeping none of its own is held to
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CeilingAnswer(
            @Nullable String inForce,
            @Nullable Boolean raiseNeedsApproval,
            @Nullable RaiseAnswer waiting,
            @Nullable NumberedRunAnswer heldBy) {}

    /** @param to absent where the raise takes the ceiling away */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RaiseAnswer(UUID changeId, @Nullable String to, PersonAnswer askedBy, String askedAt) {}
}
