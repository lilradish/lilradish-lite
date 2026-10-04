-- Top-level runs are authored by Demo Sample Grace One and Demo Sample Alan Two as people.


-- Targeted ON CONFLICT does not skip collisions with the run's unique number.
-- DB-SPECIFIC: interval literals, jsonb_build_object(), on conflict, chr() and now() use PostgreSQL's syntax and semantics.
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
     now() - interval '7 days', '00000001-0000-4000-8000-000000000102', 'person')
on conflict (run_id) do nothing;

-- DB-SPECIFIC: interval literals, jsonb_build_object(), on conflict, chr() and now() use PostgreSQL's syntax and semantics.
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
     now() - interval '6 days', '00000001-0000-4000-8000-000000000102', 'person'),
    ('0000000f-0000-4000-8000-000000000104', '00000002-0000-4000-8000-000000000101', 4, 'Wrong size delivered',
     '00000006-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000108',
     '0000000f-0000-4000-8000-000000000104', 0,
     jsonb_build_object('complaint', 'Hi,' || chr(10) || chr(10)
         || 'The boots I ordered from Demo Sample (order 6120-4471) came in a size 9, but I ordered a size 7.'
         || chr(10) || chr(10) || 'Best,' || chr(10) || 'Priya Shah'),
     now() - interval '4 days', '00000001-0000-4000-8000-000000000103', 'person'),
    ('0000000f-0000-4000-8000-000000000105', '00000002-0000-4000-8000-000000000101', 5, 'Refund not received',
     '00000006-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000108',
     '0000000f-0000-4000-8000-000000000105', 0,
     jsonb_build_object('complaint', 'Hello,' || chr(10) || chr(10)
         || 'I sent the blender back three weeks ago (order 8835-2290) and the refund has still not reached my'
         || ' account.' || chr(10) || chr(10) || 'Regards,' || chr(10) || 'Tom Becker'),
     now() - interval '3 days', '00000001-0000-4000-8000-000000000102', 'person'),
    ('0000000f-0000-4000-8000-000000000106', '00000002-0000-4000-8000-000000000101', 6, 'Parcel left in the rain',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000106', 0,
     jsonb_build_object('title', 'Parcel left in the rain', 'amount', 124.00, 'category', 'Delivery',
         'complaint', 'Hi,' || chr(10) || chr(10)
         || 'The courier left my parcel (order 7781-0032) on the doorstep in the rain, and the bookshelf inside is'
         || ' warped.' || chr(10) || chr(10) || 'Thanks,' || chr(10) || 'Maria Lopez'),
     now() - interval '2 days', '00000001-0000-4000-8000-000000000103', 'person'),
    ('0000000f-0000-4000-8000-000000000107', '00000002-0000-4000-8000-000000000101', 7, 'Order arrived late',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000107', 0,
     jsonb_build_object('title', 'Order arrived late', 'amount', 28.75, 'category', 'Delivery',
         'complaint', 'Hello,' || chr(10) || chr(10)
         || 'The cake stand I ordered for the 12th (order 3391-0475) only arrived on the 15th, after the party.'
         || ' The tracking number was DEMO-TRACK-000000001.' || chr(10) || chr(10) || 'Kind regards,' || chr(10)
         || 'Owen Price'),
     now() - interval '1 day 20 minutes', '00000001-0000-4000-8000-000000000102', 'person'),
    ('0000000f-0000-4000-8000-000000000108', '00000002-0000-4000-8000-000000000101', 8, 'Old address on the invoice',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000108', 0,
     jsonb_build_object('title', 'Old address on the invoice', 'amount', 46.20, 'category', 'Billing',
         'complaint', 'Hello,' || chr(10) || chr(10)
         || 'I changed my billing address, but the invoice for the desk lamp I bought (order 9042-6615) still shows'
         || ' the old one. Could you send a corrected invoice?' || chr(10) || chr(10)
         || 'Thank you,' || chr(10) || 'Lena Fischer'),
     now() - interval '1 day', '00000001-0000-4000-8000-000000000103', 'person'),
    ('0000000f-0000-4000-8000-000000000110', '00000002-0000-4000-8000-000000000101', 10, 'Charger stopped working',
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000110', 0,
     jsonb_build_object('complaint', 'Hi,' || chr(10) || chr(10)
         || 'The laptop charger from my Demo Sample order (order 3307-9921) stopped working after two days.'
         || chr(10) || chr(10) || 'Cheers,' || chr(10) || 'Chris Novak'),
     now() - interval '150 minutes', '00000001-0000-4000-8000-000000000102', 'person'),
    ('0000000f-0000-4000-8000-000000000111', '00000002-0000-4000-8000-000000000101', 11, 'Lid does not close',
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000111', 0,
     jsonb_build_object('complaint', 'Hello,' || chr(10) || chr(10)
         || 'The lid of the storage box I bought (order 2214-5508) does not close properly, so the box cannot be'
         || ' stacked.' || chr(10) || chr(10) || 'Best wishes,' || chr(10) || 'Aisha Khan'),
     now() - interval '100 minutes', '00000001-0000-4000-8000-000000000103', 'person'),
    ('0000000f-0000-4000-8000-000000000112', '00000002-0000-4000-8000-000000000101', 12, 'Refund to the wrong card',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000112', 0,
     jsonb_build_object('title', 'Refund to the wrong card', 'amount', 72.90, 'category', 'Billing',
         'complaint', 'Hello,' || chr(10) || chr(10)
         || 'The refund for the coffee grinder I returned (order 4409-7763) went to a card I cancelled last year.'
         || ' How do I get it back?' || chr(10) || chr(10) || 'Regards,' || chr(10) || 'David Kim'),
     now() - interval '50 minutes', '00000001-0000-4000-8000-000000000102', 'person')
