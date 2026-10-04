alter table model_call_turnaways
    -- Set where the model turned the call away as what may be spent with it is used up; written in the transaction
    -- ending the call, after it is ended turned_away, or model_call_turnaways_model_call_outcome_fk refuses it.
    add column spent_up boolean not null default false,
    -- Carried only where spent_up, so that the call it names ended turned away, and stays so.
    add column model_call_outcome model_call_outcome,
    add constraint model_call_turnaways_model_call_outcome_fk foreign key (model_call_id, model_call_outcome)
        references model_calls (model_call_id, outcome),
    add constraint model_call_turnaways_model_call_outcome_exactly_for_spent_up
        check ((model_call_outcome is not null) = spent_up),
    add constraint model_call_turnaways_model_call_outcome_is_turned_away check (model_call_outcome = 'turned_away'),
    add constraint model_call_turnaways_resent_only_unspent check (not (spent_up and resent_at is not null));

-- DB-SPECIFIC: where predicates on indexes use PostgreSQL's syntax and semantics.
create unique index model_call_turnaways_one_spent_up_per_call on model_call_turnaways (model_call_id) where spent_up;

alter table model_calls
    -- True when both counts came from the model. Otherwise sent_count and any came_back_count are system-measured.
    add column counted_by_model boolean not null default false,
    add constraint model_calls_counted_by_model_only_for_came_back
        check (not counted_by_model or coalesce(outcome = 'came_back', false));
