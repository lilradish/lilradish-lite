-- Targeted ON CONFLICT does not skip collisions with the run's unique number.
-- DB-SPECIFIC: jsonb_build_object(), chr(), interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This runs seed group supplies PostgreSQL native subject_kind enum inputs.
insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                  started_with, created_at, created_by, created_by_kind) values
    ('0000000f-0000-4000-8000-000000000117', '00000002-0000-4000-8000-000000000101', 17, 'Teapot arrived with a broken spout',
     '00000006-0000-4000-8000-000000000113', '00000007-0000-4000-8000-000000000115',
     '0000000f-0000-4000-8000-000000000117', 0,
     jsonb_build_object('complaint', 'Hello,' || chr(10) || chr(10)
         || 'The stoneware teapot I ordered from Mooncrate (order DEMO-ORDER-1717) arrived with its spout'
         || ' snapped off. The box was crushed at one corner, right where the spout sat, and I have kept the box and all'
         || ' the packing.' || chr(10) || chr(10)
         || 'Could you send a replacement? It is a birthday present for the 14th.' || chr(10) || chr(10)
         || 'Kind regards,' || chr(10) || 'Wren Thistlewood'),
     timestamptz '2000-01-10 00:00:00+00' - interval '15 minutes', '00000001-0000-4000-8000-000000000102', 'person')
on conflict (run_id) do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_steps seed group supplies PostgreSQL native step_kind, entry_kind, step_producer, subject_kind enum inputs.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                       pinned_kind, producer, reviewed_by_model, tries, created_at, created_by, created_by_kind) values
    ('00000010-0000-4000-8000-000000001701', '0000000f-0000-4000-8000-000000000117',
     '00000007-0000-4000-8000-000000000115', '0000000c-0000-4000-8000-000000000110', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '15 minutes' + interval '100 milliseconds',
     '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000001702', '0000000f-0000-4000-8000-000000000117',
     '00000007-0000-4000-8000-000000000115', '0000000c-0000-4000-8000-000000000111', 'code_step',
     null, null, 'code', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '200 milliseconds',
     '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000001703', '0000000f-0000-4000-8000-000000000117',
     '00000007-0000-4000-8000-000000000115', '0000000c-0000-4000-8000-000000000112', 'entry',
     '00000007-0000-4000-8000-000000000107', 'workflow', null, false, null,
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '900 milliseconds',
     '00000000-0000-4000-8000-000000000001', 'system')
on conflict do nothing;

-- Started beneath a step running a workflow, so it names no case and records no discriminator input.
-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This runs seed group supplies PostgreSQL native run_step_kind, subject_kind enum inputs.
insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id, parent_run_step_id,
                  parent_run_step_kind, parent_workflow_step_id, root_run_id, depth, parent_depth,
                  created_at, created_by, created_by_kind) values
    ('0000000f-0000-4000-8000-000000000118', '00000002-0000-4000-8000-000000000101', 18,
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000117', '00000010-0000-4000-8000-000000001703', 'workflow',
     '0000000c-0000-4000-8000-000000000112', '0000000f-0000-4000-8000-000000000117', 1, 0,
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '950 milliseconds',
     '00000000-0000-4000-8000-000000000001', 'system')
on conflict (run_id) do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_steps seed group supplies PostgreSQL native step_kind, entry_kind, step_producer, subject_kind enum inputs.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                       pinned_kind, producer, reviewed_by_model, tries, created_at, created_by, created_by_kind) values
    ('00000010-0000-4000-8000-000000001801', '0000000f-0000-4000-8000-000000000118',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '1 second',
     '00000000-0000-4000-8000-000000000001', 'system')
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This productions seed group supplies PostgreSQL native run_step_kind, step_producer, subject_kind enum inputs.
insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                         reviewed_by_model, tries, may_run_again, pinned_version_id, try_number, producer,
                         created_at, created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation) values
    ('00000011-0000-4000-8000-000000001701', '00000010-0000-4000-8000-000000001701',
     '0000000f-0000-4000-8000-000000000117', '0000000f-0000-4000-8000-000000000117', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     timestamptz '2000-01-10 00:00:00+00' - interval '15 minutes' + interval '150 milliseconds',
     '00000000-0000-4000-8000-000000000001', 'system',
     timestamptz '2000-01-10 00:00:00+00' - interval '12 minutes', '00000001-0000-4000-8000-000000000103', 'person',
     'The spout broke in transit: the box was crushed at the corner where it sat, so this is a delivery fault'
         || ' and not a product fault.'),
    ('00000011-0000-4000-8000-000000001702', '00000010-0000-4000-8000-000000001702',
     '0000000f-0000-4000-8000-000000000117', '0000000f-0000-4000-8000-000000000117', 'code_step', 'code',
     false, 2, true, null, 1, 'code',
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '250 milliseconds',
     '00000000-0000-4000-8000-000000000001', 'system',
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '700 milliseconds',
     '00000000-0000-4000-8000-000000000001', 'system', null),
    ('00000011-0000-4000-8000-000000001801', '00000010-0000-4000-8000-000000001801',
     '0000000f-0000-4000-8000-000000000118', '0000000f-0000-4000-8000-000000000117', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '1050 milliseconds',
     '00000000-0000-4000-8000-000000000001', 'system',
     timestamptz '2000-01-10 00:00:00+00' - interval '7 minutes', '00000001-0000-4000-8000-000000000103', 'person',
     'Delivery, as on the run above: the box was crushed at the corner where the spout sat. The customer kept the box'
         || ' and the packing for the courier claim, and needs the replacement before the 14th.')
