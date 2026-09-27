package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.library.ConstantJson;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.run.Reasons;
import org.lilradish.lite.domain.run.ReviewOutcome;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * What a person does to a step of a run, each asked of a member holding the permission it takes, each naming
 * the try it acts on, and each answered with the step as it is read once the act has landed and the run has
 * gone on as far as it goes by itself.
 */
@RestController
final class StepActsController {

    static final String TRY = RunStepsController.STEP + "/tries/{number}";

    static final String SENDING = RunStepsController.STEP + "/sending";

    private static final String DECISIONS = "decisions";

    private static final String OUTCOME = "outcome";

    private static final String WHY = "why";

    /* A code point sent at its worst: a surrogate pair, each half escaped as six bytes. */
    private static final int ESCAPED_AT_WORST = 12;

    /* Every value a step gives back, at its first level, refused in the longest words, each escaped at its worst. */
    private static final int LARGEST_REVIEW_BODY = 256 * (Reasons.LONGEST * ESCAPED_AT_WORST + 256);

    private static final String REVIEW_REFUSED = "This takes a JSON object holding the decisions of one review: for"
            + " each value waiting, by its field's name, an object saying it is assured, or refused and why.";

    private static final String VALUES = "values";

    /* An answer less its values and its reason, written as a page sends it: nothing between its tokens. */
    private static final String ANSWER_ENVELOPE = "{\"" + VALUES + "\":,\"" + WHY + "\":\"\"}";

    /* The values written out and the reason at their longest, each code point escaped at its worst. */
    private static final int LARGEST_ANSWER_BODY =
            Math.toIntExact(ESCAPED_AT_WORST * (Declaration.MOST_SENT + Reasons.LONGEST) + ANSWER_ENVELOPE.length());

    private static final String ANSWER_REFUSED =
            "This takes a JSON object holding what is given for every field the answer gives back, and why.";

    private final StepActs acts;

    private final RunSteps steps;

    StepActsController(StepActs acts, RunSteps steps) {
        this.acts = acts;
        this.steps = steps;
    }

    @PutMapping(TRY)
    @GroupPermissionRequired(GroupPermission.ANSWER_STEP)
    StepAnswers.StepAnswer askAgain(
            @PathVariable String runId,
            @PathVariable String stepId,
            @PathVariable String number,
            HttpServletRequest request)
            throws IOException {
        Addressed addressed = addressed(runId, stepId, number, request);
        ChangeRequests.requireNothingSent(request);
        acts.askAgain(addressed.group(), addressed.run(), addressed.step(), addressed.number(), addressed.caller());
        return read(addressed);
    }

    @PutMapping(TRY + "/review")
    @GroupPermissionRequired(GroupPermission.REVIEW_AT_GATE)
    StepAnswers.StepAnswer review(
            @PathVariable String runId,
            @PathVariable String stepId,
            @PathVariable String number,
            HttpServletRequest request)
            throws IOException {
        Addressed addressed = addressed(runId, stepId, number, request);
        ChangeRequests.requireNoParameter(request);
        Map<String, StepActs.ReviewedRecord> decisions = decisionsIn(request);
        acts.review(
                addressed.group(),
                addressed.run(),
                addressed.step(),
                addressed.number(),
                addressed.caller(),
                decisions);
        return read(addressed);
    }