on conflict (run_id) do nothing;

-- Untargeted from here on, for the reason the pool seed gives.
-- DB-SPECIFIC: interval literals, on conflict and now() use PostgreSQL's syntax and semantics.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                       pinned_kind, producer, reviewed_by_model, tries, created_at, created_by, created_by_kind) values
    ('00000010-0000-4000-8000-000000000201', '0000000f-0000-4000-8000-000000000102',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     now() - interval '6 days', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000202', '0000000f-0000-4000-8000-000000000102',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000102', 'route',
     null, null, null, false, null,
     now() - interval '6 days' + interval '2 hours', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000401', '0000000f-0000-4000-8000-000000000104',
     '00000007-0000-4000-8000-000000000108', '0000000c-0000-4000-8000-000000000104', 'code_step',
     null, null, 'code', false, 2,
     now() - interval '4 days', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000501', '0000000f-0000-4000-8000-000000000105',
     '00000007-0000-4000-8000-000000000108', '0000000c-0000-4000-8000-000000000104', 'code_step',
     null, null, 'code', false, 2,
     now() - interval '3 days', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000601', '0000000f-0000-4000-8000-000000000106',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     now() - interval '2 days', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000701', '0000000f-0000-4000-8000-000000000107',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     now() - interval '1 day 20 minutes', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000801', '0000000f-0000-4000-8000-000000000108',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     now() - interval '1 day', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000802', '0000000f-0000-4000-8000-000000000108',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000102', 'route',
     null, null, null, false, null,
     now() - interval '1 day' + interval '30 minutes', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000001001', '0000000f-0000-4000-8000-000000000110',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     now() - interval '150 minutes', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000001101', '0000000f-0000-4000-8000-000000000111',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     now() - interval '100 minutes', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000001201', '0000000f-0000-4000-8000-000000000112',
     '00000007-0000-4000-8000-000000000104', '0000000c-0000-4000-8000-000000000101', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'model', false, 3,
     now() - interval '50 minutes', '00000000-0000-4000-8000-000000000001', 'system')
on conflict do nothing;

-- Targeted for the reason run 1 is.
-- DB-SPECIFIC: interval literals, on conflict and now() use PostgreSQL's syntax and semantics.
insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id, parent_run_step_id,
                  parent_run_step_kind, parent_workflow_step_id, parent_route_case_id, root_run_id, depth,
                  parent_depth, created_at, created_by, created_by_kind) values
    ('0000000f-0000-4000-8000-000000000103', '00000002-0000-4000-8000-000000000101', 3,
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000102', '00000010-0000-4000-8000-000000000202', 'route',
     '0000000c-0000-4000-8000-000000000102', '0000000d-0000-4000-8000-000000000101',
     '0000000f-0000-4000-8000-000000000102', 1, 0,
     now() - interval '6 days' + interval '2 hours', '00000000-0000-4000-8000-000000000001', 'system'),
    ('0000000f-0000-4000-8000-000000000109', '00000002-0000-4000-8000-000000000101', 9,
     '00000006-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000107',
     '0000000f-0000-4000-8000-000000000108', '00000010-0000-4000-8000-000000000802', 'route',
     '0000000c-0000-4000-8000-000000000102', '0000000d-0000-4000-8000-000000000101',
     '0000000f-0000-4000-8000-000000000108', 1, 0,
     now() - interval '1 day' + interval '30 minutes', '00000000-0000-4000-8000-000000000001', 'system')
on conflict (run_id) do nothing;

-- DB-SPECIFIC: interval literals, on conflict and now() use PostgreSQL's syntax and semantics.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                       pinned_kind, producer, reviewed_by_model, tries, created_at, created_by, created_by_kind) values
    ('00000010-0000-4000-8000-000000000301', '0000000f-0000-4000-8000-000000000103',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     now() - interval '6 days' + interval '2 hours', '00000000-0000-4000-8000-000000000001', 'system'),
    ('00000010-0000-4000-8000-000000000901', '0000000f-0000-4000-8000-000000000109',
     '00000007-0000-4000-8000-000000000107', '0000000c-0000-4000-8000-000000000103', 'entry',
     '00000007-0000-4000-8000-000000000102', 'question', 'person', false, 2,
     now() - interval '1 day' + interval '30 minutes', '00000000-0000-4000-8000-000000000001', 'system')
on conflict do nothing;

