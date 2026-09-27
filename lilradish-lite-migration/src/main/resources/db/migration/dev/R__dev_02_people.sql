-- Development only. 000106, pooled by the pool seed, is left out: somebody still in the pool after leaving.
-- A name re-seeded here overwrites the directory's, while the pool seed never rewrites its own copy of it.

insert into people (user_id, display_name) values
    ('000101', 'Ada Lovelace'),
    ('000102', 'Grace Hopper'),
    ('000103', 'Alan Turing'),
    ('000104', '山田太郎'),
    ('000105', 'Barbara Liskov'),
    ('000107', 'Ada Yonath'),
    ('000108', 'Adam Smith'),
    ('000109', 'Augusta Ada King'),
    ('000110', 'Grace Kelly'),
    ('000111', 'Alan Kay'),
    ('000112', 'Alana Turner'),
    ('000113', 'Barbara McClintock'),
    ('000114', '山田花子'),
    ('000115', '田中' || U&'\3000' || '一郎'),
    ('000116', 'Émilie du Châtelet'),
    ('000117', 'Katherine Johnson'),
    ('000118', 'Dorothy Vaughan'),
    ('000119', 'Mary Jackson'),
    ('000120', 'Margaret Hamilton'),
    ('000121', 'Frances Allen'),
    ('000122', 'John Backus'),
    ('000123', 'Donald Knuth'),
    ('000124', 'Edsger Dijkstra'),
    ('000125', 'Tony Hoare'),
    ('000126', 'Leslie Lamport'),
    ('000127', 'Hedy Lamarr'),
    ('000128', 'Radia Perlman')
on conflict (user_id) do update set display_name = excluded.display_name;
