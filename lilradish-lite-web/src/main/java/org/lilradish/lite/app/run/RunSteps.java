package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.app.pool.PersonAnswer;
import org.lilradish.lite.app.pool.PersonRows;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A run's steps as one moment of the store reads them, for a member of its group who may read the run, shaped by
 * {@link StepReading} from what this reads and nothing else.
 */
@Component
final class RunSteps {

    /** While a run is running its page reads it again this long after each read; one read is one snapshot. */
    static final int REREAD_AFTER_SECONDS = 5;

    private static final String HEADER = """
            select run.number, run.parent_run_id is null as at_top from runs run where %s
            """.formatted(RunScope.RUN_IN_VIEW);

    // DB-SPECIFIC: array casts and any(…) are PostgreSQL's.
    private static final String PEOPLE = """
            select person.subject_id, person.user_id, person.display_name
              from subjects person
             where person.subject_id = any(cast(:subjects as uuid[]))
            """;

    // DB-SPECIFIC: an enum compared to a literal, and a comparison selected as a value, are PostgreSQL's.
    private static final String LOST = """
            select try.production_id, cast(try.did_not_fit_reason as text) as did_not_fit_reason, call.error_detail,
                   call.error_detail_truncated
              from productions try
              join model_calls call on call.model_call_id = try.model_call_id
             where try.run_id = :run and try.producer = 'model' and try.lost_reason in ('did_not_fit', 'errored')
            """;

    /*
     * Only calls to produce: a review turned away is told apart from them. Oldest first by when each was written, and
     * by its time-ordered key where two were written in one instant.
     */
    private static final String TURNED_AWAY = """
            select call.production_id, call.run_step_send_attempt_id, turnaway.created_at, turnaway.said,
                   turnaway.said_truncated, turnaway.resent_at is not null as sent_again
              from model_call_turnaways turnaway
              join model_calls call on call.model_call_id = turnaway.model_call_id
             where call.run_id = :run and call.purpose = 'produce'
             order by turnaway.created_at, turnaway.model_call_turnaway_id
            """;

    private final JdbcClient database;

    private final GroupRoles roles;

    private final RunSnapshots snapshots;

    private final TransactionTemplate snapshot;

    RunSteps(JdbcClient database, GroupRoles roles, RunSnapshots snapshots, PlatformTransactionManager manager) {
        this.database = database;
        this.roles = roles;
        this.snapshots = snapshots;
        TransactionTemplate snapshot = new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        this.snapshot = snapshot;
    }

    /** Somebody holding nothing in the group is refused as the group is, and a run they may not read as none. */
    StepAnswers.RunStepsAnswer steps(GroupId group, RunId run, UserId caller) {
        return requireNonNull(
                snapshot.execute(status -> reading(group, run, caller).steps()));
    }

    /** A step no version of the run holds is refused as none. */
    StepAnswers.StepAnswer step(GroupId group, RunId run, WorkflowStepId step, UserId caller) {
        return requireNonNull(
                snapshot.execute(status -> reading(group, run, caller).step(step)));
    }

    private StepReading reading(GroupId group, RunId run, UserId caller) {
        Set<GroupRole> held = roles.heldBy(caller, group);
        GroupReach.requireMember(held);
        Set<GroupPermission> permitted = GroupReach.reachedBy(held);
        Header header = RunScope.scoped(database.sql(HEADER), group, run, caller, permitted)
                .query((result, number) -> new Header(result.getInt("number"), result.getBoolean("at_top")))
                .optional()
                .orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
        RunSnapshot read = snapshots.asRead(group, run);
        return new StepReading(
                read,
                header.number(),
                header.atTop(),
                permitted,
                RunScope.subjectOf(database, caller),
                CeilingRaises.waiting(database, run, caller),
                people(StepReading.named(read)),
                RunBudget.spentByTryAsRead(database, run),
                StepReading.namesLost(read) ? lost(run) : Map.of(),
                StepReading.namesCalls(read) ? turnedAway(run) : Map.of());
    }

    private Map<ProductionId, List<StepReading.TurnawayRecord>> turnedAway(RunId run) {
        Map<ProductionId, List<StepReading.TurnawayRecord>> turned = new HashMap<>();
        database.sql(TURNED_AWAY).param("run", run.value()).query(result -> {
            turned.computeIfAbsent(
                            new ProductionId(result.getObject("production_id", UUID.class)),
                            ignored -> new ArrayList<>())
                    .add(new StepReading.TurnawayRecord(
                            new RunStepSendAttemptId(result.getObject("run_step_send_attempt_id", UUID.class)),
                            result.getObject("created_at", OffsetDateTime.class).toInstant(),
                            result.getString("said"),
                            result.getBoolean("said_truncated"),
                            result.getBoolean("sent_again")));
        });
        return turned;
    }

    private Map<ProductionId, StepReading.LostRecord> lost(RunId run) {
        Map<ProductionId, StepReading.LostRecord> lost = new HashMap<>();
        database.sql(LOST).param("run", run.value()).query(result -> {
            String misfit = result.getString("did_not_fit_reason");
            lost.put(
                    new ProductionId(result.getObject("production_id", UUID.class)),
                    new StepReading.LostRecord(
                            misfit == null ? null : StoreLabels.parse(DidNotFitReason.class, misfit),
                            result.getString("error_detail"),
                            result.getBoolean("error_detail_truncated")));
        });
        return lost;
    }

    private Map<SubjectId, PersonAnswer> people(Set<SubjectId> named) {
        Map<SubjectId, PersonAnswer> found = new HashMap<>();
        if (named.isEmpty()) {
            return found;
        }
        database.sql(PEOPLE)
                .param(
                        "subjects",
                        named.stream()
                                .map(subject -> subject.value().toString())
                                .toArray(String[]::new))
                .query(result -> {
                    PersonRows.Person person = PersonRows.person(result);
                    found.put(person.subjectId(), PersonAnswer.of(person));
                });
        return found;
    }

    private record Header(int number, boolean atTop) {}
}
