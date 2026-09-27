package org.lilradish.lite.app.run;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.run.RunId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What a run is found within, written once: its group, and only a run the caller may read there. */
public final class RunScope {

    /** A run aliased {@code run}, bound as {@code :run}, {@code :group}, {@code :caller}, {@code :every}, {@code :own}. */
    static final String RUN_IN_VIEW = """
            run.run_id = :run and run.group_id = :group
            and (:every or (:own and exists (select 1
                                               from runs root
                                              where root.run_id = run.root_run_id and %s)))
            """.formatted(startedBy("root", ":caller"));

    private static final String CALLER =
            "select subject.subject_id from subjects subject where subject.user_id = :caller";

    private RunScope() {}

    /** The subject the caller is; none where no subject is them yet, which produced nothing. */
    static @Nullable SubjectId subjectOf(JdbcClient database, UserId caller) {
        return database.sql(CALLER)
                .param("caller", caller.value())
                .query(UUID.class)
                .optional()
                .map(SubjectId::new)
                .orElse(null);
    }

    /** Which of a group's runs somebody may read: every one, those of trees whose root they started, or none. */
    enum Reach {
        EVERY,
        OWN,
        NONE
    }

    static Reach reachedBy(Set<GroupPermission> permitted) {
        if (permitted.contains(GroupPermission.READ_ALL_RUNS)) {
            return Reach.EVERY;
        }
        return permitted.contains(GroupPermission.READ_OWN_RUNS) ? Reach.OWN : Reach.NONE;
    }

    /** Whether somebody may read a run they have just started, which is a root they started. */
    public static boolean readsWhatTheyStart(Set<GroupPermission> permitted) {
        return reachedBy(permitted) != Reach.NONE;
    }

    /** Whether the run aliased {@code run} was started by the person whose user id {@code caller} yields. */
    static String startedBy(String run, String caller) {
        return """
                exists (select 1 from subjects starter
                         where starter.subject_id = %s.created_by and starter.user_id = %s)""".formatted(run, caller);
    }

    /** Somebody reads the runs of the tree whose root they started, however deep, where they may read their own. */
    static JdbcClient.StatementSpec scoped(
            JdbcClient.StatementSpec statement,
            GroupId group,
            RunId run,
            UserId caller,
            Set<GroupPermission> permitted) {
        Reach reach = reachedBy(permitted);
        return statement
                .param("run", run.value())
                .param("group", group.value())
                .param("caller", caller.value())
                .param("every", reach == Reach.EVERY)
                .param("own", reach == Reach.OWN);
    }
}