-- The production names its call and the call names its production. Insert the production open,
-- then insert its call before closing the production.
-- DB-SPECIFIC: interval literals, on conflict and now() use PostgreSQL's syntax and semantics.
insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                         reviewed_by_model, tries, may_run_again, pinned_version_id, try_number, producer,
                         created_at, created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation,
                         lost_reason) values
    ('00000011-0000-4000-8000-000000000201', '00000010-0000-4000-8000-000000000201',
     '0000000f-0000-4000-8000-000000000102', '0000000f-0000-4000-8000-000000000102', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     now() - interval '6 days', '00000000-0000-4000-8000-000000000001', 'system', null, null, default, null, null),
    ('00000011-0000-4000-8000-000000000301', '00000010-0000-4000-8000-000000000301',
     '0000000f-0000-4000-8000-000000000103', '0000000f-0000-4000-8000-000000000102', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     now() - interval '6 days' + interval '2 hours', '00000000-0000-4000-8000-000000000001', 'system',
     now() - interval '6 days' + interval '3 hours', '00000001-0000-4000-8000-000000000103', 'person',
     'The payment log shows the second charge as a duplicate of the first.', null),
    ('00000011-0000-4000-8000-000000000401', '00000010-0000-4000-8000-000000000401',
     '0000000f-0000-4000-8000-000000000104', '0000000f-0000-4000-8000-000000000104', 'code_step', 'code',
     false, 2, true, null, 1, 'code',
     now() - interval '4 days', '00000000-0000-4000-8000-000000000001', 'system',
     now() - interval '4 days' + interval '2 seconds', '00000000-0000-4000-8000-000000000001', 'system', null, null),
    ('00000011-0000-4000-8000-000000000501', '00000010-0000-4000-8000-000000000501',
     '0000000f-0000-4000-8000-000000000105', '0000000f-0000-4000-8000-000000000105', 'code_step', 'code',
     false, 2, true, null, 1, 'code',
     now() - interval '3 days', '00000000-0000-4000-8000-000000000001', 'system',
     now() - interval '3 days' + interval '5 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, 'nothing_came_back'),
    ('00000011-0000-4000-8000-000000000502', '00000010-0000-4000-8000-000000000501',
     '0000000f-0000-4000-8000-000000000105', '0000000f-0000-4000-8000-000000000105', 'code_step', 'code',
     false, 2, true, null, 2, 'code',
     now() - interval '3 days' + interval '5 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     now() - interval '3 days' + interval '40 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, 'nothing_came_back'),
    ('00000011-0000-4000-8000-000000000601', '00000010-0000-4000-8000-000000000601',
     '0000000f-0000-4000-8000-000000000106', '0000000f-0000-4000-8000-000000000106', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     now() - interval '2 days', '00000000-0000-4000-8000-000000000001', 'system', null, null, default, null, null),
    ('00000011-0000-4000-8000-000000000701', '00000010-0000-4000-8000-000000000701',
     '0000000f-0000-4000-8000-000000000107', '0000000f-0000-4000-8000-000000000107', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     now() - interval '1 day 20 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, null, default, null, null),
    ('00000011-0000-4000-8000-000000000801', '00000010-0000-4000-8000-000000000801',
     '0000000f-0000-4000-8000-000000000108', '0000000f-0000-4000-8000-000000000108', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     now() - interval '1 day', '00000000-0000-4000-8000-000000000001', 'system', null, null, default, null, null),
    ('00000011-0000-4000-8000-000000000901', '00000010-0000-4000-8000-000000000901',
     '0000000f-0000-4000-8000-000000000109', '0000000f-0000-4000-8000-000000000108', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     now() - interval '1 day' + interval '30 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, null, default, null, null),
    ('00000011-0000-4000-8000-000000001001', '00000010-0000-4000-8000-000000001001',
     '0000000f-0000-4000-8000-000000000110', '0000000f-0000-4000-8000-000000000110', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     now() - interval '150 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     now() - interval '130 minutes', '00000001-0000-4000-8000-000000000103', 'person',
     'The charger failed in use, and the box it came in was intact.', null),
    ('00000011-0000-4000-8000-000000001101', '00000010-0000-4000-8000-000000001101',
     '0000000f-0000-4000-8000-000000000111', '0000000f-0000-4000-8000-000000000111', 'question', 'person',
     false, 2, null, '00000007-0000-4000-8000-000000000102', 1, 'person',
     now() - interval '100 minutes', '00000000-0000-4000-8000-000000000001', 'system',
     null, null, default, null, null),
    ('00000011-0000-4000-8000-000000001201', '00000010-0000-4000-8000-000000001201',
     '0000000f-0000-4000-8000-000000000112', '0000000f-0000-4000-8000-000000000112', 'question', 'model',
     false, 3, null, '00000007-0000-4000-8000-000000000102', 1, 'model',
     now() - interval '50 minutes', '00000000-0000-4000-8000-000000000001', 'system', null, null, default, null, null)
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and now() use PostgreSQL's syntax and semantics.
insert into production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id, run_step_kind,
                               workflow_step_id, binding_id, binding_reads_a_constant, created_at) values
    ('00000013-0000-4000-8000-000000000201', '00000011-0000-4000-8000-000000000201',
     '0000000f-0000-4000-8000-000000000102', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000201', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, now() - interval '6 days'),
    ('00000013-0000-4000-8000-000000000202', '00000011-0000-4000-8000-000000000201',
     '0000000f-0000-4000-8000-000000000102', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000201', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, now() - interval '6 days'),
    ('00000013-0000-4000-8000-000000000301', '00000011-0000-4000-8000-000000000301',
     '0000000f-0000-4000-8000-000000000103', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000301', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false, now() - interval '6 days' + interval '2 hours'),
    ('00000013-0000-4000-8000-000000000302', '00000011-0000-4000-8000-000000000301',
     '0000000f-0000-4000-8000-000000000103', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000301', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true, now() - interval '6 days' + interval '2 hours'),
    ('00000013-0000-4000-8000-000000000401', '00000011-0000-4000-8000-000000000401',
     '0000000f-0000-4000-8000-000000000104', '0000000f-0000-4000-8000-000000000104',
     '00000010-0000-4000-8000-000000000401', 'code_step', '0000000c-0000-4000-8000-000000000104',
     '0000000e-0000-4000-8000-000000000109', false, now() - interval '4 days'),
    ('00000013-0000-4000-8000-000000000501', '00000011-0000-4000-8000-000000000501',
     '0000000f-0000-4000-8000-000000000105', '0000000f-0000-4000-8000-000000000105',
     '00000010-0000-4000-8000-000000000501', 'code_step', '0000000c-0000-4000-8000-000000000104',
     '0000000e-0000-4000-8000-000000000109', false, now() - interval '3 days'),
    ('00000013-0000-4000-8000-000000000502', '00000011-0000-4000-8000-000000000502',
     '0000000f-0000-4000-8000-000000000105', '0000000f-0000-4000-8000-000000000105',
     '00000010-0000-4000-8000-000000000501', 'code_step', '0000000c-0000-4000-8000-000000000104',
     '0000000e-0000-4000-8000-000000000109', false, now() - interval '3 days' + interval '5 minutes'),
    ('00000013-0000-4000-8000-000000000601', '00000011-0000-4000-8000-000000000601',
     '0000000f-0000-4000-8000-000000000106', '0000000f-0000-4000-8000-000000000106',
     '00000010-0000-4000-8000-000000000601', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, now() - interval '2 days'),
    ('00000013-0000-4000-8000-000000000602', '00000011-0000-4000-8000-000000000601',
     '0000000f-0000-4000-8000-000000000106', '0000000f-0000-4000-8000-000000000106',
     '00000010-0000-4000-8000-000000000601', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, now() - interval '2 days'),
    ('00000013-0000-4000-8000-000000000701', '00000011-0000-4000-8000-000000000701',
     '0000000f-0000-4000-8000-000000000107', '0000000f-0000-4000-8000-000000000107',
     '00000010-0000-4000-8000-000000000701', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, now() - interval '1 day 20 minutes'),
    ('00000013-0000-4000-8000-000000000702', '00000011-0000-4000-8000-000000000701',
     '0000000f-0000-4000-8000-000000000107', '0000000f-0000-4000-8000-000000000107',
     '00000010-0000-4000-8000-000000000701', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, now() - interval '1 day 20 minutes'),
    ('00000013-0000-4000-8000-000000000801', '00000011-0000-4000-8000-000000000801',
     '0000000f-0000-4000-8000-000000000108', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000801', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, now() - interval '1 day'),
    ('00000013-0000-4000-8000-000000000802', '00000011-0000-4000-8000-000000000801',
     '0000000f-0000-4000-8000-000000000108', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000801', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, now() - interval '1 day'),
    ('00000013-0000-4000-8000-000000000901', '00000011-0000-4000-8000-000000000901',
     '0000000f-0000-4000-8000-000000000109', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000901', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false, now() - interval '1 day' + interval '30 minutes'),
    ('00000013-0000-4000-8000-000000000902', '00000011-0000-4000-8000-000000000901',
     '0000000f-0000-4000-8000-000000000109', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000901', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true, now() - interval '1 day' + interval '30 minutes'),
    ('00000013-0000-4000-8000-000000001001', '00000011-0000-4000-8000-000000001001',
     '0000000f-0000-4000-8000-000000000110', '0000000f-0000-4000-8000-000000000110',
     '00000010-0000-4000-8000-000000001001', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false, now() - interval '150 minutes'),
    ('00000013-0000-4000-8000-000000001002', '00000011-0000-4000-8000-000000001001',
     '0000000f-0000-4000-8000-000000000110', '0000000f-0000-4000-8000-000000000110',
     '00000010-0000-4000-8000-000000001001', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true, now() - interval '150 minutes'),
    ('00000013-0000-4000-8000-000000001101', '00000011-0000-4000-8000-000000001101',
     '0000000f-0000-4000-8000-000000000111', '0000000f-0000-4000-8000-000000000111',
     '00000010-0000-4000-8000-000000001101', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000107', false, now() - interval '100 minutes'),
    ('00000013-0000-4000-8000-000000001102', '00000011-0000-4000-8000-000000001101',
     '0000000f-0000-4000-8000-000000000111', '0000000f-0000-4000-8000-000000000111',
     '00000010-0000-4000-8000-000000001101', 'question', '0000000c-0000-4000-8000-000000000103',
     '0000000e-0000-4000-8000-000000000108', true, now() - interval '100 minutes'),
    ('00000013-0000-4000-8000-000000001201', '00000011-0000-4000-8000-000000001201',
     '0000000f-0000-4000-8000-000000000112', '0000000f-0000-4000-8000-000000000112',
     '00000010-0000-4000-8000-000000001201', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000101', false, now() - interval '50 minutes'),
    ('00000013-0000-4000-8000-000000001202', '00000011-0000-4000-8000-000000001201',
     '0000000f-0000-4000-8000-000000000112', '0000000f-0000-4000-8000-000000000112',
     '00000010-0000-4000-8000-000000001201', 'question', '0000000c-0000-4000-8000-000000000101',
     '0000000e-0000-4000-8000-000000000102', true, now() - interval '50 minutes')
on conflict do nothing;

-- DB-SPECIFIC: interval literals, to_json(), ->>, on conflict, text and uuid use PostgreSQL's syntax and semantics.
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

-- Both counts as this system measures them under the development catalog; the model counted neither.
-- DB-SPECIFIC: interval literals, on conflict and uuid use PostgreSQL's syntax and semantics.
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

-- DB-SPECIFIC: update productions try set ended_at = call.ended_at, ended_by = call.created_by, ended_by_kind = call.created_by_kind,
-- model_call_id = call.model_call_id, model_call_outcome = call.outcome from uses PostgreSQL's syntax and semantics.
update productions try
   set ended_at = call.ended_at, ended_by = call.created_by, ended_by_kind = call.created_by_kind,
       model_call_id = call.model_call_id, model_call_outcome = call.outcome
  from model_calls call
 where call.production_id = try.production_id and try.ended_at is null
   and call.model_call_id in ('00000015-0000-4000-8000-000000000201', '00000015-0000-4000-8000-000000000601',
                              '00000015-0000-4000-8000-000000000701', '00000015-0000-4000-8000-000000000801',
                              '00000015-0000-4000-8000-000000001201');

-- A model gives how sure it is of a category; a person, who gives none, leaves it to review.
-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
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
    ('00000012-0000-4000-8000-000000000401', '00000011-0000-4000-8000-000000000401', 'code_step', 'code',
     null, null, 'reference', 'always', null, '"SUP-2772B42D"', null),
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

-- DB-SPECIFIC: interval literals, on conflict and now() use PostgreSQL's syntax and semantics.
insert into production_inputs (production_input_id, branch_run_id, run_id, root_run_id, run_step_id, run_step_kind,
                               workflow_step_id, binding_id, binding_reads_a_constant, source_workflow_step_id,
                               source_run_step_id, source_run_step_kind, source_production_id,
                               source_production_value_id, created_at) values
    ('00000013-0000-4000-8000-000000000203', '0000000f-0000-4000-8000-000000000103',
     '0000000f-0000-4000-8000-000000000102', '0000000f-0000-4000-8000-000000000102',
     '00000010-0000-4000-8000-000000000202', 'route', '0000000c-0000-4000-8000-000000000102',
     '0000000e-0000-4000-8000-000000000103', false, '0000000c-0000-4000-8000-000000000101',
     '00000010-0000-4000-8000-000000000201', 'question', '00000011-0000-4000-8000-000000000201',
     '00000012-0000-4000-8000-000000000201', now() - interval '6 days' + interval '2 hours'),
    ('00000013-0000-4000-8000-000000000803', '0000000f-0000-4000-8000-000000000109',
     '0000000f-0000-4000-8000-000000000108', '0000000f-0000-4000-8000-000000000108',
     '00000010-0000-4000-8000-000000000802', 'route', '0000000c-0000-4000-8000-000000000102',
     '0000000e-0000-4000-8000-000000000103', false, '0000000c-0000-4000-8000-000000000101',
     '00000010-0000-4000-8000-000000000801', 'question', '00000011-0000-4000-8000-000000000801',
     '00000012-0000-4000-8000-000000000801', now() - interval '1 day' + interval '30 minutes')
on conflict do nothing;

-- Nobody reviews what they produced, so Demo Sample Grace One reviews what Demo Sample Alan Two answered.
-- DB-SPECIFIC: interval literals, on conflict and now() use PostgreSQL's syntax and semantics.
insert into reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                     created_at, created_by, created_by_kind) values
    ('00000016-0000-4000-8000-000000000201', '00000011-0000-4000-8000-000000000201',
     '00000010-0000-4000-8000-000000000201', '0000000f-0000-4000-8000-000000000102',
     '00000000-0000-4000-8000-000000000001', false,
     now() - interval '6 days' + interval '2 hours', '00000001-0000-4000-8000-000000000103', 'person'),
    ('00000016-0000-4000-8000-000000000301', '00000011-0000-4000-8000-000000000301',
     '00000010-0000-4000-8000-000000000301', '0000000f-0000-4000-8000-000000000102',
     '00000001-0000-4000-8000-000000000103', false,
     now() - interval '6 days' + interval '4 hours', '00000001-0000-4000-8000-000000000102', 'person'),
    ('00000016-0000-4000-8000-000000000701', '00000011-0000-4000-8000-000000000701',
     '00000010-0000-4000-8000-000000000701', '0000000f-0000-4000-8000-000000000107',
     '00000000-0000-4000-8000-000000000001', false,
     now() - interval '1 day', '00000001-0000-4000-8000-000000000103', 'person'),
    ('00000016-0000-4000-8000-000000000801', '00000011-0000-4000-8000-000000000801',
     '00000010-0000-4000-8000-000000000801', '0000000f-0000-4000-8000-000000000108',
     '00000000-0000-4000-8000-000000000001', false,
     now() - interval '1 day' + interval '30 minutes', '00000001-0000-4000-8000-000000000102', 'person'),
    ('00000016-0000-4000-8000-000000001001', '00000011-0000-4000-8000-000000001001',
     '00000010-0000-4000-8000-000000001001', '0000000f-0000-4000-8000-000000000110',
     '00000001-0000-4000-8000-000000000103', false,
     now() - interval '110 minutes', '00000001-0000-4000-8000-000000000102', 'person')
on conflict do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
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
     'That is the courier''s tracking number, not the order reference.'),
    ('00000016-0000-4000-8000-000000000801', '00000012-0000-4000-8000-000000000802',
     '00000011-0000-4000-8000-000000000801', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000001001', '00000012-0000-4000-8000-000000001001',
     '00000011-0000-4000-8000-000000001001', false, true, 'assured', null),
    ('00000016-0000-4000-8000-000000001001', '00000012-0000-4000-8000-000000001002',
     '00000011-0000-4000-8000-000000001001', false, true, 'assured', null)
on conflict do nothing;

-- DB-SPECIFIC: interval literals, on conflict and now() use PostgreSQL's syntax and semantics.
insert into run_stops (run_stop_id, run_id, root_run_id, created_at, created_by, created_by_kind) values
    ('00000017-0000-4000-8000-000000000601', '0000000f-0000-4000-8000-000000000106',
     '0000000f-0000-4000-8000-000000000106', now() - interval '2 days' + interval '3 hours',
     '00000001-0000-4000-8000-000000000102', 'person')
on conflict do nothing;

-- These attachments contain a complete one-page PDF and are stored in the development database.
-- Both sides of the deferred metadata/content relationship are written in the same statement.
-- DB-SPECIFIC: data-modifying with, uuid, decode(), octet_length() and on conflict use PostgreSQL's syntax and semantics.
with fixture (file_id, original_name, content) as (
    select file_id, original_name, decode('255044462d312e340a312030206f626a0a3c3c202f54797065202f436174616c6f67202f5061676573203220302052203e3e0a656e646f626a0a322030206f626a0a3c3c202f54797065202f5061676573202f4b696473205b33203020525d202f436f756e742031203e3e0a656e646f626a0a332030206f626a0a3c3c202f54797065202f50616765202f506172656e74203220302052202f4d65646961426f78205b30203020333030203134345d202f436f6e74656e7473203420302052202f5265736f7572636573203c3c202f466f6e74203c3c202f4631203520302052203e3e203e3e203e3e0a656e646f626a0a342030206f626a0a3c3c202f4c656e677468203533203e3e0a73747265616d0a4254202f4631203138205466203336203732205464202844656d6f2053616d706c65206174746163686d656e742920546a2045540a656e6473747265616d0a656e646f626a0a352030206f626a0a3c3c202f54797065202f466f6e74202f53756274797065202f5479706531202f42617365466f6e74202f48656c766574696361203e3e0a656e646f626a0a787265660a3020360a303030303030303030302036353533352066200a30303030303030303039203030303030206e200a30303030303030303538203030303030206e200a30303030303030313135203030303030206e200a30303030303030323431203030303030206e200a30303030303030333433203030303030206e200a747261696c65720a3c3c202f53697a652036202f526f6f74203120302052203e3e0a7374617274787265660a3431330a2525454f460a', 'hex')
      from (values (cast('00000018-0000-4000-8000-000000000113' as uuid), 'Demo Sample ready.pdf'),
                   ('00000018-0000-4000-8000-000000000114', 'Demo Sample waiting.pdf'),
                   ('00000018-0000-4000-8000-000000000115', 'Demo Sample complete.pdf')) as files(file_id, original_name)
), metadata as (
    insert into stored_files (file_id, group_id, original_name, media_type, byte_size)
    select file_id, '00000002-0000-4000-8000-000000000101', original_name, 'application/pdf', octet_length(content) from fixture
    on conflict (file_id) do nothing
)
insert into file_contents (file_id, content)
select file_id, content from fixture
on conflict (file_id) do nothing;

-- DB-SPECIFIC: uuid, interval literals, jsonb_build_object(), on conflict and now() use PostgreSQL's syntax and semantics.
insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                  started_with, created_at, created_by, created_by_kind)
select started.run_id, '00000002-0000-4000-8000-000000000101', started.number, started.name, '00000006-0000-4000-8000-000000000106',
       '00000007-0000-4000-8000-000000000109', started.run_id, 0,
       jsonb_build_object('topic', started.topic, 'attachment', jsonb_build_object(
           'id', file.file_id, 'name', file.original_name, 'type', file.media_type, 'size', file.byte_size)),
       now() - started.age, '00000001-0000-4000-8000-000000000102', 'person'
  from (values (cast('0000000f-0000-4000-8000-000000000113' as uuid), 13, 'Demo Sample attachment ready',
                'Demo Sample attachment handling', cast('00000018-0000-4000-8000-000000000113' as uuid), interval '40 minutes'),
               ('0000000f-0000-4000-8000-000000000114', 14, 'Demo Sample research waiting',
                'Demo Sample evidence review', '00000018-0000-4000-8000-000000000114', interval '30 minutes'),
               ('0000000f-0000-4000-8000-000000000115', 15, 'Demo Sample research complete',
                'Demo Sample research summary', '00000018-0000-4000-8000-000000000115', interval '20 minutes'))
       as started(run_id, number, name, topic, file_id, age)
  join stored_files file on file.file_id = started.file_id
on conflict (run_id) do nothing;

-- DB-SPECIFIC: ->, ->>, uuid and on conflict use PostgreSQL's syntax and semantics.
insert into run_file_holders (run_id, file_id, group_id)
select run_id, cast(started_with -> 'attachment' ->> 'id' as uuid), group_id
  from runs where run_id in ('0000000f-0000-4000-8000-000000000113', '0000000f-0000-4000-8000-000000000114', '0000000f-0000-4000-8000-000000000115')
on conflict (run_id, file_id) do nothing;

-- The first step is reached, but the stopped root has never dispatched a try.
-- DB-SPECIFIC: uuid and on conflict use PostgreSQL's syntax and semantics.
insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                       producer, reviewed_by_model, tries, created_at, created_by, created_by_kind)
select planned.step_id, run.run_id, run.entry_version_id, '0000000c-0000-4000-8000-000000000105', 'search',
       'model', false, 2, run.created_at, '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000010-0000-4000-8000-000000001301' as uuid), cast('0000000f-0000-4000-8000-000000000113' as uuid)),
               ('00000010-0000-4000-8000-000000001401', '0000000f-0000-4000-8000-000000000114'),
               ('00000010-0000-4000-8000-000000001501', '0000000f-0000-4000-8000-000000000115')) as planned(step_id, run_id)
  join runs run on run.run_id = planned.run_id
on conflict (run_step_id) do nothing;

-- DB-SPECIFIC: interval literals and on conflict use PostgreSQL's syntax and semantics.
insert into run_stops (run_stop_id, run_id, root_run_id, created_at, created_by, created_by_kind)
select '00000017-0000-4000-8000-000000001301', run_id, root_run_id, created_at + interval '1 second', '00000001-0000-4000-8000-000000000102', 'person'
  from runs where run_id = '0000000f-0000-4000-8000-000000000113'
on conflict (run_stop_id) do nothing;

-- The model production is opened before the call that closes it is inserted.
-- DB-SPECIFIC: uuid and on conflict use PostgreSQL's syntax and semantics.
insert into productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                         reviewed_by_model, tries, try_number, producer, created_at, created_by, created_by_kind)
select planned.production_id, ran.run_step_id, run.run_id, run.root_run_id, 'search', 'model', false, 2, 1,
       'model', ran.created_at, '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000011-0000-4000-8000-000000001401' as uuid), cast('00000010-0000-4000-8000-000000001401' as uuid)),
               ('00000011-0000-4000-8000-000000001501', '00000010-0000-4000-8000-000000001501')) as planned(production_id, step_id)
  join run_steps ran on ran.run_step_id = planned.step_id join runs run on run.run_id = ran.run_id
on conflict (production_id) do nothing;

-- Only the bound topic contributes provenance; the literal part reads no value.
-- DB-SPECIFIC: uuid and on conflict use PostgreSQL's syntax and semantics.
insert into production_inputs (production_input_id, production_id, run_id, root_run_id, run_step_id,
                               run_step_kind, workflow_step_id, search_question_part_id,
                               binding_reads_a_constant, created_at)
select input.input_id, try.production_id, try.run_id, try.root_run_id, try.run_step_id, 'search', '0000000c-0000-4000-8000-000000000105',
       '00000019-0000-4000-8000-000000000102', false, try.created_at
  from (values (cast('00000013-0000-4000-8000-000000001401' as uuid), cast('00000011-0000-4000-8000-000000001401' as uuid)),
               ('00000013-0000-4000-8000-000000001501', '00000011-0000-4000-8000-000000001501')) as input(input_id, production_id)
  join productions try on try.production_id = input.production_id
on conflict (production_input_id) do nothing;

-- The current search wire carries the topic and no file or attachment metadata.
-- DB-SPECIFIC: uuid, text, to_json(), ->>, interval literals and on conflict use PostgreSQL's syntax and semantics.
insert into run_step_send_attempts (run_step_send_attempt_id, run_step_id, run_id, run_step_kind, workflow_step_id,
                                    purpose, model, mode, production_id, payload, search_question,
                                    created_at, created_by, created_by_kind)
select sent.attempt_id, try.run_step_id, try.run_id, 'search', '0000000c-0000-4000-8000-000000000105', 'produce', 'sample_model', 'ordinary',
       try.production_id, '{"envelope_version":5,"question":'
           || cast(to_json('Find information about ' || (run.started_with ->> 'topic')) as text) || '}',
       'Find information about ' || (run.started_with ->> 'topic'),
       try.created_at + interval '2 seconds', '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000014-0000-4000-8000-000000001401' as uuid), cast('00000011-0000-4000-8000-000000001401' as uuid)),
               ('00000014-0000-4000-8000-000000001501', '00000011-0000-4000-8000-000000001501')) as sent(attempt_id, production_id)
  join productions try on try.production_id = sent.production_id join runs run on run.run_id = try.run_id
