create type run_ceiling_change_outcome as enum ('approved', 'refused', 'withdrawn');
create type run_step_kind as enum ('question', 'workflow', 'code_step', 'route');
create type run_step_hold_reason as enum ('entry_stopped', 'too_long', 'turned_away', 'code_step_not_held');
create type run_step_failure_reason as enum ('uncuttable_length', 'unclaimed_value', 'model_not_deployed');
create type model_call_purpose as enum ('produce', 'review', 'help');
create type review_unbuilt_reason as enum ('list_not_here', 'takes_no_longer_declared', 'no_longer_declared');

-- ---------------------------------------------------------------- runs

create table runs (
    -- No default, as on subjects: this key is minted before there is a row for it to name.
    run_id                   uuid          not null,
    group_id                 uuid          not null,
    number                   integer       not null,
    name                     text,
    name_folded              text          generated always as (search_fold(name)) stored,
    entry_id                 uuid          not null,
    entry_version_id         uuid          not null,
    -- Always true, only to match entry_versions.approved through runs_version_approved_fk.
    version_approved         boolean       not null default true,
    -- Only on a run at the top, so that only its version is held to belong to its own group.
    top_level_group_id       uuid          generated always as (
        case when parent_run_id is null then group_id end) stored,
    parent_run_id            uuid,
    parent_run_step_id       uuid,
    parent_run_step_kind     run_step_kind,
    parent_workflow_step_id  uuid,
    -- The case a route took, whose target is the version this run runs, the fallback included.
    parent_route_case_id     uuid,
    -- Only under a step running a workflow, so that only that run is held to the version the step pins.
    parent_pinned_version_id uuid          generated always as (
        case when parent_run_step_kind = 'workflow' then entry_version_id end) stored,
    root_run_id              uuid          not null,
    -- How far below its root, so that no two runs can each be the other's parent.
    depth                    integer       not null,
    parent_depth             integer,
    started_with             jsonb,
    created_at               timestamptz   not null default now(),
    created_by               uuid          not null,
    created_by_kind          subject_kind  not null default 'person',
    updated_at               timestamptz,
    updated_by               uuid,
    -- Unchecked while updated_by is null, for the reason pool_members gives.
    updated_by_kind          subject_kind  not null default 'person',

    constraint runs_pk primary key (run_id),
    constraint runs_group_fk foreign key (group_id) references groups (group_id),
    constraint runs_number_unique unique (group_id, number),
    constraint runs_number_positive check (number >= 1),
    constraint runs_version_fk foreign key (entry_version_id) references workflow_versions (entry_version_id),
    constraint runs_version_entry_fk foreign key (entry_version_id, entry_id)
        references entry_versions (entry_version_id, entry_id),
    constraint runs_version_approved_fk foreign key (entry_version_id, version_approved)
        references entry_versions (entry_version_id, approved),
    constraint runs_version_approved check (version_approved),
    constraint runs_top_level_entry_fk foreign key (entry_id, top_level_group_id)
        references entries (entry_id, group_id),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint runs_group_unique unique (run_id, group_id),
    constraint runs_version_unique unique (entry_version_id, run_id),
    constraint runs_depth_unique unique (run_id, root_run_id, depth),
    constraint runs_parent_workflow_step_unique
        unique (run_id, parent_run_id, parent_run_step_id, parent_run_step_kind, parent_workflow_step_id),
    -- Leads with root_run_id, so that a whole tree is read by it and runs_root_fk is indexed by it.
    constraint runs_root_unique unique (root_run_id, run_id),
    constraint runs_root_fk foreign key (root_run_id, group_id) references runs (run_id, group_id),
    constraint runs_parent_together check (
        (parent_run_id is null) = (parent_run_step_id is null)
            and (parent_run_id is null) = (parent_run_step_kind is null)
            and (parent_run_id is null) = (parent_workflow_step_id is null)
            and (parent_run_id is null) = (parent_depth is null)),
    -- Leads with parent_run_id, so that every key naming a parent is indexed by it.
    constraint runs_parent_step_unique unique (parent_run_id, parent_run_step_id),
    constraint runs_parent_fk foreign key (parent_run_id, root_run_id, parent_depth)
        references runs (run_id, root_run_id, depth),
    constraint runs_depth_zero_exactly_for_top_level check ((parent_run_id is null) = (depth = 0)),
    constraint runs_depth_one_below_parent check (depth = parent_depth + 1),
    constraint runs_parent_step_is_workflow_or_route check (parent_run_step_kind in ('workflow', 'route')),
    -- For the reason workflow_steps_pinned_together gives: it keeps runs_parent_route_case_fk awake.
    constraint runs_parent_route_case_exactly_for_route
        check (coalesce(parent_run_step_kind = 'route', false) = (parent_route_case_id is not null)),
    constraint runs_own_root_exactly_for_top_level check ((parent_run_id is null) = (root_run_id = run_id)),
    constraint runs_name_exactly_for_top_level check ((parent_run_id is null) = (name is not null)),
    -- Ranges and escaping for the reasons subjects_user_id_visible gives.
    constraint runs_name_visible check (
        name is null or (length(name) > 0 and name !~ '[\u0000-\u001f\u007f-\u009f]')),
    constraint runs_name_bounded check (length(name) <= 128),
    constraint runs_started_with_exactly_for_top_level check ((parent_run_id is null) = (started_with is not null)),
    constraint runs_started_with_is_an_object check (jsonb_typeof(started_with) = 'object'),
    -- One value of at most 8388608 characters written out, each kept as at most six in jsonb's text: a control
    -- escaped \u00XX; a comma or colon gains only a space.
    constraint runs_started_with_bounded check (length(started_with::text) <= 50331648),
    constraint runs_author_kind_fk foreign key (created_by, created_by_kind) references subjects (subject_id, kind),
    constraint runs_author_is_person_or_system check (created_by_kind in ('person', 'system')),
    constraint runs_author_is_person_exactly_for_top_level
        check ((parent_run_id is null) = (created_by_kind = 'person')),
    constraint runs_editor_person_fk foreign key (updated_by, updated_by_kind)
        references subjects (subject_id, kind),
    constraint runs_editor_is_person check (updated_by_kind = 'person'),
    constraint runs_updated_together check ((updated_at is null) = (updated_by is null)),
    constraint runs_updated_after_created check (updated_at is null or updated_at >= created_at),
    constraint runs_updated_only_for_top_level check (updated_at is null or parent_run_id is null)
);

