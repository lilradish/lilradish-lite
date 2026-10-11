

-- Targeted ON CONFLICT does not skip collisions with other unique constraints.
-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into groups (group_id, key, name, created_at, created_by) values
    ('00000002-0000-4000-8000-000000000101', 'SUPPORT', 'Customer support', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000002-0000-4000-8000-000000000102', 'BILLING', 'Imaginary Mooncrate billing', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (group_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This group_members seed group supplies PostgreSQL native group_role enum inputs.
insert into group_members (group_member_id, group_id, subject_id, role, created_at, created_by) values
    ('00000004-0000-4000-8000-000000000101', '00000002-0000-4000-8000-000000000101',
     '00000001-0000-4000-8000-000000000102', 'owner', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000004-0000-4000-8000-000000000102', '00000002-0000-4000-8000-000000000101',
     '00000001-0000-4000-8000-000000000103', 'overseer', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000004-0000-4000-8000-000000000103', '00000002-0000-4000-8000-000000000102',
     '00000001-0000-4000-8000-000000000102', 'owner', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000004-0000-4000-8000-000000000104', '00000002-0000-4000-8000-000000000102',
     '00000001-0000-4000-8000-000000000105', 'overseer', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict do nothing;

-- Targeted for the reason groups is.
-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entries seed group supplies PostgreSQL native entry_kind enum inputs.
insert into entries (entry_id, group_id, kind, name, purpose, created_at, created_by) values
    ('00000006-0000-4000-8000-000000000101', '00000002-0000-4000-8000-000000000101',
     'question', 'Summarise a complaint', 'Says what a complaint is about, and which product and order it names.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000102', '00000002-0000-4000-8000-000000000101',
     'workflow', 'Handle a complaint', 'Reads a complaint that came in and sends it where its category belongs.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000103', '00000002-0000-4000-8000-000000000101',
     'reference_list', 'Complaint categories', default, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000104', '00000002-0000-4000-8000-000000000101',
     'workflow', 'Escalate a complaint', 'Summarises a complaint for whoever it is passed up to.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000105', '00000002-0000-4000-8000-000000000101',
     'workflow', 'Stamp a complaint', 'Gives a complaint a reference drawn from its words, to quote it back by.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_id) do nothing;

-- Targeted for the reason groups is.
-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This code_step_publications seed group supplies PostgreSQL native code_step enum inputs.
insert into code_step_publications (code_step_publication_id, code_step, group_key, every_group, created_at, created_by) values
    ('00000009-0000-4000-8000-000000000101', 'stamp_reference', 'SUPPORT', false, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000009-0000-4000-8000-000000000103', 'stamp_reference', 'BILLING', false, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (code_step_publication_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz '2000-01-02 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entry_versions seed group supplies PostgreSQL native entry_kind, subject_kind enum inputs.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_at, created_by, approved_at, approved_by, approved_by_kind, retired_at, retired_by, retired_by_kind) values
    ('00000007-0000-4000-8000-000000000101', '00000006-0000-4000-8000-000000000101', 'question', 1, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000',
     timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder',
     timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder'),
    ('00000007-0000-4000-8000-000000000102', '00000006-0000-4000-8000-000000000101', 'question', 2, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000',
     timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder',
     default, default, default),
    ('00000007-0000-4000-8000-000000000103', '00000006-0000-4000-8000-000000000101', 'question', 3, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000',
     default, default, default,
     default, default, default)
on conflict do nothing;

-- DB-SPECIFIC: on conflict and timestamptz '2000-01-02 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entry_versions seed group supplies PostgreSQL native entry_kind, subject_kind enum inputs.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_at, created_by, approved_at, approved_by, approved_by_kind) values
    ('00000007-0000-4000-8000-000000000104', '00000006-0000-4000-8000-000000000102', 'workflow', 1, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000',
     timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder'),
    ('00000007-0000-4000-8000-000000000105', '00000006-0000-4000-8000-000000000102', 'workflow', 2, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000',
     default, default, default)
on conflict do nothing;

-- Complaint categories: one in service and one draft, leaving existing pins in service.
-- DB-SPECIFIC: on conflict and timestamptz '2000-01-02 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entry_versions seed group supplies PostgreSQL native entry_kind, subject_kind enum inputs.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_at, created_by, approved_at, approved_by, approved_by_kind) values
    ('00000007-0000-4000-8000-000000000106', '00000006-0000-4000-8000-000000000103', 'reference_list', 1, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000',
     timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder'),
    ('00000007-0000-4000-8000-000000000113', '00000006-0000-4000-8000-000000000103', 'reference_list', 2, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000', default, default, default)
on conflict do nothing;

-- DB-SPECIFIC: on conflict and timestamptz '2000-01-02 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entry_versions seed group supplies PostgreSQL native entry_kind, subject_kind enum inputs.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, created_at, created_by, approved_at, approved_by, approved_by_kind) values
    ('00000007-0000-4000-8000-000000000107', '00000006-0000-4000-8000-000000000104', 'workflow', 1, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000',
     timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder'),
    ('00000007-0000-4000-8000-000000000108', '00000006-0000-4000-8000-000000000105', 'workflow', 1, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000',
     timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder')
on conflict do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entry_version_submissions seed group supplies PostgreSQL native subject_kind enum inputs.
insert into entry_version_submissions (entry_version_submission_id, entry_version_id, created_at, created_by, created_by_kind) values
    ('00000008-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000103', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000', 'seeder')
on conflict do nothing;


-- reference_list_terms, workflow_steps and declaration_fields carry deferrable uniques, which an untargeted
-- on conflict fails on; the rest follow them.
-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into reference_list_versions (entry_version_id, note, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000106',
     'Choose the term for what the customer asks to have put right, not for what they mention on the way.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000113',
     'Choose the remedy the customer needs next; these fictional categories are ready for a review of the draft.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into reference_list_terms (reference_list_term_id, entry_version_id, position, term, meaning, created_at, created_by) values
    ('0000000a-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000106', 1, 'Billing',
     'A charge, a refund or an invoice is what is disputed.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000a-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000106', 2, 'Delivery',
     'What was ordered came late, came damaged or never came.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000a-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000106', 3, 'Product fault',
     'What came works wrongly, and how it came is not the complaint.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000a-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000113', 1, 'Billing',
     'Check the disputed charge or refund before proposing a remedy.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000a-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000113', 2, 'Delivery',
     'Check the fictional dispatch record and ask for a box photograph.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000a-0000-4000-8000-000000000106', '00000007-0000-4000-8000-000000000113', 3, 'Product fault',
     'Ask what failed during ordinary use before offering a replacement.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (reference_list_term_id) do nothing;

-- DB-SPECIFIC: on conflict, chr() and timestamptz literals use PostgreSQL's syntax and semantics.
insert into question_versions (entry_version_id, instruction, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000101',
     'Summarise the complaint in one paragraph.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000102',
     'Say which category the complaint falls under, which product it concerns and any order reference it gives.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000103',
     'Say which category the complaint falls under, which product it concerns and any order reference it gives.'
         || chr(10) || 'Leave the order reference empty where the complaint gives none.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into workflow_versions (entry_version_id, ceiling, raise_needs_approval, may_be_helped, helper_model, helper_mode, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000104', 200000, true, true, 'sample_model', 'ordinary', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000105', default, default, default, default, default, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000107', default, default, default, default, default, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000108', default, default, default, default, default, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do update set raise_needs_approval = true
    where excluded.raise_needs_approval and not workflow_versions.raise_needs_approval
      and workflow_versions.updated_by is null;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This workflow_steps seed group supplies PostgreSQL native step_kind, entry_kind, step_producer enum inputs.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, label, kind, pinned_version_id, pinned_kind, producer, producer_model, producer_mode, tries, tells_what_happened, created_at, created_by) values
    ('0000000c-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000104', 1, 'summarise', 'Read the complaint',
     'entry', '00000007-0000-4000-8000-000000000102', 'question',
     'model', 'sample_model', 'ordinary', 3, true, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('0000000c-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104', 2, 'route_by_category', 'Send it by category',
     'route', default, default,
     default, default, default, default, default, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('0000000c-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000107', 1, 'summarise_for_escalation', 'Summarise it for escalation',
     'entry', '00000007-0000-4000-8000-000000000102', 'question',
     'person', default, default, 2, default, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This workflow_steps seed group supplies PostgreSQL native step_kind, code_step, step_producer enum inputs.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, label, kind, code_step, producer, tries, created_at, created_by) values
    ('0000000c-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000108', 1, 'stamp', 'Draw a reference from its words',
     'code_step', 'stamp_reference', 'code', 2, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id, created_at, created_by) values
    ('0000000d-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000102', 'Billing',
     '00000007-0000-4000-8000-000000000107', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000d-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000102', 'Delivery',
     '00000007-0000-4000-8000-000000000107', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000d-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000102', null,
     '00000007-0000-4000-8000-000000000107', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (route_case_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This declaration_fields seed group supplies PostgreSQL native entry_kind, declaration_side, field_kind, field_standing enum inputs.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, parent_field_id, position, name, label, help, kind, text_limit, text_lines, term_list_version_id, must_be_given, standing, standing_threshold, created_at, created_by) values
    ('0000000b-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000101', 'question', 'takes', null, 1,
     'complaint', 'The complaint', default, 'text', 4000, 'several', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000101', 'question', 'gives', null, 1,
     'summary', 'Summary', default, 'text', 1000, 'several', null, true, 'never', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000102', 'question', 'takes', null, 1,
     'complaint', 'The complaint', 'As the customer wrote it, greeting and signature included.',
     'text', 4000, 'several', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000102', 'question', 'takes', null, 2,
     'channel', 'Came in by', default, 'text', 32, 'one', null, false, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000102', 'question', 'gives', null, 1,
     'category', 'Complaint category', 'What the customer asks to have put right.',
     'term', null, null, '00000007-0000-4000-8000-000000000106', true, 'above_confidence', 80, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000106', '00000007-0000-4000-8000-000000000102', 'question', 'gives', null, 2,
     'details', 'Order details', default, 'fields', null, null, null, false, 'never', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000107', '00000007-0000-4000-8000-000000000102', 'question', 'gives',
     '0000000b-0000-4000-8000-000000000106', 1,
     'product', 'Product', default, 'text', 128, 'one', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000108', '00000007-0000-4000-8000-000000000102', 'question', 'gives',
     '0000000b-0000-4000-8000-000000000106', 2,
     'order_reference', 'Order reference', default, 'text', 64, 'one', null, false, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000109', '00000007-0000-4000-8000-000000000103', 'question', 'takes', null, 1,
     'complaint', 'The complaint', default, 'text', 4000, 'several', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000110', '00000007-0000-4000-8000-000000000103', 'question', 'takes', null, 2,
     'channel', 'Came in by', default, 'text', 32, 'one', null, false, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000111', '00000007-0000-4000-8000-000000000103', 'question', 'gives', null, 1,
     'category', 'Complaint category', default, 'term', null, null, '00000007-0000-4000-8000-000000000106', true, 'above_confidence', 90, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000112', '00000007-0000-4000-8000-000000000103', 'question', 'gives', null, 2,
     'details', 'Order details', default, 'fields', null, null, null, false, 'never', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000113', '00000007-0000-4000-8000-000000000103', 'question', 'gives',
     '0000000b-0000-4000-8000-000000000112', 1,
     'product', 'Product', default, 'text', 128, 'one', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000114', '00000007-0000-4000-8000-000000000103', 'question', 'gives',
     '0000000b-0000-4000-8000-000000000112', 2,
     'order_reference', 'Order reference', default, 'text', 64, 'one', null, false, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000115', '00000007-0000-4000-8000-000000000104', 'workflow', 'takes', null, 1,
     'complaint', 'The complaint', 'As the customer wrote it, greeting and signature included.',
     'text', 4000, 'several', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000116', '00000007-0000-4000-8000-000000000107', 'workflow', 'takes', null, 1,
     'complaint', 'The complaint', default, 'text', 4000, 'several', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000117', '00000007-0000-4000-8000-000000000108', 'workflow', 'takes', null, 1,
     'complaint', 'The complaint', default, 'text', 4000, 'several', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000118', '00000007-0000-4000-8000-000000000108', 'workflow', 'gives', null, 1,
     'reference', 'Reference', default, 'text', 12, 'several', null, true, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),

    ('0000000b-0000-4000-8000-000000000119', '00000007-0000-4000-8000-000000000104', 'workflow', 'takes', null, 2,
     'title', 'Title', default, 'text', 128, 'one', null, false, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000120', '00000007-0000-4000-8000-000000000104', 'workflow', 'takes', null, 3,
     'amount', 'Amount in dispute', default, 'number', null, null, null, false, null, null, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000121', '00000007-0000-4000-8000-000000000104', 'workflow', 'takes', null, 4,
     'category', 'Complaint category', default, 'term', null, null, '00000007-0000-4000-8000-000000000106', false,
     null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (declaration_field_id) do nothing;

-- A null target path is the route's discriminator.
-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This bindings seed group supplies PostgreSQL native step_kind enum inputs.
-- DB-SPECIFIC: This bindings seed group supplies PostgreSQL jsonb constant input.
insert into bindings (binding_id, entry_version_id, workflow_step_id, step_kind, route_case_id, target_path, source_step_id, source_path, constant, created_at, created_by) values
    ('0000000e-0000-4000-8000-000000000101', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000101', null, null, 'complaint',
     null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000101', null, null, 'channel',
     null, null, '"email"', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000103', '00000007-0000-4000-8000-000000000104',
     '0000000c-0000-4000-8000-000000000102', 'route', null, null,
     '0000000c-0000-4000-8000-000000000101', 'category', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000104', '00000007-0000-4000-8000-000000000104',
     null, null, '0000000d-0000-4000-8000-000000000101', 'complaint',
     null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000104',
     null, null, '0000000d-0000-4000-8000-000000000102', 'complaint',
     null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000106', '00000007-0000-4000-8000-000000000104',
     null, null, '0000000d-0000-4000-8000-000000000103', 'complaint',
     null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000107', '00000007-0000-4000-8000-000000000107',
     '0000000c-0000-4000-8000-000000000103', null, null, 'complaint',
     null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000108', '00000007-0000-4000-8000-000000000107',
     '0000000c-0000-4000-8000-000000000103', null, null, 'channel',
     null, null, '"escalation"', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000109', '00000007-0000-4000-8000-000000000108',
     '0000000c-0000-4000-8000-000000000104', null, null, 'complaint',
     null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000110', '00000007-0000-4000-8000-000000000108',
     null, null, null, 'reference',
     '0000000c-0000-4000-8000-000000000104', 'reference', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (binding_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entries seed group supplies PostgreSQL native entry_kind enum inputs.
insert into entries (entry_id, group_id, kind, name, purpose, created_at, created_by) values
    ('00000006-0000-4000-8000-000000000107', '00000002-0000-4000-8000-000000000101', 'workflow',
     'Imaginary Mooncrate settlement review', 'A person drafts an invented settlement note and a model reviews its evidence.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000006-0000-4000-8000-000000000108', '00000002-0000-4000-8000-000000000101', 'question',
     'Imaginary Mooncrate settlement note', 'Retain the evidence and outstanding questions for an invented claim.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz '2000-01-02 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entry_versions seed group supplies PostgreSQL native entry_kind, subject_kind enum inputs.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, approved_at, approved_by, approved_by_kind, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000110', '00000006-0000-4000-8000-000000000107', 'workflow', 1, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000007-0000-4000-8000-000000000111', '00000006-0000-4000-8000-000000000108', 'question', 1, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into workflow_versions (entry_version_id, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000110', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into question_versions (entry_version_id, instruction, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000111', 'Summarise the invented settlement evidence, identify missing proof and retain every fictional identifier.', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This workflow_steps seed group supplies PostgreSQL native step_kind, entry_kind, step_producer enum inputs.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, label, kind, pinned_version_id, pinned_kind, producer, reviewer_model, reviewer_mode, tries, created_at, created_by) values
    ('0000000c-0000-4000-8000-000000000106', '00000007-0000-4000-8000-000000000110', 1, 'review_note', 'Draft the settlement note', 'entry',
     '00000007-0000-4000-8000-000000000111', 'question', 'person', 'sample_model', 'ordinary', 2, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This declaration_fields seed group supplies PostgreSQL native entry_kind, declaration_side, field_kind, field_standing enum inputs.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name, label, kind, text_limit, text_lines, must_be_given, standing, created_at, created_by) values
    ('0000000b-0000-4000-8000-000000000127', '00000007-0000-4000-8000-000000000110', 'workflow', 'takes', 0, 'complaint', 'The claim', 'text', 4000, 'several', true, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000128', '00000007-0000-4000-8000-000000000110', 'workflow', 'gives', 0, 'summary', 'Settlement note', 'text', 1000, 'several', true, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000129', '00000007-0000-4000-8000-000000000111', 'question', 'takes', 0, 'complaint', 'The claim', 'text', 4000, 'several', true, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000130', '00000007-0000-4000-8000-000000000111', 'question', 'gives', 0, 'summary', 'Settlement note', 'text', 1000, 'several', true, 'never', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (declaration_field_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_step_id, source_path, created_at, created_by) values
    ('0000000e-0000-4000-8000-000000000114', '00000007-0000-4000-8000-000000000110', '0000000c-0000-4000-8000-000000000106', 'complaint', null, 'complaint', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000115', '00000007-0000-4000-8000-000000000110', null, 'summary', '0000000c-0000-4000-8000-000000000106', 'summary', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (binding_id) do nothing;


-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entries seed group supplies PostgreSQL native entry_kind enum inputs.
insert into entries (entry_id, group_id, kind, name, purpose, created_at, created_by) values
    ('00000006-0000-4000-8000-000000000106', '00000002-0000-4000-8000-000000000101',
     'workflow', 'Research a fictional claim from files', 'Reads the text in practice files, then searches with those texts and your topic. All examples are invented.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz '2000-01-02 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entry_versions seed group supplies PostgreSQL native entry_kind, subject_kind enum inputs.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, approved_at, approved_by, approved_by_kind, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000109', '00000006-0000-4000-8000-000000000106', 'workflow', 1, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000', 'seeder', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into workflow_versions (entry_version_id, created_at, created_by) values ('00000007-0000-4000-8000-000000000109', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz '2000-01-02 00:00:00+00' use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entries seed group supplies PostgreSQL native entry_kind enum inputs.
insert into entries (entry_id, group_id, kind, name, purpose, created_at, created_by) values
    ('00000006-0000-4000-8000-000000000112', '00000002-0000-4000-8000-000000000101',
     'question', 'Imaginary Mooncrate evidence search', 'Finds invented evidence about the topic and all supplied file texts.', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This entry_versions seed group supplies PostgreSQL native entry_kind, subject_kind enum inputs.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, approved_at, approved_by, approved_by_kind, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000112', '00000006-0000-4000-8000-000000000112', 'question', 1, timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000', 'seeder', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into question_versions (entry_version_id, instruction, search_way, created_at, created_by) values
    ('00000007-0000-4000-8000-000000000112',
     'Read the supplied texts alongside the fictional topic. Find supporting evidence, identify missing proof and report sources for each text.', 'sample_web_search', timestamptz '2000-01-02 00:00:00+00',
     '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This declaration_fields seed group supplies PostgreSQL native entry_kind, declaration_side, field_kind, field_standing enum inputs.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name, label, kind, text_limit, text_lines, must_be_given, standing, created_at, created_by) values
    ('0000000b-0000-4000-8000-000000000131', '00000007-0000-4000-8000-000000000112', 'question', 'takes', 0,
     'topic', 'Search topic', 'text', 512, 'one', true, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000132', '00000007-0000-4000-8000-000000000112', 'question', 'gives', 0,
     'title', 'Title', 'text', 200, 'several', true, 'never', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000133', '00000007-0000-4000-8000-000000000112', 'question', 'gives', 1,
     'body', 'Body', 'text', 16000, 'several', true, 'never', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000134', '00000007-0000-4000-8000-000000000112', 'question', 'gives', 2,
     'references', 'References', 'text', 8000, 'several', false, 'never', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000135', '00000007-0000-4000-8000-000000000112', 'question', 'gives', 3,
     'other', 'Other findings', 'text', 4000, 'several', false, 'never', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (declaration_field_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This workflow_steps seed group supplies PostgreSQL native step_kind, step_producer, field_standing, entry_kind enum inputs.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, label, kind, search_way, producer, producer_model, producer_mode, tries, tells_what_happened, search_title_standing, search_body_standing, search_references_standing, search_other_standing, pinned_version_id, pinned_kind, created_at, created_by) values
    ('0000000c-0000-4000-8000-000000000105', '00000007-0000-4000-8000-000000000109', 2, 'research', 'Search for evidence', 'search', 'sample_web_search',
     'model', 'sample_model', 'ordinary', 2, true, 'always', 'never', 'always', 'always',
     '00000007-0000-4000-8000-000000000112', 'question', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- DB-SPECIFIC: on conflict, text[] and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This declaration_fields seed group supplies PostgreSQL native entry_kind, declaration_side, field_kind, field_standing enum inputs.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name, label, help, kind, text_limit, text_lines, must_be_given, standing, file_types, file_maximum_bytes, created_at, created_by) values
    ('0000000b-0000-4000-8000-000000000122', '00000007-0000-4000-8000-000000000109', 'workflow', 'takes', 0, 'topic', 'Search topic', 'The topic used for the search.', 'text', 512, 'one', true, null, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000123', '00000007-0000-4000-8000-000000000109', 'workflow', 'takes', 1, 'attachment', 'Text files', 'Upload the texts to research.', 'file', null, null, true, null, cast(array['.txt', '.md', '.csv', '.json'] as text[]), 2097152, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000124', '00000007-0000-4000-8000-000000000109', 'workflow', 'gives', 0, 'title', 'Title', null, 'text', 200, 'several', true, null, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000125', '00000007-0000-4000-8000-000000000109', 'workflow', 'gives', 1, 'body', 'Body', null, 'text', 16000, 'several', true, null, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000b-0000-4000-8000-000000000126', '00000007-0000-4000-8000-000000000109', 'workflow', 'gives', 2, 'references', 'References', null, 'text', 8000, 'several', false, null, null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (declaration_field_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_path, created_at, created_by) values
    ('0000000e-0000-4000-8000-000000000116', '00000007-0000-4000-8000-000000000109', '0000000c-0000-4000-8000-000000000105',
     'topic', 'topic', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (binding_id) do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into bindings (binding_id, entry_version_id, target_path, source_step_id, source_path, created_at, created_by) values
    ('0000000e-0000-4000-8000-000000000111', '00000007-0000-4000-8000-000000000109', 'title', '0000000c-0000-4000-8000-000000000105', 'title', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000112', '00000007-0000-4000-8000-000000000109', 'body', '0000000c-0000-4000-8000-000000000105', 'body', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('0000000e-0000-4000-8000-000000000113', '00000007-0000-4000-8000-000000000109', 'references', '0000000c-0000-4000-8000-000000000105', 'references', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (binding_id) do nothing;


-- DB-SPECIFIC: PostgreSQL ON CONFLICT targets the stable seed identifiers.
-- DB-SPECIFIC: timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This workflow_steps seed group supplies PostgreSQL native step_kind, code_step, step_producer enum inputs.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, label, kind, code_step, producer, tries, created_at, created_by) values
 ('0000000c-0000-4000-8000-000000000108', '00000007-0000-4000-8000-000000000109', 1, 'read_files', 'Read the files', 'code_step', 'read_text_file', 'code', 2, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
-- DB-SPECIFIC: This declaration_fields seed group supplies PostgreSQL native entry_kind, declaration_side, field_kind enum inputs.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name, label, kind, text_limit, text_lines, holds_many, many_limit, must_be_given, created_at, created_by) values
  ('0000000b-0000-4000-8000-000000000136', '00000007-0000-4000-8000-000000000112', 'question', 'takes', 1, 'text', 'Text from the files', 'text', 262144, 'several', true, 3, true, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (declaration_field_id) do nothing;

update declaration_fields set holds_many = true, many_limit = 3
where declaration_field_id = '0000000b-0000-4000-8000-000000000123' and updated_by is null;

-- Seed names fill only unnamed versions; a reader's own name survives repeatable migration runs.
-- DB-SPECIFIC: PostgreSQL UUID casts identify the seeded versions.
update entry_versions version set version_name = named.name
from (values
    ('00000007-0000-4000-8000-000000000101'::uuid, 'Original complaint summary'),
    ('00000007-0000-4000-8000-000000000102'::uuid, 'Category and order reference'),
    ('00000007-0000-4000-8000-000000000103'::uuid, 'Missing order reference guidance'),
    ('00000007-0000-4000-8000-000000000104'::uuid, 'Customer support routing'),
    ('00000007-0000-4000-8000-000000000105'::uuid, 'Next support workflow draft'),
    ('00000007-0000-4000-8000-000000000106'::uuid, 'Current complaint categories'),
    ('00000007-0000-4000-8000-000000000107'::uuid, 'Escalation summary'),
    ('00000007-0000-4000-8000-000000000108'::uuid, 'Complaint reference stamp'),
    ('00000007-0000-4000-8000-000000000109'::uuid, 'File-based claim research'),
    ('00000007-0000-4000-8000-000000000110'::uuid, 'Settlement note review'),
    ('00000007-0000-4000-8000-000000000111'::uuid, 'Evidence and open questions'),
    ('00000007-0000-4000-8000-000000000112'::uuid, 'Evidence search over the file texts'),
    ('00000007-0000-4000-8000-000000000113'::uuid, 'Remedy guidance draft')
) as named(id, name)
where version.entry_version_id = named.id and version.version_name is null;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
insert into bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_step_id, source_path, created_at, created_by) values
 ('0000000e-0000-4000-8000-000000000117', '00000007-0000-4000-8000-000000000109', '0000000c-0000-4000-8000-000000000108', 'file', null, 'attachment', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000e-0000-4000-8000-000000000118', '00000007-0000-4000-8000-000000000109', '0000000c-0000-4000-8000-000000000105', 'text', '0000000c-0000-4000-8000-000000000108', 'text', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (binding_id) do nothing;

-- A newer in-service question leaves historical pins alone. The editable draft below pins the older one.
-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, version_name, created_at, created_by, approved_at, approved_by, approved_by_kind) values
 ('00000007-0000-4000-8000-000000000114', '00000006-0000-4000-8000-000000000101', 'question', 4, 'Evidence-aware complaint categories',
  timestamptz '2000-01-03 00:00:00+00', '00000000-0000-4000-8000-000000000000',
  timestamptz '2000-01-03 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
insert into question_versions (entry_version_id, instruction, created_at, created_by) values
 ('00000007-0000-4000-8000-000000000114',
  'Say which category the complaint falls under, which product it concerns and any order reference it gives. Use the customer evidence before guessing a category.',
  timestamptz '2000-01-03 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- The two in-service questions share the declaration contract, including their nested fields and reference-list pin.
-- DB-SPECIFIC: PostgreSQL UUID casts and ON CONFLICT identify the fixed copies.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, parent_field_id, position, name, label, help, kind, text_limit, text_lines, term_list_version_id, must_be_given, standing, standing_threshold, created_at, created_by)
select copied.id, '00000007-0000-4000-8000-000000000114', source.entry_kind, source.side,
       case when source.parent_field_id is null then null else '0000000b-0000-4000-8000-000000000203'::uuid end,
       source.position, source.name, source.label, source.help, source.kind, source.text_limit, source.text_lines,
       source.term_list_version_id, source.must_be_given, source.standing, source.standing_threshold, source.created_at, source.created_by
from declaration_fields source join (values
 ('0000000b-0000-4000-8000-000000000103'::uuid, '0000000b-0000-4000-8000-000000000200'::uuid),
 ('0000000b-0000-4000-8000-000000000104'::uuid, '0000000b-0000-4000-8000-000000000201'::uuid),
 ('0000000b-0000-4000-8000-000000000105'::uuid, '0000000b-0000-4000-8000-000000000202'::uuid),
 ('0000000b-0000-4000-8000-000000000106'::uuid, '0000000b-0000-4000-8000-000000000203'::uuid),
 ('0000000b-0000-4000-8000-000000000107'::uuid, '0000000b-0000-4000-8000-000000000204'::uuid),
 ('0000000b-0000-4000-8000-000000000108'::uuid, '0000000b-0000-4000-8000-000000000205'::uuid)
) as copied(original, id) on source.declaration_field_id = copied.original
on conflict (declaration_field_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name, label, kind, text_limit, text_lines, must_be_given, created_at, created_by) values
 ('0000000b-0000-4000-8000-000000000206', '00000007-0000-4000-8000-000000000105', 'workflow', 'takes', 1,
  'complaint', 'The complaint', 'text', 4000, 'several', true, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (declaration_field_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, label, kind, pinned_version_id, pinned_kind, producer, tries, created_at, created_by) values
 ('0000000c-0000-4000-8000-000000000109', '00000007-0000-4000-8000-000000000105', 1, 'summarise', 'Read the complaint',
  'entry', '00000007-0000-4000-8000-000000000102', 'question', 'person', 2, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
insert into bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_path, constant, created_at, created_by) values
 ('0000000e-0000-4000-8000-000000000119', '00000007-0000-4000-8000-000000000105', '0000000c-0000-4000-8000-000000000109', 'complaint', 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000e-0000-4000-8000-000000000120', '00000007-0000-4000-8000-000000000105', '0000000c-0000-4000-8000-000000000109', 'channel', null, '"email"', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (binding_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
-- DB-SPECIFIC: This entries seed group supplies PostgreSQL native entry_kind enum inputs.
insert into entries (entry_id, group_id, kind, name, purpose, created_at, created_by) values
 ('00000006-0000-4000-8000-000000000113', '00000002-0000-4000-8000-000000000101', 'workflow', 'Replace a damaged delivery',
  'Reads a complaint about a parcel that came damaged, stamps it with a reference and passes it up for a replacement.',
  timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
-- DB-SPECIFIC: This entry_versions seed group supplies PostgreSQL native entry_kind, subject_kind enum inputs.
insert into entry_versions (entry_version_id, entry_id, entry_kind, number, version_name, created_at, created_by, approved_at, approved_by, approved_by_kind) values
 ('00000007-0000-4000-8000-000000000115', '00000006-0000-4000-8000-000000000113', 'workflow', 1, 'Damaged delivery escalation',
  timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000',
  timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000', 'seeder')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
insert into workflow_versions (entry_version_id, created_at, created_by) values
 ('00000007-0000-4000-8000-000000000115', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (entry_version_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
-- DB-SPECIFIC: This declaration_fields seed group supplies PostgreSQL native entry_kind, declaration_side, field_kind enum inputs.
insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name, label, help, kind, text_limit, text_lines, term_list_version_id, must_be_given, created_at, created_by) values
 ('0000000b-0000-4000-8000-000000000137', '00000007-0000-4000-8000-000000000115', 'workflow', 'takes', 1, 'complaint', 'The complaint',
  'As the customer wrote it, greeting and signature included.', 'text', 4000, 'several', null, true, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000b-0000-4000-8000-000000000138', '00000007-0000-4000-8000-000000000115', 'workflow', 'gives', 1, 'reference', 'Reference',
  null, 'text', 12, 'several', null, true, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000b-0000-4000-8000-000000000139', '00000007-0000-4000-8000-000000000115', 'workflow', 'gives', 2, 'category', 'Complaint category',
  null, 'term', null, null, '00000007-0000-4000-8000-000000000106', true, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (declaration_field_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
-- DB-SPECIFIC: This workflow_steps seed group supplies PostgreSQL native step_kind, entry_kind, step_producer enum inputs.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, label, kind, pinned_version_id, pinned_kind, producer, tries, created_at, created_by) values
 ('0000000c-0000-4000-8000-000000000110', '00000007-0000-4000-8000-000000000115', 1, 'read_complaint', 'Read the complaint',
  'entry', '00000007-0000-4000-8000-000000000102', 'question', 'person', 2, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000c-0000-4000-8000-000000000112', '00000007-0000-4000-8000-000000000115', 3, 'escalate', 'Pass it up for a replacement',
  'entry', '00000007-0000-4000-8000-000000000107', 'workflow', null, null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT and timestamptz literals.
-- DB-SPECIFIC: This workflow_steps seed group supplies PostgreSQL native step_kind, code_step, step_producer enum inputs.
insert into workflow_steps (workflow_step_id, entry_version_id, position, name, label, kind, code_step, producer, tries, created_at, created_by) values
 ('0000000c-0000-4000-8000-000000000111', '00000007-0000-4000-8000-000000000115', 2, 'stamp', 'Stamp a reference',
  'code_step', 'stamp_reference', 'code', 2, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (workflow_step_id) do nothing;

-- DB-SPECIFIC: PostgreSQL ON CONFLICT, jsonb constant input and timestamptz literals.
insert into bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_step_id, source_path, constant, created_at, created_by) values
 ('0000000e-0000-4000-8000-000000000121', '00000007-0000-4000-8000-000000000115', '0000000c-0000-4000-8000-000000000110', 'complaint', null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000e-0000-4000-8000-000000000122', '00000007-0000-4000-8000-000000000115', '0000000c-0000-4000-8000-000000000110', 'channel', null, null, '"email"', timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000e-0000-4000-8000-000000000123', '00000007-0000-4000-8000-000000000115', '0000000c-0000-4000-8000-000000000111', 'complaint', null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000e-0000-4000-8000-000000000124', '00000007-0000-4000-8000-000000000115', '0000000c-0000-4000-8000-000000000112', 'complaint', null, 'complaint', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000e-0000-4000-8000-000000000125', '00000007-0000-4000-8000-000000000115', null, 'reference', '0000000c-0000-4000-8000-000000000111', 'reference', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
 ('0000000e-0000-4000-8000-000000000126', '00000007-0000-4000-8000-000000000115', null, 'category', '0000000c-0000-4000-8000-000000000110', 'category', null, timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (binding_id) do nothing;
