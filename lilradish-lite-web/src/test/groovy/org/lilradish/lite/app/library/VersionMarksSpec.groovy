package org.lilradish.lite.app.library

import java.sql.ResultSet
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.registry.VersionStanding
import spock.lang.Specification

/**
 * What a row of marks reads as, over a result set standing in for the store; what each statement selects is
 * asked of a real server wherever a version is read or changed.
 */
class VersionMarksSpec extends Specification {

    static final EntryVersionId VERSION = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000001"))

    /** Judged by whether each mark is held, never by one time compared with another. */
    def "in service is approved and not retired, of whichever version the alias names"() {
        expect:
        VersionMarks.inService("holder") == "(holder.approved_at is not null and holder.retired_at is null)"
    }

    def "waiting on approval is unapproved with a submission open, of whichever version the alias names"() {
        when:
        def written = VersionMarks.awaitingApproval("holder")

        then:
        written.startsWith("(holder.approved_at is null and exists (select 1")
        written.contains("where pending.entry_version_id = holder.entry_version_id")
        written.contains("and pending.withdrawn_at is null")
        !written.contains("version.")
    }

    def "a standing is read off the three marks the row carries"() {
        given:
        def row = marks(submitted: submitted, approved: approved, retired: retired)

        expect:
        VersionMarks.standing(row, VERSION) == standing

        where:
        submitted | approved | retired || standing
        false     | false    | false   || VersionStanding.DRAFT
        true      | false    | false   || VersionStanding.SUBMITTED
        true      | true     | false   || VersionStanding.IN_SERVICE
        false     | true     | true    || VersionStanding.RETIRED
    }

    /** Seeding submits only what it started; anything else is a store gone wrong, and said so rather than shown. */
    def "a version submitted by seeding it did not start fails the read, whatever it otherwise stands at"() {
        given:
        def row = marks(submitted: true, approved: approved, submitted_by_another_seeder: true)

        when:
        VersionMarks.standing(row, VERSION)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Version 00000007-0000-4000-8000-000000000001 holds a submission by seeding that seeding did not start"

        where:
        approved << [false, true]
    }

    def "whether the caller wrote it is read off the row as the store answered it"() {
        expect:
        VersionMarks.writtenByCaller(marks(written_by_caller: written)) == written

        where:
        written << [true, false]
    }

    private ResultSet marks(Map<String, Boolean> held) {
        Stub(ResultSet) {
            getBoolean(_ as String) >> { String column -> held.getOrDefault(column, false) }
        }
    }
}
