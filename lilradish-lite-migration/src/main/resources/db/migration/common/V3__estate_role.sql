create type estate_role as enum ('steward', 'watcher');

create table estate_role_grants (
    estate_role_grant_id uuid         not null default uuidv7(),
    subject_id           uuid         not null,
    subject_kind         subject_kind not null default 'person',
    role                 estate_role  not null,
    created_at           timestamptz  not null default now(),
    created_by           uuid         not null,
    removed_at           timestamptz,
    removed_by           uuid,
    -- Unchecked while removed_by is null, for the reason pool_members gives.
    removed_by_kind      subject_kind not null default 'person',

    constraint estate_role_grants_pk primary key (estate_role_grant_id),
    constraint estate_role_grants_person_fk foreign key (subject_id, subject_kind)
        references subjects (subject_id, kind),
    constraint estate_role_grants_is_person check (subject_kind = 'person'),
    constraint estate_role_grants_author_fk foreign key (created_by)
        references subjects (subject_id),
    constraint estate_role_grants_remover_person_fk foreign key (removed_by, removed_by_kind)
        references subjects (subject_id, kind),
    constraint estate_role_grants_remover_is_person check (removed_by_kind = 'person'),
    constraint estate_role_grants_removed_together
        check ((removed_at is null) = (removed_by is null)),
    constraint estate_role_grants_removed_after_created
        check (removed_at is null or removed_at >= created_at)
);

create unique index estate_role_grants_one_current_holding
    on estate_role_grants (subject_id, role) where removed_at is null;
