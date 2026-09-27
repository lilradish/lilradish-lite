package org.lilradish.lite.app.run;

import jakarta.servlet.http.HttpServletRequest;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.MintedIdentifiers;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A run's steps, answered to a member of the group the gate admitted them into who may read the run: all of
 * them for both ways of drawing it, or one for its own page.
 */
@RestController
final class RunStepsController {

    static final String STEPS = RunsController.RUN + "/steps";

    static final String STEP = STEPS + "/{stepId}";

    private static final String PARAMETER_REFUSED = "A run's steps take no parameter.";

    private final RunSteps steps;

    RunStepsController(RunSteps steps) {
        this.steps = steps;
    }

    @GetMapping(STEPS)
    @GroupMembershipRequired
    StepAnswers.RunStepsAnswer steps(@PathVariable String runId, HttpServletRequest request) {
        RunId run = RunsController.runAt(runId);
        QueryParameters.requireNone(request, PARAMETER_REFUSED);
        return steps.steps(ActAdmission.admittedGroup(request), run, CallerAdmission.callerOf(request));
    }

    @GetMapping(STEP)
    @GroupMembershipRequired
    StepAnswers.StepAnswer step(@PathVariable String runId, @PathVariable String stepId, HttpServletRequest request) {
        RunId run = RunsController.runAt(runId);
        WorkflowStepId step = stepAt(stepId);
        QueryParameters.requireNone(request, PARAMETER_REFUSED);
        return steps.step(ActAdmission.admittedGroup(request), run, step, CallerAdmission.callerOf(request));
    }

    static WorkflowStepId stepAt(String spelled) {
        return MintedIdentifiers.read(spelled)
                .map(WorkflowStepId::new)
                .orElseThrow(RunRefusal.STEP_NOT_IN_VIEW::raised);
    }
}
