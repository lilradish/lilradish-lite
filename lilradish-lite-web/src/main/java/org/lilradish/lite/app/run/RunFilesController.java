package org.lilradish.lite.app.run;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.lilradish.lite.app.files.FileOpenAdmission;
import org.lilradish.lite.app.files.RunFiles;
import org.lilradish.lite.app.files.TransferPace;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.MintedIdentifiers;
import org.lilradish.lite.web.QueryParameters;
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

    RunFilesController(JdbcClient database, GroupRoles roles, RunFiles files, FileOpenAdmission fileAdmission) {
        this.database = database;
        this.roles = roles;
        this.files = files;
        this.fileAdmission = fileAdmission;
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
        try (InputStream content = opened.content()) {
            response.setHeader("Content-Type", opened.file().type().value());
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
            OutputStream download = response.getOutputStream();
            byte[] chunk = new byte[64 * 1024];
            long startedAtNanos = System.nanoTime();
            long sent = 0;
            int count;
            while ((count = content.read(chunk)) >= 0) {
                download.write(chunk, 0, count);
                sent += count;
                if (TransferPace.tooSlow(startedAtNanos, sent, System.nanoTime())) {
                    download.close();
                    return;
                }
            }
        }
    }
}
