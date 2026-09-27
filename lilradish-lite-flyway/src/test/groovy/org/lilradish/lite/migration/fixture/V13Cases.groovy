package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V13__group_currency.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V13Cases {

    /** GROUP's choice; OTHER_GROUP has chosen none. */
    static final Map<String, String> A_CURRENCY = [group_id: literal(GROUP), currency: "'EUR'",
                                                   created_by: literal(STEWARD)]

    static final List<String> FIXTURE = [
        insertInto("group_currencies", A_CURRENCY),
    ]

    static final List<Map<String, String>> CASES = [
        attack("group_currencies_pk", "group_currencies", insertInto("group_currencies",
                A_CURRENCY + [currency: "'USD'"])),
        attack("group_currencies_group_fk", "group_currencies", insertInto("group_currencies",
                A_CURRENCY + [group_id: literal(ABSENT)])),
        attack("group_currencies_author_person_fk", "group_currencies", insertInto("group_currencies",
                A_CURRENCY + [group_id: literal(OTHER_GROUP), created_by: literal(SEEDER)])),
        attack("group_currencies_author_is_person", "group_currencies", insertInto("group_currencies",
                A_CURRENCY + [group_id: literal(OTHER_GROUP), created_by: literal(WORKFLOW_RUNNER),
                              created_by_kind: "'system'"])),
        *editorAttacks("group_currencies"),
        attack("group_currencies_currency_shape", "group_currencies", insertInto("group_currencies",
                A_CURRENCY + [group_id: literal(OTHER_GROUP), currency: "'eur'"])),
    ]

    private V13Cases() {}
}
