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
        PersonRows.Person startedBy = run.startedBy();
        Runs.Stop stop = run.stop();
        Runs.At at = run.at();
        RunBudget.Spend spend = run.spend();
        return new RunAnswer(
                run.runId().value(),
                run.number(),
                name == null ? null : name.value(),
                above == null ? null : above.value(),
                new WorkflowAnswer(
                        run.workflow().entryId().value(),
                        run.workflow().name().value(),
                        run.workflow().version()),
                startedBy == null ? null : PersonAnswer.of(startedBy),
                run.startedAt().toString(),
                run.startedWith(),
                run.state().published(),
                at == null ? null : new AtAnswer(at.step().value(), at.name().value()),
                stop == null ? null : stopAnswer(stop),
                new SpendAnswer(
                        Long.toString(spend.sent()),
                        Long.toString(spend.cameBack()),
                        Long.toString(spend.spent()),
                        spend.cameBackUnknown(),
                        spend.measuredHere() ? Boolean.TRUE : null),
                ceilingAnswer(run.ceiling()),
                run.acts().stream().map(RunAct::published).toList());
    }

    private static StopAnswer stopAnswer(Runs.Stop stop) {
        return switch (stop.by()) {
            case Runs.Stopper.ByPerson byPerson ->
                new StopAnswer(stop.at().toString(), PersonAnswer.of(byPerson.person()), null);
            case Runs.Stopper.ByCeiling byCeiling ->
                new StopAnswer(stop.at().toString(), null, numbered(byCeiling.reached()));
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
     * @param startedWith absent beneath another run; each value as the start request sends it
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
            WorkflowAnswer workflow,
            @Nullable PersonAnswer startedBy,
            String startedAt,
            @Nullable JsonNode startedWith,
            String state,
            @Nullable AtAnswer at,
            @Nullable StopAnswer stopped,
            SpendAnswer spend,
            CeilingAnswer ceiling,
            List<String> acts) {}

    /** @param version the number of the version that ran */
    record WorkflowAnswer(UUID entryId, String name, int version) {}

    /** @param name what the workflow calls the step */
    record AtAnswer(UUID stepId, String name) {}

    /** @param ceilingOf absent where somebody stopped it, whom {@code by} names */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StopAnswer(
            String at, @Nullable PersonAnswer by, @Nullable NumberedRunAnswer ceilingOf) {}

    record NumberedRunAnswer(UUID runId, int number) {}

    /**
     * @param cameBackUnknown whether a call counted came back saying nothing, or is not back yet
     * @param measuredHere present where any of it is what this system measured, the model having counted none of
     *     that call; absent where the model counted all of it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SpendAnswer(
            String sent,
            String cameBack,
            String spent,
            boolean cameBackUnknown,
            @Nullable Boolean measuredHere) {}

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
