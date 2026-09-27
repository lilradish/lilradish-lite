-- Development only, and repeatable for the reasons the pool seed gives. One run, stopped short of the engine
-- planning its first step. A run at the top is refused the seeder, so Grace, who owns its group, starts it.

-- Keys by their first eight digits, going on from the library seed's: f run.

-- Targeted for the reason groups is in the library seed: a run the app numbered 1 is refused, not skipped.
insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                  started_with, created_by, created_by_kind) values
    ('0000000f-0000-4000-8000-000000000101', '00000002-0000-4000-8000-000000000101', 1, 'Kettle arrived broken',
     '00000006-0000-4000-8000-000000000102', '00000007-0000-4000-8000-000000000104',
     '0000000f-0000-4000-8000-000000000101', 0,
     jsonb_build_object('complaint', 'Hello,' || chr(10) || chr(10)
         || 'The kettle I ordered (order 4471-2208) came in a crushed box with its lid cracked.'
         || ' I would like a new one sent, or my money back.' || chr(10) || chr(10)
         || 'Kind regards,' || chr(10) || 'Jane Doe'),
     '00000001-0000-4000-8000-000000000102', 'person')
on conflict (run_id) do nothing;
