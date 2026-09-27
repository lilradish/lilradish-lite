-- DB-SPECIFIC, for every migration and development seed. Concepts that port, spelt otherwise elsewhere:
--   timestamptz
--   text
--   uuid, and uuidv7() as a time-ordered default for it
--   now()
--   partial indexes, unique or not
--   stored generated columns
--   on conflict do nothing / do update
--   the regex operators ~ and !~, and \uwxyz escapes inside a pattern, elsewhere only in recent versions
--   U&'' literals
--   immutable, strict and parallel safe, and a SQL-standard function body
--   boolean
--   jsonb, elsewhere only in recent versions, and jsonb_typeof()
--   is distinct from, elsewhere only in recent versions
--   the is json predicate, elsewhere a function
--   num_nonnulls
--   unique nulls not distinct
--   length(), counting characters, where elsewhere a length may drop trailing spaces or count code units
--   || as concatenation, and chr()
--   a foreign key listing its target's columns in another order than the target key declares them
-- Constructs that may have no counterpart:
--   enum types
--   deferrable unique constraints, checked when the transaction commits
--   every now() in one transaction is equal
--   :: casts
--   a unique over a nullable column admitting any number of nulls, where elsewhere it may admit one
--   a comparison used as a value inside a check, as in (a is null) = (b is null)
--   regex bracket ranges taken as code points, where elsewhere a range may follow the collation
--   casefold, normalize and the pg_unicode_fast collation
create type subject_kind as enum ('person', 'system', 'seeder');

-- The one definition of how held text and typed text are compared, which every stored fold and every
-- search goes through. Stable only within a server major version: every stored fold is computed again after one.
create function search_fold(text) returns text
    language sql immutable strict parallel safe
    return normalize(casefold(normalize($1 collate pg_unicode_fast, NFD)), NFC);

create table subjects (
    -- No default: this key is minted before there is a row for it to name.
    subject_id    uuid         not null,
    kind          subject_kind not null,
    user_id       text,
    display_name  text,
    user_id_folded      text generated always as (search_fold(user_id)) stored,
    display_name_folded text generated always as (search_fold(display_name)) stored,
    created_at    timestamptz  not null default now(),
    created_by    uuid         not null,

    constraint subjects_pk primary key (subject_id),
    constraint subjects_author_fk foreign key (created_by) references subjects (subject_id),
    -- A second full btree over every row beside subjects_pk, which the planner will often prefer to the primary key.
    -- That is the cost of a foreign key target for every row that claims what kind of subject it names.
    constraint subjects_kind_unique unique (subject_id, kind),
    -- Nulls are distinct to a unique constraint, so this is uniqueness among people exactly.
    constraint subjects_user_unique unique (user_id),
    constraint subjects_user_only_for_people
        check ((kind = 'person') = (user_id is not null)),
    -- Ranges rather than [[:cntrl:]], classified per platform and per locale. \uwxyz is exactly
    -- four hexadecimal digits, where \xhhh is one character however many digits follow and so
    -- swallows whatever hexadecimal is written next to it; escaped rather than typed, so no invisible
    -- character is written into the check that refuses it. The domain holds the rest of the rule,
    -- which is versioned by Unicode and so cannot be kept level from in here.
    constraint subjects_user_id_visible check (
        user_id is null
            or (length(user_id) > 0
                and user_id !~ '[\u0000-\u001f\u007f-\u009f]')),
    -- Names the refusal the btree index row size limit would make anonymous, that limit being a byte
    -- count after compression: it refuses one value and admits another of equal length.
    constraint subjects_user_id_bounded check (length(user_id) <= 256),
    constraint subjects_display_name_only_for_people
        check (kind = 'person' or display_name is null),
    constraint subjects_display_name_visible check (
        display_name is null
            or (length(display_name) > 0
                and display_name !~ '[\u0000-\u001f\u007f-\u009f]')),
    constraint subjects_display_name_bounded check (length(display_name) <= 256)
);

-- A row whose created_by is its own subject_id satisfies subjects_author_fk, which is how a first
-- subject can exist at all.
insert into subjects (subject_id, kind, created_by) values
    ('00000000-0000-4000-8000-000000000000', 'seeder', '00000000-0000-4000-8000-000000000000');

-- This row pairs with a constant compiled into the application. Neither side can see the other, and
-- a test holds them level — editing one alone reddens there rather than at the first act recorded
-- against a system actor.
insert into subjects (subject_id, kind, created_by) values
    ('00000000-0000-4000-8000-000000000001', 'system', '00000000-0000-4000-8000-000000000000');
