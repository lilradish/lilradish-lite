package org.lilradish.lite.app.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.listing.ListParameters;
import org.lilradish.lite.app.pool.PersonAnswer;
import org.lilradish.lite.domain.listing.ListCursor;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.run.RunSortColumn;
import org.lilradish.lite.domain.workflow.StepId;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A group's runs at the top a page at a time, answered to any member of the group the gate admitted them into:
 * which are listed is what the member may read, said beside them, and one who may read none is answered with none.
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
                next == null ? null : ListCursor.mint(reading.shape(), query, next),
                published(reading.reach()));
    }

    private static String published(RunScope.Reach reach) {
        return switch (reach) {
            case EVERY -> "all";
            case OWN -> "own";
            case NONE -> "none";
        };
    }

    private static RunRowAnswer answer(RunList.RunRow row) {
        RunList.Listed run = row.listed();
        StepId at = row.at();
        return new RunRowAnswer(
                run.runId().value(),
                run.number(),
                run.name().value(),
                new WorkflowAnswer(run.workflow().value(), run.version()),
                PersonAnswer.of(run.startedBy()),
                run.startedAt().toString(),
                run.lastHappenedAt().toString(),
                row.state().published(),
                at == null ? null : at.value());
    }

    /** As {@code PageAnswer}, with which runs the reader may read: all, their own, or none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunPageAnswer(List<RunRowAnswer> items, @Nullable String nextCursor, String reading) {}

    /** @param at the name of the step a running run is on, left out where it is not running */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunRowAnswer(
            UUID runId,
            int number,
            String name,
            WorkflowAnswer workflow,
            PersonAnswer startedBy,
            String startedAt,
            String lastHappenedAt,
            String state,
            @Nullable String at) {}

    record WorkflowAnswer(String name, int version) {}
}
