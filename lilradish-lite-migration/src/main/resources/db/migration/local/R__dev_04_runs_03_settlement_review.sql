-- Targeted ON CONFLICT does not skip collisions with the run's unique number.
-- DB-SPECIFIC: jsonb_build_object(), timestamptz '2000-01-10 00:00:00+00', interval literals and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This runs seed group supplies PostgreSQL native subject_kind enum inputs.
-- DB-SPECIFIC: PostgreSQL dollar quoting delimits the claim string in this seed group.
insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                  started_with, created_at, created_by, created_by_kind) values
    ('0000000f-0000-4000-8000-000000000116', '00000002-0000-4000-8000-000000000101', 16,
     'Imaginary Mooncrate: a person drafts the settlement',
     '00000006-0000-4000-8000-000000000107', '00000007-0000-4000-8000-000000000110',
     '0000000f-0000-4000-8000-000000000116', 0,
     jsonb_build_object('complaint', $claim$## Imaginary Mooncrate claim
Mira Cloudmere asks about invoice `DEMO-INVOICE-216`.
**12 pretend credits** were reserved; the signed receipt is still missing.
[Invented practice guide](https://example.invalid/settlement)$claim$),
     timestamptz '2000-01-10 00:00:00+00' - interval '20 minutes', '00000001-0000-4000-8000-000000000102', 'person')
on conflict (run_id) do nothing;

-- DB-SPECIFIC: timestamptz '2000-01-10 00:00:00+00', interval literals and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_steps seed group supplies PostgreSQL native step_kind, entry_kind, step_producer, subject_kind enum inputs.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                       pinned_kind, producer, reviewed_by_model, tries, created_at, created_by, created_by_kind) values
    ('00000010-0000-4000-8000-000000001601', '0000000f-0000-4000-8000-000000000116',
     '00000007-0000-4000-8000-000000000110', '0000000c-0000-4000-8000-000000000106', 'entry',
     '00000007-0000-4000-8000-000000000111', 'question', 'person', true, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '20 minutes', '00000000-0000-4000-8000-000000000001', 'system')
on conflict (run_step_id) do nothing;

-- DB-SPECIFIC: uuid casts, interval literals and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This productions seed group supplies PostgreSQL native run_step_kind, step_producer, subject_kind enum inputs.
insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_at, created_by,
                         created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
select drafted.id, ran.run_step_id, ran.run_id, ran.run_id, 'question', 'person', true, 2,
       ran.pinned_version_id, drafted.number, 'person', ran.created_at + drafted.elapsed,
       '00000001-0000-4000-8000-000000000102', 'person', ran.created_at + drafted.elapsed + interval '1 minute',
       '00000001-0000-4000-8000-000000000103', 'person', 'I copied only the invented claim evidence and left the missing receipt visible.'
  from (values (cast('00000011-0000-4000-8000-000000001601' as uuid), 1, interval '1 minute'),
               ('00000011-0000-4000-8000-000000001602', 2, interval '5 minutes')) as drafted(id, number, elapsed)
  join run_steps ran on ran.run_step_id = '00000010-0000-4000-8000-000000001601'
on conflict (production_id) do nothing;

-- DB-SPECIFIC: uuid casts, to_json(), jsonb casts and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL native run_step_kind, step_producer, field_standing enum inputs.
-- DB-SPECIFIC: PostgreSQL dollar quoting delimits the note strings in this seed group.
insert into production_values (production_value_id, production_id, run_step_kind, producer, pinned_version_id,
                               declaration_field_id, field_standing, value)
select drafted.id, try.production_id, 'question', 'person', try.pinned_version_id, '0000000b-0000-4000-8000-000000000130', 'never', cast(to_json(drafted.body) as jsonb)
  from (values (cast('00000012-0000-4000-8000-000000001601' as uuid), cast('00000011-0000-4000-8000-000000001601' as uuid), '**Imaginary settlement: receipt missing**'),
               ('00000012-0000-4000-8000-000000001602', '00000011-0000-4000-8000-000000001602', $note$## Imaginary Mooncrate settlement
Mira Cloudmere supplied invoice `DEMO-INVOICE-216`.

- **Keep** the payment trace.
- Ask for the signed receipt before releasing **12 pretend credits**.

[Invented practice guide](https://example.invalid/settlement)$note$)) as drafted(id, production_id, body)
  join productions try on try.production_id = drafted.production_id
on conflict (production_value_id) do nothing;

-- DB-SPECIFIC: uuid casts and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_inputs seed group supplies PostgreSQL native run_step_kind enum inputs.
insert into production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id, run_step_kind,
                               workflow_step_id, binding_id, binding_reads_a_constant, created_at)
select input.id, try.production_id, try.run_id, try.root_run_id, try.run_step_id, 'question',
       '0000000c-0000-4000-8000-000000000106', '0000000e-0000-4000-8000-000000000114', false, try.created_at
  from (values (cast('00000013-0000-4000-8000-000000001601' as uuid), cast('00000011-0000-4000-8000-000000001601' as uuid)),
               ('00000013-0000-4000-8000-000000001602', '00000011-0000-4000-8000-000000001602')) as input(id, production_id)
  join productions try on try.production_id = input.production_id
on conflict (production_input_id) do nothing;

-- DB-SPECIFIC: uuid casts, json_build_object(), json_build_array(), json_strip_nulls(), text casts, interval literals and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_step_send_attempts seed group supplies PostgreSQL native run_step_kind, model_call_purpose, subject_kind enum inputs.
insert into run_step_send_attempts (run_step_send_attempt_id, run_step_id, run_id, run_step_kind, workflow_step_id,
                                    purpose, model, mode, production_id, payload, created_at, created_by, created_by_kind)
select sent.id, try.run_step_id, try.run_id, 'question', '0000000c-0000-4000-8000-000000000106',
       'review', 'sample_model', 'ordinary', try.production_id,
       cast(json_strip_nulls(json_build_object('envelope_version', 5,
            'instruction', 'Summarise the invented settlement evidence, identify missing proof and retain every fictional identifier.',
            'takes', run.started_with,
            'refused', case when try.try_number = 2 then json_build_object(
                'values', json_build_object('summary', '**Imaginary settlement: receipt missing**'),
                'words', json_build_object('summary', '**Missing signed receipt.**')) end,
            'answer', json_build_object('summary', value.value),
            'deciding', json_build_array('summary'))) as text),
       try.ended_at + interval '1 second', '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000014-0000-4000-8000-000000001601' as uuid), cast('00000011-0000-4000-8000-000000001601' as uuid)),
               ('00000014-0000-4000-8000-000000001602', '00000011-0000-4000-8000-000000001602')) as sent(id, production_id)
  join productions try on try.production_id = sent.production_id join production_values value on value.production_id = try.production_id
  join runs run on run.run_id = try.run_id
