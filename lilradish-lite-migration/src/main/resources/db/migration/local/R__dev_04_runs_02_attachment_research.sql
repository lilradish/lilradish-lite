-- DB-SPECIFIC: PostgreSQL data-modifying WITH, uuid casts, convert_to(), octet_length() and ON CONFLICT.
with fixture(file_id, original_name, content) as (
 select cast(file_id as uuid), original_name, convert_to(words, 'UTF8')
 from (values
 ('00000018-0000-4000-8000-000000000113', 'Mooncrate ready claim.txt', 'Invented Mooncrate claim DEMO-CLAIM-17: a purple parcel arrived damaged. Keep its box and packing.'),
 ('00000018-0000-4000-8000-000000000213', 'Mooncrate ready evidence.md', '# Invented evidence note
Mira Cloudmere asks for the box label before the fictional claim can be settled.'),
 ('00000018-0000-4000-8000-000000000114', 'Mooncrate waiting claim.txt', 'Invented Mooncrate claim DEMO-CLAIM-17: a purple parcel arrived damaged. Keep its box and packing.'),
 ('00000018-0000-4000-8000-000000000214', 'Mooncrate waiting evidence.md', '# Invented evidence note
Mira Cloudmere asks for the box label before the fictional claim can be settled.'),
 ('00000018-0000-4000-8000-000000000115', 'Mooncrate complete claim.txt', 'Invented Mooncrate claim DEMO-CLAIM-17: a purple parcel arrived damaged. Keep its box and packing.'),
 ('00000018-0000-4000-8000-000000000215', 'Mooncrate complete evidence.md', '# Invented evidence note
Mira Cloudmere asks for the box label before the fictional claim can be settled.')
 ) as files(file_id, original_name, words)
), metadata as (
 insert into stored_files (file_id, group_id, original_name, byte_size)
 select file_id, '00000002-0000-4000-8000-000000000101', original_name, octet_length(content) from fixture
 on conflict (file_id) do nothing
)
insert into file_contents (file_id, content)
select file_id, content from fixture on conflict (file_id) do nothing;

-- Targeted ON CONFLICT does not skip collisions with the run's unique number.
-- DB-SPECIFIC: uuid, interval literals, jsonb_build_object(), on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This runs seed group supplies PostgreSQL native subject_kind enum inputs.
-- DB-SPECIFIC: PostgreSQL jsonb_build_array() constructs the attachment array in this seed group.
insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                  started_with, created_at, created_by, created_by_kind)
select started.run_id, '00000002-0000-4000-8000-000000000101', started.number, started.name, '00000006-0000-4000-8000-000000000106',
       '00000007-0000-4000-8000-000000000109', started.run_id, 0,
       jsonb_build_object('topic', started.topic, 'attachment', jsonb_build_array(
           jsonb_build_object('id', file.file_id, 'name', file.original_name, 'size', file.byte_size),
           jsonb_build_object('id', second.file_id, 'name', second.original_name, 'size', second.byte_size))),
       timestamptz '2000-01-10 00:00:00+00' - started.age, '00000001-0000-4000-8000-000000000102', 'person'
  from (values (cast('0000000f-0000-4000-8000-000000000113' as uuid), 13, 'Mooncrate attachment ready',
                'Mooncrate attachment handling', cast('00000018-0000-4000-8000-000000000113' as uuid), interval '40 minutes'),
               ('0000000f-0000-4000-8000-000000000114', 14, 'Mooncrate research waiting',
                'Mooncrate evidence review', '00000018-0000-4000-8000-000000000114', interval '30 minutes'),
               ('0000000f-0000-4000-8000-000000000115', 15, 'Mooncrate research complete',
                'Mooncrate research summary', '00000018-0000-4000-8000-000000000115', interval '20 minutes'))
       as started(run_id, number, name, topic, file_id, age)
  join stored_files file on file.file_id = started.file_id
  join stored_files second on second.file_id = cast(replace(cast(started.file_id as text), '0000000001', '0000000002') as uuid)
on conflict (run_id) do nothing;

-- DB-SPECIFIC: ->, ->>, uuid and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: PostgreSQL jsonb_array_elements() expands the attachment array in this seed group.
insert into run_file_holders (run_id, file_id, group_id)
select run_id, cast(file.value ->> 'id' as uuid), group_id
  from runs cross join lateral jsonb_array_elements(started_with -> 'attachment') file(value) where run_id in ('0000000f-0000-4000-8000-000000000113', '0000000f-0000-4000-8000-000000000114', '0000000f-0000-4000-8000-000000000115')
on conflict (run_id, file_id) do nothing;

-- DB-SPECIFIC: PostgreSQL uuid casts, jsonb aggregation/operators, interval and ON CONFLICT.
-- DB-SPECIFIC: This run_steps seed group supplies PostgreSQL native step_kind, step_producer, subject_kind enum inputs.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, producer, reviewed_by_model, tries, created_at, created_by, created_by_kind)
select cast(replace(cast(run_id as text), '0000000f', '00000010') as uuid), run_id, entry_version_id,
 '0000000c-0000-4000-8000-000000000108', 'code_step', 'code', false, 2, created_at, '00000000-0000-4000-8000-000000000001', 'system'
from runs where run_id in ('0000000f-0000-4000-8000-000000000113', '0000000f-0000-4000-8000-000000000114', '0000000f-0000-4000-8000-000000000115')
 and entry_version_id = '00000007-0000-4000-8000-000000000109' on conflict (run_step_id) do nothing;

-- DB-SPECIFIC: PostgreSQL uuid casts, interval and ON CONFLICT.
-- DB-SPECIFIC: This productions seed group supplies PostgreSQL native run_step_kind, step_producer, subject_kind enum inputs.
insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer, reviewed_by_model, tries, may_run_again, try_number, producer, created_at, created_by, created_by_kind, ended_at, ended_by, ended_by_kind)
select cast(replace(cast(run_step_id as text), '00000010', '00000011') as uuid), run_step_id, run_id, run_id, 'code_step', 'code', false, 2, true, 1, 'code', created_at, '00000000-0000-4000-8000-000000000001', 'system', created_at + interval '500 milliseconds', '00000000-0000-4000-8000-000000000001', 'system'
from run_steps where workflow_step_id = '0000000c-0000-4000-8000-000000000108'
 and run_id in ('0000000f-0000-4000-8000-000000000113', '0000000f-0000-4000-8000-000000000114', '0000000f-0000-4000-8000-000000000115')
 and entry_version_id = '00000007-0000-4000-8000-000000000109' on conflict (production_id) do nothing;