on conflict (run_step_send_attempt_id) do nothing;

-- Counts and observations match the configured development search conversion.
-- DB-SPECIFIC: uuid, interval literals and on conflict use PostgreSQL's syntax and semantics.
insert into model_calls (model_call_id, run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                         production_id, model, mode, envelope_version, sent_count, came_back_count, counted_by_model,
                         outcome, answer, ended_at, created_at, created_by, created_by_kind)
select called.call_id, sent.run_id, try.root_run_id, 'produce', sent.run_step_send_attempt_id, sent.run_step_id,
       sent.production_id, 'sample_model', 'ordinary', 5, 7, 9, true, 'came_back',
       '{"title":"Damaged parcels: refund and claim rules","body":"## Summary\n\nMost carriers treat a parcel that arrives **damaged** as a claim the sender makes, but the buyer still has to *report it quickly*.\n\n### What to check first\n\n1. Photograph the outer box and the item before unpacking further.\n2. Keep all packing material until the claim is settled.\n3. Note the tracking number and the delivery date.\n\n> Claims filed late usually need proof that the damage could not be seen at delivery.\n\n### Typical time limits\n\n- Visible damage: report within **48 hours**\n- Hidden damage: report within **14 days**\n- Missing contents: report within **7 days**\n\n---\n\nA reply to the customer can open like this:\n\n```text\nWe are sorry your order arrived damaged. Please send two photos of the box and the item.\n```\n\nThe [claim guide](https://example.com/claims) lists the full form, and `claim_id` is printed on the receipt.","references":"1. [Carrier claims guide](https://example.com/claims)\n2. [Consumer returns overview](https://example.org/returns)\n3. https://example.net/damaged-parcels","other":""}',
       sent.created_at + interval '1 second', sent.created_at, '00000000-0000-4000-8000-000000000001', 'system'
  from (values (cast('00000015-0000-4000-8000-000000001401' as uuid), cast('00000014-0000-4000-8000-000000001401' as uuid)),
               ('00000015-0000-4000-8000-000000001501', '00000014-0000-4000-8000-000000001501')) as called(call_id, attempt_id)
  join run_step_send_attempts sent on sent.run_step_send_attempt_id = called.attempt_id
  join productions try on try.production_id = sent.production_id