    @PutMapping(TRY + "/answer")
    @GroupPermissionRequired(GroupPermission.ANSWER_STEP)
    StepAnswers.StepAnswer answer(
            @PathVariable String runId,
            @PathVariable String stepId,
            @PathVariable String number,
            HttpServletRequest request)
            throws IOException {
        Addressed addressed = addressed(runId, stepId, number, request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, LARGEST_ANSWER_BODY, ANSWER_REFUSED);
        JsonNode values = body.path(VALUES);
        JsonNode why = body.path(WHY);
        if (!body.isObject()
                || !values.isObject()
                || body.size() != (why.isMissingNode() ? 1 : 2)
                || !(why.isMissingNode() || why.isNull() || why.isString())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, ANSWER_REFUSED);
        }
        String said = reasonIn(why);
        acts.answer(
                addressed.group(),
                addressed.run(),
                addressed.step(),
                addressed.number(),
                addressed.caller(),
                ConstantJson.sent(values.toString()),
                said);
        return read(addressed);
    }

    /** Takes no body, so no more than a byte of one is ever read before it is refused. */
    @PutMapping(SENDING)
    @GroupPermissionRequired(GroupPermission.START_RUN)
    StepAnswers.StepAnswer trySending(
            @PathVariable String runId, @PathVariable String stepId, HttpServletRequest request) throws IOException {
        RunId run = RunsController.runAt(runId);
        WorkflowStepId step = RunStepsController.stepAt(stepId);
        GroupId group = ActAdmission.admittedGroup(request);
        UserId caller = CallerAdmission.callerOf(request);
        ChangeRequests.requireNothingSent(request);
        acts.trySending(group, run, step, caller);
        return steps.step(group, run, step, caller);
    }

    /** A reason sent, text or nothing, as {@link Reasons#judged} takes it or refuses it. */
    private static String reasonIn(JsonNode why) {
        String said = why.isString() ? why.asString() : null;
        RefusalCode refused = Reasons.judged(said);
        if (refused != null) {
            throw RunRefusal.answering(refused).raised();
        }
        return requireNonNull(said, "a reason taken is one sent");
    }

    /** The step addressed, refused as none before anything else is read off the request. */
    private static Addressed addressed(String runId, String stepId, String number, HttpServletRequest request) {
        RunId run = RunsController.runAt(runId);
        WorkflowStepId step = RunStepsController.stepAt(stepId);
        return new Addressed(
                ActAdmission.admittedGroup(request), run, step, numberAt(number), CallerAdmission.callerOf(request));
    }

    /** A number no try could have names none, which is where the step is not. */
    private static int numberAt(String spelled) {
        if (spelled.isEmpty()
                || spelled.length() > 9
                || !spelled.chars().allMatch(Character::isDigit)
                || spelled.charAt(0) == '0') {
            throw RunRefusal.STEP_MOVED_ON.raised();
        }
        return Integer.parseInt(spelled);
    }

    private StepAnswers.StepAnswer read(Addressed addressed) {
        return steps.step(addressed.group(), addressed.run(), addressed.step(), addressed.caller());
    }

    private static Map<String, StepActs.ReviewedRecord> decisionsIn(HttpServletRequest request) throws IOException {
        JsonNode body = JsonBody.read(request, LARGEST_REVIEW_BODY, REVIEW_REFUSED);
        JsonNode decided = body.path(DECISIONS);
        if (!body.isObject() || body.size() != 1 || !decided.isObject()) {
            throw unusable();
        }
        Map<String, StepActs.ReviewedRecord> decisions = new HashMap<>();
        for (Map.Entry<String, JsonNode> decision : decided.properties()) {
            decisions.put(decision.getKey(), decided(decision.getValue()));
        }
        return decisions;
    }

    private static StepActs.ReviewedRecord decided(JsonNode decision) {
        JsonNode sent = decision.path(OUTCOME);
        if (!decision.isObject() || !sent.isString()) {
            throw unusable();
        }
        ReviewOutcome outcome = Arrays.stream(ReviewOutcome.values())
                .filter(each -> each.published().equals(sent.asString()))
                .findFirst()
                .orElseThrow(StepActsController::unusable);
        return switch (outcome) {
            case ASSURED -> {
                if (decision.size() != 1) {
                    throw unusable();
                }
                yield new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null);
            }
            case REFUSED -> {
                JsonNode why = decision.path(WHY);
                if (decision.size() > 2 || (decision.size() == 2 && !decision.has(WHY))) {
                    throw unusable();
                }
                if (!why.isMissingNode() && !why.isNull() && !why.isString()) {
                    throw unusable();
                }
                yield new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, reasonIn(why));
            }
        };
    }

    private static ApiErrorException unusable() {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, REVIEW_REFUSED);
    }

    private record Addressed(GroupId group, RunId run, WorkflowStepId step, int number, UserId caller) {}
}