create index runs_at_top_by_group on runs (group_id, created_at, number) where parent_run_id is null;

create index runs_by_starter on runs (created_by, group_id, created_at) where parent_run_id is null;

create index runs_by_parent_route_case on runs (parent_route_case_id) where parent_route_case_id is not null;

create table run_steps (
    run_step_id       uuid          not null default uuidv7(),
    run_id            uuid          not null,
    -- Carried so that the step is one of the version its run ran.
    entry_version_id  uuid          not null,
    workflow_step_id  uuid          not null,
    -- Carried so that what the step runs, the version it pins, who produces, whether a model reviews and how
    -- many tries it declares are read off this row.
    step_kind         step_kind     not null,
    pinned_version_id uuid,
    pinned_kind       entry_kind,
    producer          step_producer,
    reviewed_by_model boolean       not null,
    tries             integer,
    kind              run_step_kind generated always as (case step_kind
        when 'code_step' then 'code_step'::run_step_kind
        when 'route' then 'route'::run_step_kind
        else case pinned_kind
            when 'question' then 'question'::run_step_kind
            when 'workflow' then 'workflow'::run_step_kind end end) stored,
    calls_a_model     boolean       generated always as (
        coalesce(producer = 'model', false) or reviewed_by_model) stored,
    created_at        timestamptz   not null default now(),
    created_by        uuid          not null,
    created_by_kind   subject_kind  not null default 'system',

    constraint run_steps_pk primary key (run_step_id),
    constraint run_steps_run_fk foreign key (run_id, entry_version_id) references runs (run_id, entry_version_id),
    constraint run_steps_workflow_step_fk foreign key (workflow_step_id, entry_version_id)
        references workflow_steps (workflow_step_id, entry_version_id),
    constraint run_steps_step_kind_fk foreign key (workflow_step_id, step_kind)
        references workflow_steps (workflow_step_id, kind),
    constraint run_steps_pinned_fk foreign key (workflow_step_id, pinned_version_id, pinned_kind)
        references workflow_steps (workflow_step_id, pinned_version_id, pinned_kind),
    -- For the reason workflow_steps_pinned_together gives: it keeps run_steps_pinned_fk awake.
    constraint run_steps_pinned_together check ((pinned_version_id is null) = (pinned_kind is null)),
    constraint run_steps_pinned_exactly_for_entry check ((step_kind = 'entry') = (pinned_version_id is not null)),
    constraint run_steps_producer_fk foreign key (workflow_step_id, producer, reviewed_by_model, tries)
        references workflow_steps (workflow_step_id, producer, reviewed_by_model, tries),
    -- For the same reason, this and run_steps_producer_together keep run_steps_producer_fk awake.
    constraint run_steps_producer_exactly_for_question_or_code_step
        check (coalesce(kind in ('question', 'code_step'), false) = (producer is not null)),
    constraint run_steps_producer_together check ((producer is null) = (tries is null)),
    constraint run_steps_reviewed_by_model_only_for_a_producer check (not reviewed_by_model or producer is not null),
    constraint run_steps_one_per_workflow_step unique (workflow_step_id, run_id),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint run_steps_workflow_step_kind_unique unique (run_id, run_step_id, kind, workflow_step_id),
    constraint run_steps_producer_unique unique (run_id, run_step_id, kind, producer, reviewed_by_model, tries),
    constraint run_steps_calls_a_model_unique unique (run_id, run_step_id, kind, calls_a_model),
    constraint run_steps_pinned_unique unique (run_step_id, pinned_version_id),
    constraint run_steps_author_system_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint run_steps_author_is_system check (created_by_kind = 'system')
);