-- DB-SPECIFIC: PostgreSQL uuid casts, jsonb_agg, convert_from, WITH ORDINALITY and ON CONFLICT.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL native run_step_kind, step_producer, field_standing enum inputs.
-- DB-SPECIFIC: PostgreSQL jsonb_array_elements() and JSON ->/->> operators read this seed group.
insert into production_values (production_value_id, production_id, run_step_kind, producer, field_name, field_standing, value)
select cast(replace(cast(read.production_id as text), '00000011', '00000012') as uuid), read.production_id, 'code_step', 'code', 'text', 'always', jsonb_agg(convert_from(content.content, 'UTF8') order by file.ordinality)
from productions read join runs run using (run_id)
cross join lateral jsonb_array_elements(run.started_with -> 'attachment') with ordinality file(value, ordinality)
join file_contents content on content.file_id = cast(file.value ->> 'id' as uuid)
where read.run_step_kind = 'code_step' and run.run_id in ('0000000f-0000-4000-8000-000000000113', '0000000f-0000-4000-8000-000000000114', '0000000f-0000-4000-8000-000000000115')
 and run.entry_version_id = '00000007-0000-4000-8000-000000000109' group by read.production_id on conflict (production_value_id) do nothing;

-- DB-SPECIFIC: PostgreSQL uuid casts and ON CONFLICT.
-- DB-SPECIFIC: This production_inputs seed group supplies PostgreSQL native run_step_kind enum inputs.
insert into production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id, run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant, created_at)
select cast(replace(cast(production_id as text), '00000011', '00000013') as uuid), production_id, run_id, root_run_id, run_step_id, 'code_step', '0000000c-0000-4000-8000-000000000108', '0000000e-0000-4000-8000-000000000117', false, created_at
from productions where run_step_kind = 'code_step' and run_id in ('0000000f-0000-4000-8000-000000000113', '0000000f-0000-4000-8000-000000000114', '0000000f-0000-4000-8000-000000000115') on conflict (production_input_id) do nothing;

