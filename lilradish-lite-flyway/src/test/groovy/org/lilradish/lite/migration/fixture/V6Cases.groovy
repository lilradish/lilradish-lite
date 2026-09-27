package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaRows.*

/** V6__people.sql's rules: the rows they are proved beside, and a case attacking each. */
final class V6Cases {

    static final List<String> FIXTURE = [
        "insert into app.people (user_id, display_name) values ('000002', 'Ada Lovelace')",
    ]

    static final List<Map<String, String>> CASES = [
        attack("people_pk", "people",
                "insert into app.people (user_id, display_name) values ('000002', 'Grace Hopper')"),
        attack("people_user_id_visible", "people",
                "insert into app.people (user_id, display_name) values ('00' || chr(9) || '99', 'Grace Hopper')"),
        attack("people_user_id_bounded", "people",
                "insert into app.people (user_id, display_name) values (repeat('9', 257), 'Grace Hopper')"),
        attack("people_display_name_visible", "people",
                "insert into app.people (user_id, display_name) values ('000099', 'Grace' || chr(10) || 'Hopper')"),
        attack("people_display_name_bounded", "people",
                "insert into app.people (user_id, display_name) values ('000099', repeat('a', 257))"),
    ]

    private V6Cases() {}
}
