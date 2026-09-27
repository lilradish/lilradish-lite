create type entry_kind as enum ('workflow', 'question', 'reference_list');

-- ---------------------------------------------------------------- entries

create table entries (
    -- No default, as on subjects: this key is minted before there is a row for it to name.
    entry_id        uuid         not null,
    group_id        uuid         not null,
    kind            entry_kind   not null,
    name            text         not null,
    name_folded     text         generated always as (search_fold(name)) stored,
    purpose         text,
    created_at      timestamptz  not null default now(),
    created_by      uuid         not null,
    updated_at      timestamptz,
    updated_by      uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind subject_kind not null default 'person',

    constraint entries_pk primary key (entry_id),
    constraint entries_group_fk foreign key (group_id) references groups (group_id),
    constraint entries_author_fk foreign key (created_by) references subjects (subject_id),
    constraint entries_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint entries_editor_is_person check (updated_by_kind = 'person'),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint entries_kind_unique unique (entry_id, kind),
    constraint entries_group_unique unique (entry_id, group_id),
    -- Folds computed again after a major upgrade can collide here where the old ones did not.
    constraint entries_name_unique unique (group_id, kind, name_folded),
    constraint entries_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint entries_updated_after_created check (updated_at is null or updated_at >= created_at),
    -- Ranges and escaping for the reasons subjects_user_id_visible gives.
    constraint entries_name_visible check (
        length(name) > 0 and name !~ '[\u0000-\u001f\u007f-\u009f]'),
    -- Named and deterministic for the reason subjects_user_id_bounded gives.
    constraint entries_name_bounded check (length(name) <= 128),
    constraint entries_purpose_visible check (
        purpose is null or (length(purpose) > 0 and purpose !~ '[\u0000-\u001f\u007f-\u009f]')),
    constraint entries_purpose_bounded check (length(purpose) <= 512)
);

-- ---------------------------------------------------------------- versions

create table entry_versions (
    entry_version_id uuid         not null default uuidv7(),
    entry_id         uuid         not null,
    -- Carried only to reach entries_kind_unique, and to be reached by every key naming a version of one kind.
    entry_kind       entry_kind   not null,
    number           integer      not null,
    -- Counts the writes to a draft's content, so a save naming another revision than this is refused.
    revision         integer      not null default 1,
    created_at       timestamptz  not null default now(),
    created_by       uuid         not null,
    approved_at      timestamptz,
    approved_by      uuid,
    -- Unchecked while approved_by is null, for the reason pool_members gives.
    approved_by_kind subject_kind not null default 'person',
    approved         boolean      generated always as (approved_at is not null) stored,
    retired_at       timestamptz,
    retired_by       uuid,
    -- Unchecked while retired_by is null, for the reason pool_members gives.
    retired_by_kind  subject_kind not null default 'person',

    constraint entry_versions_pk primary key (entry_version_id),
    constraint entry_versions_entry_fk foreign key (entry_id, entry_kind) references entries (entry_id, kind),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint entry_versions_kind_unique unique (entry_version_id, entry_kind),
    constraint entry_versions_entry_unique unique (entry_version_id, entry_id),
    -- Approving is an update to this key: it waits on, and holds up, every row being written that names the version.
    constraint entry_versions_approved_unique unique (entry_version_id, approved),
    constraint entry_versions_number_unique unique (entry_id, number),
    constraint entry_versions_number_positive check (number >= 1),
    constraint entry_versions_revision_positive check (revision >= 1),
    constraint entry_versions_author_fk foreign key (created_by) references subjects (subject_id),
    constraint entry_versions_approver_kind_fk foreign key (approved_by, approved_by_kind)
        references subjects (subject_id, kind),
    constraint entry_versions_approver_is_person_or_seeder
        check (approved_by_kind in ('person', 'seeder')),
    constraint entry_versions_approver_is_opener_exactly_for_seeder
        check ((approved_by_kind = 'seeder') = (approved_by = created_by)),
    -- Equal to created_at only because every now() in one transaction is.
    constraint entry_versions_seeder_approves_as_it_opens
        check (approved_by_kind <> 'seeder' or approved_at = created_at),
    constraint entry_versions_retirer_kind_fk foreign key (retired_by, retired_by_kind)
        references subjects (subject_id, kind),
    constraint entry_versions_retirer_is_person_or_seeder
        check (retired_by_kind in ('person', 'seeder')),
    constraint entry_versions_seeder_retires_as_it_opens
        check (retired_by_kind <> 'seeder' or (retired_by = created_by and retired_at = created_at)),
    constraint entry_versions_approved_together check ((approved_at is null) = (approved_by is null)),
    constraint entry_versions_approved_after_created
        check (approved_at is null or approved_at >= created_at),
    constraint entry_versions_retired_together check ((retired_at is null) = (retired_by is null)),
    -- A check whose expression is null passes, so the order below admits a retired row that was
    -- never approved; entry_versions_retired_once_approved is what refuses it.
    constraint entry_versions_retired_after_approved
        check (retired_at is null or retired_at >= approved_at),
    constraint entry_versions_retired_once_approved
        check (retired_at is null or approved_at is not null)
);

