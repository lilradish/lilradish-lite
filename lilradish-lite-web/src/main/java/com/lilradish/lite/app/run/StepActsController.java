package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.library.ConstantJson;
import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.run.ProductionValueId;
import com.lilradish.lite.domain.run.Reasons;
import com.lilradish.lite.domain.run.ReviewOutcome;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.ChangeRequests;
import com.lilradish.lite.web.FileTransfers;
import com.lilradish.lite.web.GroupPermissionRequired;
import com.lilradish.lite.web.JsonBody;
import com.lilradish.lite.web.MintedIdentifiers;
import com.lilradish.lite.web.MultipartFiles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
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

    static final String TRY = RunStepsController.TRY;

    static final String SENDING = RunStepsController.STEP + "/sending";

    static final String LENGTH_REFUSAL = RunStepsController.STEP + "/length/refusal";

    private static final String LENGTH_REFUSAL_BODY =
            "This takes a JSON object holding only the holdId and valueId of the value to reject for length.";

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
    private final FileTransfers transfers;

    StepActsController(StepActs acts, RunSteps steps, FileTransfers transfers) {
        this.acts = acts;
        this.steps = steps;
        this.transfers = transfers;
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

    @PutMapping(LENGTH_REFUSAL)
    @GroupPermissionRequired(GroupPermission.REVIEW_AT_GATE)
    StepAnswers.StepAnswer refuseForLength(
            @PathVariable String runId, @PathVariable String stepId, HttpServletRequest request) throws IOException {
        RunId run = RunsController.runAt(runId);
        WorkflowStepId step = RunStepsController.stepAt(stepId);
        GroupId group = ActAdmission.admittedGroup(request);
        UserId caller = CallerAdmission.callerOf(request);
        ChangeRequests.requireNoParameter(request);
        // The bound includes both UUIDs even when every character uses a JSON Unicode escape.
        JsonNode body = JsonBody.read(request, 1024, LENGTH_REFUSAL_BODY);
        if (!body.isObject() || body.size() != 2 || !body.has("holdId") || !body.has("valueId")) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, LENGTH_REFUSAL_BODY);
        }
        acts.refuseForLength(
                group,
                run,
                step,
                caller,
                lengthIdentifier(body.path("holdId")),
                new ProductionValueId(lengthIdentifier(body.path("valueId"))));
        return steps.step(group, run, step, caller);
    }

    private static UUID lengthIdentifier(JsonNode node) {
        if (!node.isString()) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, LENGTH_REFUSAL_BODY);
        }
        return MintedIdentifiers.read(node.asString())
                .orElseThrow(() -> new ApiErrorException(RefusalCode.BODY_UNUSABLE, LENGTH_REFUSAL_BODY));
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
    Object answer(
            @PathVariable String runId,
            @PathVariable String stepId,
            @PathVariable String number,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        Addressed addressed = addressed(runId, stepId, number, request);
        ChangeRequests.requireNoParameter(request);
        if (MultipartFiles.sent(request)) {
            return transfers.upload(
                    request,
                    response,
                    LARGEST_ANSWER_BODY,
                    multipart -> answer(addressed, multipart.document(), multipart));
        }
        return answer(addressed, JsonBody.read(request, LARGEST_ANSWER_BODY, ANSWER_REFUSED), null);
    }

    private StepAnswers.StepAnswer answer(Addressed addressed, JsonNode body, @Nullable MultipartFiles multipart) {
        JsonNode values = body.path(VALUES);
        JsonNode why = body.path(WHY);
        if (!body.isObject()
                || !values.isObject()
                || body.size() != (why.isMissingNode() ? 1 : 2)
                || !(why.isMissingNode() || why.isNull() || why.isString())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, ANSWER_REFUSED);
        }
        String said = reasonIn(why);
        if (multipart == null) {
            acts.answer(
                    addressed.group(),
                    addressed.run(),
                    addressed.step(),
                    addressed.number(),
                    addressed.caller(),
                    ConstantJson.sent(values.toString()),
                    said);
        } else {
            acts.answerFiles(
                    addressed.group(),
                    addressed.run(),
                    addressed.step(),
                    addressed.number(),
                    addressed.caller(),
                    ConstantJson.sent(values.toString()),
                    said,
                    multipart);
        }
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
