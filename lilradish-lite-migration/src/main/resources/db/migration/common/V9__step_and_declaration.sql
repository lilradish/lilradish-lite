create type step_kind as enum ('entry', 'code_step', 'route');
create type step_producer as enum ('model', 'person', 'code');
create type declaration_side as enum ('takes', 'gives');
create type field_kind as enum ('text', 'number', 'date', 'moment', 'yes_no', 'term', 'fields');
create type field_standing as enum ('always', 'never', 'above_confidence');

-- ---------------------------------------------------------------- steps

-- Null is what nobody has chosen yet, which a draft may hold; each rule here refuses only a combination
-- no step could ever be finished from.
create table workflow_steps (
    workflow_step_id    uuid          not null default uuidv7(),
    entry_version_id    uuid          not null,
    position            integer       not null,
    name                text          not null,
    kind                step_kind,
    pinned_version_id   uuid,
    pinned_kind         entry_kind,
    code_step           code_step,
    producer            step_producer,
    producer_model      text,
    -- 'ordinary' where the model runs as it is, for the reason workflow_versions.helper_mode gives.
    producer_mode       text,
    tries               integer,
    -- The reviewer is the model named where one is; otherwise a person on a step running a question or a
    -- code step, and nobody on a workflow or a route.
    reviewer_model      text,
    -- 'ordinary' where the model runs as it is, as producer_mode.
    reviewer_mode       text,
    reviewed_by_model   boolean       generated always as (reviewer_model is not null) stored,
    tells_what_happened boolean       not null default false,
    created_at          timestamptz   not null default now(),
    created_by          uuid          not null,
    updated_at          timestamptz,
    updated_by          uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind     subject_kind  not null default 'person',

    constraint workflow_steps_pk primary key (workflow_step_id),
    constraint workflow_steps_version_fk foreign key (entry_version_id)
        references workflow_versions (entry_version_id),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint workflow_steps_kind_unique unique (workflow_step_id, kind),
    constraint workflow_steps_version_unique unique (workflow_step_id, entry_version_id),
    constraint workflow_steps_pinned_unique unique (workflow_step_id, pinned_version_id, pinned_kind),
    constraint workflow_steps_producer_unique unique (workflow_step_id, producer, reviewed_by_model, tries),
    constraint workflow_steps_producer_model_unique unique (workflow_step_id, producer_model, producer_mode),
    constraint workflow_steps_reviewer_model_unique unique (workflow_step_id, reviewer_model, reviewer_mode),
    -- Deferred for the reason reference_list_terms_position_unique gives.
    constraint workflow_steps_position_unique unique (entry_version_id, position)
        deferrable initially deferred,
    constraint workflow_steps_position_not_negative check (position >= 0),
    constraint workflow_steps_name_shape check (name ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint workflow_steps_pinned_fk foreign key (pinned_version_id, pinned_kind)
        references entry_versions (entry_version_id, entry_kind),
    -- A key with a null column is not checked at all, so a pin without its kind would reach nothing.
    constraint workflow_steps_pinned_together check ((pinned_version_id is null) = (pinned_kind is null)),
    constraint workflow_steps_pinned_is_question_or_workflow
        check (pinned_kind is null or pinned_kind in ('question', 'workflow')),
    constraint workflow_steps_pinned_only_for_entry
        check (pinned_version_id is null or coalesce(kind = 'entry', false)),
    constraint workflow_steps_not_own_version
        check (pinned_version_id is null or pinned_version_id <> entry_version_id),
    constraint workflow_steps_code_step_only_for_code_step
        check (code_step is null or coalesce(kind = 'code_step', false)),
    constraint workflow_steps_code_step_produced_by_code_or_person
        check ((kind = 'code_step') is not true or producer is null or producer <> 'model'),
    constraint workflow_steps_question_produced_by_model_or_person
        check ((pinned_kind = 'question') is not true or producer is null or producer <> 'code'),
    constraint workflow_steps_workflow_or_route_unproduced check (
        (kind = 'route' or pinned_kind = 'workflow') is not true
            or (producer is null and tries is null and reviewer_model is null)),
    constraint workflow_steps_producer_model_only_for_model check (
        (producer_model is null and producer_mode is null) or coalesce(producer = 'model', false)),
    constraint workflow_steps_producer_model_together check ((producer_model is null) = (producer_mode is null)),
    constraint workflow_steps_producer_model_shape check (producer_model ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint workflow_steps_producer_mode_shape check (producer_mode ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint workflow_steps_told_only_for_model
        check (not tells_what_happened or coalesce(producer = 'model', false)),
    constraint workflow_steps_tries_positive check (tries >= 1),
    constraint workflow_steps_reviewer_model_together check ((reviewer_model is null) = (reviewer_mode is null)),
    constraint workflow_steps_reviewer_model_shape check (reviewer_model ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint workflow_steps_reviewer_mode_shape check (reviewer_mode ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint workflow_steps_author_fk foreign key (created_by) references subjects (subject_id),
    constraint workflow_steps_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint workflow_steps_editor_is_person check (updated_by_kind = 'person'),
    constraint workflow_steps_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint workflow_steps_updated_after_created check (updated_at is null or updated_at >= created_at)
);

create index workflow_steps_by_pinned_version
    on workflow_steps (pinned_version_id) where pinned_version_id is not null;

create table route_cases (
    route_case_id     uuid         not null default uuidv7(),
    -- Carried so that what binds a case's inputs can be held to the version the case is in.
    entry_version_id  uuid         not null,
    workflow_step_id  uuid         not null,
    -- Carried only to reach workflow_steps_kind_unique, so what holds a case is a route.
    step_kind         step_kind    not null default 'route',
    -- The fallback is the case with no term.
    term              text,
    target_version_id uuid,
    -- Unchecked while target_version_id is null, for the reason pool_members gives.
    target_kind       entry_kind   not null default 'workflow',
    created_at        timestamptz  not null default now(),
    created_by        uuid         not null,
    updated_at        timestamptz,
    updated_by        uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind   subject_kind not null default 'person',

    constraint route_cases_pk primary key (route_case_id),
    constraint route_cases_step_fk foreign key (workflow_step_id, entry_version_id)
        references workflow_steps (workflow_step_id, entry_version_id),
    constraint route_cases_route_fk foreign key (workflow_step_id, step_kind)
        references workflow_steps (workflow_step_id, kind),
    constraint route_cases_is_route check (step_kind = 'route'),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint route_cases_version_unique unique (route_case_id, entry_version_id),
    constraint route_cases_target_unique unique (route_case_id, workflow_step_id, target_version_id),
    constraint route_cases_target_fk foreign key (target_version_id, target_kind)
        references entry_versions (entry_version_id, entry_kind),
    constraint route_cases_target_is_workflow check (target_kind = 'workflow'),
    constraint route_cases_not_own_version
        check (target_version_id is null or target_version_id <> entry_version_id),
    constraint route_cases_term_visible check (
        term is null or (length(term) > 0 and term !~ '[\u0000-\u001f\u007f-\u009f]')),
    constraint route_cases_term_bounded check (length(term) <= 128),
    constraint route_cases_author_fk foreign key (created_by) references subjects (subject_id),
    constraint route_cases_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint route_cases_editor_is_person check (updated_by_kind = 'person'),
    constraint route_cases_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint route_cases_updated_after_created check (updated_at is null or updated_at >= created_at)
);

create unique index route_cases_one_fallback on route_cases (workflow_step_id) where term is null;

create index route_cases_by_step on route_cases (workflow_step_id);

create index route_cases_by_target on route_cases (target_version_id) where target_version_id is not null;

-- ---------------------------------------------------------------- declarations

create table declaration_fields (
    declaration_field_id uuid             not null default uuidv7(),
    entry_version_id     uuid,
    entry_kind           entry_kind,
    workflow_step_id     uuid,
    -- Unchecked while workflow_step_id is null, for the reason pool_members gives.
    step_kind            step_kind        not null default 'route',
    -- Whichever of the two owns the field, so that one key can hold a parent to the same owner.
    owner_id             uuid             generated always as (coalesce(entry_version_id, workflow_step_id)) stored,
    side                 declaration_side not null,
    parent_field_id      uuid,
    -- Unchecked while parent_field_id is null, for the reason pool_members gives.
    parent_kind          field_kind       not null default 'fields',
    position             integer          not null,
    name                 text             not null,
    label                text,
    help                 text,
    kind                 field_kind       not null,
    holds_many           boolean          not null default false,
    text_limit           integer,
    many_limit           integer,
    term_list_version_id uuid,
    -- Unchecked while term_list_version_id is null, for the reason pool_members gives.
    term_list_kind       entry_kind       not null default 'reference_list',
    must_be_given        boolean,
    standing             field_standing,
    standing_threshold   integer,
    created_at           timestamptz      not null default now(),
    created_by           uuid             not null,
    updated_at           timestamptz,
    updated_by           uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind      subject_kind     not null default 'person',

    constraint declaration_fields_pk primary key (declaration_field_id),
    constraint declaration_fields_exactly_one_owner
        check (num_nonnulls(entry_version_id, workflow_step_id) = 1),
    constraint declaration_fields_version_fk foreign key (entry_version_id, entry_kind)
        references entry_versions (entry_version_id, entry_kind),
    -- Together for the reason workflow_steps_pinned_together gives.
    constraint declaration_fields_version_together check ((entry_version_id is null) = (entry_kind is null)),
    constraint declaration_fields_version_is_question_or_workflow
        check (entry_kind is null or entry_kind in ('question', 'workflow')),
    constraint declaration_fields_route_fk foreign key (workflow_step_id, step_kind)
        references workflow_steps (workflow_step_id, kind),
    constraint declaration_fields_route_is_route check (step_kind = 'route'),
    constraint declaration_fields_route_only_for_gives check (workflow_step_id is null or side = 'gives'),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint declaration_fields_parent_key_unique unique (declaration_field_id, owner_id, side, kind),
    constraint declaration_fields_standing_unique unique (declaration_field_id, owner_id, standing),
    constraint declaration_fields_threshold_unique unique (declaration_field_id, standing_threshold),
    -- A parent has the same owner and side and holds fields; a cycle longer than one is the application's.
    constraint declaration_fields_parent_fk foreign key (parent_field_id, owner_id, side, parent_kind)
        references declaration_fields (declaration_field_id, owner_id, side, kind),
    constraint declaration_fields_parent_is_fields check (parent_kind = 'fields'),
    constraint declaration_fields_not_own_parent
        check (parent_field_id is null or parent_field_id <> declaration_field_id),
    -- Deferred for the reason reference_list_terms_position_unique gives; nulls not distinct, so the
    -- owner that is absent and a parent that is absent still collide.
    constraint declaration_fields_position_unique
        unique nulls not distinct (entry_version_id, workflow_step_id, side, parent_field_id, position)
        deferrable initially deferred,
    constraint declaration_fields_position_not_negative check (position >= 0),
    constraint declaration_fields_name_shape check (name ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint declaration_fields_label_visible check (
        label is null or (length(label) > 0 and label !~ '[\u0000-\u001f\u007f-\u009f]')),
    constraint declaration_fields_label_bounded check (length(label) <= 128),
    constraint declaration_fields_help_visible check (
        help is null or (length(help) > 0 and help !~ '[\u0000-\u001f\u007f-\u009f]')),
    constraint declaration_fields_help_bounded check (length(help) <= 512),
    constraint declaration_fields_text_limit_positive check (text_limit >= 1),
    constraint declaration_fields_text_limit_only_for_text check (text_limit is null or kind = 'text'),
    constraint declaration_fields_many_limit_positive check (many_limit >= 1),
    constraint declaration_fields_many_limit_only_for_many check (many_limit is null or holds_many),
    constraint declaration_fields_term_list_fk foreign key (term_list_version_id, term_list_kind)
        references entry_versions (entry_version_id, entry_kind),
    constraint declaration_fields_term_list_is_reference_list check (term_list_kind = 'reference_list'),
    constraint declaration_fields_term_list_only_for_term check (term_list_version_id is null or kind = 'term'),
    constraint declaration_fields_must_be_given_on_every_field check (must_be_given is not null),
    constraint declaration_fields_standing_only_for_unheld_gives
        check (standing is null or (side = 'gives' and parent_field_id is null)),
    constraint declaration_fields_standing_only_for_a_question
        check (standing is null or coalesce(entry_kind = 'question', false)),
    constraint declaration_fields_standing_threshold_only_for_above_confidence
        check (standing_threshold is null or coalesce(standing = 'above_confidence', false)),
    constraint declaration_fields_standing_threshold_range check (standing_threshold between 1 and 100),
    constraint declaration_fields_author_fk foreign key (created_by) references subjects (subject_id),
    constraint declaration_fields_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint declaration_fields_editor_is_person check (updated_by_kind = 'person'),
    constraint declaration_fields_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint declaration_fields_updated_after_created check (updated_at is null or updated_at >= created_at)
);

create index declaration_fields_by_route
    on declaration_fields (workflow_step_id) where workflow_step_id is not null;

create index declaration_fields_by_parent
    on declaration_fields (parent_field_id) where parent_field_id is not null;

create index declaration_fields_by_term_list
    on declaration_fields (term_list_version_id) where term_list_version_id is not null;

-- ---------------------------------------------------------------- bindings

-- A path names fields and never a place among many.
create table bindings (
    binding_id       uuid         not null default uuidv7(),
    -- The workflow version the binding is written in; with neither a step nor a case, it fills an output.
    entry_version_id uuid         not null,
    workflow_step_id uuid,
    -- Carried only on a route's discriminator, to reach workflow_steps_kind_unique.
    step_kind        step_kind,
    route_case_id    uuid,
    target_path      text,
    source_step_id   uuid,
    source_path      text,
    constant         jsonb,
    -- So that a value recorded as it was used is held to whether its binding fills nothing, and what it reads.
    discriminates    boolean      generated always as (target_path is null) stored,
    reads_a_step     boolean      generated always as (source_step_id is not null) stored,
    reads_a_constant boolean      generated always as (constant is not null) stored,
    created_at       timestamptz  not null default now(),
    created_by       uuid         not null,
    updated_at       timestamptz,
    updated_by       uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind  subject_kind not null default 'person',

    constraint bindings_pk primary key (binding_id),
    constraint bindings_version_fk foreign key (entry_version_id) references workflow_versions (entry_version_id),
    constraint bindings_at_most_one_consumer check (num_nonnulls(workflow_step_id, route_case_id) <= 1),
    constraint bindings_step_fk foreign key (workflow_step_id, entry_version_id)
        references workflow_steps (workflow_step_id, entry_version_id),
    constraint bindings_route_case_fk foreign key (route_case_id, entry_version_id)
        references route_cases (route_case_id, entry_version_id),
    -- A route's discriminator is the binding on it that fills no input.
    constraint bindings_no_target_only_for_a_step check (target_path is not null or workflow_step_id is not null),
    constraint bindings_step_kind_exactly_for_no_target check ((target_path is null) = (step_kind is not null)),
    constraint bindings_route_fk foreign key (workflow_step_id, step_kind)
        references workflow_steps (workflow_step_id, kind),
    constraint bindings_step_kind_is_route check (step_kind is null or step_kind = 'route'),
    constraint bindings_target_path_shape
        check (target_path ~ '^[a-z][a-z0-9_]{0,62}(\.[a-z][a-z0-9_]{0,62})*$'),
    constraint bindings_target_path_bounded check (length(target_path) <= 1023),
    -- A constant, or a path into this workflow's input or into a step.
    constraint bindings_exactly_one_source check (
        (constant is null) = (source_path is not null) and (constant is null or source_step_id is null)),
    constraint bindings_source_step_fk foreign key (source_step_id, entry_version_id)
        references workflow_steps (workflow_step_id, entry_version_id),
    constraint bindings_not_from_itself check (
        source_step_id is null or workflow_step_id is null or source_step_id <> workflow_step_id),
    constraint bindings_source_path_shape
        check (source_path ~ '^[a-z][a-z0-9_]{0,62}(\.[a-z][a-z0-9_]{0,62})*$'),
    constraint bindings_source_path_bounded check (length(source_path) <= 1023),
    -- At most 1048576 characters as written out, whatever it holds.
    constraint bindings_constant_bounded check (constant is null or length(constant::text) <= 1048576),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint bindings_consumer_source_unique
        unique (binding_id, workflow_step_id, discriminates, reads_a_step, reads_a_constant),
    constraint bindings_source_step_unique unique (binding_id, source_step_id),
    constraint bindings_author_fk foreign key (created_by) references subjects (subject_id),
    constraint bindings_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint bindings_editor_is_person check (updated_by_kind = 'person'),
    constraint bindings_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint bindings_updated_after_created check (updated_at is null or updated_at >= created_at)
);

create unique index bindings_one_discriminator on bindings (workflow_step_id) where target_path is null;

create index bindings_by_version on bindings (entry_version_id);

create index bindings_by_step on bindings (workflow_step_id) where workflow_step_id is not null;

create index bindings_by_route_case on bindings (route_case_id) where route_case_id is not null;

create index bindings_by_source_step on bindings (source_step_id) where source_step_id is not null;