-- The stopped root keeps a declined try as evidence.
-- DB-SPECIFIC: uuid and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_steps seed group supplies PostgreSQL native step_kind, step_producer, entry_kind, subject_kind enum inputs.
-- DB-SPECIFIC: This seed group uses PostgreSQL verbose interval literals.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                       producer, reviewed_by_model, tries, pinned_version_id, pinned_kind, created_at, created_by, created_by_kind)
select planned.step_id, run.run_id, run.entry_version_id, '0000000c-0000-4000-8000-000000000105', 'search',
       'model', false, 2, '00000007-0000-4000-8000-000000000112', 'question', run.created_at + interval '500 milliseconds', '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000010-0000-4000-8000-000000001301' as uuid), cast('0000000f-0000-4000-8000-000000000113' as uuid)),
               ('00000010-0000-4000-8000-000000001401', '0000000f-0000-4000-8000-000000000114'),
               ('00000010-0000-4000-8000-000000001501', '0000000f-0000-4000-8000-000000000115')) as planned(step_id, run_id)
  join runs run on run.run_id = planned.run_id
on conflict (run_step_id) do nothing;

-- DB-SPECIFIC: interval literals and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_stops seed group supplies PostgreSQL native subject_kind enum inputs.
insert into run_stops (run_stop_id, run_id, root_run_id, created_at, created_by, created_by_kind)
select '00000017-0000-4000-8000-000000001301', run_id, root_run_id, created_at + interval '4 seconds', '00000001-0000-4000-8000-000000000102', 'person'
  from runs where run_id = '0000000f-0000-4000-8000-000000000113'
on conflict (run_stop_id) do nothing;

-- The model production is opened before the call that closes it is inserted.
-- DB-SPECIFIC: uuid and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This productions seed group supplies PostgreSQL native run_step_kind, step_producer, subject_kind enum inputs.
insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_at, created_by, created_by_kind)
select planned.production_id, ran.run_step_id, run.run_id, run.root_run_id, 'search', 'model', false, 2, '00000007-0000-4000-8000-000000000112', 1,
       'model', ran.created_at, '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000011-0000-4000-8000-000000001301' as uuid), cast('00000010-0000-4000-8000-000000001301' as uuid)),
               ('00000011-0000-4000-8000-000000001401', '00000010-0000-4000-8000-000000001401'),
               ('00000011-0000-4000-8000-000000001501', '00000010-0000-4000-8000-000000001501')) as planned(production_id, step_id)
  join run_steps ran on ran.run_step_id = planned.step_id join runs run on run.run_id = ran.run_id
on conflict (production_id) do nothing;

-- DB-SPECIFIC: uuid and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_inputs seed group supplies PostgreSQL native run_step_kind enum inputs.
insert into production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id,
                               run_step_kind, workflow_step_id, binding_id,
                               binding_reads_a_constant, created_at)
select input.input_id, try.production_id, try.run_id, try.root_run_id, try.run_step_id, 'search', '0000000c-0000-4000-8000-000000000105',
       '0000000e-0000-4000-8000-000000000116', false, try.created_at
  from (values (cast('00000013-0000-4000-8000-000000001301' as uuid), cast('00000011-0000-4000-8000-000000001301' as uuid)),
               ('00000013-0000-4000-8000-000000001401', '00000011-0000-4000-8000-000000001401'),
               ('00000013-0000-4000-8000-000000001501', '00000011-0000-4000-8000-000000001501')) as input(input_id, production_id)
  join productions try on try.production_id = input.production_id
on conflict (production_input_id) do nothing;

