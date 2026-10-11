package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.LibraryCases.*
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*
import static com.lilradish.lite.migration.fixture.SoundnessCases.*

import com.lilradish.lite.migration.fixture.GroupCases
import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification
import org.postgresql.util.PSQLException

/** What memberships, groups, entries and their versions, code step publications, soundness checks
 * and currencies are held to. */
class SchemaConstraintRegistryIntegrationSpec extends SchemaConstraintSpecification {

    // DB-SPECIFIC: the fixture variations below input PostgreSQL native group_role,
    // group_member_removal, entry_kind, subject_kind and soundness_check_outcome labels.

    def "a version name has the database nonempty floor without copying the application's Unicode rules"() {
        expect:
        attempt("entry_versions", "update app.entry_versions set version_name = ${literal(value)}" +
                " where entry_version_id = '${VERSION}'") == refusal

        where:
        value                                     || refusal
        "Mooncrate review"                        || null
        "a" * 64                                  || null
        Character.toString(0x1F4E6) * 64           || null
        " "                                       || null
        "a" + Character.toString(9) + "b"         || null
        ""                                        || "entry_versions_version_name_not_empty"
    }

    def "version names may repeat and may be cleared without changing the version's address"() {
        expect:
        readInside("update app.entry_versions set version_name = 'Same current name'" +
                " where entry_version_id in ('${VERSION}', '${WORKFLOW_VERSION}');" +
                " update app.entry_versions set version_name = null where entry_version_id = '${VERSION}'",
                "select count(*) from app.entry_versions where" +
                        " (entry_version_id = '${VERSION}' and version_name is null)" +
                        " or (entry_version_id = '${WORKFLOW_VERSION}' and version_name = 'Same current name')") == "2"
    }

    def "the database counts supplementary Unicode characters once and refuses a sixty-fifth"() {
        when:
        attempt("entry_versions", "update app.entry_versions set version_name = ${literal(Character.toString(0x1F4E6) * 65)}" +
                " where entry_version_id = '${VERSION}'")

        then:
        def refused = thrown(PSQLException)
        refused.SQLState == "22001"
    }

    /** Where a group key is held to one shape: how a row carrying one is written, and the rule refusing it. */
    static final List<List<String>> GROUP_KEYED = [
        ["groups", "insert into app.groups (group_id, key, name, created_by)" +
                " values ('${SPARE_GROUP}', %s, 'Billing', '${STEWARD}')", "groups_key_shape"],
        ["code_step_publications", insertInto("code_step_publications", A_PUBLICATION + [group_key: "%s"]),
         "code_step_publications_group_key_shape"],
    ]

    /** Each with whether it is a group key; anything outside printable ASCII is written as a code point. */
    static final List<List<Object>> GROUP_KEYS = [
        ["AB", true],
        ["Z" * 16, true],
        ["A", false],
        ["Z" * 17, false],
        ["Ab", false],
        ["A1", false],
        ["A" + Character.toString(0x00C9), false],
        ["A" + Character.toString(0xFF21), false],
        ["A" + Character.toString(0x212A), false],
        ["A" + Character.toString(0x0130), false],
        ["A" + Character.toString(0x0131), false],
        ["A" + Character.toString(0x017F), false],
        ["AB" + Character.toString(0x000A), false],
    ]

    def "a membership closes with its time position who closed it and how all four or none"() {
        when:
        def refusedBy = attempt("group_members",
                "update app.group_members set ${assignments} where group_member_id = '${HOLDING}'")

        then:
        refusedBy == refusal
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.

        where:
        assignments                                                               || refusal
        "removed_at = now(), removed_by = '${STEWARD}', removal = 'role_taken', removed_position = 1" || null
        "removed_at = now(), removed_by = '${STEWARD}', removal = 'role_taken', removed_position = 0" || "group_members_removed_position_positive"
        "removed_at = now(), removed_by = '${STEWARD}'"                           || "group_members_removed_together"
        "removed_by = '${STEWARD}', removal = 'removed_from_group'"               || "group_members_removed_together"
        "removal = 'removed_from_group'"                                          || "group_members_removed_together"
    }

