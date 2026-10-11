-- Repeatable migrations rerun when their checksum changes; every statement must survive reapplication.
-- Untargeted on conflict do nothing also lets partial unique indexes arbitrate current stays and role holdings.
-- The numeric description prefix orders these files, since repeatable migrations have no version.

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This subjects seed group supplies PostgreSQL native subject_kind enum inputs.
insert into subjects (subject_id, kind, user_id, display_name, created_at, created_by) values
    ('00000001-0000-4000-8000-000000000101', 'person', '000101', 'Ada Moonvale', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000102', 'person', '000102', 'Grace Starling', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000103', 'person', '000103', 'Alan Cloudmere', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000104', 'person', '000104', '山田太郎', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000105', 'person', '000105', 'Barbara Mosswick', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000106', 'person', '000106', null, timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
insert into pool_members (subject_id, created_at, created_by) values
    ('00000001-0000-4000-8000-000000000101', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000102', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000103', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000104', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000105', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000106', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict do nothing;

-- DB-SPECIFIC: on conflict and timestamptz literals use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This estate_role_grants seed group supplies PostgreSQL native estate_role enum inputs.
insert into estate_role_grants (subject_id, role, created_at, created_by) values
    ('00000001-0000-4000-8000-000000000101', 'watcher', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000102', 'steward', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000103', 'watcher', timestamptz '2000-01-01 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict do nothing;
