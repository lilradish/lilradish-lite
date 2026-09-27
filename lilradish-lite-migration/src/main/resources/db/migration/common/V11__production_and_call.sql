create type model_call_outcome as enum ('came_back', 'nothing_came_back', 'errored', 'turned_away');
create type try_lost_reason as enum ('did_not_fit', 'nothing_came_back', 'errored');
create type review_outcome as enum ('assured', 'refused');
create type did_not_fit_reason as enum ('not_the_shape', 'field_missing', 'field_unknown', 'nothing_given',
    'not_its_kind', 'too_long', 'too_many', 'not_a_term', 'unkeepable', 'too_long_to_keep', 'confidence_missing',
    'confidence_unasked', 'confidence_not_a_percent', 'undecided', 'words_missing', 'words_too_long',
    'words_unkeepable', 'cut_off', 'not_kept_as_it_came');
create type code_error_reason as enum ('gave_nothing', 'not_declared', 'nothing_given', 'not_its_kind', 'too_long',
    'too_many', 'not_a_term', 'unkeepable', 'too_long_to_keep', 'takes_a_list_not_here', 'gives_a_list_not_here',
    'takes_otherwise', 'gives_otherwise', 'failed_on_this_side', 'said_nothing');

-- ---------------------------------------------------------------- calls