alter table runs add constraint runs_parent_step_fk
    foreign key (parent_run_id, parent_run_step_id, parent_run_step_kind, parent_workflow_step_id)
    references run_steps (run_id, run_step_id, kind, workflow_step_id);

alter table runs add constraint runs_parent_route_case_fk
    foreign key (parent_route_case_id, parent_workflow_step_id, entry_version_id)
    references route_cases (route_case_id, workflow_step_id, target_version_id);

alter table runs add constraint runs_parent_pinned_version_fk
    foreign key (parent_run_step_id, parent_pinned_version_id) references run_steps (run_step_id, pinned_version_id);

-- ---------------------------------------------------------------- stops and ceilings

create table run_stops (
    run_stop_id           uuid         not null default uuidv7(),
    run_id                uuid         not null,
    -- Carried so that only a run at the top is stopped, and the run whose ceiling was reached is in its tree.
    root_run_id           uuid         not null,
    ceiling_run_id        uuid,
    created_at            timestamptz  not null default now(),
    created_by            uuid         not null,
    created_by_kind       subject_kind not null default 'person',
    opened_again_at       timestamptz,
    opened_again_by       uuid,
    -- Unchecked while opened_again_by is null, for the reason pool_members gives.
    opened_again_by_kind  subject_kind not null default 'person',

    constraint run_stops_pk primary key (run_stop_id),
    constraint run_stops_run_fk foreign key (run_id, root_run_id) references runs (run_id, root_run_id),
    constraint run_stops_run_is_root check (run_id = root_run_id),
    constraint run_stops_ceiling_run_fk foreign key (ceiling_run_id, root_run_id)
        references runs (run_id, root_run_id),
    constraint run_stops_author_kind_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint run_stops_author_is_person_or_system check (created_by_kind in ('person', 'system')),
    constraint run_stops_ceiling_exactly_for_system
        check ((created_by_kind = 'system') = (ceiling_run_id is not null)),
    constraint run_stops_opened_again_person_fk foreign key (opened_again_by, opened_again_by_kind)
        references subjects (subject_id, kind),
    constraint run_stops_opened_again_is_person check (opened_again_by_kind = 'person'),
    constraint run_stops_opened_again_together check ((opened_again_at is null) = (opened_again_by is null)),
    constraint run_stops_opened_again_after_created
        check (opened_again_at is null or opened_again_at >= created_at)
);

create unique index run_stops_one_in_force on run_stops (run_id) where opened_again_at is null;

