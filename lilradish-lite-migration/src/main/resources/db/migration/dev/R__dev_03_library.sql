-- Development only, and repeatable for the reasons the pool seed gives. One group and its library, with a
-- version at every standing. Every act here is the seeder's; what a person does is done in the app.

-- Keys by their first eight digits: 2 group, 4 membership, 6 entry, 7 version, 8 submission, 9 code step
-- publication, a term, b field, c step, d case, e binding.

-- Targeted, so a row made in the app holding this key or name is refused under its own rule, not skipped.
insert into groups (group_id, key, name, created_by) values
    ('00000002-0000-4000-8000-000000000101', 'SUPPORT', 'Customer support', '00000000-0000-4000-8000-000000000000')
on conflict (group_id) do nothing;

insert into group_members (group_member_id, group_id, subject_id, role, created_by) values
    ('00000004-0000-4000-8000-000000000101', '00000002-0000-4000-8000-000000000101',
     '00000001-0000-4000-8000-000000000102', 'owner', '00000000-0000-4000-8000-000000000000'),
    ('00000004-0000-4000-8000-000000000102', '00000002-0000-4000-8000-000000000101',
     '00000001-0000-4000-8000-000000000103', 'overseer', '00000000-0000-4000-8000-000000000000')
on conflict do nothing;

-- Targeted for the reason groups is.
-- One entry says nothing of what it is for, which a card has to be read without too.
insert into entries (entry_id, group_id, kind, name, purpose, created_by) values
    ('00000006-0000-4000-8000-000000000101', '00000002-0000-4000-8000-000000000101',
     'question', 'Summarise a complaint', 'Says what a complaint is about, and which product and order it names.',
     '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000102', '00000002-0000-4000-8000-000000000101',
     'workflow', 'Handle a complaint', 'Reads a complaint that came in and sends it where its category belongs.',
     '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000103', '00000002-0000-4000-8000-000000000101',
     'reference_list', 'Complaint categories', default, '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000104', '00000002-0000-4000-8000-000000000101',
     'workflow', 'Escalate a complaint', 'Summarises a complaint for whoever it is passed up to.',
     '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000105', '00000002-0000-4000-8000-000000000101',
     'workflow', 'Stamp a complaint', 'Gives a complaint a reference drawn from its words, to quote it back by.',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_id) do nothing;

-- The code step the development source set holds, published to the group by its key as a migration publishes one.
-- Targeted for the reason groups is.
insert into code_step_publications (code_step_publication_id, code_step, group_key, every_group, created_by) values
    ('00000009-0000-4000-8000-000000000101', 'stamp_reference', 'SUPPORT', false,
     '00000000-0000-4000-8000-000000000000')
on conflict (code_step_publication_id) do nothing;

-- Summarise a complaint: 1 retired, 2 in service, 3 submitted.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_by,
                            approved_at, approved_by, approved_by_kind,
                            retired_at, retired_by, retired_by_kind) values
    ('00000007-0000-4000-8000-000000000101', '00000006-0000-4000-8000-000000000101', 'question', 1,
     '00000000-0000-4000-8000-000000000000',
     now(), '00000000-0000-4000-8000-000000000000', 'seeder',
     now(), '00000000-0000-4000-8000-000000000000', 'seeder'),
    ('00000007-0000-4000-8000-000000000102', '00000006-0000-4000-8000-000000000101', 'question', 2,
     '00000000-0000-4000-8000-000000000000',
     now(), '00000000-0000-4000-8000-000000000000', 'seeder',
     default, default, default),
    ('00000007-0000-4000-8000-000000000103', '00000006-0000-4000-8000-000000000101', 'question', 3,
     '00000000-0000-4000-8000-000000000000',
     default, default, default,
     default, default, default)
on conflict do nothing;

-- Handle a complaint: 1 in service, 2 a draft.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_by,
                            approved_at, approved_by, approved_by_kind) values
    ('00000007-0000-4000-8000-000000000104', '00000006-0000-4000-8000-000000000102', 'workflow', 1,
     '00000000-0000-4000-8000-000000000000',
     now(), '00000000-0000-4000-8000-000000000000', 'seeder'),
    ('00000007-0000-4000-8000-000000000105', '00000006-0000-4000-8000-000000000102', 'workflow', 2,
     '00000000-0000-4000-8000-000000000000',
     default, default, default)
on conflict do nothing;

-- Complaint categories: 1 in service.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_by,
                            approved_at, approved_by, approved_by_kind) values
    ('00000007-0000-4000-8000-000000000106', '00000006-0000-4000-8000-000000000103', 'reference_list', 1,
     '00000000-0000-4000-8000-000000000000',
     now(), '00000000-0000-4000-8000-000000000000', 'seeder')
on conflict do nothing;