on conflict (model_call_id) do nothing;

-- DB-SPECIFIC: update from uses PostgreSQL's syntax and semantics.
update productions try set ended_at = call.ended_at, ended_by = call.created_by, ended_by_kind = call.created_by_kind,
       model_call_id = call.model_call_id, model_call_outcome = call.outcome
  from model_calls call
 where call.production_id = try.production_id and try.ended_at is null
   and call.model_call_id in ('00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501');

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into production_values (production_value_id, production_id, run_step_kind, producer,
                               field_name, field_standing, value) values
    ('00000012-0000-4000-8000-000000001401', '00000011-0000-4000-8000-000000001401', 'search', 'model', 'title', 'always', '"Damaged parcels: refund and claim rules"'),
    ('00000012-0000-4000-8000-000000001402', '00000011-0000-4000-8000-000000001401', 'search', 'model', 'body', 'never', '"## Summary\n\nMost carriers treat a parcel that arrives **damaged** as a claim the sender makes, but the buyer still has to *report it quickly*.\n\n### What to check first\n\n1. Photograph the outer box and the item before unpacking further.\n2. Keep all packing material until the claim is settled.\n3. Note the tracking number and the delivery date.\n\n> Claims filed late usually need proof that the damage could not be seen at delivery.\n\n### Typical time limits\n\n- Visible damage: report within **48 hours**\n- Hidden damage: report within **14 days**\n- Missing contents: report within **7 days**\n\n---\n\nA reply to the customer can open like this:\n\n```text\nWe are sorry your order arrived damaged. Please send two photos of the box and the item.\n```\n\nThe [claim guide](https://example.com/claims) lists the full form, and `claim_id` is printed on the receipt."'),
    ('00000012-0000-4000-8000-000000001403', '00000011-0000-4000-8000-000000001401', 'search', 'model', 'references', 'always', '"1. [Carrier claims guide](https://example.com/claims)\n2. [Consumer returns overview](https://example.org/returns)\n3. https://example.net/damaged-parcels"'),
    ('00000012-0000-4000-8000-000000001404', '00000011-0000-4000-8000-000000001401', 'search', 'model', 'other', 'always', null),
    ('00000012-0000-4000-8000-000000001501', '00000011-0000-4000-8000-000000001501', 'search', 'model', 'title', 'always', '"Damaged parcels: refund and claim rules"'),
    ('00000012-0000-4000-8000-000000001502', '00000011-0000-4000-8000-000000001501', 'search', 'model', 'body', 'never', '"## Summary\n\nMost carriers treat a parcel that arrives **damaged** as a claim the sender makes, but the buyer still has to *report it quickly*.\n\n### What to check first\n\n1. Photograph the outer box and the item before unpacking further.\n2. Keep all packing material until the claim is settled.\n3. Note the tracking number and the delivery date.\n\n> Claims filed late usually need proof that the damage could not be seen at delivery.\n\n### Typical time limits\n\n- Visible damage: report within **48 hours**\n- Hidden damage: report within **14 days**\n- Missing contents: report within **7 days**\n\n---\n\nA reply to the customer can open like this:\n\n```text\nWe are sorry your order arrived damaged. Please send two photos of the box and the item.\n```\n\nThe [claim guide](https://example.com/claims) lists the full form, and `claim_id` is printed on the receipt."'),
    ('00000012-0000-4000-8000-000000001503', '00000011-0000-4000-8000-000000001501', 'search', 'model', 'references', 'always', '"1. [Carrier claims guide](https://example.com/claims)\n2. [Consumer returns overview](https://example.org/returns)\n3. https://example.net/damaged-parcels"'),
    ('00000012-0000-4000-8000-000000001504', '00000011-0000-4000-8000-000000001501', 'search', 'model', 'other', 'always', null)
