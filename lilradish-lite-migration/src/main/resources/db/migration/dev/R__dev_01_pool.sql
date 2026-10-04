-- Repeatable rather than versioned, so it is re-applied whenever its own bytes change and every
-- statement here has to survive running twice. `on conflict do nothing` carries that, and carries it
-- without a target so that the partial unique indexes arbitrate too — one current pool stay, one
-- current holding of a role — and not the primary keys alone.
--
-- Numbered because a repeatable migration carries no version and is ordered by its description
-- alone: a second file here is sorted against this one by that prefix and by nothing else.

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into subjects (subject_id, kind, user_id, display_name, created_by) values
    ('00000001-0000-4000-8000-000000000101', 'person', '000101', 'Demo Sample Ada One', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000102', 'person', '000102', 'Demo Sample Grace One', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000103', 'person', '000103', 'Demo Sample Alan Two', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000104', 'person', '000104', '山田太郎', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000105', 'person', '000105', 'Demo Sample Barbara One', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000106', 'person', '000106', null, '00000000-0000-4000-8000-000000000000')
on conflict do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into pool_members (subject_id, created_by) values
    ('00000001-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000103', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000104', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000105', '00000000-0000-4000-8000-000000000000'),
    ('00000001-0000-4000-8000-000000000106', '00000000-0000-4000-8000-000000000000')
on conflict do nothing;

-- DB-SPECIFIC: on conflict uses PostgreSQL's syntax and semantics.
insert into estate_role_grants (subject_id, role, created_by) values
    ('00000001-0000-4000-8000-000000000101', 'watcher', '00000000-0000-4000-8000-000000000000')
on conflict do nothing;
