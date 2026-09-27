package org.lilradish.lite.app.library;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.lilradish.lite.app.change.Author;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.registry.VersionStanding;

/**
 * A version's standing and authorship, written once for every statement reading them. The starter is
 * never also held as a writer, so authorship asks both.
 */
final class VersionMarks {

    // DB-SPECIFIC: exists(…) selected as a boolean and an enum compared with a literal are PostgreSQL's.
    static final String SELECTED = """
            version.approved_at is not null as approved,
            version.retired_at is not null as retired,
            %1$s as submitted,
            exists (select 1
                      from entry_version_submissions pending
                     where pending.entry_version_id = version.entry_version_id
                       and pending.withdrawn_at is null
                       and pending.created_by_kind = 'seeder'
                       and pending.created_by <> version.created_by) as submitted_by_another_seeder,
            (version.created_by = %2$s
                 or exists (select 1
                              from entry_version_writers writer
                             where writer.entry_version_id = version.entry_version_id
                               and writer.created_by = %2$s)) as written_by_caller""".formatted(submissionOpen("version"), Author.OF_CALLER);

    private VersionMarks() {}

    /** Whether the version aliased {@code alias} is in service now. */
    static String inService(String alias) {
        return "(%1$s.approved_at is not null and %1$s.retired_at is null)".formatted(alias);
    }

    /** Whether the version aliased {@code alias} waits on approval, approving leaving its submission open. */
    static String awaitingApproval(String alias) {
        return "(%s.approved_at is null and %s)".formatted(alias, submissionOpen(alias));
    }

    /** Seeding submits only what it started, so a seeded submission of anything else is a store gone wrong. */
    static VersionStanding standing(ResultSet result, EntryVersionId version) throws SQLException {
        if (result.getBoolean("submitted_by_another_seeder")) {
            throw new IllegalStateException(
                    "Version " + version.value() + " holds a submission by seeding that seeding did not start");
        }
        return VersionStanding.of(
                result.getBoolean("submitted"), result.getBoolean("approved"), result.getBoolean("retired"));
    }

    static boolean writtenByCaller(ResultSet result) throws SQLException {
        return result.getBoolean("written_by_caller");
    }

    private static String submissionOpen(String alias) {
        return """
                exists (select 1
                          from entry_version_submissions pending
                         where pending.entry_version_id = %s.entry_version_id
                           and pending.withdrawn_at is null)""".formatted(alias);
    }
}
