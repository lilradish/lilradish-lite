-- DB-SPECIFIC: PostgreSQL extension membership identifies the GIN text opclass; identifier quoting preserves its namespace.
-- DB-SPECIFIC: DO blocks, pg_catalog queries, oid/name types, regclass/regtype casts, has_schema_privilege() and format() use PostgreSQL's syntax and semantics.
-- DB-SPECIFIC: PostgreSQL dollar quoting delimits this PL/pgSQL DO body.
do $$
declare
    trigrams oid;
    opclass_schema name;
    opclass_name name;
begin
    select extension.oid into trigrams
      from pg_catalog.pg_extension extension
     where extension.extname = 'pg_trgm';
    if trigrams is null then
        raise exception 'pg_trgm must be installed by the database administrator before migrations are run.';
    end if;

    select namespace.nspname, opclass.opcname into strict opclass_schema, opclass_name
      from pg_catalog.pg_opclass opclass
      join pg_catalog.pg_am access_method on access_method.oid = opclass.opcmethod
      join pg_catalog.pg_namespace namespace on namespace.oid = opclass.opcnamespace
      join pg_catalog.pg_depend member
        on member.classid = 'pg_catalog.pg_opclass'::pg_catalog.regclass
       and member.objid = opclass.oid and member.objsubid = 0
       and member.refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass
       and member.refobjid = trigrams and member.deptype = 'e'
     where access_method.amname = 'gin'
       and opclass.opcname = 'gin_trgm_ops'
       and opclass.opcintype = 'pg_catalog.text'::pg_catalog.regtype;
    if not pg_catalog.has_schema_privilege(current_user, opclass_schema, 'USAGE') then
        raise exception 'The migration role requires USAGE on the schema containing pg_trgm.';
    end if;

    execute pg_catalog.format('create index people_display_name_folded_trigrams on people using gin (display_name_folded %I.%I)', opclass_schema, opclass_name);
    execute pg_catalog.format('create index subjects_display_name_folded_trigrams on subjects using gin (display_name_folded %I.%I)', opclass_schema, opclass_name);
    execute pg_catalog.format('create index subjects_user_id_folded_trigrams on subjects using gin (user_id_folded %I.%I)', opclass_schema, opclass_name);
    execute pg_catalog.format('create index groups_name_folded_trigrams on groups using gin (name_folded %I.%I)', opclass_schema, opclass_name);
    execute pg_catalog.format('create index entries_name_folded_trigrams on entries using gin (name_folded %I.%I)', opclass_schema, opclass_name);
end
$$;
