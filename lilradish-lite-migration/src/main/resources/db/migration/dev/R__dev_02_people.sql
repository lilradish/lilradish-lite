-- Development only. 000106, pooled by the pool seed, is left out: somebody still in the pool after leaving.
-- A name re-seeded here overwrites the directory's, while the pool seed never rewrites its own copy of it.

-- DB-SPECIFIC: on conflict and U&' use PostgreSQL's syntax and semantics.
insert into people (user_id, display_name) values
    ('000101', 'Demo Sample Ada One'),
    ('000102', 'Demo Sample Grace One'),
    ('000103', 'Demo Sample Alan Two'),
    ('000104', '山田太郎'),
    ('000105', 'Demo Sample Barbara One'),
    ('000107', 'Demo Sample Ada Two'),
    ('000108', 'Demo Sample Adam One'),
    ('000109', 'Demo Sample Autumn Ada One'),
    ('000110', 'Demo Sample Grace Two'),
    ('000111', 'Demo Sample Alan One'),
    ('000112', 'Demo Sample Alana One'),
    ('000113', 'Demo Sample Barbara Two'),
    ('000114', '山田花子'),
    ('000115', '田中' || U&'\3000' || '一郎'),
    ('000116', 'Demo Sample Émilie One'),
    ('000117', 'Demo Sample Katherine One'),
    ('000118', 'Demo Sample Dorothy One'),
    ('000119', 'Demo Sample Mary One'),
    ('000120', 'Demo Sample Margaret One'),
    ('000121', 'Demo Sample Frances One'),
    ('000122', 'Demo Sample John One'),
    ('000123', 'Demo Sample Donald One'),
    ('000124', 'Demo Sample Evan One'),
    ('000125', 'Demo Sample Tony One'),
    ('000126', 'Demo Sample Leslie One'),
    ('000127', 'Demo Sample Helen One'),
    ('000128', 'Demo Sample Riley One')
on conflict (user_id) do update set display_name = excluded.display_name;
