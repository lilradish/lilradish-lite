-- A type with no labels holds no value until one is added, and a label added to a type that already
-- exists cannot be used before it commits: a statement naming it belongs to a later migration.
create type code_step as enum ();

-- ---------------------------------------------------------------- code steps

create table code_step_publications (
    code_step_publication_id uuid         not null default uuidv7(),
    code_step                code_step    not null,
    -- A key and not a group, reaching no row: a key no group holds yet may be published to.
    group_key                text,
    -- Said outright, so that a null key never silently means every group.
    every_group              boolean      not null,
    created_at               timestamptz  not null default now(),
    created_by               uuid         not null,
    created_by_kind          subject_kind not null default 'seeder',

    constraint code_step_publications_pk primary key (code_step_publication_id),
    constraint code_step_publications_author_seeder_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint code_step_publications_author_is_seeder check (created_by_kind = 'seeder'),
    constraint code_step_publications_exactly_one_form check ((group_key is null) = every_group),
    constraint code_step_publications_group_key_shape check (group_key ~ '^[A-Z]{2,16}$'),
    constraint code_step_publications_group_key_unique unique (code_step, group_key)
);

create unique index code_step_publications_one_every_group
    on code_step_publications (code_step) where every_group;

-- ---------------------------------------------------------------- reference lists

create table reference_list_versions (
    entry_version_id uuid         not null,
    -- Carried only to reach entry_versions_kind_unique, as every kind below is.
    entry_kind       entry_kind   not null default 'reference_list',
    note             text,
    created_at       timestamptz  not null default now(),
    created_by       uuid         not null,
    updated_at       timestamptz,
    updated_by       uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind  subject_kind not null default 'person',

    constraint reference_list_versions_pk primary key (entry_version_id),
    constraint reference_list_versions_version_fk foreign key (entry_version_id, entry_kind)
        references entry_versions (entry_version_id, entry_kind),
    constraint reference_list_versions_is_reference_list check (entry_kind = 'reference_list'),
    constraint reference_list_versions_author_fk foreign key (created_by) references subjects (subject_id),
    constraint reference_list_versions_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint reference_list_versions_editor_is_person check (updated_by_kind = 'person'),
    constraint reference_list_versions_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint reference_list_versions_updated_after_created
        check (updated_at is null or updated_at >= created_at),
    -- Prose, so a tab and a line feed are held; the rest of C0 and C1 is refused as subjects refuses it.
    constraint reference_list_versions_note_visible check (
        note is null or (length(note) > 0 and note !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]')),
    constraint reference_list_versions_note_bounded check (length(note) <= 2048)
);

create table reference_list_terms (
    reference_list_term_id uuid         not null default uuidv7(),
    entry_version_id       uuid         not null,
    position               integer      not null,
    term                   text         not null,
    meaning                text         not null,
    created_at             timestamptz  not null default now(),
    created_by             uuid         not null,
    updated_at             timestamptz,
    updated_by             uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind        subject_kind not null default 'person',

    constraint reference_list_terms_pk primary key (reference_list_term_id),
    constraint reference_list_terms_version_fk foreign key (entry_version_id)
        references reference_list_versions (entry_version_id),
    -- Checked at commit, so rows renumber one statement at a time without passing through a collision.
    -- Deferrable: an on conflict without a target fails on this table.
    constraint reference_list_terms_position_unique unique (entry_version_id, position)
        deferrable initially deferred,
    constraint reference_list_terms_position_not_negative check (position >= 0),
    constraint reference_list_terms_author_fk foreign key (created_by) references subjects (subject_id),
    constraint reference_list_terms_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint reference_list_terms_editor_is_person check (updated_by_kind = 'person'),
    constraint reference_list_terms_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint reference_list_terms_updated_after_created check (updated_at is null or updated_at >= created_at),
    constraint reference_list_terms_term_visible check (
        length(term) > 0 and term !~ '[\u0000-\u001f\u007f-\u009f]'),
    constraint reference_list_terms_term_bounded check (length(term) <= 128),
    constraint reference_list_terms_meaning_visible check (
        length(meaning) > 0 and meaning !~ '[\u0000-\u001f\u007f-\u009f]'),
    constraint reference_list_terms_meaning_bounded check (length(meaning) <= 512)
);

-- ---------------------------------------------------------------- questions and workflows

create table question_versions (
    entry_version_id uuid         not null,
    entry_kind       entry_kind   not null default 'question',
    instruction      text,
    created_at       timestamptz  not null default now(),
    created_by       uuid         not null,
    updated_at       timestamptz,
    updated_by       uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind  subject_kind not null default 'person',

    constraint question_versions_pk primary key (entry_version_id),
    constraint question_versions_version_fk foreign key (entry_version_id, entry_kind)
        references entry_versions (entry_version_id, entry_kind),
    constraint question_versions_is_question check (entry_kind = 'question'),
    constraint question_versions_author_fk foreign key (created_by) references subjects (subject_id),
    constraint question_versions_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint question_versions_editor_is_person check (updated_by_kind = 'person'),
    constraint question_versions_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint question_versions_updated_after_created check (updated_at is null or updated_at >= created_at),
    -- Prose, for the reason reference_list_versions_note_visible gives.
    constraint question_versions_instruction_visible check (
        instruction is null
            or (length(instruction) > 0 and instruction !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]')),
    constraint question_versions_instruction_bounded check (length(instruction) <= 8192)
);

create table workflow_versions (
    entry_version_id     uuid         not null,
    entry_kind           entry_kind   not null default 'workflow',
    ceiling              bigint,
    keeps_own_ceiling    boolean      not null default false,
    raise_needs_approval boolean      not null default false,
    may_be_helped        boolean      not null default false,
    helper_model         text,
    -- A model run as it is is stored as 'ordinary', never as null: a key with a null column is not checked at all.
    helper_mode          text,
    created_at           timestamptz  not null default now(),
    created_by           uuid         not null,
    updated_at           timestamptz,
    updated_by           uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind      subject_kind not null default 'person',

    constraint workflow_versions_pk primary key (entry_version_id),
    constraint workflow_versions_version_fk foreign key (entry_version_id, entry_kind)
        references entry_versions (entry_version_id, entry_kind),
    constraint workflow_versions_is_workflow check (entry_kind = 'workflow'),
    constraint workflow_versions_author_fk foreign key (created_by) references subjects (subject_id),
    constraint workflow_versions_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint workflow_versions_editor_is_person check (updated_by_kind = 'person'),
    constraint workflow_versions_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint workflow_versions_updated_after_created check (updated_at is null or updated_at >= created_at),
    constraint workflow_versions_ceiling_positive check (ceiling >= 1),
    constraint workflow_versions_helper_only_for_may_be_helped
        check ((helper_model is null and helper_mode is null) or may_be_helped),
    constraint workflow_versions_helper_model_together check ((helper_model is null) = (helper_mode is null)),
    constraint workflow_versions_helper_model_shape check (helper_model ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint workflow_versions_helper_mode_shape check (helper_mode ~ '^[a-z][a-z0-9_]{0,62}$'),
    -- A foreign key target, at the cost subjects_kind_unique gives.
    constraint workflow_versions_helper_unique unique (entry_version_id, helper_model, helper_mode, may_be_helped)
);
