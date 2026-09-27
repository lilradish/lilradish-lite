package org.lilradish.lite.testutil.library

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER

/**
 * A workflow of a group's in service taking what a person starting a run of it fills in, written straight into a
 * store: text, a number, a term of a list of the group's, many texts, and fields of their own.
 */
final class TakingWorkflow {

    private TakingWorkflow() {}

    /** A reference list in service offering two terms in its own order, and saying how to choose between them. */
    static void list(LibraryStore store, String entry, String version, String group) {
        store.entry(entry, group, "reference_list", "Kinds of complaint")
        store.seeded(version, entry, 1)
        store.content(version, "reference_list", "Pick the one the customer names first.")
        [["late", "It came after the day promised."], ["damaged", "It came broken."]].eachWithIndex { term, position ->
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, ?, ?::uuid)
                    """).params(version, position, term[0], term[1], SEEDER).update()
        }
    }

    /** A workflow's version in service with its content, taking nothing yet. */
    static void workflow(LibraryStore store, String entry, String version, String group, String name) {
        store.entry(entry, group, "workflow", name)
        store.seeded(version, entry, 1)
        store.content(version, "workflow")
    }

    /**
     * What the version takes: the complaint in at most twenty characters, which must be given; an amount; the
     * kind of complaint off the list; at most two tags of ten; and a contact holding an email that must be given.
     */
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

    /** One field of what the version takes, at its place among its parent's or the first level's. */
    static String field(LibraryStore store, String version, int position, String name, String kind, Map shaped) {
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
