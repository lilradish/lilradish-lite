-- DB-SPECIFIC: PostgreSQL's DO block, extension catalogs and schema USAGE check enforce the database prerequisite.
do $pg_trgm_prerequisite$
declare
    extension_schema pg_catalog.oid;
begin
    select namespace.oid into extension_schema
      from pg_catalog.pg_extension extension
      join pg_catalog.pg_namespace namespace on namespace.oid = extension.extnamespace
     where extension.extname = 'pg_trgm';

    if extension_schema is null then
        raise exception 'pg_trgm must be installed by the database administrator before migrations are run.';
    end if;
    if not pg_catalog.has_schema_privilege(extension_schema, 'USAGE') then
        raise exception 'The migration role requires USAGE on the schema containing pg_trgm.';
    end if;
end
$pg_trgm_prerequisite$;

-- DB-SPECIFIC: as enum uses PostgreSQL's syntax and semantics.
create type subject_kind as enum ('person', 'system', 'seeder');

-- The one definition of how held text and typed text are compared, which every stored fold and every
-- search goes through. Stable only within a server major version: every stored fold is computed again after one.
-- DB-SPECIFIC: normalize(), casefold(), collate pg_unicode_fast, immutable, strict, parallel safe, language sql and text use PostgreSQL's syntax and semantics.
create function search_fold(text) returns text
    language sql immutable strict parallel safe
    return normalize(casefold(normalize($1 collate pg_unicode_fast, NFD)), NFC);

-- DB-SPECIFIC: length(), text, !~, four-digit Unicode escapes in regular expressions, generated always, timestamptz, now(), uuid, subject_kind and its label comparisons use PostgreSQL's syntax and semantics.
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
    -- character is written into the check that refuses it.
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
-- DB-SPECIFIC: These two subjects seed inserts supply PostgreSQL native subject_kind enum values.
insert into subjects (subject_id, kind, created_by) values
    ('00000000-0000-4000-8000-000000000000', 'seeder', '00000000-0000-4000-8000-000000000000');

insert into subjects (subject_id, kind, created_by) values
    ('00000000-0000-4000-8000-000000000001', 'system', '00000000-0000-4000-8000-000000000000');