    def "the ending position and the other ending fields refuse either unpaired side independently"() {
        expect:
        attempt("group_members", statement) == "group_members_removed_together"

        where:
        statement << GroupCases.REMOVAL_PAIRING
    }

    def "an open membership permits all ending fields absent"() {
        expect:
        readInside("update app.group_members set removed_at = null, removed_by = null," +
                " removal = null, removed_position = null where group_member_id = '${HOLDING}'",
                "select removed_at is null and removed_by is null and removal is null and removed_position is null" +
                " from app.group_members where group_member_id = '${HOLDING}'") == "t"
    }

    def "a role once closed may be held again"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("group_members",
                "update app.group_members set removed_at = now(), removed_by = '${STEWARD}', removal = 'role_taken', removed_position = 1" +
                        " where group_member_id = '${HOLDING}';" +
                        " insert into app.group_members (group_id, subject_id, role, created_by)" +
                        " values ('${GROUP}', '${STEWARD}', 'owner', '${STEWARD}')")

        then:
        refusedBy == null
    }

    def "a group name is held once, whatever case either was typed in"() {
        when:
        def refusedBy = attempt("groups", "insert into app.groups (group_id, key, name, created_by)" +
                " values ('${SPARE_GROUP}', 'BILLING', '${name}', '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        name                || refusal
        "customer support"  || "groups_name_unique"
        "Customer Support"  || "groups_name_unique"
        "Customer supports" || null
    }

    /** Folded in full, so a sharp s is the two letters it folds to, and one group name holds either spelling. */
    def "a group name is held once whichever way a sharp s is spelt"() {
        when:
        def refusedBy = attempt("groups", NAME_THE_OTHER_GROUP_STRASSE + "insert into app.groups (group_id, key," +
                " name, created_by) values ('${SPARE_GROUP}', 'BILLING', ${nameAsWritten}, '${STEWARD}')")

        then:
        refusedBy == refusal
        // DB-SPECIFIC: chr() uses PostgreSQL's syntax and semantics.

        where:
        nameAsWritten                  || refusal
        "'STRASSE'"                    || "groups_name_unique"
        "'strasse'"                    || "groups_name_unique"
        "'STRA' || chr(7838) || 'E'"   || "groups_name_unique"
        "'Strase'"                     || null
    }

    def "an entry name is held once within its group and kind, whatever case either was typed in"() {
        when:
        def refusedBy = attempt("entries", "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                " values ('${SPARE_ENTRY}', '${group}', '${kind}', '${name}', '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        group       | kind       | name                  || refusal
        GROUP       | "question" | "customer complaint"  || "entries_name_unique"
        GROUP       | "workflow" | "Customer complaint"  || null
        OTHER_GROUP | "question" | "Customer complaint"  || null
        GROUP       | "question" | "Customer complaints" || null
    }

    def "an entry name is one to 128 characters"() {
        when:
        def refusedBy = attempt("entries", "insert into app.entries (entry_id, group_id, kind, name, created_by)" +
                " values ('${SPARE_ENTRY}', '${GROUP}', 'question', '${name}', '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        name      || refusal
        ""        || "entries_name_visible"
        "a"       || null
        "a" * 128 || null
    }

    def "a version is of the kind its entry is"() {
        when:
        def refusedBy = attempt("entry_versions", "insert into app.entry_versions (entry_id, entry_kind, number, created_by)" +
                " values ('${entry}', '${kind}', 3, '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        entry          | kind             || refusal
        LIST_ENTRY     | "reference_list" || null
        WORKFLOW_ENTRY | "workflow"       || null
        LIST_ENTRY     | "workflow"       || "entry_versions_entry_fk"
        WORKFLOW_ENTRY | "question"       || "entry_versions_entry_fk"
    }

    def "a version opens at revision one when none is named"() {
        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax; text || non-string converts the bigint count to text.
        def stored = readInside(OPEN_A_LIST_VERSION, "select revision::text || '|' || count(*) over ()" +
                " from app.entry_versions where entry_id = '${LIST_ENTRY}' and number = 3")

        then:
        stored == "1|1"
    }

    def "a version's revision is one or more, whether it is opened at one or a save moves it on"() {
        when:
        def refusedBy = attempt("entry_versions", statement)

        then:
        refusedBy == refusal

        where:
        statement                                                                                  || refusal
        OPEN_A_LIST_VERSION_AT_REVISION_ONE                                                        || null
        "update app.entry_versions set revision = 2 where entry_version_id = '${DRAFT}'"           || null
        "update app.entry_versions set revision = 1000 where entry_version_id = '${DRAFT}'"        || null
        "update app.entry_versions set revision = 0 where entry_version_id = '${DRAFT}'"           || "entry_versions_revision_positive"
        "update app.entry_versions set revision = -1 where entry_version_id = '${DRAFT}'"          || "entry_versions_revision_positive"
    }

    def "the seeder approves only a version it opened, and a person never one they opened"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("entry_versions", "insert into app.entry_versions" +
                " (entry_id, entry_kind, number, created_by, approved_at, approved_by, approved_by_kind)" +
                " values ('${ENTRY}', 'question', 3, '${opener}', now(), '${approver}', '${kind}')")

        then:
        refusedBy == refusal

        where:
        opener  | approver | kind     || refusal
        SEEDER  | SEEDER   | "seeder" || null
        STEWARD | SEEDER   | "seeder" || "entry_versions_approver_is_opener_exactly_for_seeder"
        STEWARD | STEWARD  | "person" || "entry_versions_approver_is_opener_exactly_for_seeder"
        SEEDER  | STEWARD  | "person" || null
        MEMBER  | STEWARD  | "person" || null
    }

    def "the seeder approves a version only as it opens it"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("entry_versions", "insert into app.entry_versions" +
                " (entry_id, entry_kind, number, created_at, created_by, approved_at, approved_by, approved_by_kind)" +
                " values ('${ENTRY}', 'question', 3, ${openedAt}, '${SEEDER}', now(), '${SEEDER}', 'seeder')")

        then:
        refusedBy == refusal
        // DB-SPECIFIC: interval literals and now() use PostgreSQL's syntax and semantics.

        where:
        openedAt                   || refusal
        "now()"                    || null
        "now() - interval '1 day'" || "entry_versions_seeder_approves_as_it_opens"
    }

    def "the seeder retires only a version it opened, and only as it opens it"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("entry_versions", "insert into app.entry_versions (entry_id, entry_kind, number," +
                " created_at, created_by, approved_at, approved_by, approved_by_kind, retired_at, retired_by, retired_by_kind)" +
                " values ('${ENTRY}', 'question', 3, ${openedAt}, '${opener}', now(), '${approver}', '${approverKind}'," +
                " now(), '${SEEDER}', 'seeder')")

        then:
        refusedBy == refusal
        // DB-SPECIFIC: interval literals and now() use PostgreSQL's syntax and semantics.

        where:
        opener  | openedAt                   | approver | approverKind || refusal
        SEEDER  | "now()"                    | SEEDER   | "seeder"     || null
        STEWARD | "now()"                    | MEMBER   | "person"     || "entry_versions_seeder_retires_as_it_opens"
        SEEDER  | "now() - interval '1 day'" | STEWARD  | "person"     || "entry_versions_seeder_retires_as_it_opens"
    }

    def "a version is retired no earlier than it was approved"() {
        when:
        def refusedBy = attempt("entry_versions", "update app.entry_versions set retired_at = ${retiredAt}," +
                " retired_by = '${STEWARD}' where entry_version_id = '${VERSION}'")

        then:
        refusedBy == refusal
        // DB-SPECIFIC: interval literals use PostgreSQL's syntax and semantics.

        where:
        retiredAt                                || refusal
        "approved_at"                            || null
        "approved_at - interval '1 microsecond'" || "entry_versions_retired_after_approved"
    }

    def "a version once approved lets the next draft of its entry open, and one only withdrawn does not"() {
        when:
        def refusedBy = attempt("entry_versions", "${first}insert into app.entry_versions" +
                " (entry_id, entry_kind, number, created_by) values ('${ENTRY}', 'question', 3, '${STEWARD}')")

        then:
        refusedBy == refusal

        where:
        first                   || refusal
        APPROVE_THE_DRAFT       || null
        WITHDRAW_THE_SUBMISSION || "entry_versions_one_unapproved"
    }

    def "a version whose submission was withdrawn may be submitted again"() {
        when:
        def refusedBy = attempt("entry_version_submissions", "${WITHDRAW_THE_SUBMISSION}" +
                "insert into app.entry_version_submissions (entry_version_id, created_by) values ('${DRAFT}', '${STEWARD}')")

        then:
        refusedBy == null
    }

    def "an entry whose stop was let go may be stopped again"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("entry_stops",
                "update app.entry_stops set let_go_at = now(), let_go_by = '${STEWARD}' where entry_stop_id = '${STOP}';" +
                        " insert into app.entry_stops (entry_id, created_by) values ('${ENTRY}', '${STEWARD}')")

        then:
        refusedBy == null
    }

    def "every code step is named in lower-case English letters, digits and underscores, starting with a letter"() {
        when:
        // DB-SPECIFIC: ::, pg_enum and regtype use PostgreSQL's syntax and semantics.
        def labels = queryForStrings("select enumlabel from pg_enum where enumtypid = 'app.code_step'::regtype")

        then:
        labels.every { it ==~ /[a-z][a-z0-9_]{0,62}/ }

        and: "judged over labels that exist, which here are those setupSpec added as a migration would"
        !labels.isEmpty()
    }

    def "a code step is published to one group key or to every group, and never to both or neither"() {
        when:
        def refusedBy = attempt("code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [code_step: literal(OTHER_CODE_STEP), group_key: literal(key), every_group: everyGroup]))

        then:
        refusedBy == refusal

        where:
        key       | everyGroup || refusal
        "BILLING" | "false"    || null
        null      | "true"     || null
        "BILLING" | "true"     || "code_step_publications_exactly_one_form"
        null      | "false"    || "code_step_publications_exactly_one_form"
    }

    def "a code step is published to every group once, and to any number of keys beside that"() {
        when:
        def refusedBy = attempt("code_step_publications", insertInto("code_step_publications",
                A_PUBLICATION + [code_step: literal(codeStep), group_key: literal(key), every_group: everyGroup]))

        then:
        refusedBy == refusal

        where:
        codeStep        | key       | everyGroup || refusal
        CODE_STEP       | null      | "true"     || "code_step_publications_one_every_group"
        CODE_STEP       | "BILLING" | "false"    || null
        OTHER_CODE_STEP | null      | "true"     || null
    }

    def "a group key and the key a code step is published to are two to sixteen capital English letters"() {
        when:
        def refusedBy = attempt(table, String.format(statement, literal(key)))

        then:
        refusedBy == (holds ? null : rule)

        where:
        [table, statement, rule, key, holds] << [GROUP_KEYED, GROUP_KEYS].combinations().collect { it.flatten() }
    }

    def "a check is started by a person and by nobody else"() {
        when:
        def refusedBy = attempt("soundness_checks", insertInto("soundness_checks", AN_ENDED_CHECK + [
                created_by: literal(author), created_by_kind: literal(kind)]))

        then:
        refusedBy == refusal

        where:
        author          | kind     || refusal
        MEMBER          | "person" || null
        WORKFLOW_RUNNER | "system" || "soundness_checks_author_is_person"
        SEEDER          | "seeder" || "soundness_checks_author_is_person"
        WORKFLOW_RUNNER | "person" || "soundness_checks_author_person_fk"
    }

    def "one check runs at a time, and one finished or cut short lets the next start"() {
        when:
        def refusedBy = attempt("soundness_checks", first + insertInto("soundness_checks", A_RUNNING_CHECK))

        then:
        refusedBy == refusal

        where:
        first                           || refusal
        ""                              || "soundness_checks_one_running"
        endTheRunningCheck("finished")  || null
        endTheRunningCheck("cut_short") || null
    }

    def "any number of checks ended stand beside the one running"() {
        when:
        def refusedBy = attempt("soundness_checks", insertInto("soundness_checks", AN_ENDED_CHECK + [
                outcome: literal(outcome)]))

        then:
        refusedBy == null

        where:
        outcome << ["finished", "cut_short"]
    }

    def "a group is counted once in each check, and again in the next"() {
        when:
        def refusedBy = attempt("soundness_counts", first + insertInto("soundness_counts", A_COUNT + [
                soundness_check_id: literal(check), group_id: literal(group)]))

        then:
        refusedBy == refusal

        where:
        first                          | check         | group       || refusal
        ""                             | CHECK         | GROUP       || "soundness_counts_pk"
        ""                             | CHECK         | OTHER_GROUP || null
        endTheRunningCheck("finished") | RUNNING_CHECK | GROUP       || null
    }

    def "only a finished check holds counts, so one running or cut short holds none"() {
        when:
        def refusedBy = attempt("soundness_counts", first + insertInto("soundness_counts", A_COUNT + [
                soundness_check_id: literal(check)]))

        then:
        refusedBy == refusal

        where:
        first                           | check         || refusal
        ""                              | CHECK         || null
        ""                              | RUNNING_CHECK || "soundness_counts_check_fk"
        endTheRunningCheck("cut_short") | RUNNING_CHECK || "soundness_counts_check_fk"
    }

    def "a check holding counts stays finished"() {
        when:
        def refusedBy = attempt("soundness_checks", first + "update app.soundness_checks" +
                " set outcome = ${literal(outcome)} where soundness_check_id = '${CHECK}'")

        then:
        refusedBy == "soundness_counts_check_fk"

        where:
        first                          | outcome
        ""                             | "cut_short"
        endTheRunningCheck("finished") | null
    }

    def "neither a check holding counts nor a group counted is deleted from under them"() {
        when:
        def refusedBy = attempt(table, statement)

        then:
        refusedBy == refusal

        where:
        table              | statement              || refusal
        "soundness_checks" | DELETE_THE_CHECK       || "soundness_counts_check_fk"
        "groups"           | DELETE_A_COUNTED_GROUP || "soundness_counts_group_fk"
    }

    def "a group's workflows and unsound are none or more, and its unsound never more than its workflows"() {
        when:
        def refusedBy = attempt("soundness_counts", insertInto("soundness_counts", A_COUNT + [
                workflows: workflows, unsound: unsound]))

        then:
        refusedBy == refusal

        where:
        workflows | unsound || refusal
        "0"       | "0"     || null
        "3"       | "0"     || null
        "3"       | "3"     || null
        "3"       | "4"     || "soundness_counts_unsound_at_most_workflows"
        "-1"      | "0"     || "soundness_counts_unsound_at_most_workflows"
        "0"       | "-1"    || "soundness_counts_unsound_not_negative"
        "-1"      | "-1"    || "soundness_counts_unsound_not_negative"
    }

    def "a group's currency changes to any three capital English letters, and to nothing else"() {
        when:
        // DB-SPECIFIC: now() uses PostgreSQL's syntax and semantics.
        def refusedBy = attempt("group_currencies", setting("group_currencies",
                "currency = ${literal(currency)}, updated_at = now(), updated_by = '${STEWARD}'"))

        then:
        refusedBy == refusal

        where:
        currency                           || refusal
        "AAA"                              || null
        "USD"                              || null
        "ZZZ"                              || null
        ""                                 || "group_currencies_currency_shape"
        "US"                               || "group_currencies_currency_shape"
        "USDX"                             || "group_currencies_currency_shape"
        "usd"                              || "group_currencies_currency_shape"
        "Usd"                              || "group_currencies_currency_shape"
        "US1"                              || "group_currencies_currency_shape"
        "US@"                              || "group_currencies_currency_shape"
        "US["                              || "group_currencies_currency_shape"
        " USD"                             || "group_currencies_currency_shape"
        "USD" + Character.toString(0x000A) || "group_currencies_currency_shape"
        "US" + Character.toString(0x00C9)  || "group_currencies_currency_shape"
        "US" + Character.toString(0xFF21)  || "group_currencies_currency_shape"
        "US" + Character.toString(0x212A)  || "group_currencies_currency_shape"
        "US" + Character.toString(0x0130)  || "group_currencies_currency_shape"
    }
}
