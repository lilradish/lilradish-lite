-- 000001 is a value this deployment chose and that nothing in this system knows anything about, the
-- shape of a user identifier belonging to whichever identity provider a deployer runs. A
-- deployment issuing something else changes this file, and only this file.

insert into subjects (subject_id, kind, user_id, created_by) values
    ('00000001-0000-4000-8000-000000000001', 'person', '000001',
     '00000000-0000-4000-8000-000000000000');

insert into pool_members (subject_id, created_by) values
    ('00000001-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000000');

insert into estate_role_grants (subject_id, role, created_by) values
    ('00000001-0000-4000-8000-000000000001', 'steward', '00000000-0000-4000-8000-000000000000');
