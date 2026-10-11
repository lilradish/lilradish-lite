-- 000106, pooled by the pool seed, is left out: somebody still in the pool after leaving.
-- A name re-seeded here overwrites the directory's, while the pool seed never rewrites its own copy of it.

-- DB-SPECIFIC: on conflict and U&' use PostgreSQL's syntax and semantics.
insert into people (user_id, display_name) values
    ('000101', 'Ada Moonvale'),
    ('000102', 'Grace Starling'),
    ('000103', 'Alan Cloudmere'),
    ('000104', '山田太郎'),
    ('000105', 'Barbara Mosswick'),
    ('000107', 'Ada Fernwhistle'),
    ('000108', 'Adam Cloudfen'),
    ('000109', 'Autumn Ada Willowmere'),
    ('000110', 'Grace Emberleigh'),
    ('000111', 'Alan Moonvale'),
    ('000112', 'Alana Fernwhistle'),
    ('000113', 'Barbara Starling'),
    ('000114', '山田花子'),
    ('000115', '田中' || U&'\3000' || '一郎'),
    ('000116', 'Émilie Moonbrook'),
    ('000117', 'Katherine Cloudmere'),
    ('000118', 'Dorothy Fernwhistle'),
    ('000119', 'Mary Mosswick'),
    ('000120', 'Margaret Willowmere'),
    ('000121', 'Frances Moonbrook'),
    ('000122', 'John Emberleigh'),
    ('000123', 'Donald Cloudfen'),
    ('000124', 'Evan Starling'),
    ('000125', 'Tony Moonbrook'),
    ('000126', 'Leslie Fernwhistle'),
    ('000127', 'Helen Willowmere'),
    ('000128', 'Riley Cloudmere')
on conflict (user_id) do update set display_name = excluded.display_name;
