

-- Targeted ON CONFLICT does not skip collisions with the run's unique number.
-- DB-SPECIFIC: interval literals, jsonb_build_object(), on conflict, chr() and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This runs seed group supplies PostgreSQL native subject_kind enum inputs.
insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                  started_with, created_at, created_by, created_by_kind) values
    ('0000000f-0000-4000-8000-000000000101', '00000002-0000-4000-8000-000000000101', 1, 'Kettle arrived broken',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000101', 0,
     jsonb_build_object('title', 'Kettle arrived broken', 'amount', 39.95, 'category', 'Delivery',
         'complaint', 'Hello,' || chr(10) || chr(10)
         || 'The kettle I ordered (order 4471-2208) came in a crushed box with its lid cracked.'
         || ' I would like a new one sent, or my money back.' || chr(10) || chr(10)
         || 'Kind regards,' || chr(10) || 'Jane Doe'),
     timestamptz '2000-01-10 00:00:00+00' - interval '7 days', '00000001-0000-4000-8000-000000000102', 'person')
on conflict (run_id) do nothing;

-- DB-SPECIFIC: interval literals, jsonb_build_object(), on conflict, chr() and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This runs seed group supplies PostgreSQL native subject_kind enum inputs.
insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                  started_with, created_at, created_by, created_by_kind) values
    ('0000000f-0000-4000-8000-000000000102', '00000002-0000-4000-8000-000000000101', 2, 'Charged twice for one order',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000102', 0,
     jsonb_build_object('title', 'Charged twice for one order', 'amount', 89.50, 'category', 'Billing',
         'complaint', 'Hello,' || chr(10) || chr(10)
         || 'My card was charged twice for the wireless headphones I ordered (order 5530-1187), once on the day I'
         || ' ordered and again a week later. Please refund the second charge.' || chr(10) || chr(10)
         || 'Thanks,' || chr(10) || 'Sam Taylor'),
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days', '00000001-0000-4000-8000-000000000102', 'person'),
    ('0000000f-0000-4000-8000-000000000104', '00000002-0000-4000-8000-000000000101', 4, 'Wrong size delivered',
     '00000006-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000108',
     '0000000f-0000-4000-8000-000000000104', 0,
     jsonb_build_object('complaint', 'Hi,' || chr(10) || chr(10)
         || 'The boots I ordered from Mooncrate (order 6120-4471) came in a size 9, but I ordered a size 7.'
         || chr(10) || chr(10) || 'Best,' || chr(10) || 'Priya Shah'),
     timestamptz '2000-01-10 00:00:00+00' - interval '4 days', '00000001-0000-4000-8000-000000000103', 'person'),
    ('0000000f-0000-4000-8000-000000000105', '00000002-0000-4000-8000-000000000101', 5, 'Refund not received',
     '00000006-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000108',
     '0000000f-0000-4000-8000-000000000105', 0,
     jsonb_build_object('complaint', 'Hello,' || chr(10) || chr(10)
         || 'I sent the blender back three weeks ago (order 8835-2290) and the refund has still not reached my'
         || ' account.' || chr(10) || chr(10) || 'Regards,' || chr(10) || 'Tom Becker'),
     timestamptz '2000-01-10 00:00:00+00' - interval '3 days', '00000001-0000-4000-8000-000000000102', 'person'),
    ('0000000f-0000-4000-8000-000000000106', '00000002-0000-4000-8000-000000000101', 6, 'Parcel left in the rain',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000106', 0,
     jsonb_build_object('title', 'Parcel left in the rain', 'amount', 124.00, 'category', 'Delivery',
         'complaint', 'Hi,' || chr(10) || chr(10)
         || 'The courier left my parcel (order 7781-0032) on the doorstep in the rain, and the bookshelf inside is'
         || ' warped.' || chr(10) || chr(10) || 'Thanks,' || chr(10) || 'Maria Lopez'),
     timestamptz '2000-01-10 00:00:00+00' - interval '2 days', '00000001-0000-4000-8000-000000000103', 'person'),
    ('0000000f-0000-4000-8000-000000000107', '00000002-0000-4000-8000-000000000101', 7, 'Order arrived late',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000107', 0,
     jsonb_build_object('title', 'Order arrived late', 'amount', 28.75, 'category', 'Delivery',
         'complaint', 'Hello,' || chr(10) || chr(10)
         || 'The cake stand I ordered for the 12th (order 3391-0475) only arrived on the 15th, after the party.'
         || ' The tracking number was DEMO-TRACK-000000001.' || chr(10) || chr(10) || 'Kind regards,' || chr(10)
         || 'Owen Price'),
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day 20 minutes', '00000001-0000-4000-8000-000000000102', 'person'),
    ('0000000f-0000-4000-8000-000000000108', '00000002-0000-4000-8000-000000000101', 8, 'Old address on the invoice',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000108', 0,
     jsonb_build_object('title', 'Old address on the invoice', 'amount', 46.20, 'category', 'Billing',
         'complaint', 'Hello,' || chr(10) || chr(10)
         || 'I changed my billing address, but the invoice for the desk lamp I bought (order 9042-6615) still shows'
         || ' the old one. Could you send a corrected invoice?' || chr(10) || chr(10)
         || 'Thank you,' || chr(10) || 'Lena Fischer'),
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day', '00000001-0000-4000-8000-000000000103', 'person'),
    ('0000000f-0000-4000-8000-000000000110', '00000002-0000-4000-8000-000000000101', 10, 'Charger stopped working',
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000110', 0,
     jsonb_build_object('complaint', 'Hi,' || chr(10) || chr(10)
         || 'The laptop charger from my Mooncrate order (order 3307-9921) stopped working after two days.'
         || chr(10) || chr(10) || 'Cheers,' || chr(10) || 'Chris Novak'),
     timestamptz '2000-01-10 00:00:00+00' - interval '150 minutes', '00000001-0000-4000-8000-000000000102', 'person'),
    ('0000000f-0000-4000-8000-000000000111', '00000002-0000-4000-8000-000000000101', 11, 'Lid does not close',
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000111', 0,
     jsonb_build_object('complaint', 'Hello,' || chr(10) || chr(10)
         || 'The lid of the storage box I bought (order 2214-5508) does not close properly, so the box cannot be'
         || ' stacked.' || chr(10) || chr(10) || 'Best wishes,' || chr(10) || 'Aisha Khan'),
     timestamptz '2000-01-10 00:00:00+00' - interval '100 minutes', '00000001-0000-4000-8000-000000000103', 'person'),
    ('0000000f-0000-4000-8000-000000000112', '00000002-0000-4000-8000-000000000101', 12, 'Refund to the wrong card',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000112', 0,
     jsonb_build_object('title', 'Refund to the wrong card', 'amount', 72.90, 'category', 'Billing',
         'complaint', 'Hello,' || chr(10) || chr(10)
         || 'The refund for the coffee grinder I returned (order 4409-7763) went to a card I cancelled last year.'
         || ' How do I get it back?' || chr(10) || chr(10) || 'Regards,' || chr(10) || 'David Kim'),
     timestamptz '2000-01-10 00:00:00+00' - interval '50 minutes', '00000001-0000-4000-8000-000000000102', 'person')
on conflict (run_id) do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_steps seed group supplies PostgreSQL native step_kind, entry_kind, step_producer, subject_kind enum inputs.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                       pinned_kind, producer, reviewed_by_model, tries, created_at, created_by, created_by_kind) values
    ('00000010-0000-4000-8000-000000000201', '0000000f-0000-4000-8000-000000000102',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000202', '0000000f-0000-4000-8000-000000000102',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000102', 'route',
     null, null, null, false, null,
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '2 hours', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000401', '0000000f-0000-4000-8000-000000000104',
     '00000007-0000-4000-8000-000000000108', '0000000c-0000-4000-8000-000000000104', 'code_step',
     null, null, 'code', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '4 days', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000501', '0000000f-0000-4000-8000-000000000105',
     '00000007-0000-4000-8000-000000000108', '0000000c-0000-4000-8000-000000000104', 'code_step',
     null, null, 'code', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '3 days', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000601', '0000000f-0000-4000-8000-000000000106',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     timestamptz '2000-01-10 00:00:00+00' - interval '2 days', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000701', '0000000f-0000-4000-8000-000000000107',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day 20 minutes', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000801', '0000000f-0000-4000-8000-000000000108',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000802', '0000000f-0000-4000-8000-000000000108',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000102', 'route',
     null, null, null, false, null,
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day' + interval '30 minutes', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000001001', '0000000f-0000-4000-8000-000000000110',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '150 minutes', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000001101', '0000000f-0000-4000-8000-000000000111',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '100 minutes', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000001201', '0000000f-0000-4000-8000-000000000112',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     timestamptz '2000-01-10 00:00:00+00' - interval '50 minutes', '00000000-0000-4000-8000-000000000001', 'system')
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This runs seed group supplies PostgreSQL native run_step_kind, subject_kind enum inputs.
insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id, parent_run_step_id,
                  parent_run_step_kind, parent_workflow_step_id, parent_route_case_id, root_run_id, depth,
                  parent_depth, created_at, created_by, created_by_kind) values
    ('0000000f-0000-4000-8000-000000000103', '00000002-0000-4000-8000-000000000101', 3,
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000102', '00000010-0000-4000-8000-000000000202', 'route',
     '0000000c-0000-4000-8000-000000000102', '0000000d-0000-4000-8000-000000000101',
     '0000000f-0000-4000-8000-000000000102', 1, 0,
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '2 hours', '00000000-0000-4000-8000-000000000001', 'system'),
    ('0000000f-0000-4000-8000-000000000109', '00000002-0000-4000-8000-000000000101', 9,
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000108', '00000010-0000-4000-8000-000000000802', 'route',
     '0000000c-0000-4000-8000-000000000102', '0000000d-0000-4000-8000-000000000101',
     '0000000f-0000-4000-8000-000000000108', 1, 0,
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day' + interval '30 minutes', '00000000-0000-4000-8000-000000000001', 'system')
on conflict (run_id) do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_steps seed group supplies PostgreSQL native step_kind, entry_kind, step_producer, subject_kind enum inputs.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                       pinned_kind, producer, reviewed_by_model, tries, created_at, created_by, created_by_kind) values
    ('00000010-0000-4000-8000-000000000301', '0000000f-0000-4000-8000-000000000103',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '2 hours', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000901', '0000000f-0000-4000-8000-000000000109',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day' + interval '30 minutes', '00000000-0000-4000-8000-000000000001', 'system')
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This productions seed group supplies PostgreSQL native run_step_kind, step_producer, subject_kind, try_lost_reason enum inputs.
insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                         reviewed_by_model, tries, may_run_again, pinned_version_id, try_number, producer,
                         created_at, created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation,
                         lost_reason) values
    ('00000011-0000-4000-8000-000000000201', '00000010-0000-4000-8000-000000000201',
     '0000000f-0000-4000-8000-000000000102', '0000000f-0000-4000-8000-000000000102', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days', '00000000-0000-4000-8000-000000000001', 'system', null, null, default, null, null),
    ('00000011-0000-4000-8000-000000000301', '00000010-0000-4000-8000-000000000301',
     '0000000f-0000-4000-8000-000000000103', '0000000f-0000-4000-8000-000000000102', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '2 hours', '00000000-0000-4000-8000-000000000001', 'system',
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '3 hours', '00000001-0000-4000-8000-000000000103', 'person',
     'The payment log shows the second charge as a duplicate of the first.', null),
    ('00000011-0000-4000-8000-000000000401', '00000010-0000-4000-8000-000000000401',
     '0000000f-0000-4000-8000-000000000104', '0000000f-0000-4000-8000-000000000104', 'code_step', 'code',
     false, 2, true, null, 1, 'code',
     timestamptz '2000-01-10 00:00:00+00' - interval '4 days', '00000000-0000-4000-8000-000000000001', 'system',
     timestamptz '2000-01-10 00:00:00+00' - interval '4 days' + interval '2 seconds', '00000000-0000-4000-8000-000000000001', 'system', null, null),
    ('00000011-0000-4000-8000-000000000501', '00000010-0000-4000-8000-000000000501',
     '0000000f-0000-4000-8000-000000000105', '0000000f-0000-4000-8000-000000000105', 'code_step', 'code',
     false, 2, true, null, 1, 'code',
     timestamptz '2000-01-10 00:00:00+00' - interval '3 days', '00000000-0000-4000-8000-000000000001', 'system',
     timestamptz '2000-01-10 00:00:00+00' - interval '3 days' + interval '5 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, 'nothing_came_back'),
    ('00000011-0000-4000-8000-000000000502', '00000010-0000-4000-8000-000000000501',
     '0000000f-0000-4000-8000-000000000105', '0000000f-0000-4000-8000-000000000105', 'code_step', 'code',
     false, 2, true, null, 2, 'code',
     timestamptz '2000-01-10 00:00:00+00' - interval '3 days' + interval '5 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     timestamptz '2000-01-10 00:00:00+00' - interval '3 days' + interval '40 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, 'nothing_came_back'),
    ('00000011-0000-4000-8000-000000000601', '00000010-0000-4000-8000-000000000601',
     '0000000f-0000-4000-8000-000000000106', '0000000f-0000-4000-8000-000000000106', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     timestamptz '2000-01-10 00:00:00+00' - interval '2 days', '00000000-0000-4000-8000-000000000001', 'system', null, null, default, null, null),
    ('00000011-0000-4000-8000-000000000701', '00000010-0000-4000-8000-000000000701',
     '0000000f-0000-4000-8000-000000000107', '0000000f-0000-4000-8000-000000000107', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day 20 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, null, default, null, null),
    ('00000011-0000-4000-8000-000000000801', '00000010-0000-4000-8000-000000000801',
     '0000000f-0000-4000-8000-000000000108', '0000000f-0000-4000-8000-000000000108', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day', '00000000-0000-4000-8000-000000000001', 'system', null, null, default, null, null),
    ('00000011-0000-4000-8000-000000000901', '00000010-0000-4000-8000-000000000901',
     '0000000f-0000-4000-8000-000000000109', '0000000f-0000-4000-8000-000000000108', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day' + interval '30 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, null, default, null, null),
    ('00000011-0000-4000-8000-000000001001', '00000010-0000-4000-8000-000000001001',
     '0000000f-0000-4000-8000-000000000110', '0000000f-0000-4000-8000-000000000110', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     timestamptz '2000-01-10 00:00:00+00' - interval '150 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     timestamptz '2000-01-10 00:00:00+00' - interval '130 minutes', '00000001-0000-4000-8000-000000000103', 'person',
     'The charger failed in use, and the box it came in was intact.', null),
    ('00000011-0000-4000-8000-000000001101', '00000010-0000-4000-8000-000000001101',
     '0000000f-0000-4000-8000-000000000111', '0000000f-0000-4000-8000-000000000111', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     timestamptz '2000-01-10 00:00:00+00' - interval '100 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, null, default, null, null),
    ('00000011-0000-4000-8000-000000001201', '00000010-0000-4000-8000-000000001201',
     '0000000f-0000-4000-8000-000000000112', '0000000f-0000-4000-8000-000000000112', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     timestamptz '2000-01-10 00:00:00+00' - interval '50 minutes', '00000000-0000-4000-8000-000000000001', 'system', null, null, default, null, null)
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_inputs seed group supplies PostgreSQL native run_step_kind enum inputs.
insert into production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id, run_step_kind,
                               workflow_step_id, binding_id, binding_reads_a_constant, created_at) values
    ('00000013-0000-4000-8000-000000000201', '00000011-0000-4000-8000-000000000201',
     '0000000f-0000-4000-8000-000000000102', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000201', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, timestamptz '2000-01-10 00:00:00+00' - interval '6 days'),
    ('00000013-0000-4000-8000-000000000202', '00000011-0000-4000-8000-000000000201',
     '0000000f-0000-4000-8000-000000000102', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000201', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, timestamptz '2000-01-10 00:00:00+00' - interval '6 days'),
    ('00000013-0000-4000-8000-000000000301', '00000011-0000-4000-8000-000000000301',
     '0000000f-0000-4000-8000-000000000103', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000301', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false, timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '2 hours'),
    ('00000013-0000-4000-8000-000000000302', '00000011-0000-4000-8000-000000000301',
     '0000000f-0000-4000-8000-000000000103', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000301', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true, timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '2 hours'),
    ('00000013-0000-4000-8000-000000000401', '00000011-0000-4000-8000-000000000401',
     '0000000f-0000-4000-8000-000000000104', '0000000f-0000-4000-8000-000000000104',
     '00000010-0000-4000-8000-000000000401', 'code_step', '0000000c-0000-4000-8000-000000000104',
     '0000000e-0000-4000-8000-000000000109', false, timestamptz '2000-01-10 00:00:00+00' - interval '4 days'),
    ('00000013-0000-4000-8000-000000000501', '00000011-0000-4000-8000-000000000501',
     '0000000f-0000-4000-8000-000000000105', '0000000f-0000-4000-8000-000000000105',
     '00000010-0000-4000-8000-000000000501', 'code_step', '0000000c-0000-4000-8000-000000000104',
     '0000000e-0000-4000-8000-000000000109', false, timestamptz '2000-01-10 00:00:00+00' - interval '3 days'),
    ('00000013-0000-4000-8000-000000000502', '00000011-0000-4000-8000-000000000502',
     '0000000f-0000-4000-8000-000000000105', '0000000f-0000-4000-8000-000000000105',
     '00000010-0000-4000-8000-000000000501', 'code_step', '0000000c-0000-4000-8000-000000000104',
     '0000000e-0000-4000-8000-000000000109', false, timestamptz '2000-01-10 00:00:00+00' - interval '3 days' + interval '5 minutes'),
    ('00000013-0000-4000-8000-000000000601', '00000011-0000-4000-8000-000000000601',
     '0000000f-0000-4000-8000-000000000106', '0000000f-0000-4000-8000-000000000106',
     '00000010-0000-4000-8000-000000000601', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, timestamptz '2000-01-10 00:00:00+00' - interval '2 days'),
    ('00000013-0000-4000-8000-000000000602', '00000011-0000-4000-8000-000000000601',
     '0000000f-0000-4000-8000-000000000106', '0000000f-0000-4000-8000-000000000106',
     '00000010-0000-4000-8000-000000000601', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, timestamptz '2000-01-10 00:00:00+00' - interval '2 days'),
    ('00000013-0000-4000-8000-000000000701', '00000011-0000-4000-8000-000000000701',
     '0000000f-0000-4000-8000-000000000107', '0000000f-0000-4000-8000-000000000107',
     '00000010-0000-4000-8000-000000000701', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, timestamptz '2000-01-10 00:00:00+00' - interval '1 day 20 minutes'),
    ('00000013-0000-4000-8000-000000000702', '00000011-0000-4000-8000-000000000701',
     '0000000f-0000-4000-8000-000000000107', '0000000f-0000-4000-8000-000000000107',
     '00000010-0000-4000-8000-000000000701', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, timestamptz '2000-01-10 00:00:00+00' - interval '1 day 20 minutes'),
    ('00000013-0000-4000-8000-000000000801', '00000011-0000-4000-8000-000000000801',
     '0000000f-0000-4000-8000-000000000108', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000801', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, timestamptz '2000-01-10 00:00:00+00' - interval '1 day'),
    ('00000013-0000-4000-8000-000000000802', '00000011-0000-4000-8000-000000000801',
     '0000000f-0000-4000-8000-000000000108', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000801', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, timestamptz '2000-01-10 00:00:00+00' - interval '1 day'),
    ('00000013-0000-4000-8000-000000000901', '00000011-0000-4000-8000-000000000901',
     '0000000f-0000-4000-8000-000000000109', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000901', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false, timestamptz '2000-01-10 00:00:00+00' - interval '1 day' + interval '30 minutes'),
    ('00000013-0000-4000-8000-000000000902', '00000011-0000-4000-8000-000000000901',
     '0000000f-0000-4000-8000-000000000109', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000901', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true, timestamptz '2000-01-10 00:00:00+00' - interval '1 day' + interval '30 minutes'),
    ('00000013-0000-4000-8000-000000001001', '00000011-0000-4000-8000-000000001001',
     '0000000f-0000-4000-8000-000000000110', '0000000f-0000-4000-8000-000000000110',
     '00000010-0000-4000-8000-000000001001', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false, timestamptz '2000-01-10 00:00:00+00' - interval '150 minutes'),
    ('00000013-0000-4000-8000-000000001002', '00000011-0000-4000-8000-000000001001',
     '0000000f-0000-4000-8000-000000000110', '0000000f-0000-4000-8000-000000000110',
     '00000010-0000-4000-8000-000000001001', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true, timestamptz '2000-01-10 00:00:00+00' - interval '150 minutes'),
    ('00000013-0000-4000-8000-000000001101', '00000011-0000-4000-8000-000000001101',
     '0000000f-0000-4000-8000-000000000111', '0000000f-0000-4000-8000-000000000111',
     '00000010-0000-4000-8000-000000001101', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false, timestamptz '2000-01-10 00:00:00+00' - interval '100 minutes'),
    ('00000013-0000-4000-8000-000000001102', '00000011-0000-4000-8000-000000001101',
     '0000000f-0000-4000-8000-000000000111', '0000000f-0000-4000-8000-000000000111',
     '00000010-0000-4000-8000-000000001101', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true, timestamptz '2000-01-10 00:00:00+00' - interval '100 minutes'),
    ('00000013-0000-4000-8000-000000001201', '00000011-0000-4000-8000-000000001201',
     '0000000f-0000-4000-8000-000000000112', '0000000f-0000-4000-8000-000000000112',
     '00000010-0000-4000-8000-000000001201', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, timestamptz '2000-01-10 00:00:00+00' - interval '50 minutes'),
    ('00000013-0000-4000-8000-000000001202', '00000011-0000-4000-8000-000000001201',
     '0000000f-0000-4000-8000-000000000112', '0000000f-0000-4000-8000-000000000112',
     '00000010-0000-4000-8000-000000001201', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, timestamptz '2000-01-10 00:00:00+00' - interval '50 minutes')
on conflict do nothing;

-- DB-SPECIFIC: interval literals, to_json(), ->>, on conflict, text and uuid use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_step_send_attempts seed group supplies PostgreSQL native run_step_kind, model_call_purpose, subject_kind enum inputs.
insert into run_step_send_attempts (run_step_send_attempt_id, run_step_id, run_id, run_step_kind, workflow_step_id,
                                    purpose, model, mode, production_id, payload, created_at, created_by,
                                    created_by_kind)
select sent.attempt_id, try.run_step_id, try.run_id, try.run_step_kind, step.workflow_step_id, 'produce',
       step.producer_model, step.producer_mode, try.production_id,
       '{"envelope_version":4,"instruction":' || cast(to_json(asked.instruction) as text)
           || ',"takes":{"complaint":' || cast(to_json(run.started_with ->> 'complaint') as text)
           || ',"channel":"email"}}',
       try.created_at + interval '2 seconds', try.created_by, try.created_by_kind
  from (values (cast('00000014-0000-4000-8000-000000000201' as uuid),
                cast('00000011-0000-4000-8000-000000000201' as uuid)),
               ('00000014-0000-4000-8000-000000000601', '00000011-0000-4000-8000-000000000601'),
               ('00000014-0000-4000-8000-000000000701', '00000011-0000-4000-8000-000000000701'),
               ('00000014-0000-4000-8000-000000000801', '00000011-0000-4000-8000-000000000801'),
               ('00000014-0000-4000-8000-000000001201', '00000011-0000-4000-8000-000000001201'))
       as sent (attempt_id, production_id)
  join productions try on try.production_id = sent.production_id
  join run_steps ran on ran.run_step_id = try.run_step_id
  join workflow_steps step on step.workflow_step_id = ran.workflow_step_id
  join question_versions asked on asked.entry_version_id = try.pinned_version_id
  join runs run on run.run_id = try.run_id
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and uuid use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This model_calls seed group supplies PostgreSQL native model_call_purpose, model_call_outcome, subject_kind enum inputs.
insert into model_calls (model_call_id, run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                         production_id, model, mode, envelope_version, sent_count, came_back_count, outcome, answer,
                         ended_at, created_at, created_by, created_by_kind)
select called.call_id, sent.run_id, try.root_run_id, sent.purpose, sent.run_step_send_attempt_id, sent.run_step_id,
       sent.production_id, sent.model, sent.mode, 4, called.sent_count, called.came_back_count, 'came_back',
       called.answer, sent.created_at + interval '40 seconds', sent.created_at, sent.created_by, sent.created_by_kind
  from (values (cast('00000015-0000-4000-8000-000000000201' as uuid),
                cast('00000014-0000-4000-8000-000000000201' as uuid), 738, 35,
                '{"values":{"category":"Billing","details":{"product":"Wireless headphones",'
                    || '"order_reference":"5530-1187"}},"confidences":{"category":94}}'),
               ('00000015-0000-4000-8000-000000000601', '00000014-0000-4000-8000-000000000601', 724, 32,
                '{"values":{"category":"Delivery","details":{"product":"Bookshelf",'
                    || '"order_reference":"7781-0032"}},"confidences":{"category":85}}'),
               ('00000015-0000-4000-8000-000000000701', '00000014-0000-4000-8000-000000000701', 735, 35,
                '{"values":{"category":"Delivery","details":{"product":"Cake stand",'
                    || '"order_reference":"DEMO-TRACK-000000001"}},"confidences":{"category":88}}'),
               ('00000015-0000-4000-8000-000000000801', '00000014-0000-4000-8000-000000000801', 736, 32,
                '{"values":{"category":"Billing","details":{"product":"Desk lamp",'
                    || '"order_reference":"9042-6615"}},"confidences":{"category":91}}'),
               ('00000015-0000-4000-8000-000000001201', '00000014-0000-4000-8000-000000001201', 728, 33,
                '{"values":{"category":"Billing","details":{"product":"Coffee grinder",'
                    || '"order_reference":"4409-7763"}},"confidences":{"category":96}}'))
       as called (call_id, attempt_id, sent_count, came_back_count, answer)
  join run_step_send_attempts sent on sent.run_step_send_attempt_id = called.attempt_id
  join productions try on try.production_id = sent.production_id
on conflict do nothing;

-- DB-SPECIFIC: update from uses PostgreSQL's syntax and semantics.
update productions try
   set ended_at = call.ended_at, ended_by = call.created_by, ended_by_kind = call.created_by_kind,
       model_call_id = call.model_call_id, model_call_outcome = call.outcome
  from model_calls call
 where call.production_id = try.production_id and try.ended_at is null
   and call.model_call_id in ('00000015-0000-4000-8000-000000000201', '00000015-0000-4000-8000-000000000601',
                              '00000015-0000-4000-8000-000000000701', '00000015-0000-4000-8000-000000000801',
                              '00000015-0000-4000-8000-000000001201');

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL native run_step_kind, step_producer, field_standing enum inputs.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL jsonb value input.
insert into production_values (production_value_id, production_id, run_step_kind, producer, pinned_version_id,
                               declaration_field_id, field_name, field_standing, standing_threshold, value,
                               confidence) values
    ('00000012-0000-4000-8000-000000000201', '00000011-0000-4000-8000-000000000201', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', null, 'above_confidence', 80,
     '"Billing"', 94),
    ('00000012-0000-4000-8000-000000000202', '00000011-0000-4000-8000-000000000201', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', null, 'never', null,
     '{"product": "Wireless headphones", "order_reference": "5530-1187"}', null),
    ('00000012-0000-4000-8000-000000000301', '00000011-0000-4000-8000-000000000301', 'question', 'person',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', null, 'above_confidence', 80,
     '"Billing"', null),
    ('00000012-0000-4000-8000-000000000302', '00000011-0000-4000-8000-000000000301', 'question', 'person',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', null, 'never', null,
     '{"product": "Wireless headphones", "order_reference": "5530-1187"}', null),
    ('00000012-0000-4000-8000-000000000601', '00000011-0000-4000-8000-000000000601', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', null, 'above_confidence', 80,
     '"Delivery"', 85),
    ('00000012-0000-4000-8000-000000000602', '00000011-0000-4000-8000-000000000601', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', null, 'never', null,
     '{"product": "Bookshelf", "order_reference": "7781-0032"}', null),
    ('00000012-0000-4000-8000-000000000701', '00000011-0000-4000-8000-000000000701', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', null, 'above_confidence', 80,
     '"Delivery"', 88),
    ('00000012-0000-4000-8000-000000000702', '00000011-0000-4000-8000-000000000701', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', null, 'never', null,
     '{"product": "Cake stand", "order_reference": "DEMO-TRACK-000000001"}', null),
    ('00000012-0000-4000-8000-000000000801', '00000011-0000-4000-8000-000000000801', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', null, 'above_confidence', 80,
     '"Billing"', 91),
    ('00000012-0000-4000-8000-000000000802', '00000011-0000-4000-8000-000000000801', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', null, 'never', null,
     '{"product": "Desk lamp", "order_reference": "9042-6615"}', null),
    ('00000012-0000-4000-8000-000000001001', '00000011-0000-4000-8000-000000001001', 'question', 'person',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', null, 'above_confidence', 80,
     '"Product fault"', null),
    ('00000012-0000-4000-8000-000000001002', '00000011-0000-4000-8000-000000001001', 'question', 'person',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', null, 'never', null,
     '{"product": "Laptop charger", "order_reference": "3307-9921"}', null),
    ('00000012-0000-4000-8000-000000001201', '00000011-0000-4000-8000-000000001201', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000105', null, 'above_confidence', 80,
     '"Billing"', 96),
    ('00000012-0000-4000-8000-000000001202', '00000011-0000-4000-8000-000000001201', 'question', 'model',
     '00000007-0000-4000-8000-000000000102', '0000000b-0000-4000-8000-000000000106', null, 'never', null,
     '{"product": "Coffee grinder", "order_reference": "4409-7763"}', null)
on conflict do nothing;

-- The reference is the one the release's stamp_reference code step draws from the complaint it was given.
-- DB-SPECIFIC: ->>, convert_to(), sha256(), encode(), to_jsonb() and on conflict use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_values seed group supplies PostgreSQL native run_step_kind, step_producer, field_standing enum inputs.
insert into production_values (production_value_id, production_id, run_step_kind, producer, field_name, field_standing, value)
select '00000012-0000-4000-8000-000000000401', '00000011-0000-4000-8000-000000000401', 'code_step', 'code', 'reference', 'always',
       to_jsonb('SUP-' || upper(left(encode(sha256(convert_to(run.started_with ->> 'complaint', 'UTF8')), 'hex'), 8)))
  from runs run
 where run.run_id = '0000000f-0000-4000-8000-000000000104'
on conflict (production_value_id) do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This production_inputs seed group supplies PostgreSQL native run_step_kind enum inputs.
insert into production_inputs (production_input_id, branch_run_id, run_id, root_run_id, run_step_id, run_step_kind,
                               workflow_step_id, binding_id, binding_reads_a_constant, source_workflow_step_id,
                               source_run_step_id, source_run_step_kind, source_production_id,
                               source_production_value_id, created_at) values
    ('00000013-0000-4000-8000-000000000203', '0000000f-0000-4000-8000-000000000103',
     '0000000f-0000-4000-8000-000000000102', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000202', 'route', '0000000c-0000-4000-8000-000000000102',
     '0000000e-0000-4000-8000-000000000103', false, '0000000c-0000-4000-8000-000000000101',
     '00000010-0000-4000-8000-000000000201', 'question', '00000011-0000-4000-8000-000000000201',
     '00000012-0000-4000-8000-000000000201', timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '2 hours'),
    ('00000013-0000-4000-8000-000000000803', '0000000f-0000-4000-8000-000000000109',
     '0000000f-0000-4000-8000-000000000108', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000802', 'route', '0000000c-0000-4000-8000-000000000102',
     '0000000e-0000-4000-8000-000000000103', false, '0000000c-0000-4000-8000-000000000101',
     '00000010-0000-4000-8000-000000000801', 'question', '00000011-0000-4000-8000-000000000801',
     '00000012-0000-4000-8000-000000000801', timestamptz '2000-01-10 00:00:00+00' - interval '1 day' + interval '30 minutes')
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This reviews seed group supplies PostgreSQL native subject_kind enum inputs.
insert into reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                     created_at, created_by, created_by_kind) values
    ('00000016-0000-4000-8000-000000000201', '00000011-0000-4000-8000-000000000201',
     '00000010-0000-4000-8000-000000000201', '0000000f-0000-4000-8000-000000000102',
     '00000000-0000-4000-8000-000000000001', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '2 hours', '00000001-0000-4000-8000-000000000103', 'person'),
    ('00000016-0000-4000-8000-000000000301', '00000011-0000-4000-8000-000000000301',
     '00000010-0000-4000-8000-000000000301', '0000000f-0000-4000-8000-000000000102',
     '00000001-0000-4000-8000-000000000103', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '6 days' + interval '4 hours', '00000001-0000-4000-8000-000000000102', 'person'),
    ('00000016-0000-4000-8000-000000000701', '00000011-0000-4000-8000-000000000701',
     '00000010-0000-4000-8000-000000000701', '0000000f-0000-4000-8000-000000000107',
     '00000000-0000-4000-8000-000000000001', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day', '00000001-0000-4000-8000-000000000103', 'person'),
    ('00000016-0000-4000-8000-000000000801', '00000011-0000-4000-8000-000000000801',
     '00000010-0000-4000-8000-000000000801', '0000000f-0000-4000-8000-000000000108',
     '00000000-0000-4000-8000-000000000001', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '1 day' + interval '30 minutes', '00000001-0000-4000-8000-000000000102', 'person'),
    ('00000016-0000-4000-8000-000000001001', '00000011-0000-4000-8000-000000001001',
     '00000010-0000-4000-8000-000000001001', '0000000f-0000-4000-8000-000000000110',
     '00000001-0000-4000-8000-000000000103', false,
     timestamptz '2000-01-10 00:00:00+00' - interval '110 minutes', '00000001-0000-4000-8000-000000000102', 'person')
on conflict do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This review_decisions seed group supplies PostgreSQL native review_outcome enum inputs.
-- DB-SPECIFIC: PostgreSQL dollar quoting delimits the explanation strings in this seed group.
insert into review_decisions (review_id, production_value_id, production_id, for_length, value_needs_review,
                              outcome, explanation) values
    ('00000016-0000-4000-8000-000000000201', '00000012-0000-4000-8000-000000000202',
     '00000011-0000-4000-8000-000000000201', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000000301', '00000012-0000-4000-8000-000000000301',
     '00000011-0000-4000-8000-000000000301', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000000301', '00000012-0000-4000-8000-000000000302',
     '00000011-0000-4000-8000-000000000301', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000000701', '00000012-0000-4000-8000-000000000702',
     '00000011-0000-4000-8000-000000000701', false, true, 'refused',
     $reason$## Check the order reference
That is the courier's tracking number, not the order reference.
Ask Mira Cloudmere for the invented order record.$reason$),
    ('00000016-0000-4000-8000-000000000801', '00000012-0000-4000-8000-000000000802',
     '00000011-0000-4000-8000-000000000801', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000001001', '00000012-0000-4000-8000-000000001001',
     '00000011-0000-4000-8000-000000001001', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000001001', '00000012-0000-4000-8000-000000001002',
     '00000011-0000-4000-8000-000000001001', false, true, 'assured', null)
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and timestamptz '2000-01-10 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This run_stops seed group supplies PostgreSQL native subject_kind enum inputs.
insert into run_stops (run_stop_id, run_id, root_run_id, created_at, created_by, created_by_kind) values
    ('00000017-0000-4000-8000-000000000601', '0000000f-0000-4000-8000-000000000106',
     '0000000f-0000-4000-8000-000000000106', timestamptz '2000-01-10 00:00:00+00' - interval '2 days' + interval '3 hours',
     '00000001-0000-4000-8000-000000000102', 'person')
on conflict do nothing;