on conflict (production_value_id) do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into model_call_tool_uses (model_call_id, run_id, model_call_outcome, tool, most_uses, uses, uses_said)
select model_call_id, run_id, outcome, 'web_search', 3, 2, true from model_calls
 where model_call_id in ('00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501')
on conflict (model_call_id) do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into model_call_tool_prices (model_call_id, currency, per_use)
select model_call_id, 'USD', 0.01 from model_call_tool_uses
 where model_call_id in ('00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501')
on conflict (model_call_id, currency) do nothing;

-- The development conversion reports the complete user payload as its observed query.
-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into model_call_searches (model_call_id, position, searched_for, found)
select call.model_call_id, 1, sent.payload, 'Demo Sample page'
  from model_calls call join run_step_send_attempts sent using (run_step_send_attempt_id)
 where call.model_call_id in ('00000015-0000-4000-8000-000000001401', '00000015-0000-4000-8000-000000001501')
on conflict (model_call_id, position) do nothing;

-- The completed run's body was assured by a person after the model returned it.
-- DB-SPECIFIC: interval literals and on conflict use PostgreSQL's syntax and semantics.
insert into reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                     created_at, created_by, created_by_kind)
select '00000016-0000-4000-8000-000000001501', production_id, run_step_id, root_run_id, ended_by, false,
       ended_at + interval '1 minute', '00000001-0000-4000-8000-000000000103', 'person'
  from productions where production_id = '00000011-0000-4000-8000-000000001501'
on conflict (review_id) do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into review_decisions (review_id, production_value_id, production_id, for_length, value_needs_review, outcome)
values ('00000016-0000-4000-8000-000000001501', '00000012-0000-4000-8000-000000001502', '00000011-0000-4000-8000-000000001501', false, true, 'assured')
on conflict (review_id, production_value_id) do nothing;
