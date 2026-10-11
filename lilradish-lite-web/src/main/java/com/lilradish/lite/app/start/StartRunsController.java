package com.lilradish.lite.app.start;

import com.lilradish.lite.app.library.ConstantJson;
import com.lilradish.lite.domain.filling.Filling;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.run.RunName;
import com.lilradish.lite.domain.wire.JsonValue;
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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Starting a run, asked of a member who may start one in the group the gate admitted them into, and answered with
 * the run's address and its number. Read permissions are separate from permission to start a run.
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
    private final FileTransfers transfers;

    StartRunsController(StartRuns starts, FileTransfers transfers) {
        this.starts = starts;
        this.transfers = transfers;
    }

    @PostMapping(RUNS)
    @GroupPermissionRequired(GroupPermission.START_RUN)
    Object start(HttpServletRequest request, HttpServletResponse response) throws IOException {
        GroupId group = ActAdmission.admittedGroup(request);
        ChangeRequests.requireNoParameter(request);
        UserId caller = CallerAdmission.callerOf(request);
        if (MultipartFiles.sent(request)) {
            return transfers.upload(
                    request,
                    response,
                    Filling.LARGEST_REQUEST_BYTES,
                    multipart -> start(caller, group, multipart.document(), multipart));
        }
        JsonNode body = JsonBody.read(request, Filling.LARGEST_REQUEST_BYTES, StartRefusal.BODY_UNUSABLE.sentence());
        return start(caller, group, body, null);
    }

    private ResponseEntity<StartedAnswer> start(
            UserId caller, GroupId group, JsonNode body, @Nullable MultipartFiles multipart) {
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
                ? starts.start(group, version, name, values, caller)
                : starts.startFiles(group, version, name, values, caller, multipart);
        UUID run = started.run().value();
        return ResponseEntity.created(UriComponentsBuilder.fromPath(RUN)
                        .buildAndExpand(group.value(), run)
                        .toUri())
                .body(new StartedAnswer(run, started.number()));
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

    record StartedAnswer(UUID runId, int number) {}
}