on conflict (run_step_send_attempt_id) do nothing;

-- DB-SPECIFIC: uuid casts, interval literals, json_build_object() and text casts, and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This model_calls seed group supplies PostgreSQL native model_call_purpose, model_call_outcome, subject_kind enum inputs.
-- DB-SPECIFIC: PostgreSQL dollar quoting delimits the review string in this seed group.
insert into model_calls (model_call_id, run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id, production_id,
                         model, mode, envelope_version, sent_count, came_back_count, counted_by_model, outcome, answer, ended_at, created_at, created_by, created_by_kind)
select called.id, sent.run_id, sent.run_id, 'review', sent.run_step_send_attempt_id, sent.run_step_id, sent.production_id,
       'sample_model', 'ordinary', 5, 16, 8, true, 'came_back',
       cast(json_build_object('decisions', json_build_object('summary', json_build_object('outcome', 'refused', 'words', called.words))) as text),
       sent.created_at + interval '1 second', sent.created_at, '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000015-0000-4000-8000-000000001601' as uuid), cast('00000014-0000-4000-8000-000000001601' as uuid), '**Missing signed receipt.**'),
               ('00000015-0000-4000-8000-000000001602', '00000014-0000-4000-8000-000000001602', $review$## Missing payment proof
The **invented payment trace** does not establish a signed receipt.
Ask Mira Cloudmere for document `DEMO-RECEIPT-216`.
[Invented practice guide](https://example.invalid/settlement)$review$)) as called(id, attempt_id, words)
  join run_step_send_attempts sent on sent.run_step_send_attempt_id = called.attempt_id
on conflict (model_call_id) do nothing;

-- DB-SPECIFIC: uuid casts and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This reviews seed group supplies PostgreSQL native model_call_outcome, subject_kind enum inputs.
insert into reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                     model_call_id, model_call_outcome, created_at, created_by, created_by_kind)
select reviewed.id, try.production_id, try.run_step_id, try.root_run_id, try.ended_by, true,
       call.model_call_id, call.outcome, call.ended_at, '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000016-0000-4000-8000-000000001601' as uuid), cast('00000015-0000-4000-8000-000000001601' as uuid)),
               ('00000016-0000-4000-8000-000000001602', '00000015-0000-4000-8000-000000001602')) as reviewed(id, call_id)
  join model_calls call on call.model_call_id = reviewed.call_id join productions try on try.production_id = call.production_id
on conflict (review_id) do nothing;

-- DB-SPECIFIC: jsonb casts, -> and ->>, and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This review_decisions seed group supplies PostgreSQL native review_outcome enum inputs.
insert into review_decisions (review_id, production_value_id, production_id, for_length, value_needs_review, outcome, explanation)
select review.review_id, value.production_value_id, value.production_id, false, true, 'refused',
       cast(call.answer as jsonb) -> 'decisions' -> 'summary' ->> 'words'
  from reviews review join model_calls call on call.model_call_id = review.model_call_id
  join production_values value on value.production_id = review.production_id
 where review.review_id in ('00000016-0000-4000-8000-000000001601', '00000016-0000-4000-8000-000000001602')
on conflict (review_id, production_value_id) do nothing;
