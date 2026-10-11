package com.lilradish.lite.app.run;

import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.GroupMembershipRequired;
import com.lilradish.lite.web.MintedIdentifiers;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
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

    static final String TRY = STEP + "/tries/{number}";

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

    @GetMapping(TRY)
    @GroupMembershipRequired
    StepAnswers.TryDetailsRecord tryDetails(
            @PathVariable String runId,
            @PathVariable String stepId,
            @PathVariable String number,
            HttpServletRequest request) {
        RunId run = RunsController.runAt(runId);
        WorkflowStepId step = stepAt(stepId);
        int spentTry = spentTryAt(number);
        QueryParameters.requireNone(request, PARAMETER_REFUSED);
        return steps.tryDetails(
                ActAdmission.admittedGroup(request), run, step, spentTry, CallerAdmission.callerOf(request));
    }

    private static int spentTryAt(String spelled) {
        if (spelled.isEmpty() || spelled.length() > 9 || spelled.charAt(0) == '0') {
            throw RunRefusal.STEP_NOT_IN_VIEW.raised();
        }
        for (int at = 0; at < spelled.length(); at++) {
            if (spelled.charAt(at) < '0' || spelled.charAt(at) > '9') {
                throw RunRefusal.STEP_NOT_IN_VIEW.raised();
            }
        }
        return Integer.parseInt(spelled);
    }
}