create index run_stops_by_run on run_stops (run_id, created_at);

-- The ceiling in force is set by the change of highest position that holds, awaiting nothing or approved; its
-- timestamps, which tie within a transaction, never decide it.
create table run_ceiling_changes (
    run_ceiling_change_id uuid                       not null default uuidv7(),
    run_id                uuid                       not null,
    position              integer                    not null,
    from_ceiling          bigint,
    to_ceiling            bigint,
    awaits_approval       boolean                    not null,
    created_at            timestamptz                not null default now(),
    created_by            uuid                       not null,
    created_by_kind       subject_kind               not null default 'person',
    -- Nothing here refuses deciding a raise that a change of higher position has since superseded: withdrawing
    -- it as that change is made is the application's.
    outcome               run_ceiling_change_outcome,
    decided_at            timestamptz,
    decided_by            uuid,
    -- Unchecked while decided_by is null, for the reason pool_members gives.
    decided_by_kind       subject_kind               not null default 'person',

    constraint run_ceiling_changes_pk primary key (run_ceiling_change_id),
    constraint run_ceiling_changes_run_fk foreign key (run_id) references runs (run_id),
    constraint run_ceiling_changes_position_positive check (position >= 1),
    constraint run_ceiling_changes_position_unique unique (run_id, position),
    constraint run_ceiling_changes_from_ceiling_positive check (from_ceiling >= 1),
    constraint run_ceiling_changes_to_ceiling_positive check (to_ceiling >= 1),
    constraint run_ceiling_changes_from_differs_from_to check (from_ceiling is distinct from to_ceiling),
    constraint run_ceiling_changes_awaits_approval_only_for_a_raise check (
        not awaits_approval or (from_ceiling is not null and (to_ceiling is null or to_ceiling > from_ceiling))),
    constraint run_ceiling_changes_author_person_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint run_ceiling_changes_author_is_person check (created_by_kind = 'person'),
    constraint run_ceiling_changes_outcome_only_for_awaiting check (outcome is null or awaits_approval),
    constraint run_ceiling_changes_decided_together
        check ((outcome is null) = (decided_at is null) and (outcome is null) = (decided_by is null)),
    constraint run_ceiling_changes_decider_person_fk foreign key (decided_by, decided_by_kind)
        references subjects (subject_id, kind),
    constraint run_ceiling_changes_decider_is_person check (decided_by_kind = 'person'),
    constraint run_ceiling_changes_decided_after_created check (decided_at is null or decided_at >= created_at),
    constraint run_ceiling_changes_decider_is_not_requester
        check (outcome is null or outcome = 'withdrawn' or decided_by <> created_by)
);

create unique index run_ceiling_changes_one_awaiting
    on run_ceiling_changes (run_id) where awaits_approval and outcome is null;

-- ---------------------------------------------------------------- what happens to a step

