-- What each role bundles is held in the application; this type names only which of them somebody
-- holds, so a change to a bundle is not a change to this schema.
create type group_role as enum ('operator', 'overseer', 'owner');

-- ---------------------------------------------------------------- groups

create table groups (
    -- No default, as on subjects: this key is minted before there is a row for it to name.
    group_id        uuid         not null,
    key             text         not null,
    name            text         not null,
    name_folded     text         generated always as (search_fold(name)) stored,
    created_at      timestamptz  not null default now(),
    created_by      uuid         not null,
    updated_at      timestamptz,
    updated_by      uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind subject_kind not null default 'person',

    constraint groups_pk primary key (group_id),
    constraint groups_author_fk foreign key (created_by) references subjects (subject_id),
    constraint groups_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint groups_editor_is_person check (updated_by_kind = 'person'),
    -- Capitals only, so a plain unique is uniqueness whatever case a key was typed in.
    constraint groups_key_unique unique (key),
    constraint groups_key_shape check (key ~ '^[A-Z]{2,16}$'),
    -- Unique whatever case either was typed in; folds computed again after a major upgrade can collide.
    constraint groups_name_unique unique (name_folded),
    constraint groups_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint groups_updated_after_created check (updated_at is null or updated_at >= created_at),
    -- Ranges and escaping for the reasons subjects_user_id_visible gives.
    constraint groups_name_visible check (
        length(name) > 0 and name !~ '[\u0000-\u001f\u007f-\u009f]'),
    -- Named and deterministic for the reason subjects_user_id_bounded gives.
    constraint groups_name_bounded check (length(name) <= 128)
);

-- ---------------------------------------------------------------- membership

create type group_member_removal as enum ('role_taken', 'removed_from_group');

-- That whoever is here is in the pool cannot be a key: the target would be pool_members' current
-- stays, and PostgreSQL does not accept a partial index as a foreign key target. So the key reaches
-- subjects, and the rest is the application's.
create table group_members (
    group_member_id uuid         not null default uuidv7(),
    group_id        uuid         not null,
    subject_id      uuid         not null,
    subject_kind    subject_kind not null default 'person',
    role            group_role   not null,
    created_at      timestamptz  not null default now(),
    created_by      uuid         not null,
    removed_at      timestamptz,
    removed_by      uuid,
    -- Unchecked while removed_by is null, for the reason pool_members gives.
    removed_by_kind subject_kind not null default 'person',
    removal         group_member_removal,

    constraint group_members_pk primary key (group_member_id),
    constraint group_members_group_fk foreign key (group_id) references groups (group_id),
    constraint group_members_person_fk foreign key (subject_id, subject_kind)
        references subjects (subject_id, kind),
    constraint group_members_is_person check (subject_kind = 'person'),
    constraint group_members_author_fk foreign key (created_by) references subjects (subject_id),
    constraint group_members_remover_person_fk foreign key (removed_by, removed_by_kind)
        references subjects (subject_id, kind),
    constraint group_members_remover_is_person check (removed_by_kind = 'person'),
    constraint group_members_removed_together check (
        (removed_at is null) = (removed_by is null) and (removed_at is null) = (removal is null)),
    constraint group_members_removed_after_created
        check (removed_at is null or removed_at >= created_at)
);

create unique index group_members_one_current_holding
    on group_members (group_id, subject_id, role) where removed_at is null;

-- A member holds a row per role, so how many groups they are in is count(distinct group_id) over
-- this index and never count(*).
create index group_members_by_subject on group_members (subject_id) where removed_at is null;
