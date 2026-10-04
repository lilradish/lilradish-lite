-- The source is an actual run step, including one beneath or above the held step in the same tree.
-- DB-SPECIFIC: ::, num_nonnulls(), generated always and uuid use PostgreSQL's syntax and semantics.
alter table run_step_holds
    add column root_run_id uuid,
    add column source_run_id uuid,
    add column source_run_step_id uuid,
    add column source_workflow_step_id uuid,
    add column source_run_step_kind run_step_kind generated always as
        (case when source_run_step_id is not null then 'code_step'::run_step_kind end) stored,
    add constraint run_step_holds_source_together check
        (num_nonnulls(root_run_id, source_run_id, source_run_step_id, source_workflow_step_id) in (0, 4)),
    add constraint run_step_holds_source_only_for_code_step_not_held check
        (source_run_step_id is null or (reason = 'code_step_not_held' and calls_a_model)),
    add constraint run_step_holds_root_fk foreign key (root_run_id, run_id)
        references runs (root_run_id, run_id),
    add constraint run_step_holds_source_root_fk foreign key (root_run_id, source_run_id)
        references runs (root_run_id, run_id),
    add constraint run_step_holds_source_step_fk foreign key
        (source_run_id, source_run_step_id, source_run_step_kind, source_workflow_step_id)
        references run_steps (run_id, run_step_id, kind, workflow_step_id);

alter table run_step_holds
    drop constraint run_step_holds_code_step_not_held_only_for_code_step,
    add constraint run_step_holds_code_step_not_held_for_current_or_source_code check
        (reason <> 'code_step_not_held' or run_step_kind = 'code_step' or source_run_step_id is not null);

-- DB-SPECIFIC: where predicates on indexes use PostgreSQL's syntax and semantics.
create index run_step_holds_by_root
    on run_step_holds (root_run_id) where root_run_id is not null;

-- DB-SPECIFIC: where predicates on indexes use PostgreSQL's syntax and semantics.
create index run_step_holds_by_source_run
    on run_step_holds (source_run_id) where source_run_id is not null;

-- DB-SPECIFIC: ::, length(), num_nonnulls(), text, ~, generated always and uuid use PostgreSQL's syntax and semantics.
alter table run_step_failures
    add column root_run_id uuid,
    add column source_run_id uuid,
    add column source_run_step_id uuid,
    add column source_workflow_step_id uuid,
    add column source_field text,
    add column source_run_step_kind run_step_kind generated always as (
        case when source_run_step_id is not null then 'code_step'::run_step_kind end) stored,
    add constraint run_step_failures_source_together check (
        num_nonnulls(root_run_id, source_run_id, source_run_step_id, source_workflow_step_id, source_field) in (0, 5)),
    add constraint run_step_failures_source_exactly_for_a_source_failure check (
        (reason in ('source_no_longer_matches', 'source_list_not_here')) = (source_run_step_id is not null)),
    add constraint run_step_failures_source_only_for_produce check (
        reason not in ('source_no_longer_matches', 'source_list_not_here') or coalesce(purpose = 'produce', false)),
    add constraint run_step_failures_source_field_shape check (
        source_field ~ '^[a-z][a-z0-9_]{0,62}(\.[a-z][a-z0-9_]{0,62})*$'),
    add constraint run_step_failures_source_field_bounded check (length(source_field) <= 1023),
    add constraint run_step_failures_root_fk foreign key (root_run_id, run_id)
        references runs (root_run_id, run_id),
    add constraint run_step_failures_source_root_fk foreign key (root_run_id, source_run_id)
        references runs (root_run_id, run_id),
    add constraint run_step_failures_source_step_fk foreign key (
        source_run_id, source_run_step_id, source_run_step_kind, source_workflow_step_id)
        references run_steps (run_id, run_step_id, kind, workflow_step_id),
    drop constraint run_step_failures_sending_failure_only_for_calls_a_model,
    add constraint run_step_failures_sending_failure_only_for_calls_a_model check (
        reason not in ('uncuttable_length', 'model_not_deployed', 'tool_not_offered', 'source_no_longer_matches', 'source_list_not_here')
        or calls_a_model),
    drop constraint run_step_failures_production_exactly_for_a_sending_failure,
    add constraint run_step_failures_production_exactly_for_a_sending_failure check (
        (reason in ('uncuttable_length', 'model_not_deployed', 'tool_not_offered', 'source_no_longer_matches', 'source_list_not_here'))
        = (production_id is not null));

-- DB-SPECIFIC: where predicates on indexes use PostgreSQL's syntax and semantics.
create index run_step_failures_by_root
    on run_step_failures (root_run_id) where root_run_id is not null;

-- DB-SPECIFIC: where predicates on indexes use PostgreSQL's syntax and semantics.
create index run_step_failures_by_source_run
    on run_step_failures (source_run_id) where source_run_id is not null;