create table run_step_holds (
    run_step_hold_id         uuid                 not null default uuidv7(),
    run_step_id              uuid                 not null,
    -- Carried so that what happened in a run is read by the run alone; calls_a_model is carried, with run_step_kind
    -- to reach run_steps_calls_a_model_unique, so the check below reads whether the step calls a model.
    run_id                   uuid                 not null,
    run_step_kind            run_step_kind        not null,
    calls_a_model            boolean              not null,
    -- A stopped entry holds a step whatever it runs, since the entry stopped may be the run's own workflow.
    reason                   run_step_hold_reason not null,
    -- The attempt to produce that was too long to send, or whose call was turned away every time. An attempt to
    -- review that cannot be sent leaves its production waiting, and never holds the step back.
    run_step_send_attempt_id uuid,
    -- Always 'produce', only to match run_step_send_attempts.purpose through run_step_holds_attempt_fk; unchecked
    -- while run_step_send_attempt_id is null, for the reason pool_members gives.
    attempt_purpose          model_call_purpose   not null default 'produce',
    -- So that a hold on length names an attempt that was too long, and one turned away an attempt that was sent.
    attempt_too_long         boolean              generated always as (reason = 'too_long') stored,
    created_at               timestamptz          not null default now(),
    created_by               uuid                 not null,
    created_by_kind          subject_kind         not null default 'system',
    released_at              timestamptz,
    released_by              uuid,
    -- Unchecked while released_by is null, for the reason pool_members gives.
    released_by_kind         subject_kind         not null default 'system',

    constraint run_step_holds_pk primary key (run_step_hold_id),
    constraint run_step_holds_step_fk foreign key (run_id, run_step_id, run_step_kind, calls_a_model)
        references run_steps (run_id, run_step_id, kind, calls_a_model),
    constraint run_step_holds_too_long_or_turned_away_only_for_calls_a_model
        check (reason not in ('too_long', 'turned_away') or calls_a_model),
    constraint run_step_holds_attempt_exactly_for_too_long_or_turned_away
        check ((reason in ('too_long', 'turned_away')) = (run_step_send_attempt_id is not null)),
    constraint run_step_holds_code_step_not_held_only_for_code_step
        check (reason <> 'code_step_not_held' or run_step_kind = 'code_step'),
    constraint run_step_holds_attempt_is_produce check (attempt_purpose = 'produce'),
    constraint run_step_holds_author_system_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint run_step_holds_author_is_system check (created_by_kind = 'system'),
    constraint run_step_holds_releaser_system_fk foreign key (released_by, released_by_kind)
        references subjects (subject_id, kind),
    constraint run_step_holds_releaser_is_system check (released_by_kind = 'system'),
    constraint run_step_holds_released_together check ((released_at is null) = (released_by is null)),
    constraint run_step_holds_released_after_created check (released_at is null or released_at >= created_at),
    -- A foreign key target, at the cost subjects_kind_unique gives.
    constraint run_step_holds_run_reason_unique unique (run_step_hold_id, run_id, reason)
);

create unique index run_step_holds_one_unreleased on run_step_holds (run_step_id) where released_at is null;

create index run_step_holds_by_run on run_step_holds (run_id, created_at);

