package com.lilradish.lite.app.run;

import com.lilradish.lite.app.files.FileOpenAdmission;
import com.lilradish.lite.app.files.RunFiles;
import com.lilradish.lite.app.group.GroupReach;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.GroupRole;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.FileTransfers;
import com.lilradish.lite.web.GroupMembershipRequired;
import com.lilradish.lite.web.MintedIdentifiers;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Files of a readable run, delivered only as attachments. */
@RestController
final class RunFilesController {

    static final String FILE = RunsController.RUN + "/files/{fileId}";

    private static final String IN_VIEW = "select 1 from runs run where " + RunScope.RUN_IN_VIEW;

    private final JdbcClient database;
    private final GroupRoles roles;
    private final RunFiles files;
    private final FileOpenAdmission fileAdmission;
    private final FileTransfers transfers;

    RunFilesController(
            JdbcClient database,
            GroupRoles roles,
            RunFiles files,
            FileOpenAdmission fileAdmission,
            FileTransfers transfers) {
        this.database = database;
        this.roles = roles;
        this.files = files;
        this.fileAdmission = fileAdmission;
        this.transfers = transfers;
    }

    @GetMapping(FILE)
    @GroupMembershipRequired
    void download(
            @PathVariable String runId,
            @PathVariable String fileId,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        RunId run = RunsController.runAt(runId);
        QueryParameters.requireNone(request, "A file takes no parameter.");
        GroupId group = ActAdmission.admittedGroup(request);
        UserId caller = CallerAdmission.callerOf(request);
        Set<GroupRole> held = roles.heldBy(caller, group);
        GroupReach.requireMember(held);
        Set<GroupPermission> permitted = GroupReach.reachedBy(held);
        if (RunScope.scoped(database.sql(IN_VIEW), group, run, caller, permitted)
                .query(Integer.class)
                .optional()
                .isEmpty()) {
            throw RunRefusal.RUN_NOT_IN_VIEW.raised();
        }
        UUID file = MintedIdentifiers.read(fileId).orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
        RunFiles.OpenedFile opened = fileAdmission.open(files, run, group, file);
        try {
            response.setHeader("Content-Type", "application/octet-stream");
            response.setHeader(
                    "Content-Disposition",
                    ContentDisposition.attachment()
                            .filename(opened.file().name().value(), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
            response.setHeader("Cache-Control", "private, no-store");
            response.setContentLengthLong(opened.file().size());
            transfers.download(request, response, opened.content());
        } catch (IOException | RuntimeException | Error failure) {
            try {
                opened.content().close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }
}
