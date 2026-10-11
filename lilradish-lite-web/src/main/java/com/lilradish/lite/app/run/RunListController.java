package com.lilradish.lite.app.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.app.listing.ListParameters;
import com.lilradish.lite.app.pool.PersonAnswer;
import com.lilradish.lite.domain.listing.ListCursor;
import com.lilradish.lite.domain.listing.ListPage;
import com.lilradish.lite.domain.listing.ListPosition;
import com.lilradish.lite.domain.listing.ListQuery;
import com.lilradish.lite.domain.run.RunSortColumn;
import com.lilradish.lite.domain.workflow.StepId;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.CallerAdmission;
import com.lilradish.lite.web.GroupMembershipRequired;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A group's runs at the top a page at a time, answered to any member of the group the gate admitted them into:
 * which are listed is what the member may read, and one who may read none is answered with none.
 * The filter is matched as typed against a run's name, kept as its starter spaced it, and spaced against a workflow's.
 */
@RestController
final class RunListController {

    static final String RUNS = ActAdmission.IN_A_GROUP + "/runs";

    private final RunList runList;

    RunListController(RunList runList) {
        this.runList = runList;
    }

    @GetMapping(RUNS)
    @GroupMembershipRequired
    RunPageAnswer runs(HttpServletRequest request) {
        Map<String, String[]> sent =
                QueryParameters.sent(request, ListParameters.TAKEN, ListParameters.PARAMETER_REFUSED);
        RunList.Reading reading =
                runList.readingOf(ActAdmission.admittedGroup(request), CallerAdmission.callerOf(request));
        ListQuery<RunSortColumn> query = ListParameters.query(reading.shape(), reading.scope(), sent);
        ListPosition after = ListParameters.after(reading.shape(), query, sent);
        ListPage<RunList.RunRow> page = runList.page(reading, query, after);
        ListPosition next = page.next();
        return new RunPageAnswer(
                page.rows().stream().map(RunListController::answer).toList(),
                next == null ? null : ListCursor.mint(reading.shape(), query, next));
    }

    private static RunRowAnswer answer(RunList.RunRow row) {
        RunList.Listed run = row.listed();
        StepId at = row.at();
        return new RunRowAnswer(
                run.runId().value(),
                run.number(),
                run.name().value(),
                new WorkflowAnswer(
                        run.workflow().value(),
                        run.version(),
                        run.versionName() == null ? null : run.versionName().value()),
                PersonAnswer.of(run.startedBy()),
                run.startedAt().toString(),
                row.state().published(),
                at == null ? null : at.value(),
                row.atLabel());
    }

    /** A page of runs the caller may read, with a cursor bound to the same reading scope. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunPageAnswer(List<RunRowAnswer> items, @Nullable String nextCursor) {}

    /** @param at the name of the step a running run is on, left out where it is not running */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunRowAnswer(
            UUID runId,
            int number,
            String name,
            WorkflowAnswer workflow,
            PersonAnswer startedBy,
            String startedAt,
            String state,
            @Nullable String at,
            @Nullable String atLabel) {}

    record WorkflowAnswer(
            String name, int version, @Nullable String versionName) {}
}
