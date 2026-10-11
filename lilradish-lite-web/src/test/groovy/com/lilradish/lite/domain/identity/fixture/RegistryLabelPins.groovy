package com.lilradish.lite.domain.identity.fixture

/** Labels pinned on the tables of people, groups, entries and their versions, code step publications and soundness checks. */
final class RegistryLabelPins {

    static final Map<String, List<String>> PINS = [
        "code_step_publications.code_step_publications_author_is_seeder": ["subject_kind:seeder"],
        "code_step_publications.created_by_kind": ["subject_kind:seeder"],
        "entries.entries_editor_is_person": ["subject_kind:person"],
        "entries.updated_by_kind": ["subject_kind:person"],
        "entry_stops.created_by_kind": ["subject_kind:person"],
        "entry_stops.entry_stops_author_is_person": ["subject_kind:person"],
        "entry_stops.entry_stops_let_go_is_person": ["subject_kind:person"],
        "entry_stops.let_go_by_kind": ["subject_kind:person"],
        "entry_version_submissions.created_by_kind": ["subject_kind:person"],
        "entry_version_submissions.entry_version_submissions_author_is_person_or_seeder":
                ["subject_kind:person", "subject_kind:seeder"],
        "entry_version_submissions.entry_version_submissions_withdrawer_is_person": ["subject_kind:person"],
        "entry_version_submissions.withdrawn_by_kind": ["subject_kind:person"],
        "entry_version_writers.created_by_kind": ["subject_kind:person"],
        "entry_version_writers.entry_version_writers_author_is_person": ["subject_kind:person"],
        "entry_versions.approved_by_kind": ["subject_kind:person"],
        "entry_versions.entry_versions_approver_is_opener_exactly_for_seeder": ["subject_kind:seeder"],
        "entry_versions.entry_versions_approver_is_person_or_seeder": ["subject_kind:person", "subject_kind:seeder"],
        "entry_versions.entry_versions_retirer_is_person_or_seeder": ["subject_kind:person", "subject_kind:seeder"],
        "entry_versions.entry_versions_seeder_approves_as_it_opens": ["subject_kind:seeder"],
        "entry_versions.entry_versions_seeder_retires_as_it_opens": ["subject_kind:seeder"],
        "entry_versions.retired_by_kind": ["subject_kind:person"],
        "estate_role_grants.estate_role_grants_is_person": ["subject_kind:person"],
        "estate_role_grants.estate_role_grants_remover_is_person": ["subject_kind:person"],
        "estate_role_grants.removed_by_kind": ["subject_kind:person"],
        "estate_role_grants.subject_kind": ["subject_kind:person"],
        "group_currencies.created_by_kind": ["subject_kind:person"],
        "group_currencies.group_currencies_author_is_person": ["subject_kind:person"],
        "group_currencies.group_currencies_editor_is_person": ["subject_kind:person"],
        "group_currencies.updated_by_kind": ["subject_kind:person"],
        "group_members.group_members_is_person": ["subject_kind:person"],
        "group_members.group_members_remover_is_person": ["subject_kind:person"],
        "group_members.removed_by_kind": ["subject_kind:person"],
        "group_members.subject_kind": ["subject_kind:person"],
        "groups.groups_editor_is_person": ["subject_kind:person"],
        "groups.updated_by_kind": ["subject_kind:person"],
        "pool_members.pool_members_is_person": ["subject_kind:person"],
        "pool_members.pool_members_remover_is_person": ["subject_kind:person"],
        "pool_members.removed_by_kind": ["subject_kind:person"],
        "pool_members.subject_kind": ["subject_kind:person"],
        "soundness_checks.created_by_kind": ["subject_kind:person"],
        "soundness_checks.soundness_checks_author_is_person": ["subject_kind:person"],
        "soundness_counts.check_outcome": ["soundness_check_outcome:finished"],
        "soundness_counts.soundness_counts_check_is_finished": ["soundness_check_outcome:finished"],
        "subjects.subjects_display_name_only_for_people": ["subject_kind:person"],
        "subjects.subjects_user_only_for_people": ["subject_kind:person"],
    ]

    private RegistryLabelPins() {}
}
