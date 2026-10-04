package org.lilradish.lite.testutil.library

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER


final class TakingWorkflow {

    private TakingWorkflow() {}


    static void list(LibraryStore store, String entry, String version, String group) {
        store.entry(entry, group, "reference_list", "Kinds of complaint")
        store.seeded(version, entry, 1)
        store.content(version, "reference_list", "Pick the one the customer names first.")
        [["late", "It came after the day promised."], ["damaged", "It came broken."]].eachWithIndex { term, position ->
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, ?, ?::uuid)
                    """).params(version, position, term[0], term[1], SEEDER).update()
        }
    }


    static void workflow(LibraryStore store, String entry, String version, String group, String name) {
        store.entry(entry, group, "workflow", name)
        store.seeded(version, entry, 1)
        store.content(version, "workflow")
    }


    static void takes(LibraryStore store, String version, String listVersion) {
        field(store, version, 1, "complaint", "text", [textLimit: 20, mustBeGiven: true, label: "The complaint",
                                                       help: "In the customer's words."])
        field(store, version, 2, "amount", "number", [:])
        field(store, version, 3, "category", "term", [list: listVersion, mustBeGiven: true])
        field(store, version, 4, "tags", "text", [textLimit: 10, manyLimit: 2])
        def contact = field(store, version, 5, "contact", "fields", [:])
        field(store, version, 1, "email", "text", [textLimit: 50, mustBeGiven: true, parent: contact])
        field(store, version, 2, "phone", "text", [textLimit: 20, parent: contact])
    }


    static String field(LibraryStore store, String version, int position, String name, String kind, Map shaped) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, field_kind, text; RETURNING.
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, parent_field_id, position, name,
                                                label, help, kind, holds_many, text_limit, many_limit,
                                                term_list_version_id, must_be_given, created_by)
                values (?::uuid, 'workflow', 'takes', ?::uuid, ?, ?, ?, ?, ?::field_kind, ?, ?, ?, ?::uuid, ?, ?::uuid)
                returning declaration_field_id::text
                """).params(version, shaped.parent, position, name, shaped.label, shaped.help, kind,
                shaped.manyLimit != null, shaped.textLimit, shaped.manyLimit, shaped.list,
                shaped.mustBeGiven ?: false, SEEDER).query(String).single()
    }
}
