package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.ABSENT
import static org.lilradish.lite.migration.fixture.SchemaKeys.ROW_OF
import static org.lilradish.lite.migration.fixture.SchemaKeys.SEEDER
import static org.lilradish.lite.migration.fixture.SchemaKeys.STEWARD
import static org.lilradish.lite.migration.fixture.SchemaKeys.WORKFLOW_RUNNER

/** How a case and a statement are written, whichever migration's rules they are about. */
final class SchemaRows {

    static Map<String, String> attack(String constraint, String table, String statement) {
        [constraint: constraint, table: table, statement: statement]
    }

    /** The five rules a table carries when people change its rows, each attacked on its fixture row. */
    static List<Map<String, String>> authorshipAttacks(String table) {
        [attack("${table}_author_fk", table, setting(table, "created_by = '${ABSENT}'")), *editorAttacks(table)]
    }

    /** The four of those that are about who changed a row, for a table whose author is held to a kind. */
    static List<Map<String, String>> editorAttacks(String table) {
        [
            attack("${table}_editor_person_fk", table, setting(table, "updated_at = now(), updated_by = '${SEEDER}'")),
            attack("${table}_editor_is_person", table,
                    setting(table, "updated_at = now(), updated_by = '${WORKFLOW_RUNNER}', updated_by_kind = 'system'")),
            attack("${table}_updated_together", table, setting(table, "updated_at = now()")),
            attack("${table}_updated_after_created", table,
                    setting(table, "updated_at = created_at - interval '1 day', updated_by = '${STEWARD}'")),
        ]
    }

    static String setting(String table, String assignments) {
        "update app.${table} set ${assignments} where ${ROW_OF[table]}"
    }

    /** Quoted whatever it is: PostgreSQL types an untyped literal by the column it is written to. */
    static String literal(Object value) {
        value == null ? "null" : "'${value}'"
    }

    static String insertInto(String table, Map<String, String> columns) {
        "insert into app.${table} (${columns.keySet().join(', ')}) values (${columns.values().join(', ')})"
    }

    /** Each value as a statement writes it: a boolean as it stands, and anything else quoted. */
    static Map<String, String> asWritten(Map<String, String> row) {
        row.collectEntries { column, value -> [column, value in ["true", "false"] ? value : literal(value)] }
    }

    private SchemaRows() {}
}