-- DB-SPECIFIC: PostgreSQL uuid casts and ON CONFLICT.
-- DB-SPECIFIC: This production_inputs seed group supplies PostgreSQL native run_step_kind enum inputs.
insert into production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id, run_step_kind, workflow_step_id, binding_id, binding_reads_a_constant, source_workflow_step_id, source_run_step_id, source_run_step_kind, source_production_id, source_production_value_id, created_at)
select cast(replace(cast(search.production_id as text), '00000011', '00000023') as uuid), search.production_id, search.run_id, search.root_run_id, search.run_step_id, 'search', '0000000c-0000-4000-8000-000000000105', '0000000e-0000-4000-8000-000000000118', false, '0000000c-0000-4000-8000-000000000108', read.run_step_id, 'code_step', read.production_id, text.production_value_id, search.created_at
from productions search join productions read on read.run_id = search.run_id and read.run_step_kind = 'code_step'
join production_values text on text.production_id = read.production_id and text.field_name = 'text'
where search.run_step_kind = 'search' and search.run_id in ('0000000f-0000-4000-8000-000000000113', '0000000f-0000-4000-8000-000000000114', '0000000f-0000-4000-8000-000000000115') on conflict (production_input_id) do nothing;

-- DB-SPECIFIC: uuid, text, to_json(), ->>, interval literals and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_step_send_attempts seed group supplies PostgreSQL native run_step_kind, model_call_purpose, subject_kind enum inputs.
insert into run_step_send_attempts (run_step_send_attempt_id, run_step_id, run_id, run_step_kind, workflow_step_id,
                                    purpose, model, mode, production_id, payload,
                                    created_at, created_by, created_by_kind)
select sent.attempt_id, try.run_step_id, try.run_id, 'search', '0000000c-0000-4000-8000-000000000105', 'produce', 'sample_model', 'ordinary',
       try.production_id, '{"envelope_version":5,"instruction":"Read the supplied texts alongside the fictional topic. Find supporting evidence, identify missing proof and report sources for each text.","takes":{"topic":'
           || cast(to_json(run.started_with ->> 'topic') as text) || ',"text":'
           || cast(text.value as text) || '}}',
       try.created_at + interval '2 seconds', '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000014-0000-4000-8000-000000001301' as uuid), cast('00000011-0000-4000-8000-000000001301' as uuid)),
               ('00000014-0000-4000-8000-000000001401', '00000011-0000-4000-8000-000000001401'),
               ('00000014-0000-4000-8000-000000001501', '00000011-0000-4000-8000-000000001501')) as sent(attempt_id, production_id)
  join productions try on try.production_id = sent.production_id join runs run on run.run_id = try.run_id
  join productions read on read.run_id = run.run_id and read.run_step_kind = 'code_step'
  join production_values text on text.production_id = read.production_id and text.field_name = 'text'
on conflict (run_step_send_attempt_id) do nothing;

