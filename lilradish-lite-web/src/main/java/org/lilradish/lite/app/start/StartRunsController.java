package org.lilradish.lite.app.start;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.library.ConstantJson;
import org.lilradish.lite.domain.filling.Filling;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.run.RunName;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.lilradish.lite.web.MintedIdentifiers;
import org.lilradish.lite.web.MultipartFiles;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Starting a run, asked of a member who may start one in the group the gate admitted them into, and answered with
 * the run's address, its number and whether they may read it, judged here by the rule reading a run is judged by:
 * whoever may start a run need not be one who may read it, and the page is never the one to judge that.
 *
 * <p>The request gives a run name, version, and filled values as JSON, or as bounded JSON followed by file parts.
 * File values come only from the checked parts. No parameter is taken.
 */
@RestController
final class StartRunsController {

    static final String RUNS = ActAdmission.IN_A_GROUP + "/runs";

    private static final String RUN = RUNS + "/{runId}";

    private static final String NAME = "name";

    private static final String VERSION = "versionId";

    private static final String VALUES = "values";

    private final StartRuns starts;

    StartRunsController(StartRuns starts) {
        this.starts = starts;
    }

    @PostMapping(RUNS)
    @GroupPermissionRequired(GroupPermission.START_RUN)
    ResponseEntity<StartedAnswer> start(HttpServletRequest request) throws IOException {
        GroupId group = ActAdmission.admittedGroup(request);
        ChangeRequests.requireNoParameter(request);
        if (MultipartFiles.sent(request)) {
            try (MultipartFiles multipart = MultipartFiles.open(request, Filling.LARGEST_REQUEST_BYTES)) {
                return start(request, group, multipart.document(), multipart);
            }
        }
        JsonNode body = JsonBody.read(request, Filling.LARGEST_REQUEST_BYTES, StartRefusal.BODY_UNUSABLE.sentence());
        return start(request, group, body, null);
    }

    private ResponseEntity<StartedAnswer> start(
            HttpServletRequest request, GroupId group, JsonNode body, @Nullable MultipartFiles multipart) {
        if (!body.isObject()
                || body.size() != 3
                || !body.path(NAME).isString()
                || !body.path(VERSION).isString()
                || !body.has(VALUES)) {
            throw StartRefusal.BODY_UNUSABLE.raised();
        }
        RunName name = nameIn(body.get(NAME).asString());
        EntryVersionId version = MintedIdentifiers.read(body.get(VERSION).asString())
                .map(EntryVersionId::new)
                .orElseThrow(StartRefusal.WORKFLOW_NOT_OFFERED::raised);
        JsonValue values = valuesIn(body.get(VALUES));
        StartRuns.Started started = multipart == null
                ? starts.start(group, version, name, values, CallerAdmission.callerOf(request))
                : starts.startFiles(group, version, name, values, CallerAdmission.callerOf(request), multipart);
        UUID run = started.run().value();
        return ResponseEntity.created(UriComponentsBuilder.fromPath(RUN)
                        .buildAndExpand(group.value(), run)
                        .toUri())
                .body(new StartedAnswer(run, started.number(), started.readable()));
    }

    private static RunName nameIn(String sent) {
        try {
            return new RunName(sent);
        } catch (IllegalArgumentException refused) {
            throw StartRefusal.RUN_NAME_UNUSABLE.raised(refused);
        }
    }

    /* Written again from the tree, which may respell a number: never a verdict changed, every value being sent as
    its text, so any number at all is no shape a field holds. */
    private static JsonValue valuesIn(JsonNode sent) {
        try {
            return ConstantJson.sent(sent.toString());
        } catch (IllegalArgumentException unread) {
            throw StartRefusal.BODY_UNUSABLE.raised(unread);
        }
    }

    record StartedAnswer(UUID runId, int number, boolean readable) {}
}