-- Written before it is sent and ended once, so a call nothing came back for is still there to count.
create table model_calls (
    model_call_id            uuid               not null default uuidv7(),
    run_id                   uuid               not null,
    -- Carried so that what a whole tree has spent is read by its root alone.
    root_run_id              uuid               not null,
    purpose                  model_call_purpose not null,
    -- The attempt that let a call to produce or to review through, and holds what it sent.
    run_step_send_attempt_id uuid,
    run_step_id              uuid,
    -- The try the call produces or reviews, as its attempt names it.
    production_id            uuid,
    -- Always false, only to match run_step_send_attempts.too_long through model_calls_attempt_fk; unchecked while
    -- run_step_send_attempt_id is null, for the reason pool_members gives.
    attempt_too_long         boolean            not null default false,
    -- The version of the run at the top, which alone says whether a run may be helped, and by what.
    root_version_id          uuid,
    -- Always true, only to match workflow_versions.may_be_helped through model_calls_helper_fk; unchecked while
    -- root_version_id is null, for the reason pool_members gives.
    root_may_be_helped       boolean            not null default true,
    -- What a call to the helper sent, which no attempt holds: nothing is held back on its account.
    request                  text,
    model                    text               not null,
    -- 'ordinary' where the model runs as it is, for the reason workflow_versions.helper_mode gives.
    mode                     text               not null,
    envelope_version         integer            not null,
    -- What this system measured before sending, replaced by what the model counted once it came back; a call
    -- turned away every time it was allowed to be sent was never taken up, and counts in no total.
    sent_count               bigint             not null,
    -- Null where nothing says how much came back, which is never a claim that nothing did.
    came_back_count          bigint,
    outcome                  model_call_outcome,
    -- Held to no characters, as it came back. A 23502/23514 DETAIL shows 64 bytes of each column selectable or given:
    -- bind values, keep log_parameter_max_length_on_error 0, pass no DETAIL or CONTEXT on, log terse, no csv/jsonlog.
    answer                   text,
    -- Set where what is kept of the answer is not as it came back.
    answer_altered           boolean            not null default false,
    error_detail             text,
    -- Set where error_detail was cut to its bound; the part cut off is kept nowhere.
    error_detail_truncated   boolean            not null default false,
    ended_at                 timestamptz,
    created_at               timestamptz        not null default now(),
    created_by               uuid               not null,
    created_by_kind          subject_kind       not null default 'system',

    constraint model_calls_pk primary key (model_call_id),
    constraint model_calls_run_fk foreign key (run_id, root_run_id) references runs (run_id, root_run_id),
    constraint model_calls_attempt_fk
        foreign key
            (run_step_send_attempt_id, run_id, run_step_id, purpose, model, mode, production_id, attempt_too_long)
        references run_step_send_attempts
            (run_step_send_attempt_id, run_id, run_step_id, purpose, model, mode, production_id, too_long),
    constraint model_calls_attempt_not_too_long check (not attempt_too_long),
    -- An attempt is sent once at most, however often it was turned away.
    constraint model_calls_attempt_unique unique (run_step_send_attempt_id),
    constraint model_calls_attempt_exactly_for_produce_or_review
        check ((purpose in ('produce', 'review')) = (run_step_send_attempt_id is not null)),
    -- For the reason workflow_steps_pinned_together gives, model_calls_attempt_together keeps model_calls_attempt_fk
    -- awake, and model_calls_root_version_exactly_for_help keeps model_calls_helper_fk awake.
    constraint model_calls_attempt_together check (
        (run_step_send_attempt_id is null) = (run_step_id is null)
            and (run_step_send_attempt_id is null) = (production_id is null)),
    constraint model_calls_root_version_exactly_for_help check ((purpose = 'help') = (root_version_id is not null)),
    constraint model_calls_root_version_fk foreign key (root_version_id, root_run_id)
        references runs (entry_version_id, run_id),
    constraint model_calls_helper_fk foreign key (root_version_id, model, mode, root_may_be_helped)
        references workflow_versions (entry_version_id, helper_model, helper_mode, may_be_helped),
    constraint model_calls_mode_shape check (mode ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint model_calls_root_may_be_helped check (root_may_be_helped),
    constraint model_calls_request_exactly_for_help check ((purpose = 'help') = (request is not null)),
    -- JSON text, for the reason run_step_send_attempts_payload_is_json gives.
    constraint model_calls_request_is_json check (request is json),
    constraint model_calls_request_bounded check (length(request) <= 8388608),
    constraint model_calls_envelope_version_positive check (envelope_version >= 1),
    constraint model_calls_sent_count_positive check (sent_count >= 1),
    constraint model_calls_came_back_count_not_negative check (came_back_count >= 0),
    constraint model_calls_came_back_count_exactly_for_came_back
        check (coalesce(outcome = 'came_back', false) = (came_back_count is not null)),
    constraint model_calls_answer_exactly_for_came_back
        check (coalesce(outcome = 'came_back', false) = (answer is not null)),
    constraint model_calls_answer_bounded check (length(answer) <= 8388608),
    constraint model_calls_answer_altered_only_for_answer check (not answer_altered or answer is not null),
    constraint model_calls_error_detail_exactly_for_errored
        check (coalesce(outcome = 'errored', false) = (error_detail is not null)),
    -- Prose, for the reason reference_list_versions_note_visible gives.
    constraint model_calls_error_detail_visible check (
        error_detail is null
            or (length(error_detail) > 0 and error_detail !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]')),
    constraint model_calls_error_detail_bounded check (length(error_detail) <= 2048),
    constraint model_calls_truncated_only_for_error_detail
        check (not error_detail_truncated or error_detail is not null),
    constraint model_calls_ended_together check ((outcome is null) = (ended_at is null)),
    constraint model_calls_ended_after_created check (ended_at is null or ended_at >= created_at),
    constraint model_calls_author_system_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint model_calls_author_is_system check (created_by_kind = 'system'),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint model_calls_production_outcome_unique
        unique (model_call_id, run_step_id, purpose, outcome, production_id, answer_altered),
    constraint model_calls_run_purpose_unique unique (model_call_id, run_id, purpose),
    constraint model_calls_outcome_unique unique (model_call_id, outcome)
);

create index model_calls_by_run on model_calls (run_id, created_at);

create index model_calls_by_root on model_calls (root_run_id);

create index model_calls_by_root_version on model_calls (root_version_id) where root_version_id is not null;

-- What a start finds still out, and records as a call nothing came back for.
create index model_calls_unended_by_created on model_calls (created_at) where outcome is null;

create unique index model_calls_one_unended_per_step
    on model_calls (run_step_id) where outcome is null and run_step_id is not null;

create table model_call_turnaways (
    model_call_turnaway_id uuid         not null default uuidv7(),
    model_call_id          uuid         not null,
    said                   text,
    -- Set where what the model said was cut to its bound; the part cut off is kept nowhere.
    said_truncated         boolean      not null default false,
    created_at             timestamptz  not null default now(),
    -- Set once the call was sent again after this turnaway, in the transaction that let it be.
    resent_at              timestamptz,
    created_by             uuid         not null,
    created_by_kind        subject_kind not null default 'system',

    constraint model_call_turnaways_pk primary key (model_call_turnaway_id),
    constraint model_call_turnaways_call_fk foreign key (model_call_id) references model_calls (model_call_id),
    constraint model_call_turnaways_author_system_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint model_call_turnaways_author_is_system check (created_by_kind = 'system'),
    -- Prose, for the reason reference_list_versions_note_visible gives.
    constraint model_call_turnaways_said_visible check (
        said is null or (length(said) > 0 and said !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]')),
    constraint model_call_turnaways_said_bounded check (length(said) <= 2048),
    constraint model_call_turnaways_truncated_only_for_said check (not said_truncated or said is not null),
    constraint model_call_turnaways_resent_after_turned_away check (resent_at >= created_at)
);

create index model_call_turnaways_by_call on model_call_turnaways (model_call_id, created_at);

-- ---------------------------------------------------------------- help

-- Only a question that was sent: one too long for the helper's model, or asked at a ceiling, never was.
create table run_help_exchanges (
    run_help_exchange_id uuid               not null default uuidv7(),
    run_id               uuid               not null,
    -- Carried so that a production drafted from it is held to the tree it was asked in.
    root_run_id          uuid               not null,
    question             text               not null,
    model_call_id        uuid               not null,
    -- Carried only to reach model_calls_run_purpose_unique, so the call is one to the helper, about this run.
    model_call_purpose   model_call_purpose not null default 'help',
    -- Carried once the call has ended, so that there is an answer only where the call came back.
    model_call_outcome   model_call_outcome,
    -- Unwrapped once from the call's answer, which may hold characters this refuses.
    answer               text,
    answered             boolean            generated always as (answer is not null) stored,
    created_at           timestamptz        not null default now(),
    created_by           uuid               not null,
    created_by_kind      subject_kind       not null default 'person',

    constraint run_help_exchanges_pk primary key (run_help_exchange_id),
    constraint run_help_exchanges_run_fk foreign key (run_id, root_run_id) references runs (run_id, root_run_id),
    constraint run_help_exchanges_model_call_fk foreign key (model_call_id, run_id, model_call_purpose)
        references model_calls (model_call_id, run_id, purpose),
    constraint run_help_exchanges_model_call_is_help check (model_call_purpose = 'help'),
    constraint run_help_exchanges_model_call_unique unique (model_call_id),
    constraint run_help_exchanges_model_call_outcome_fk foreign key (model_call_id, model_call_outcome)
        references model_calls (model_call_id, outcome),
    constraint run_help_exchanges_answer_only_for_came_back
        check (answer is null or coalesce(model_call_outcome = 'came_back', false)),
    -- Prose, for the reason reference_list_versions_note_visible gives.
    constraint run_help_exchanges_question_visible check (
        length(question) > 0 and question !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]'),
    constraint run_help_exchanges_question_bounded check (length(question) <= 2048),
    constraint run_help_exchanges_answer_visible check (
        answer is null or (length(answer) > 0 and answer !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]')),
    constraint run_help_exchanges_answer_bounded check (length(answer) <= 8388608),
    constraint run_help_exchanges_author_person_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint run_help_exchanges_author_is_person check (created_by_kind = 'person'),
    -- A foreign key target, at the cost subjects_kind_unique gives.
    constraint run_help_exchanges_root_answered_unique unique (run_help_exchange_id, root_run_id, answered)
);

create index run_help_exchanges_by_run on run_help_exchanges (run_id, created_at);

-- ---------------------------------------------------------------- tries

-- One try, written when it is asked for, whoever produces it, and ended when its production is in or lost.
create table productions (
    production_id          uuid               not null default uuidv7(),
    run_step_id            uuid               not null,
    run_id                 uuid               not null,
    -- Carried so that a question to the helper it is drafted from, and a hold it is refused for, are in its tree.
    root_run_id            uuid               not null,
    -- Carried so that what the step runs, who it names to produce, whether a model reviews and how many tries it
    -- declares are read off this row.
    run_step_kind          run_step_kind      not null,
    step_producer          step_producer      not null,
    reviewed_by_model      boolean            not null,
    tries                  integer            not null,
    -- As the release declared when this try was asked for; no version holds it.
    may_run_again          boolean,
    pinned_version_id      uuid,
    try_number             integer            not null,
    producer               step_producer      not null,
    model_call_id          uuid,
    -- Always 'produce', only to match model_calls.purpose through productions_model_call_fk; unchecked while
    -- model_call_id is null, for the reason pool_members gives.
    model_call_purpose     model_call_purpose not null default 'produce',
    -- Carried so that a model's try ends as its call did.
    model_call_outcome     model_call_outcome,
    -- Carried so that a try whose answer is not kept as it came back did not fit; unchecked while model_call_id
    -- is null, for the reason pool_members gives.
    call_answer_altered    boolean            not null default false,
    run_help_exchange_id   uuid,
    -- Always true, only to match run_help_exchanges.answered through productions_help_exchange_fk; unchecked
    -- while run_help_exchange_id is null, for the reason pool_members gives.
    help_exchange_answered boolean            not null default true,
    explanation            text,
    lost_reason            try_lost_reason,
    -- Why a model's answer did not fit, found once as it came back and never read again from the answer.
    did_not_fit_reason     did_not_fit_reason,
    -- Why code went wrong where this system found it so, never in words: the page words it for its reader.
    code_error_reason      code_error_reason,
    -- The field that reason is about, its names from the first level joined by dots, which no name holds.
    code_error_path        text,
    -- A member nothing declares, as code wrote it, cleaned for one line, cut to 64 characters and one marking it.
    code_error_member      text,
    -- What reads the field that no longer matches: a later step of the version, or a field the workflow gives back.
    code_error_read_by_step   uuid,
    code_error_read_by_output text,
    -- What code said went wrong where it threw; where a model's call went wrong, what it said is on the call.
    lost_detail            text,
    -- Set where lost_detail was cut to its bound; the part cut off is kept nowhere.
    lost_detail_truncated  boolean            not null default false,
    -- What code gave back that did not fit its declaration, as it came back, and so held to no characters.
    returned_by_code       text,
    created_at             timestamptz        not null default now(),
    -- Who asked for the try: the system, or a person asking again or answering it here.
    created_by             uuid               not null,
    created_by_kind        subject_kind       not null default 'system',
    ended_at               timestamptz,
    ended_by               uuid,
    -- Unchecked while ended_by is null, for the reason pool_members gives.
    ended_by_kind          subject_kind       not null default 'system',
    yielded                boolean            generated always as (
        ended_at is not null and lost_reason is null) stored,

    constraint productions_pk primary key (production_id),
    constraint productions_step_fk
        foreign key (run_id, run_step_id, run_step_kind, step_producer, reviewed_by_model, tries)
        references run_steps (run_id, run_step_id, kind, producer, reviewed_by_model, tries),
    constraint productions_run_fk foreign key (run_id, root_run_id) references runs (run_id, root_run_id),
    constraint productions_pinned_fk foreign key (run_step_id, pinned_version_id)
        references run_steps (run_step_id, pinned_version_id),
    -- For the reason workflow_steps_pinned_together gives: it keeps productions_pinned_fk awake.
    constraint productions_pinned_exactly_for_question
        check ((run_step_kind = 'question') = (pinned_version_id is not null)),
    constraint productions_try_number_unique unique (run_step_id, try_number),
    constraint productions_try_number_positive check (try_number >= 1),
    constraint productions_producer_is_step_producer_or_person
        check (producer = step_producer or producer = 'person'),
    constraint productions_beyond_tries_only_for_person check (try_number <= tries or created_by_kind = 'person'),
    constraint productions_code_again_only_for_may_run_again
        check (try_number = 1 or producer <> 'code' or coalesce(may_run_again, false)),
    constraint productions_may_run_again_exactly_for_code_step
        check ((run_step_kind = 'code_step') = (may_run_again is not null)),
    constraint productions_model_call_fk
        foreign key
            (model_call_id, run_step_id, model_call_purpose, model_call_outcome, production_id, call_answer_altered)
        references model_calls (model_call_id, run_step_id, purpose, outcome, production_id, answer_altered),
    constraint productions_model_call_is_produce check (model_call_purpose = 'produce'),
    constraint productions_model_call_exactly_for_ended_model
        check ((model_call_id is not null) = (producer = 'model' and ended_at is not null)),
    constraint productions_model_call_outcome_together
        check ((model_call_id is null) = (model_call_outcome is null)),
    -- Coalesced: a comparison with a null lost_reason is null, and a check that is null passes.
    constraint productions_lost_as_call_ended check (model_call_id is null or case model_call_outcome
        when 'came_back' then coalesce(lost_reason = 'did_not_fit', true)
        when 'errored' then coalesce(lost_reason = 'errored', false)
        when 'nothing_came_back' then coalesce(lost_reason = 'nothing_came_back', false)
        else false end),
    constraint productions_altered_answer_did_not_fit
        check (not call_answer_altered or coalesce(lost_reason = 'did_not_fit', false)),
    constraint productions_did_not_fit_reason_exactly_for_model_did_not_fit
        check ((did_not_fit_reason is not null) = coalesce(lost_reason = 'did_not_fit' and producer = 'model', false)),
    -- One way only: an answer not kept as it came may still carry a reason found before that one.
    constraint productions_not_kept_only_when_altered
        check (did_not_fit_reason is distinct from 'not_kept_as_it_came' or call_answer_altered),
    constraint productions_model_call_unique unique (model_call_id),
    constraint productions_help_exchange_fk
        foreign key (run_help_exchange_id, root_run_id, help_exchange_answered)
        references run_help_exchanges (run_help_exchange_id, root_run_id, answered),
    constraint productions_help_exchange_answered check (help_exchange_answered),
    constraint productions_help_exchange_only_for_person
        check (run_help_exchange_id is null or producer = 'person'),
    constraint productions_author_kind_fk foreign key (created_by, created_by_kind)
        references subjects (subject_id, kind),
    constraint productions_author_is_person_or_system check (created_by_kind in ('person', 'system')),
    constraint productions_ender_kind_fk foreign key (ended_by, ended_by_kind) references subjects (subject_id, kind),
    constraint productions_ender_is_person_or_system check (ended_by_kind in ('person', 'system')),
    constraint productions_ender_is_person_exactly_for_person_producer
        check (ended_by is null or (producer = 'person') = (ended_by_kind = 'person')),
    constraint productions_ended_together check ((ended_at is null) = (ended_by is null)),
    constraint productions_ended_after_created check (ended_at is null or ended_at >= created_at),
    constraint productions_explanation_exactly_for_ended_by_person
        check ((ended_by is not null and producer = 'person') = (explanation is not null)),
    -- Prose, for the reason reference_list_versions_note_visible gives.
    constraint productions_explanation_visible check (
        explanation is null
            or (length(explanation) > 0 and explanation !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]')),
    constraint productions_explanation_bounded check (length(explanation) <= 2048),
    constraint productions_lost_only_for_model_or_code check (lost_reason is null or producer in ('model', 'code')),
    constraint productions_lost_only_for_ended check (lost_reason is null or ended_at is not null),
    -- Whatever code gives back that does not fit is code gone wrong, so it ends errored.
    constraint productions_did_not_fit_only_for_model
        check (lost_reason is distinct from 'did_not_fit' or producer = 'model'),
    constraint productions_code_error_reason_only_for_code_errored
        check (code_error_reason is null or coalesce(lost_reason = 'errored' and producer = 'code', false)),
    -- Code gone wrong says why once: as this system's reason, or where it threw, in its own words.
    constraint productions_lost_detail_exactly_where_code_threw check (
        coalesce(lost_reason = 'errored' and producer = 'code' and code_error_reason is null, false)
            = (lost_detail is not null)),
    constraint productions_code_error_path_as_its_reason_names check (case
        when code_error_reason is null then code_error_path is null
        when code_error_reason = 'not_declared' then true
        when code_error_reason in ('gave_nothing', 'failed_on_this_side', 'said_nothing') then code_error_path is null
        else code_error_path is not null end),
    constraint productions_code_error_path_shape
        check (code_error_path ~ '^[a-z][a-z0-9_]{0,62}(\.[a-z][a-z0-9_]{0,62})*$'),
    constraint productions_code_error_path_bounded check (length(code_error_path) <= 1023),
    constraint productions_code_error_member_exactly_for_not_declared
        check ((code_error_member is not null) = coalesce(code_error_reason = 'not_declared', false)),
    -- Empty where the code named a member so, or with nothing a line can hold.
    constraint productions_code_error_member_one_line
        check (code_error_member !~ '[\u0000-\u001f\u007f-\u009f]'),
    constraint productions_code_error_member_bounded check (length(code_error_member) <= 65),
    constraint productions_code_error_read_by_exactly_for_gives_otherwise check (case
        when coalesce(code_error_reason = 'gives_otherwise', false)
            then (code_error_read_by_step is null) <> (code_error_read_by_output is null)
        else code_error_read_by_step is null and code_error_read_by_output is null end),
    -- Any step, as no key reaches the version the try's step is in; the engine names one of the run's own.
    constraint productions_code_error_read_by_step_fk
        foreign key (code_error_read_by_step) references workflow_steps (workflow_step_id),
    constraint productions_code_error_read_by_output_shape
        check (code_error_read_by_output ~ '^[a-z][a-z0-9_]{0,62}(\.[a-z][a-z0-9_]{0,62})*$'),
    constraint productions_code_error_read_by_output_bounded check (length(code_error_read_by_output) <= 1023),
    -- Prose, for the reason reference_list_versions_note_visible gives.
    constraint productions_lost_detail_visible check (
        lost_detail is null
            or (length(lost_detail) > 0 and lost_detail !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]')),
    constraint productions_lost_detail_bounded check (length(lost_detail) <= 2048),
    constraint productions_truncated_only_for_lost_detail
        check (not lost_detail_truncated or lost_detail is not null),
    -- One way only: code that threw gave nothing back to keep.
    constraint productions_returned_by_code_only_for_code_errored
        check (returned_by_code is null or coalesce(lost_reason = 'errored' and producer = 'code', false)),
    constraint productions_returned_by_code_bounded check (length(returned_by_code) <= 8388608),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint productions_yielded_unique unique (production_id, run_step_kind, producer, yielded),
    constraint productions_run_step_unique unique (production_id, run_id, run_step_id, run_step_kind),
    constraint productions_pinned_unique unique (production_id, pinned_version_id),
    constraint productions_step_producer_unique unique (production_id, run_step_id, producer),
    constraint productions_step_yielded_unique unique (production_id, run_step_id, yielded),
    constraint productions_step_reviewed_yielded_unique
        unique (production_id, run_step_id, reviewed_by_model, yielded),
    constraint productions_ender_yielded_unique
        unique (production_id, run_step_id, root_run_id, ended_by, reviewed_by_model, yielded),
    constraint productions_root_unique unique (production_id, root_run_id)
);

create unique index productions_one_unended on productions (run_step_id) where ended_at is null;

-- A draft's step deleted looks through this for a try naming it as what read it, which none of a draft's can.
create index productions_by_code_error_read_by_step on productions (code_error_read_by_step)
    where code_error_read_by_step is not null;

create index productions_by_run on productions (run_id, created_at);

-- Answering a question put to the helper updates the key productions_help_exchange_fk names, which then looks up
-- the rows naming it here.
create index productions_by_help_exchange on productions (run_help_exchange_id) where run_help_exchange_id is not null;

alter table run_step_send_attempts add constraint run_step_send_attempts_produced_fk
    foreign key (produced_production_id, run_step_id, production_producer)
    references productions (production_id, run_step_id, producer);

alter table run_step_send_attempts add constraint run_step_send_attempts_reviewed_fk
    foreign key (reviewed_production_id, run_step_id, production_yielded)
    references productions (production_id, run_step_id, yielded);

alter table run_step_failures add constraint run_step_failures_produced_fk
    foreign key (produced_production_id, run_step_id, production_producer)
    references productions (production_id, run_step_id, producer);

alter table run_step_failures add constraint run_step_failures_reviewed_fk
    foreign key (reviewed_production_id, run_step_id, production_reviewed, production_yielded)
    references productions (production_id, run_step_id, reviewed_by_model, yielded);

-- Ending a try updates the key run_step_failures_reviewed_fk names, which then looks up the rows naming it here.
create index run_step_failures_by_reviewed_production
    on run_step_failures (reviewed_production_id) where reviewed_production_id is not null;

-- A code step's declaration is the release's and is held nowhere here, so its value names its field as the
-- release does, and carries the standing the release declared for it.
create table production_values (
    production_value_id  uuid           not null default uuidv7(),
    production_id        uuid           not null,
    -- Carried so that a question's value names a field and a code step's a field name, and only a model's says
    -- how sure it is.
    run_step_kind        run_step_kind  not null,
    producer             step_producer  not null,
    -- Always true, only to match productions.yielded through production_values_production_fk.
    production_yielded   boolean        not null default true,
    -- Carried so that a question's value is of a field of the version its try pinned.
    pinned_version_id    uuid,
    declaration_field_id uuid,
    field_name           text,
    field_standing       field_standing not null,
    standing_threshold   integer,
    -- Nothing is a null here and never a JSON null, so there is one way to say it.
    value                jsonb,
    confidence           integer,
    needs_review         boolean        generated always as (case field_standing
        when 'always' then false
        when 'never' then true
        else confidence is null or confidence < standing_threshold end) stored,

    constraint production_values_pk primary key (production_value_id),
    constraint production_values_production_fk
        foreign key (production_id, run_step_kind, producer, production_yielded)
        references productions (production_id, run_step_kind, producer, yielded),
    constraint production_values_production_yielded check (production_yielded),
    constraint production_values_pinned_fk foreign key (production_id, pinned_version_id)
        references productions (production_id, pinned_version_id),
    -- For the reason workflow_steps_pinned_together gives: it keeps production_values_pinned_fk awake.
    constraint production_values_pinned_exactly_for_question
        check ((run_step_kind = 'question') = (pinned_version_id is not null)),
    -- For the same reason: it keeps production_values_field_fk awake.
    constraint production_values_field_exactly_for_question
        check ((run_step_kind = 'question') = (declaration_field_id is not null)),
    constraint production_values_field_name_exactly_for_code_step
        check ((run_step_kind = 'code_step') = (field_name is not null)),
    -- Only a field nothing holds, given back by a question, has a standing: this names one of the pinned version.
    constraint production_values_field_fk foreign key (declaration_field_id, pinned_version_id, field_standing)
        references declaration_fields (declaration_field_id, owner_id, standing),
    -- Apart from the standing, so that a null threshold switches off this key alone.
    constraint production_values_threshold_fk foreign key (declaration_field_id, standing_threshold)
        references declaration_fields (declaration_field_id, standing_threshold),
    constraint production_values_threshold_exactly_for_above_confidence
        check ((field_standing = 'above_confidence') = (standing_threshold is not null)),
    constraint production_values_threshold_range check (standing_threshold between 1 and 100),
    constraint production_values_field_name_shape check (field_name ~ '^[a-z][a-z0-9_]{0,62}$'),
    constraint production_values_field_unique unique (production_id, declaration_field_id),
    constraint production_values_field_name_unique unique (production_id, field_name),
    constraint production_values_value_not_json_null check (jsonb_typeof(value) <> 'null'),
    -- For the reason runs_started_with_bounded gives: at most 8388608 characters written out, each kept as at most six.
    constraint production_values_value_bounded check (length(value::text) <= 50331648),
    -- A model that leaves out a confidence it was asked for gave an answer that did not fit; a person and code
    -- have none to offer.
    constraint production_values_confidence_exactly_for_model_above_confidence
        check ((confidence is not null) = (producer = 'model' and field_standing = 'above_confidence')),
    constraint production_values_confidence_range check (confidence between 0 and 100),
    -- Foreign key targets, at the cost subjects_kind_unique gives.
    constraint production_values_needs_review_unique unique (production_value_id, production_id, needs_review),
    constraint production_values_production_unique unique (production_value_id, production_id)
);

create index production_values_by_field
    on production_values (declaration_field_id) where declaration_field_id is not null;

-- What waits on a review is read from here, less what a review has decided already.
create index production_values_needing_review_by_production on production_values (production_id) where needs_review;

-- ---------------------------------------------------------------- what went in

-- A binding's value as a try, or a run a route started by it, used it. Written in the transaction writing that owner,
-- locking the run at the root, then the step, then the try.
create table production_inputs (
    production_input_id        uuid          not null default uuidv7(),
    production_id              uuid,
    branch_run_id              uuid,
    -- Carried so that the binding is one of the step the value went into, in the run that step is in.
    run_id                     uuid          not null,
    -- Carried so that a value this run's input carried is one made in its own tree.
    root_run_id                uuid          not null,
    run_step_id                uuid          not null,
    run_step_kind              run_step_kind not null,
    workflow_step_id           uuid          not null,
    binding_id                 uuid          not null,
    -- Generated, so that only a run a route started records a discriminator, and a binding reading a step
    -- cannot be recorded without naming it; a binding reading a constant names no value.
    binding_discriminates      boolean       generated always as (branch_run_id is not null) stored,
    binding_reads_a_step       boolean       generated always as (source_workflow_step_id is not null) stored,
    binding_reads_a_constant   boolean       not null,
    source_workflow_step_id    uuid,
    source_run_step_id         uuid,
    source_run_step_kind       run_step_kind,
    -- Tracing up through an input and tracing down through what a workflow or route gave back both stop at the
    -- first value a step made. Set only where the step read makes values itself, to hold the value to it.
    source_run_id              uuid          generated always as (
        case when source_run_step_kind in ('question', 'code_step') then run_id end) stored,
    source_production_id       uuid,
    -- The first value a step made, as far as the trace goes, or none at a constant or a start at the top. What
    -- stood when the owner was written: a value refused and made again is traced afresh, and this row stays.
    source_production_value_id uuid,
    created_at                 timestamptz   not null default now(),

    constraint production_inputs_pk primary key (production_input_id),
    constraint production_inputs_exactly_one_owner check (num_nonnulls(production_id, branch_run_id) = 1),
    constraint production_inputs_try_fk foreign key (production_id, run_id, run_step_id, run_step_kind)
        references productions (production_id, run_id, run_step_id, run_step_kind),
    constraint production_inputs_branch_run_fk
        foreign key (branch_run_id, run_id, run_step_id, run_step_kind, workflow_step_id)
        references runs (run_id, parent_run_id, parent_run_step_id, parent_run_step_kind, parent_workflow_step_id),
    constraint production_inputs_step_fk foreign key (run_id, run_step_id, run_step_kind, workflow_step_id)
        references run_steps (run_id, run_step_id, kind, workflow_step_id),
    constraint production_inputs_run_fk foreign key (run_id, root_run_id) references runs (run_id, root_run_id),
    constraint production_inputs_binding_fk foreign key
        (binding_id, workflow_step_id, binding_discriminates, binding_reads_a_step, binding_reads_a_constant)
        references bindings (binding_id, workflow_step_id, discriminates, reads_a_step, reads_a_constant),
    constraint production_inputs_try_binding_unique unique (production_id, binding_id),
    constraint production_inputs_branch_binding_unique unique (branch_run_id, binding_id),
    constraint production_inputs_source_step_fk foreign key (binding_id, source_workflow_step_id)
        references bindings (binding_id, source_step_id),
    -- For the reason workflow_steps_pinned_together gives: these two keep the keys to the source awake.
    constraint production_inputs_source_value_together
        check ((source_production_value_id is null) = (source_production_id is null)),
    constraint production_inputs_source_step_together check (
        (source_workflow_step_id is null) = (source_run_step_id is null)
            and (source_workflow_step_id is null) = (source_run_step_kind is null)),
    -- What a workflow or route gave back may be a constant, or an input no step made, and so name no value.
    constraint production_inputs_source_value_for_a_step
        check (source_run_id is null or source_production_value_id is not null),
    constraint production_inputs_no_source_value_for_a_constant
        check (not binding_reads_a_constant or source_production_value_id is null),
    -- A run at the top is started with its input, which no step made.
    constraint production_inputs_no_source_value_for_a_top_level_input
        check (run_id <> root_run_id or binding_reads_a_step or source_production_value_id is null),
    constraint production_inputs_source_value_fk foreign key (source_production_value_id, source_production_id)
        references production_values (production_value_id, production_id),
    -- A question's or code step's value is of a try of that step in this run; a null source_run_id disables it.
    constraint production_inputs_source_production_fk
        foreign key (source_production_id, source_run_id, source_run_step_id, source_run_step_kind)
        references productions (production_id, run_id, run_step_id, run_step_kind),
    constraint production_inputs_source_run_step_fk
        foreign key (run_id, source_run_step_id, source_run_step_kind, source_workflow_step_id)
        references run_steps (run_id, run_step_id, kind, workflow_step_id),
    -- One this run's input carried, or a workflow or route beneath gave back, is of this tree; which run of it made
    -- the value, no key here can say.
    constraint production_inputs_source_root_fk foreign key (source_production_id, root_run_id)
        references productions (production_id, root_run_id)
);

-- Editing a draft's binding updates the keys production_inputs_binding_fk and production_inputs_source_step_fk
-- name, which then look up the rows naming it here.
create index production_inputs_by_binding on production_inputs (binding_id);

-- Whether something has been made from a value is read from here.
create index production_inputs_by_source_value
    on production_inputs (source_production_value_id) where source_production_value_id is not null;

-- ---------------------------------------------------------------- reviews

-- One act deciding a production's values on review; or, naming the hold on length that prompted it,
-- refusing values that had stood already.
create table reviews (
    review_id           uuid                 not null default uuidv7(),
    production_id       uuid                 not null,
    -- Carried so that a model's call is one made about this production, a hold is one in its tree, and
    -- nobody reviews what they produced.
    run_step_id         uuid                 not null,
    root_run_id         uuid                 not null,
    production_ended_by uuid                 not null,
    reviewed_by_model   boolean              not null,
    -- Always true, only to match productions.yielded through reviews_production_fk.
    production_yielded  boolean              not null default true,
    model_call_id       uuid,
    -- Always 'review', only to match model_calls.purpose through reviews_model_call_fk; unchecked while
    -- model_call_id is null, for the reason pool_members gives.
    model_call_purpose  model_call_purpose   not null default 'review',
    -- Carried so that a model's review ends as its call did.
    model_call_outcome  model_call_outcome,
    -- For the reason productions.call_answer_altered gives.
    call_answer_altered boolean              not null default false,
    run_step_hold_id    uuid,
    hold_run_id         uuid,
    -- Always 'too_long', only to match run_step_holds.reason through reviews_hold_fk; unchecked while
    -- run_step_hold_id is null, for the reason pool_members gives.
    hold_reason         run_step_hold_reason not null default 'too_long',
    -- The attempt to review that sent nothing, too long for the model or not built, a person reviews in place of.
    too_long_attempt_id uuid,
    -- Always true, only to match run_step_send_attempts.too_long, which says nothing was sent, through
    -- reviews_too_long_attempt_fk; unchecked while too_long_attempt_id is null, for the reason pool_members gives.
    attempt_too_long    boolean              not null default true,
    for_length          boolean              generated always as (run_step_hold_id is not null) stored,
    lost_reason         try_lost_reason,
    -- For the reason productions.did_not_fit_reason gives.
    did_not_fit_reason  did_not_fit_reason,
    decided             boolean              generated always as (lost_reason is null) stored,
    created_at          timestamptz          not null default now(),
    created_by          uuid                 not null,
    created_by_kind     subject_kind         not null default 'person',

    constraint reviews_pk primary key (review_id),
    constraint reviews_production_fk foreign key
        (production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model, production_yielded)
        references productions (production_id, run_step_id, root_run_id, ended_by, reviewed_by_model, yielded),
    constraint reviews_production_yielded check (production_yielded),
    constraint reviews_model_call_fk
        foreign key
            (model_call_id, run_step_id, model_call_purpose, model_call_outcome, production_id, call_answer_altered)
        references model_calls (model_call_id, run_step_id, purpose, outcome, production_id, answer_altered),
    constraint reviews_model_call_is_review check (model_call_purpose = 'review'),
    constraint reviews_model_call_exactly_for_system
        check ((created_by_kind = 'system') = (model_call_id is not null)),
    constraint reviews_model_call_outcome_together check ((model_call_id is null) = (model_call_outcome is null)),
    -- Coalesced for the reason productions_lost_as_call_ended gives.
    constraint reviews_lost_as_call_ended check (model_call_id is null or case model_call_outcome
        when 'came_back' then coalesce(lost_reason = 'did_not_fit', true)
        when 'errored' then coalesce(lost_reason = 'errored', false)
        when 'nothing_came_back' then coalesce(lost_reason = 'nothing_came_back', false)
        else false end),
    constraint reviews_altered_answer_did_not_fit
        check (not call_answer_altered or coalesce(lost_reason = 'did_not_fit', false)),
    constraint reviews_did_not_fit_reason_exactly_for_did_not_fit
        check ((did_not_fit_reason is not null) = coalesce(lost_reason = 'did_not_fit', false)),
    -- One way only, for the reason productions_not_kept_only_when_altered gives.
    constraint reviews_not_kept_only_when_altered
        check (did_not_fit_reason is distinct from 'not_kept_as_it_came' or call_answer_altered),
    constraint reviews_model_call_unique unique (model_call_id),
    constraint reviews_author_kind_fk foreign key (created_by, created_by_kind) references subjects (subject_id, kind),
    constraint reviews_author_is_person_or_system check (created_by_kind in ('person', 'system')),
    -- The model a step names reviews on it; refusing for length is a person's, whoever reviews on the step, and
    -- so is reviewing in the model's place where nothing could be sent to it, too long or not built.
    constraint reviews_author_is_system_exactly_for_model_on_review check ((created_by_kind = 'system')
        = (reviewed_by_model and run_step_hold_id is null and too_long_attempt_id is null)),
    -- A model may review what it produced; a person only refuses it for length, which vouches for nothing.
    constraint reviews_reviewer_is_not_producer
        check (created_by_kind <> 'person' or run_step_hold_id is not null or created_by <> production_ended_by),
    constraint reviews_hold_fk foreign key (run_step_hold_id, hold_run_id, hold_reason)
        references run_step_holds (run_step_hold_id, run_id, reason),
    constraint reviews_hold_run_fk foreign key (hold_run_id, root_run_id) references runs (run_id, root_run_id),
    -- For the reason workflow_steps_pinned_together gives: it keeps reviews_hold_fk and reviews_hold_run_fk awake.
    constraint reviews_hold_together check ((run_step_hold_id is null) = (hold_run_id is null)),
    constraint reviews_hold_is_too_long check (hold_reason = 'too_long'),
    constraint reviews_too_long_attempt_fk foreign key (too_long_attempt_id, production_id, attempt_too_long)
        references run_step_send_attempts (run_step_send_attempt_id, reviewed_production_id, too_long),
    constraint reviews_attempt_too_long check (attempt_too_long),
    constraint reviews_too_long_attempt_only_for_on_review
        check (run_step_hold_id is null or too_long_attempt_id is null),
    constraint reviews_lost_only_for_system check (lost_reason is null or created_by_kind = 'system'),
    -- A foreign key target, at the cost subjects_kind_unique gives.
    constraint reviews_decided_unique unique (review_id, production_id, for_length, decided)
);

-- A review that did not fit, went wrong or never came back spent the try as one that was taken would have.
create unique index reviews_one_on_review on reviews (production_id) where not for_length;

create index reviews_by_production on reviews (production_id);

create table review_decisions (
    review_id            uuid           not null,
    production_value_id  uuid           not null,
    -- Carried so that the value is one of the production reviewed, and one decided on review needs it.
    production_id        uuid           not null,
    for_length           boolean        not null,
    -- Always true, only to match reviews.decided through review_decisions_review_fk.
    review_decided       boolean        not null default true,
    value_needs_review   boolean        not null,
    outcome              review_outcome not null,
    explanation          text,
    -- A value refused for length stood, so where it needed a review this names the one that assured it.
    assured_in_review_id uuid,
    -- Always 'assured', only to match review_decisions.outcome through review_decisions_assurance_fk; unchecked
    -- while assured_in_review_id is null, for the reason pool_members gives.
    assurance_outcome    review_outcome not null default 'assured',

    constraint review_decisions_pk primary key (review_id, production_value_id),
    constraint review_decisions_review_fk foreign key (review_id, production_id, for_length, review_decided)
        references reviews (review_id, production_id, for_length, decided),
    constraint review_decisions_review_decided check (review_decided),
    constraint review_decisions_value_fk foreign key (production_value_id, production_id, value_needs_review)
        references production_values (production_value_id, production_id, needs_review),
    constraint review_decisions_on_review_only_for_needing_review check (for_length or value_needs_review),
    constraint review_decisions_for_length_only_for_refused check (not for_length or outcome = 'refused'),
    constraint review_decisions_explanation_exactly_for_refused
        check ((outcome = 'refused') = (explanation is not null)),
    -- Prose, for the reason reference_list_versions_note_visible gives.
    constraint review_decisions_explanation_visible check (
        explanation is null
            or (length(explanation) > 0 and explanation !~ '[\u0000-\u0008\u000b-\u001f\u007f-\u009f]')),
    constraint review_decisions_explanation_bounded check (length(explanation) <= 2048),
    constraint review_decisions_assurance_fk
        foreign key (production_value_id, assured_in_review_id, assurance_outcome)
        references review_decisions (production_value_id, review_id, outcome),
    constraint review_decisions_assurance_is_assured check (assurance_outcome = 'assured'),
    constraint review_decisions_assurance_exactly_for_length_needing_review
        check ((for_length and value_needs_review) = (assured_in_review_id is not null)),
    -- A foreign key target, at the cost subjects_kind_unique gives.
    constraint review_decisions_outcome_unique unique (production_value_id, review_id, outcome)
);

-- One decision on review per value follows from reviews_one_on_review and review_decisions_pk; a refusal is held
-- once whichever way, since a value refused no longer stands to be refused.
create unique index review_decisions_one_refusal
    on review_decisions (production_value_id) where outcome = 'refused';