-- Counts and observations match the configured development search conversion.
-- DB-SPECIFIC: json_build_object, chr(), repeat(), uuid and text casts, interval literals and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This seed group uses PostgreSQL dollar-quoted strings, jsonb casts and JSON ->/->> operators.
-- DB-SPECIFIC: This model_calls seed group supplies PostgreSQL native model_call_purpose, model_call_outcome and subject_kind enum inputs.
with prose(body, sources) as (values ($prose$## Imaginary Mooncrate parcel review

This is an invented practice policy, not a real carrier's rule.
First keep the box.
Then photograph the contents.

1. Record `DEMO-CLAIM-17`.
2. Ask Mira Cloudmere for the missing evidence.

- **Keep** the packing.
- ~~Discard the label~~ Keep it for review.

> The imaginary claim needs proof of damage.

| Evidence | Invented allowance | Review note |
| --- | --- | --- |
| Photograph | 12 pretend credits | Extra note: include the box label |
| Packing note | 7 pretend credits | Record the fictional dispatch date |

```fictional-policy
claim = DEMO-CLAIM-17
```

Read the [invented guide](https://example.com/claims "Imaginary guide").
https://example.org/terms
[https://example.net/status](https://example.net/status)
[Contact](mailto:demo@example.invalid)
[Local note](file:///imaginary/claim)
![Imaginary box diagram](https://example.com/box.png)

[Terms][fictional-terms]

[fictional-terms]: https://example.org/terms "Invented terms"

<aside>Invented review note, shown exactly as written.</aside>

Evidence&#8203;status: pending.
Evidence$prose$ || chr(8203) || $prose$owner: Mira Cloudmere.

---$prose$, $references$Invented sources only:
[Practice guide](https://example.com/claims)
https://example.org/terms$references$))
insert into model_calls (model_call_id, run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                         production_id, model, mode, envelope_version, sent_count, came_back_count, counted_by_model,
                         outcome, answer, declined_words, ended_at, created_at, created_by, created_by_kind)
select called.call_id, sent.run_id, try.root_run_id, 'produce', sent.run_step_send_attempt_id, sent.run_step_id,
       sent.production_id, 'sample_model', 'ordinary', 5, 7, 9, true, 'came_back',
       case when sent.run_id = '0000000f-0000-4000-8000-000000000113' then '{"refusal":"## Missing evidence\nI cannot settle the invented parcel claim.\nAsk for the original box label."}' else cast(json_build_object('title', 'Imaginary Mooncrate: ' || (cast(sent.payload as jsonb) -> 'takes' ->> 'topic')
           || ' / Invented Mooncrate claim / Invented evidence note', 'body', prose.body || chr(10) || 'File 1: ' || (cast(sent.payload as jsonb) -> 'takes' -> 'text' ->> 0)
           || chr(10) || 'File 2: ' || (cast(sent.payload as jsonb) -> 'takes' -> 'text' ->> 1),
           'references', case when sent.run_id = '0000000f-0000-4000-8000-000000000114'
               then repeat('> ', 33) || 'Imaginary appeal chain: retain every review note.' else prose.sources end, 'other', '') as text) end,
       case when sent.run_id = '0000000f-0000-4000-8000-000000000113' then $declined$## Missing evidence
I cannot settle the **invented parcel claim**.
Ask Mira Cloudmere for the original box label.
[Practice guide](https://example.com/claims)$declined$ else null end,
       sent.created_at + interval '1 second', sent.created_at, '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000015-0000-4000-8000-000000001301' as uuid), cast('00000014-0000-4000-8000-000000001301' as uuid)),
               ('00000015-0000-4000-8000-000000001401', '00000014-0000-4000-8000-000000001401'),
               ('00000015-0000-4000-8000-000000001501', '00000014-0000-4000-8000-000000001501')) as called(call_id, attempt_id)
  join run_step_send_attempts sent on sent.run_step_send_attempt_id = called.attempt_id
  join productions try on try.production_id = sent.production_id cross join prose
on conflict (model_call_id) do nothing;

-- DB-SPECIFIC: update from uses PostgreSQL's syntax and semantics.
update productions try set ended_at = call.ended_at, ended_by = call.created_by, ended_by_kind = call.created_by_kind,
       model_call_id = call.model_call_id, model_call_outcome = call.outcome
  from model_calls call
 where call.production_id = try.production_id and try.ended_at is null
   and call.model_call_id in ('00000015-0000-4000-8000-000000001301', '00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501');

-- DB-SPECIFIC: This productions seed update supplies PostgreSQL native try_lost_reason and did_not_fit_reason enum inputs.
update productions set lost_reason = 'did_not_fit', did_not_fit_reason = 'model_declined'
 where production_id = '00000011-0000-4000-8000-000000001301';

-- DB-SPECIFIC: jsonb, ->, uuid and enum casts, and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL native run_step_kind, step_producer, field_standing enum inputs.
insert into production_values (production_value_id, production_id, run_step_kind, producer,
                               field_name, field_standing, value)
select planned.value_id, planned.production_id, 'search', 'model', planned.field_name, planned.standing,
       case when planned.field_name = 'other' then null else cast(call.answer as jsonb) -> planned.field_name end
  from (values
    (cast('00000012-0000-4000-8000-000000001401' as uuid), cast('00000011-0000-4000-8000-000000001401' as uuid), 'title', cast('always' as field_standing)),
    ('00000012-0000-4000-8000-000000001402', '00000011-0000-4000-8000-000000001401', 'body', 'never'),
    ('00000012-0000-4000-8000-000000001403', '00000011-0000-4000-8000-000000001401', 'references', 'always'),
    ('00000012-0000-4000-8000-000000001404', '00000011-0000-4000-8000-000000001401', 'other', 'always'),
    ('00000012-0000-4000-8000-000000001501', '00000011-0000-4000-8000-000000001501', 'title', 'always'),
    ('00000012-0000-4000-8000-000000001502', '00000011-0000-4000-8000-000000001501', 'body', 'never'),
    ('00000012-0000-4000-8000-000000001503', '00000011-0000-4000-8000-000000001501', 'references', 'always'),
    ('00000012-0000-4000-8000-000000001504', '00000011-0000-4000-8000-000000001501', 'other', 'always'))
       as planned(value_id, production_id, field_name, standing)
  join model_calls call on call.production_id = planned.production_id
on conflict (production_value_id) do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This model_call_tool_uses seed group supplies PostgreSQL native model_call_outcome enum inputs.
insert into model_call_tool_uses (model_call_id, run_id, model_call_outcome, tool, most_uses, uses, uses_said)
select model_call_id, run_id, outcome, 'web_search', 3, 2, true from model_calls
 where model_call_id in ('00000015-0000-4000-8000-000000001301', '00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501')
on conflict (model_call_id) do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into model_call_tool_prices (model_call_id, currency, per_use)
select model_call_id, price.currency, price.per_use from model_call_tool_uses
 cross join (values ('USD', 0.01), ('EUR', 0.02)) as price(currency, per_use)
 where model_call_id in ('00000015-0000-4000-8000-000000001301', '00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501')
on conflict (model_call_id, currency) do nothing;

-- DB-SPECIFIC: PostgreSQL jsonb casts, JSON ->/->> operators, and on conflict.
-- DB-SPECIFIC: PostgreSQL dollar quoting delimits the found-evidence string in this seed group.
insert into model_call_searches (model_call_id, position, searched_for, found)
select call.model_call_id, 1, cast(sent.payload as jsonb) -> 'takes' ->> 'topic', $found$## Invented source notes
The imaginary claims guide asks for the box and label.
[Practice guide](https://example.com/claims)
No real carrier or policy is represented.$found$
  from model_calls call join run_step_send_attempts sent using (run_step_send_attempt_id)
 where call.model_call_id in ('00000015-0000-4000-8000-000000001301', '00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501')
on conflict (model_call_id, position) do nothing;

-- DB-SPECIFIC: PostgreSQL jsonb casts, JSON ->/->> operators, and ON CONFLICT.
insert into model_call_searches (model_call_id, position, searched_for, found)
select call.model_call_id, 2, (cast(sent.payload as jsonb) -> 'takes' ->> 'topic') || ' / evidence ledger', $found$## Invented evidence ledger
Mira Cloudmere recorded a missing box photograph and a fictional dispatch date.
[Imaginary ledger](https://example.org/evidence)
The invented claim remains open until both records are checked.$found$
  from model_calls call join run_step_send_attempts sent using (run_step_send_attempt_id)
 where call.model_call_id in ('00000015-0000-4000-8000-000000001301', '00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501')
on conflict (model_call_id, position) do nothing;

-- The completed run's body was assured by a person after the model returned it.
-- DB-SPECIFIC: interval literals and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This reviews seed group supplies PostgreSQL native subject_kind enum inputs.
insert into reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                     created_at, created_by, created_by_kind)
select '00000016-0000-4000-8000-000000001501', production_id, run_step_id, root_run_id, ended_by, false,
       ended_at + interval '1 minute', '00000001-0000-4000-8000-000000000103', 'person'
  from productions where production_id = '00000011-0000-4000-8000-000000001501'
on conflict (review_id) do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This review_decisions seed group supplies PostgreSQL native review_outcome enum inputs.
insert into review_decisions (review_id, production_value_id, production_id, for_length, value_needs_review, outcome)
values ('00000016-0000-4000-8000-000000001501', '00000012-0000-4000-8000-000000001502', '00000011-0000-4000-8000-000000001501', false, true, 'assured')
on conflict (review_id, production_value_id) do nothing;