-- Draft or submitted only while entry_versions_retired_once_approved holds.
create unique index entry_versions_one_unapproved
    on entry_versions (entry_id) where approved_at is null;

create table entry_version_submissions (
    entry_version_submission_id uuid         not null default uuidv7(),
    entry_version_id            uuid         not null,
    created_at                  timestamptz  not null default now(),
    created_by                  uuid         not null,
    created_by_kind             subject_kind not null default 'person',
    withdrawn_at                timestamptz,
    withdrawn_by                uuid,
    -- Unchecked while withdrawn_by is null, for the reason pool_members gives.
    withdrawn_by_kind           subject_kind not null default 'person',

    constraint entry_version_submissions_pk primary key (entry_version_submission_id),
    constraint entry_version_submissions_version_fk foreign key (entry_version_id)
        references entry_versions (entry_version_id),
    constraint entry_version_submissions_author_kind_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint entry_version_submissions_author_is_person_or_seeder
        check (created_by_kind in ('person', 'seeder')),
    constraint entry_version_submissions_withdrawer_person_fk
        foreign key (withdrawn_by, withdrawn_by_kind) references subjects (subject_id, kind),
    constraint entry_version_submissions_withdrawer_is_person check (withdrawn_by_kind = 'person'),
    constraint entry_version_submissions_withdrawn_together
        check ((withdrawn_at is null) = (withdrawn_by is null)),
    constraint entry_version_submissions_withdrawn_after_created
        check (withdrawn_at is null or withdrawn_at >= created_at)
);

create unique index entry_version_submissions_one_open
    on entry_version_submissions (entry_version_id) where withdrawn_at is null;

create table entry_version_writers (
    entry_version_id uuid         not null,
    created_by       uuid         not null,
    created_by_kind  subject_kind not null default 'person',
    created_at       timestamptz  not null default now(),

    constraint entry_version_writers_pk primary key (entry_version_id, created_by),
    constraint entry_version_writers_version_fk foreign key (entry_version_id)
        references entry_versions (entry_version_id),
    constraint entry_version_writers_author_person_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint entry_version_writers_author_is_person check (created_by_kind = 'person')
);

-- ---------------------------------------------------------------- stops

create table entry_stops (
    entry_stop_id   uuid         not null default uuidv7(),
    entry_id        uuid         not null,
    created_at      timestamptz  not null default now(),
    created_by      uuid         not null,
    created_by_kind subject_kind not null default 'person',
    let_go_at       timestamptz,
    let_go_by       uuid,
    -- Unchecked while let_go_by is null, for the reason pool_members gives.
    let_go_by_kind  subject_kind not null default 'person',

    constraint entry_stops_pk primary key (entry_stop_id),
    constraint entry_stops_entry_fk foreign key (entry_id) references entries (entry_id),
    constraint entry_stops_author_person_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint entry_stops_author_is_person check (created_by_kind = 'person'),
    constraint entry_stops_let_go_person_fk foreign key (let_go_by, let_go_by_kind)
        references subjects (subject_id, kind),
    constraint entry_stops_let_go_is_person check (let_go_by_kind = 'person'),
    constraint entry_stops_let_go_together check ((let_go_at is null) = (let_go_by is null)),
    constraint entry_stops_let_go_after_created check (let_go_at is null or let_go_at >= created_at)
);

create unique index entry_stops_one_in_force on entry_stops (entry_id) where let_go_at is null;
