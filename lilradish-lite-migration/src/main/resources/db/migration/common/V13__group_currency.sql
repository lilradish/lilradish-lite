-- Apart from groups, so a change of currency never overwrites the rename groups.updated_* records. A row is changed
-- to another currency, never deleted or moved to another group: the application holds that; nothing here refuses it.
create table group_currencies (
    group_id        uuid         not null,
    currency        text         not null,
    created_at      timestamptz  not null default now(),
    created_by      uuid         not null,
    created_by_kind subject_kind not null default 'person',
    updated_at      timestamptz,
    updated_by      uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind subject_kind not null default 'person',

    constraint group_currencies_pk primary key (group_id),
    constraint group_currencies_group_fk foreign key (group_id) references groups (group_id),
    constraint group_currencies_author_person_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint group_currencies_author_is_person check (created_by_kind = 'person'),
    constraint group_currencies_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint group_currencies_editor_is_person check (updated_by_kind = 'person'),
    -- Capitals only, so one currency is never held under two spellings. Which codes exist is the application's
    -- to judge.
    constraint group_currencies_currency_shape check (currency ~ '^[A-Z]{3}$'),
    constraint group_currencies_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint group_currencies_updated_after_created check (updated_at is null or updated_at >= created_at)
);
