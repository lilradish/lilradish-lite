-- The enum label must commit before the publication uses it; this script's .conf runs each statement outside a transaction.
-- DB-SPECIFIC: ALTER TYPE ADD VALUE IF NOT EXISTS uses PostgreSQL's enum syntax and semantics.
alter type code_step add value if not exists 'read_text_file';

-- DB-SPECIFIC: timestamptz literals and ON CONFLICT use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: This code_step_publications seed group supplies PostgreSQL native code_step enum values.
insert into code_step_publications (code_step_publication_id, code_step, group_key, every_group, created_at, created_by)
values ('00000009-0000-4000-8000-000000000102', 'read_text_file', 'SUPPORT', false,
        timestamptz '2000-01-02 00:00:00+00', '00000000-0000-4000-8000-000000000000')
on conflict (code_step_publication_id) do nothing;