-- Escalate a complaint and Stamp a complaint: 1 in service each.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_by,
                            approved_at, approved_by, approved_by_kind) values
    ('00000007-0000-4000-8000-000000000107', '00000006-0000-4000-8000-000000000104', 'workflow', 1,
     '00000000-0000-4000-8000-000000000000',
     now(), '00000000-0000-4000-8000-000000000000', 'seeder'),
    ('00000007-0000-4000-8000-000000000108', '00000006-0000-4000-8000-000000000105', 'workflow', 1,
     '00000000-0000-4000-8000-000000000000',
     now(), '00000000-0000-4000-8000-000000000000', 'seeder')
on conflict do nothing;

insert into entry_version_submissions (entry_version_submission_id, entry_version_id, created_by, created_by_kind) values
    ('00000008-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000103',
     '00000000-0000-4000-8000-000000000000', 'seeder')
on conflict do nothing;

-- Every version past a draft is whole, as submitting it demanded; the one draft is left as it was opened.

-- reference_list_terms, workflow_steps and declaration_fields carry deferrable uniques, which an untargeted
-- on conflict fails on; the rest follow them.
insert into reference_list_versions (entry_version_id, note, created_by) values
    ('00000007-0000-4000-8000-000000000106',
     'Choose the term for what the customer asks to have put right, not for what they mention on the way.',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

insert into reference_list_terms (reference_list_term_id, entry_version_id, position, term, meaning, created_by) values
    ('0000000a-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000106', 1, 'Billing',
     'A charge, a refund or an invoice is what is disputed.', '00000000-0000-4000-8000-000000000000'),
    ('0000000a-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000106', 2, 'Delivery',
     'What was ordered came late, came damaged or never came.', '00000000-0000-4000-8000-000000000000'),
    ('0000000a-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000106', 3, 'Product fault',
     'What came works wrongly, and how it came is not the complaint.', '00000000-0000-4000-8000-000000000000')
on conflict (reference_list_term_id) do nothing;

insert into question_versions (entry_version_id, instruction, created_by) values
    ('00000007-0000-4000-8000-000000000101',
     'Summarise the complaint in one paragraph.',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000102',
     'Say which category the complaint falls under, which product it concerns and any order reference it gives.',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000103',
     'Say which category the complaint falls under, which product it concerns and any order reference it gives.'
         || chr(10) || 'Leave the order reference empty where the complaint gives none.',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- Raising a ceiling on handling waits on approval. A database seeded before this flag takes it on a second
-- run, which only ever sets it, and never on a row somebody has since edited.
insert into workflow_versions (entry_version_id, ceiling, raise_needs_approval, may_be_helped, helper_model,
                               helper_mode, created_by) values
    ('00000007-0000-4000-8000-000000000104', 200000, true, true, 'sample_model', 'ordinary',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000105', default, default, default, default, default,
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000107', default, default, default, default, default,
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000108', default, default, default, default, default,
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do update set raise_needs_approval = true
    where excluded.raise_needs_approval and not workflow_versions.raise_needs_approval
      and workflow_versions.updated_by is null;

-- Handling runs the question, then routes on its category; every case escalates. No reviewer model is
-- named, so a person reviews.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, pinned_version_id, pinned_kind,
                            producer, producer_model, producer_mode, tries, tells_what_happened, created_by) values
    ('0000000c-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000104', 1, 'summarise',
     'entry', '00000007-0000-4000-8000-000000000102', 'question',
     'model', 'sample_model', 'ordinary', 3, true,
     '00000000-0000-4000-8000-000000000000'),
    ('0000000c-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104', 2, 'route_by_category',
     'route', default, default,
     default, default, default, default, default,
     '00000000-0000-4000-8000-000000000000'),
    ('0000000c-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000107', 1, 'summarise_for_escalation',
     'entry', '00000007-0000-4000-8000-000000000102', 'question',
     'person', default, default, 2, default,
     '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- Stamping runs the code step, whose values stand as it declares them, so nobody is asked.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step, producer, tries,
                            created_by) values
    ('0000000c-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000108', 1, 'stamp',
     'code_step', 'stamp_reference', 'code', 2, '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id, created_by) values
    ('0000000d-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000102', 'Billing',
     '00000007-0000-4000-8000-000000000107', '00000000-0000-4000-8000-000000000000'),
    ('0000000d-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000102', 'Delivery',
     '00000007-0000-4000-8000-000000000107', '00000000-0000-4000-8000-000000000000'),
    ('0000000d-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000102', null,
     '00000007-0000-4000-8000-000000000107', '00000000-0000-4000-8000-000000000000')
on conflict (route_case_id) do nothing;

-- Some fields of versions in service carry a label, help or both; every other field is read by its name.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, parent_field_id, position,
                                name, label, help, kind, text_limit, term_list_version_id, must_be_given, standing,
                                standing_threshold, created_by) values
    ('0000000b-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000101', 'question', 'takes', null, 1,
     'complaint', default, default, 'text', 4000, null, true, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000101', 'question', 'gives', null, 1,
     'summary', default, default, 'text', 1000, null, true, 'never', null, '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000102', 'question', 'takes', null, 1,
     'complaint', 'The complaint', 'As the customer wrote it, greeting and signature included.',
     'text', 4000, null, true, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000102', 'question', 'takes', null, 2,
     'channel', 'Came in by', default, 'text', 32, null, false, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000102', 'question', 'gives', null, 1,
     'category', default, 'What the customer asks to have put right.',
     'term', null, '00000007-0000-4000-8000-000000000106', true, 'above_confidence', 80,
     '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000106', '00000007-0000-4000-8000-000000000102', 'question', 'gives', null, 2,
     'details', default, default, 'fields', null, null, false, 'never', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000107', '00000007-0000-4000-8000-000000000102', 'question', 'gives',
     '0000000b-0000-4000-8000-000000000106', 1,
     'product', default, default, 'text', 128, null, true, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000108', '00000007-0000-4000-8000-000000000102', 'question', 'gives',
     '0000000b-0000-4000-8000-000000000106', 2,
     'order_reference', default, default, 'text', 64, null, false, null, null, '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000109', '00000007-0000-4000-8000-000000000103', 'question', 'takes', null, 1,
     'complaint', default, default, 'text', 4000, null, true, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000110', '00000007-0000-4000-8000-000000000103', 'question', 'takes', null, 2,
     'channel', default, default, 'text', 32, null, false, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000111', '00000007-0000-4000-8000-000000000103', 'question', 'gives', null, 1,
     'category', default, default, 'term', null, '00000007-0000-4000-8000-000000000106', true, 'above_confidence', 90,
     '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000112', '00000007-0000-4000-8000-000000000103', 'question', 'gives', null, 2,
     'details', default, default, 'fields', null, null, false, 'never', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000113', '00000007-0000-4000-8000-000000000103', 'question', 'gives',
     '0000000b-0000-4000-8000-000000000112', 1,
     'product', default, default, 'text', 128, null, true, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000114', '00000007-0000-4000-8000-000000000103', 'question', 'gives',
     '0000000b-0000-4000-8000-000000000112', 2,
     'order_reference', default, default, 'text', 64, null, false, null, null, '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000115', '00000007-0000-4000-8000-000000000104', 'workflow', 'takes', null, 1,
     'complaint', 'The complaint', 'As the customer wrote it, greeting and signature included.',
     'text', 4000, null, true, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000116', '00000007-0000-4000-8000-000000000107', 'workflow', 'takes', null, 1,
     'complaint', default, default, 'text', 4000, null, true, null, null, '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000117', '00000007-0000-4000-8000-000000000108', 'workflow', 'takes', null, 1,
     'complaint', default, default, 'text', 4000, null, true, null, null, '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000118', '00000007-0000-4000-8000-000000000108', 'workflow', 'gives', null, 1,
     'reference', default, default, 'text', 12, null, true, null, null, '00000000-0000-4000-8000-000000000000')
on conflict (declaration_field_id) do nothing;

-- A null target path is the route's discriminator.
insert into bindings (binding_id, entry_version_id, workflow_step_id, step_kind, route_case_id, target_path,
                      source_step_id, source_path, constant, created_by) values
    ('0000000e-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000101', null, null, 'complaint',
     null, 'complaint', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000101', null, null, 'channel',
     null, null, '"email"', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000102', 'route', null, null,
     '0000000c-0000-4000-8000-000000000101', 'category', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000104',
     null, null, '0000000d-0000-4000-8000-000000000101', 'complaint',
     null, 'complaint', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000104',
     null, null, '0000000d-0000-4000-8000-000000000102', 'complaint',
     null, 'complaint', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000106', '00000007-0000-4000-8000-000000000104',
     null, null, '0000000d-0000-4000-8000-000000000103', 'complaint',
     null, 'complaint', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000107', '00000007-0000-4000-8000-000000000107',
     '0000000c-0000-4000-8000-000000000103', null, null, 'complaint',
     null, 'complaint', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000108', '00000007-0000-4000-8000-000000000107',
     '0000000c-0000-4000-8000-000000000103', null, null, 'channel',
     null, null, '"escalation"', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000109', '00000007-0000-4000-8000-000000000108',
     '0000000c-0000-4000-8000-000000000104', null, null, 'complaint',
     null, 'complaint', null, '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000110', '00000007-0000-4000-8000-000000000108',
     null, null, null, 'reference',
     '0000000c-0000-4000-8000-000000000104', 'reference', null, '00000000-0000-4000-8000-000000000000')
on conflict (binding_id) do nothing;