create table run_step_send_attempts (
    run_step_send_attempt_id uuid               not null default uuidv7(),
    run_step_id              uuid               not null,
    -- Carried so that what happened in a run is read by the run alone, as on run_step_holds.
    run_id                   uuid               not null,
    -- Carried so that only a question or a code step is tried sending, and only a question to produce.
    run_step_kind            run_step_kind      not null,
    -- Carried so that the model and mode are the ones the approved step names for the purpose.
    workflow_step_id         uuid               not null,
    purpose                  model_call_purpose not null,
    model                    text               not null,
    -- 'ordinary' where the model runs as it is, for the reason workflow_versions.helper_mode gives.
    mode                     text               not null,
    -- Only the purpose's own model, so that the key naming the other purpose's model is switched off, for the
    -- reason pool_members gives.
    produced_with            text               generated always as (
        case when purpose = 'produce' then model end) stored,
    reviewed_with            text               generated always as (
        case when purpose = 'review' then model end) stored,
    -- The try being produced, or the try being reviewed.
    production_id            uuid               not null,
    -- Only the purpose's own try, for the reason produced_with gives.
    produced_production_id   uuid               generated always as (
        case when purpose = 'produce' then production_id end) stored,
    reviewed_production_id   uuid               generated always as (
        case when purpose = 'review' then production_id end) stored,
    -- Always 'model', only to match productions.producer through run_step_send_attempts_produced_fk.
    production_producer      step_producer      not null default 'model',
    -- Always true, only to match productions.yielded through run_step_send_attempts_reviewed_fk.
    production_yielded       boolean            not null default true,
    -- Not sent: to produce, measured too long and held; to review, too long or not built, and a person reviews.
    too_long                 boolean            not null default false,
    -- Why what a review would send could not be built; none where it was built.
    unbuilt_reason           review_unbuilt_reason,
    payload                  text,
    -- The attempt holding the payload this one would send again.
    repeats_attempt_id       uuid,
    -- Only a row holding its payload is reached by a repeat, so a repeat names that row and never another repeat.
    holds_payload            boolean            generated always as (payload is not null) stored,
    -- Always true, only to match holds_payload through run_step_send_attempts_repeats_fk; unchecked while
    -- repeats_attempt_id is null, for the reason pool_members gives.
    repeated_holds_payload   boolean            not null default true,
    -- Where a payload may be repeated: a step's, across its tries, to produce; the one try reviewed, to review.
    payload_scope_id         uuid               generated always as (
        case purpose when 'review' then production_id else run_step_id end) stored,
    -- The failure this was made to answer, by Try sending, on the try that failure was on.
    answers_failure_id       uuid,
    created_at               timestamptz        not null default now(),
    created_by               uuid               not null,
    created_by_kind          subject_kind       not null default 'person',

    constraint run_step_send_attempts_pk primary key (run_step_send_attempt_id),
    constraint run_step_send_attempts_step_fk foreign key (run_id, run_step_id, run_step_kind, workflow_step_id)
        references run_steps (run_id, run_step_id, kind, workflow_step_id),
    constraint run_step_send_attempts_producer_model_fk foreign key (workflow_step_id, produced_with, mode)
        references workflow_steps (workflow_step_id, producer_model, producer_mode),
    constraint run_step_send_attempts_reviewer_model_fk foreign key (workflow_step_id, reviewed_with, mode)
        references workflow_steps (workflow_step_id, reviewer_model, reviewer_mode),
    constraint run_step_send_attempts_mode_shape check (mode ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint run_step_send_attempts_author_kind_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint run_step_send_attempts_author_is_person_or_system check (created_by_kind in ('person', 'system')),
    constraint run_step_send_attempts_only_for_question_or_code_step
        check (run_step_kind in ('question', 'code_step')),
    constraint run_step_send_attempts_purpose_is_produce_or_review check (purpose in ('produce', 'review')),
    constraint run_step_send_attempts_produce_only_for_question
        check (run_step_kind = 'question' or purpose = 'review'),
    constraint run_step_send_attempts_production_is_model check (production_producer = 'model'),
    constraint run_step_send_attempts_production_yielded check (production_yielded),
    -- JSON text: C0 stands raw only as whitespace between tokens, while DEL and C1 may stand raw in a string.
    constraint run_step_send_attempts_payload_is_json check (payload is json),
    constraint run_step_send_attempts_payload_bounded check (length(payload) <= 8388608),
    constraint run_step_send_attempts_repeats_fk
        foreign key (repeats_attempt_id, payload_scope_id, purpose, repeated_holds_payload)
        references run_step_send_attempts (run_step_send_attempt_id, payload_scope_id, purpose, holds_payload),
    constraint run_step_send_attempts_repeats_exactly_for_no_payload
        check ((repeats_attempt_id is not null) = (payload is null and unbuilt_reason is null)),
    -- Only a review is built from what a release declares now, and one not built holds nothing and sends nothing.
    constraint run_step_send_attempts_unbuilt_only_for_review_not_sent
        check (unbuilt_reason is null or (purpose = 'review' and too_long and payload is null)),
    constraint run_step_send_attempts_repeated_holds_payload check (repeated_holds_payload),
    -- Measured, never said: a person who could write one would have the review handed to them.
    constraint run_step_send_attempts_too_long_review_only_for_system
        check (not (purpose = 'review' and too_long) or created_by_kind = 'system'),
    constraint run_step_send_attempts_failure_unique unique (answers_failure_id),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint run_step_send_attempts_purpose_model_production_unique
        unique (run_step_send_attempt_id, run_id, run_step_id, purpose, model, mode, production_id, too_long),
    constraint run_step_send_attempts_step_too_long_unique
        unique (run_step_send_attempt_id, run_step_id, purpose, too_long),
    constraint run_step_send_attempts_reviewed_too_long_unique
        unique (run_step_send_attempt_id, reviewed_production_id, too_long),
    constraint run_step_send_attempts_scope_holds_payload_unique
        unique (run_step_send_attempt_id, payload_scope_id, purpose, holds_payload)
);

-- One per try at most, written by the system, too long or not built; a person then reviews in the model's place,
-- and that no attempt to review the try follows is the application's, since no key can say a row is absent.
create unique index run_step_send_attempts_one_too_long_review
    on run_step_send_attempts (reviewed_production_id) where too_long;

alter table run_step_holds add constraint run_step_holds_attempt_fk
    foreign key (run_step_send_attempt_id, run_step_id, attempt_purpose, attempt_too_long)
    references run_step_send_attempts (run_step_send_attempt_id, run_step_id, purpose, too_long);

create index run_step_send_attempts_by_run on run_step_send_attempts (run_id, created_at);

create index run_step_send_attempts_by_workflow_step on run_step_send_attempts (workflow_step_id);

create index run_step_send_attempts_by_produced_production
    on run_step_send_attempts (produced_production_id) where produced_production_id is not null;

create index run_step_send_attempts_by_reviewed_production
    on run_step_send_attempts (reviewed_production_id) where reviewed_production_id is not null;

-- A failure has ended exactly when an attempt answers it, and only an attempt on its try, for its purpose, can; a
-- route's, on no try, never ends.
create table run_step_failures (
    run_step_failure_id    uuid                    not null default uuidv7(),
    run_step_id            uuid                    not null,
    run_id                 uuid                    not null,
    -- Carried to reach run_steps_calls_a_model_unique, so the checks below read what the step is.
    run_step_kind          run_step_kind           not null,
    calls_a_model          boolean                 not null,
    reason                 run_step_failure_reason not null,
    -- The try the step was on when it failed, and what it was sending that try for.
    production_id          uuid,
    purpose                model_call_purpose,
    -- Only the purpose's own try, for the reason run_step_send_attempts.produced_with gives.
    produced_production_id uuid                    generated always as (
        case when purpose = 'produce' then production_id end) stored,
    reviewed_production_id uuid                    generated always as (
        case when purpose = 'review' then production_id end) stored,
    -- Always 'model', only to match productions.producer through run_step_failures_produced_fk.
    production_producer    step_producer           not null default 'model',
    -- Always true, only to match productions.reviewed_by_model through run_step_failures_reviewed_fk.
    production_reviewed    boolean                 not null default true,
    -- Always true, only to match productions.yielded through run_step_failures_reviewed_fk.
    production_yielded     boolean                 not null default true,
    detail                 text                    not null,
    created_at             timestamptz             not null default now(),
    created_by             uuid                    not null,
    created_by_kind        subject_kind            not null default 'system',

    constraint run_step_failures_pk primary key (run_step_failure_id),
    constraint run_step_failures_step_fk foreign key (run_id, run_step_id, run_step_kind, calls_a_model)
        references run_steps (run_id, run_step_id, kind, calls_a_model),
    constraint run_step_failures_unclaimed_value_only_for_route
        check (reason <> 'unclaimed_value' or run_step_kind = 'route'),
    constraint run_step_failures_length_or_undeployed_only_for_calls_a_model
        check (reason not in ('uncuttable_length', 'model_not_deployed') or calls_a_model),
    -- Try sending sends the try the step was on, so a failure it answers has one; a route has none to send.
    constraint run_step_failures_production_exactly_for_length_or_undeployed
        check ((reason in ('uncuttable_length', 'model_not_deployed')) = (production_id is not null)),
    constraint run_step_failures_purpose_together check ((production_id is null) = (purpose is null)),
    constraint run_step_failures_purpose_is_produce_or_review check (purpose in ('produce', 'review')),
    -- A review too long to send waits on a person instead, and never fails its step.
    constraint run_step_failures_uncuttable_length_only_for_produce
        check (reason <> 'uncuttable_length' or purpose = 'produce'),
    constraint run_step_failures_production_is_model check (production_producer = 'model'),
    constraint run_step_failures_production_reviewed check (production_reviewed),
    constraint run_step_failures_production_yielded check (production_yielded),
    constraint run_step_failures_author_system_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint run_step_failures_author_is_system check (created_by_kind = 'system'),
    -- Prose, for the reason reference_list_versions_note_visible gives.
    constraint run_step_failures_detail_visible check (
        length(detail) > 0 and detail !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]'),
    constraint run_step_failures_detail_bounded check (length(detail) <= 2048),
    -- A foreign key target, at the cost subjects_kind_unique gives.
    constraint run_step_failures_production_purpose_unique unique (run_step_failure_id, production_id, purpose)
);

create index run_step_failures_by_run on run_step_failures (run_id, created_at);

alter table run_step_send_attempts add constraint run_step_send_attempts_failure_fk
    foreign key (answers_failure_id, production_id, purpose)
    references run_step_failures (run_step_failure_id, production_id, purpose);
