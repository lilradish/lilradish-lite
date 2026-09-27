create table pool_members (
    -- Time-ordered, so an append-only table does not fragment the index on its key.
    pool_member_id  uuid         not null default uuidv7(),
    subject_id      uuid         not null,
    -- Carried only to reach subjects_kind_unique, which a composite foreign key needs as its target.
    subject_kind    subject_kind not null default 'person',
    created_at      timestamptz  not null default now(),
    created_by      uuid         not null,
    removed_at      timestamptz,
    removed_by      uuid,
    -- Not null, yet claiming no remover while there is none: a composite foreign key with any null
    -- column is not checked at all, so the one below sleeps until removed_by is filled in.
    removed_by_kind subject_kind not null default 'person',

    constraint pool_members_pk primary key (pool_member_id),
    constraint pool_members_person_fk foreign key (subject_id, subject_kind)
        references subjects (subject_id, kind),
    constraint pool_members_is_person check (subject_kind = 'person'),
    constraint pool_members_author_fk foreign key (created_by) references subjects (subject_id),
    constraint pool_members_remover_person_fk foreign key (removed_by, removed_by_kind)
        references subjects (subject_id, kind),
    constraint pool_members_remover_is_person check (removed_by_kind = 'person'),
    constraint pool_members_removed_together check ((removed_at is null) = (removed_by is null)),
    constraint pool_members_removed_after_created
        check (removed_at is null or removed_at >= created_at)
);

create unique index pool_members_one_current_stay
    on pool_members (subject_id) where removed_at is null;