on conflict do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL native run_step_kind, step_producer, field_standing enum inputs.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL jsonb value input.
insert into production_values (production_value_id, production_id, run_step_kind, producer, pinned_version_id,
                               declaration_field_id, field_standing, standing_threshold, value) values
    ('00000012-0000-4000-8000-000000001701', '00000011-0000-4000-8000-000000001701', 'question', 'person',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', 'above_confidence', 80,
     '"Delivery"'),
    ('00000012-0000-4000-8000-000000001702', '00000011-0000-4000-8000-000000001701', 'question', 'person',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', 'never', null,
     '{"product": "Stoneware teapot", "order_reference": "DEMO-ORDER-1717"}'),
    ('00000012-0000-4000-8000-000000001801', '00000011-0000-4000-8000-000000001801', 'question', 'person',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', 'above_confidence', 80,
     '"Delivery"'),
    ('00000012-0000-4000-8000-000000001802', '00000011-0000-4000-8000-000000001801', 'question', 'person',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', 'never', null,
     '{"product": "Stoneware teapot", "order_reference": "DEMO-ORDER-1717"}')
on conflict do nothing;

-- The reference is the one the release's stamp_reference code step draws from the complaint it was given.
-- DB-SPECIFIC: ->>, convert_to(), sha256(), encode(), to_jsonb() and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL native run_step_kind, step_producer, field_standing enum inputs.
insert into production_values (production_value_id, production_id, run_step_kind, producer, field_name, field_standing, value)
select '00000012-0000-4000-8000-000000001703', '00000011-0000-4000-8000-000000001702', 'code_step', 'code', 'reference', 'always',
       to_jsonb('SUP-' || upper(left(encode(sha256(convert_to(run.started_with ->> 'complaint', 'UTF8')), 'hex'), 8)))
  from runs run
 where run.run_id = '0000000f-0000-4000-8000-000000000117'
on conflict (production_value_id) do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_inputs seed group supplies PostgreSQL native run_step_kind enum inputs.
insert into production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id, run_step_kind,
                               workflow_step_id, binding_id, binding_reads_a_constant, created_at) values
    ('00000013-0000-4000-8000-000000001701', '00000011-0000-4000-8000-000000001701',
     '0000000f-0000-4000-8000-000000000117', '0000000f-0000-4000-8000-000000000117',
     '00000010-0000-4000-8000-000000001701', 'question', '0000000c-0000-4000-8000-000000000110',
     '0000000e-0000-4000-8000-000000000121', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '15 minutes' + interval '150 milliseconds'),
    ('00000013-0000-4000-8000-000000001702', '00000011-0000-4000-8000-000000001701',
     '0000000f-0000-4000-8000-000000000117', '0000000f-0000-4000-8000-000000000117',
     '00000010-0000-4000-8000-000000001701', 'question', '0000000c-0000-4000-8000-000000000110',
     '0000000e-0000-4000-8000-000000000122', true,
     timestamptz '2000-01-10 00:00:00+00' - interval '15 minutes' + interval '150 milliseconds'),
    ('00000013-0000-4000-8000-000000001703', '00000011-0000-4000-8000-000000001702',
     '0000000f-0000-4000-8000-000000000117', '0000000f-0000-4000-8000-000000000117',
     '00000010-0000-4000-8000-000000001702', 'code_step', '0000000c-0000-4000-8000-000000000111',
     '0000000e-0000-4000-8000-000000000123', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '250 milliseconds'),
    ('00000013-0000-4000-8000-000000001801', '00000011-0000-4000-8000-000000001801',
     '0000000f-0000-4000-8000-000000000118', '0000000f-0000-4000-8000-000000000117',
     '00000010-0000-4000-8000-000000001801', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '1050 milliseconds'),
    ('00000013-0000-4000-8000-000000001802', '00000011-0000-4000-8000-000000001801',
     '0000000f-0000-4000-8000-000000000118', '0000000f-0000-4000-8000-000000000117',
     '00000010-0000-4000-8000-000000001801', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true,
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes' + interval '1050 milliseconds')
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This reviews seed group supplies PostgreSQL native subject_kind enum inputs.
insert into reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                     created_at, created_by, created_by_kind) values
    ('00000016-0000-4000-8000-000000001701', '00000011-0000-4000-8000-000000001701',
     '00000010-0000-4000-8000-000000001701', '0000000f-0000-4000-8000-000000000117',
     '00000001-0000-4000-8000-000000000103', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '11 minutes', '00000001-0000-4000-8000-000000000102', 'person'),
    ('00000016-0000-4000-8000-000000001801', '00000011-0000-4000-8000-000000001801',
     '00000010-0000-4000-8000-000000001801', '0000000f-0000-4000-8000-000000000117',
     '00000001-0000-4000-8000-000000000103', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '5 minutes', '00000001-0000-4000-8000-000000000102', 'person')
on conflict do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This review_decisions seed group supplies PostgreSQL native review_outcome enum inputs.
insert into review_decisions (review_id, production_value_id, production_id, for_length, value_needs_review,
                              outcome, explanation) values
    ('00000016-0000-4000-8000-000000001701', '00000012-0000-4000-8000-000000001701',
     '00000011-0000-4000-8000-000000001701', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000001701', '00000012-0000-4000-8000-000000001702',
     '00000011-0000-4000-8000-000000001701', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000001801', '00000012-0000-4000-8000-000000001801',
     '00000011-0000-4000-8000-000000001801', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000001801', '00000012-0000-4000-8000-000000001802',
     '00000011-0000-4000-8000-000000001801', false, true, 'assured', null)
on conflict do nothing;
