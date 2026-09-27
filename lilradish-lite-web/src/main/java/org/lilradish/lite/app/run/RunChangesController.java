package org.lilradish.lite.app.run;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.run.Ceiling;
import org.lilradish.lite.domain.run.CeilingChangeId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunName;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.lilradish.lite.web.MintedIdentifiers;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Every act on a run, each asked of a member holding the permission it takes, and each answered with the run as
 * it is read once the act has landed. A raise is decided by its identifier, so nobody decides one they never saw.
 */
@RestController
final class RunChangesController {

    static final String STOP = RunsController.RUN + "/stop";

    static final String CEILING = RunsController.RUN + "/ceiling";

    static final String RAISE = RunsController.RUN + "/ceiling-changes/{changeId}";

    private static final String NAME = "name";

    private static final String CEILING_MEMBER = "ceiling";

    /** Room for the longest name with every character escaped, a surrogate pair each, and its member. */
    private static final int LARGEST_NAME_BODY = 2048;

    private static final int LARGEST_CEILING_BODY = 64;

    private static final String NAME_REFUSED = "This takes a JSON object holding a run's name, and nothing else.";

    private static final String CEILING_REFUSED =
            "This takes a JSON object holding a run's ceiling in digits, or null for none, and nothing else.";

    private final RunChanges changes;

    private final Runs runs;

    RunChangesController(RunChanges changes, Runs runs) {
        this.changes = changes;
        this.runs = runs;
    }

    @PatchMapping(RunsController.RUN)
    @GroupPermissionRequired(GroupPermission.START_RUN)
    RunsController.RunAnswer rename(@PathVariable String runId, HttpServletRequest request) throws IOException {
        Addressed addressed = addressed(runId, request);
        ChangeRequests.requireNoParameter(request);
        RunName name = nameIn(request);
        changes.rename(addressed.group(), addressed.run(), name, addressed.caller());
        return read(addressed);
    }

    @PutMapping(STOP)
    @GroupPermissionRequired(GroupPermission.START_RUN)
    RunsController.RunAnswer stop(@PathVariable String runId, HttpServletRequest request) throws IOException {
        Addressed addressed = addressed(runId, request);
        ChangeRequests.requireNothingSent(request);
        changes.stop(addressed.group(), addressed.run(), addressed.caller());
        return read(addressed);
    }

    @DeleteMapping(STOP)
    @GroupPermissionRequired(GroupPermission.START_RUN)
    RunsController.RunAnswer openAgain(@PathVariable String runId, HttpServletRequest request) throws IOException {
        Addressed addressed = addressed(runId, request);
        ChangeRequests.requireNothingSent(request);
        changes.openAgain(addressed.group(), addressed.run(), addressed.caller());
        return read(addressed);
    }

    @PatchMapping(CEILING)
    @GroupPermissionRequired(GroupPermission.START_RUN)
    RunsController.RunAnswer changeCeiling(@PathVariable String runId, HttpServletRequest request) throws IOException {
        Addressed addressed = addressed(runId, request);
        ChangeRequests.requireNoParameter(request);
        @Nullable Ceiling asked = ceilingIn(request);
        changes.changeCeiling(addressed.group(), addressed.run(), asked, addressed.caller());
        return read(addressed);
    }

    @PutMapping(RAISE + "/approval")
    @GroupPermissionRequired(GroupPermission.APPROVE_ENTRY)
    RunsController.RunAnswer approveRaise(
            @PathVariable String runId, @PathVariable String changeId, HttpServletRequest request) throws IOException {
        Addressed addressed = addressed(runId, request);
        CeilingChangeId change = changeAt(changeId);
        ChangeRequests.requireNothingSent(request);
        changes.approveRaise(addressed.group(), addressed.run(), change, addressed.caller());
        return read(addressed);
    }

    @PutMapping(RAISE + "/refusal")
    @GroupPermissionRequired(GroupPermission.APPROVE_ENTRY)
    RunsController.RunAnswer refuseRaise(
            @PathVariable String runId, @PathVariable String changeId, HttpServletRequest request) throws IOException {
        Addressed addressed = addressed(runId, request);
        CeilingChangeId change = changeAt(changeId);
        ChangeRequests.requireNothingSent(request);
        changes.refuseRaise(addressed.group(), addressed.run(), change, addressed.caller());
        return read(addressed);
    }

    @PutMapping(RAISE + "/withdrawal")
    @GroupPermissionRequired(GroupPermission.START_RUN)
    RunsController.RunAnswer withdrawRaise(
            @PathVariable String runId, @PathVariable String changeId, HttpServletRequest request) throws IOException {
        Addressed addressed = addressed(runId, request);
        CeilingChangeId change = changeAt(changeId);
        ChangeRequests.requireNothingSent(request);
        changes.withdrawRaise(addressed.group(), addressed.run(), change, addressed.caller());
        return read(addressed);
    }

    /** The run addressed, refused as none before anything else is read off the request. */
    private static Addressed addressed(String runId, HttpServletRequest request) {
        RunId run = RunsController.runAt(runId);
        return new Addressed(ActAdmission.admittedGroup(request), run, CallerAdmission.callerOf(request));
    }

    private RunsController.RunAnswer read(Addressed addressed) {
        return RunsController.answer(runs.run(addressed.group(), addressed.run(), addressed.caller()));
    }

    private static CeilingChangeId changeAt(String spelled) {
        return MintedIdentifiers.read(spelled)
                .map(CeilingChangeId::new)
                .orElseThrow(RunRefusal.CEILING_RAISE_NOT_WAITING::raised);
    }

    private static RunName nameIn(HttpServletRequest request) throws IOException {
        JsonNode body = JsonBody.read(request, LARGEST_NAME_BODY, NAME_REFUSED);
        if (!body.isObject() || body.size() != 1 || !body.path(NAME).isString()) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, NAME_REFUSED);
        }
        try {
            return new RunName(body.get(NAME).asString());
        } catch (IllegalArgumentException refused) {
            throw RunRefusal.RUN_NAME_UNUSABLE.raised(refused);
        }
    }

    /** None where the body asks for none, which takes the ceiling away. */
    private static @Nullable Ceiling ceilingIn(HttpServletRequest request) throws IOException {
        JsonNode body = JsonBody.read(request, LARGEST_CEILING_BODY, CEILING_REFUSED);
        JsonNode ceiling = body.path(CEILING_MEMBER);
        if (!body.isObject() || body.size() != 1 || !(ceiling.isString() || ceiling.isNull())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, CEILING_REFUSED);
        }
        if (ceiling.isNull()) {
            return null;
        }
        try {
            return Ceiling.typed(ceiling.asString());
        } catch (IllegalArgumentException refused) {
            throw RunRefusal.CEILING_UNUSABLE.raised(refused);
        }
    }

    private record Addressed(GroupId group, RunId run, UserId caller) {}
}
