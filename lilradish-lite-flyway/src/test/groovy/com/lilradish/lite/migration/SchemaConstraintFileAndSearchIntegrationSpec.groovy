package com.lilradish.lite.migration

import static com.lilradish.lite.migration.fixture.FileCases.*
import static com.lilradish.lite.migration.fixture.ProductionAndCallCases.*
import static com.lilradish.lite.migration.fixture.RunCases.*
import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*
import static com.lilradish.lite.migration.fixture.StepAndDeclarationCases.*

import com.lilradish.lite.migration.fixture.FileCases
import com.lilradish.lite.migration.fixture.SchemaConstraintSpecification
import org.postgresql.util.PSQLException

/** What a stored file, its content, a search and the prices of a search tool are held to. */
class SchemaConstraintFileAndSearchIntegrationSpec extends SchemaConstraintSpecification {

    static final List<Map<String, String>> SEARCH_NOT_NULL_CASES = [
        [table: "model_call_tool_uses", column: "model_call_id",
         statement: insertInto("model_call_tool_uses", A_TOOL_USE + [model_call_id: "null"])],
        [table: "model_call_tool_uses", column: "run_id",
         statement: insertInto("model_call_tool_uses", A_TOOL_USE + [run_id: "null"])],
        [table: "model_call_tool_uses", column: "model_call_purpose",
         statement: insertInto("model_call_tool_uses", A_TOOL_USE + [model_call_purpose: "null"])],
        [table: "model_call_tool_uses", column: "tool",
         statement: insertInto("model_call_tool_uses", A_TOOL_USE + [tool: "null"])],
        [table: "model_call_tool_uses", column: "most_uses",
         statement: insertInto("model_call_tool_uses", A_TOOL_USE + [most_uses: "null"])],
        [table: "model_call_tool_uses", column: "searches_cut",
         statement: insertInto("model_call_tool_uses", A_TOOL_USE + [searches_cut: "null"])],
        [table: "model_call_tool_uses", column: "searches_not_as_it_came",
         statement: insertInto("model_call_tool_uses", A_TOOL_USE + [searches_not_as_it_came: "null"])],
        [table: "model_call_tool_uses", column: "searches_left_out",
         statement: insertInto("model_call_tool_uses", A_TOOL_USE + [searches_left_out: "null"])],
        [table: "model_call_tool_prices", column: "model_call_id",
         statement: insertInto("model_call_tool_prices", A_TOOL_PRICE + [model_call_id: "null"])],
        [table: "model_call_tool_prices", column: "currency",
         statement: insertInto("model_call_tool_prices", A_TOOL_PRICE + [currency: "null"])],
        [table: "model_call_tool_prices", column: "per_use",
         statement: insertInto("model_call_tool_prices", A_TOOL_PRICE + [per_use: "null"])],
    ]

    def "a file name refuses representable control character #codePoint without changing metadata"() {
        given:
        def before = contentsOf("stored_files")
        def oldName = queryForStrings("select original_name from app.stored_files where file_id = '${FILE}'")
        assert oldName.size() == 1

        when:
        // DB-SPECIFIC: chr() uses PostgreSQL's character-code semantics.
        execute("update app.stored_files set original_name = 'Demo Sample' || chr(${codePoint})" +
                " where file_id = '${FILE}'")

        then:
        def refused = thrown(PSQLException)
        refused.getSQLState() == "23514"
        refused.serverErrorMessage.schema == "app"
        refused.serverErrorMessage.table == "stored_files"
        refused.serverErrorMessage.constraint == "stored_files_name_one_line"
        contentsOf("stored_files") == before

        where:
        codePoint << ((1..31) + (127..159))
    }

    def "a visible file name is kept whole at its permitted length"() {
        given:
        def before = contentsOf("stored_files")

        when:
        def kept = readInside("update app.stored_files set original_name = '${name}' where file_id = '${FILE}'",
                "select original_name from app.stored_files where file_id = '${FILE}'")

        then:
        kept == name
        contentsOf("stored_files") == before

        where:
        name << ["Demo Sample É 山田", "Demo Sample " + ("a" * 243)]
    }

    def "a file declaration keeps canonical extension #extension and rolls it back"() {
        given:
        def before = contentsOf("declaration_fields")
        assert queryForStrings("select declaration_field_id from app.declaration_fields " +
                "where declaration_field_id = '${FILE_FIELD}'").size() == 1

        when:
        // DB-SPECIFIC: PostgreSQL array_to_json() and ::text cast.
        def kept = readInside("update app.declaration_fields set file_types = array['${extension}'] " +
                "where declaration_field_id = '${FILE_FIELD}'",
                "select array_to_json(file_types)::text from app.declaration_fields " +
                "where declaration_field_id = '${FILE_FIELD}'")

        then:
        kept == '["' + extension + '"]'
        contentsOf("declaration_fields") == before
        session.autoCommit

        where:
        extension << [".a", ".tar.gz", ".a0.b1", "." + ("a" * 32), "." + ("a" * 30) + ".b"]
    }

    def "a file declaration refuses noncanonical extension #extension without changing the row"() {
        given:
        def before = contentsOf("declaration_fields")
        assert queryForStrings("select declaration_field_id from app.declaration_fields " +
                "where declaration_field_id = '${FILE_FIELD}'").size() == 1

        when:
        execute("update app.declaration_fields set file_types = array['${extension}'] " +
                "where declaration_field_id = '${FILE_FIELD}'")

        then:
        def refused = thrown(PSQLException)
        refused.getSQLState() == "23514"
        refused.serverErrorMessage.constraint == "declaration_fields_file_types_valid"
        contentsOf("declaration_fields") == before

        where:
        extension << ["pdf", ".", ".PDF", ".tar..gz", ".tar.", ".péf", ".a/b",
                      "." + ("a" * 33), "." + ("a" * 31) + ".b"]
    }

    def "every required file content column has one actual null rejection case"() {
        when:
        // DB-SPECIFIC: pg_attribute and regclass use PostgreSQL's catalogue and relation lookup.
        def required = queryForStrings("""
            select attname
              from pg_attribute
             where attrelid = 'app.file_contents'::regclass
               and attnum > 0 and not attisdropped and attnotnull
            """)
        def registered = FileCases.NOT_NULL_CASES.collect { it.column }

        then:
        !required.isEmpty()
        !registered.isEmpty()
        registered.toSet() == required
        registered.size() == required.size()
        registered.toSet().size() == registered.size()
        FileCases.NOT_NULL_CASES.every { it.table == "file_contents" }
    }

    def "file content refuses null in #column without storing any part of the row"() {
        given:
        def beforeContents = contentsOf(table)
        def beforeMetadata = contentsOf("stored_files")

        when:
        execute(statement)

        then:
        def refused = thrown(PSQLException)
        refused.getSQLState() == "23502"
        refused.serverErrorMessage.schema == "app"
        refused.serverErrorMessage.table == table
        refused.serverErrorMessage.column == column
        contentsOf(table) == beforeContents
        contentsOf("stored_files") == beforeMetadata

        where:
        [table, column, statement] << FileCases.NOT_NULL_CASES.collect { [it.table, it.column, it.statement] }
    }

    def "empty file content is kept with zero size and its holder, then rolled back"() {
        given:
        def before = ["file_contents", "stored_files", "run_file_holders"].collectEntries { [(it): contentsOf(it)] }

        when:
        // DB-SPECIFIC: data-modifying WITH, returning and decode() use PostgreSQL's syntax and semantics.
        def kept = readInside("""
            with contents as (
                insert into app.file_contents (file_id, content) values ('${OTHER_FILE}', decode('', 'hex'))
                returning file_id
            ), metadata as (
                insert into app.stored_files (file_id, group_id, original_name, byte_size)
                select file_id, '${GROUP}', 'Demo Sample', 0 from contents
                returning file_id
            )
            insert into app.run_file_holders (run_id, file_id, group_id)
            select '${RUN}', file_id, '${GROUP}' from metadata;
            set constraints all immediate
            """,
                // DB-SPECIFIC: octet_length(bytea) and ::text use PostgreSQL's binary and cast semantics.
                """select octet_length(content)::text || '|' || byte_size::text || '|' || original_name
                     from app.file_contents join app.stored_files using (file_id)
                    where file_id = '${OTHER_FILE}'
                      and exists (select 1 from app.run_file_holders
                                   where run_id = '${RUN}' and file_id = '${OTHER_FILE}')""")

        then:
        kept == "0|0|Demo Sample"
        before.every { table, contents -> contentsOf(table) == contents }
        session.autoCommit
    }

    def "every required search accounting and price column has one actual null insert rejection case"() {
        when:
        // DB-SPECIFIC: pg_attribute, pg_class and ::regnamespace use PostgreSQL's catalogue and namespace lookup.
        def required = queryForStrings("""
            select c.relname || '.' || a.attname
              from pg_attribute a join pg_class c on c.oid = a.attrelid
             where c.relnamespace = 'app'::regnamespace
               and c.relname in ('model_call_tool_uses', 'model_call_tool_prices')
               and a.attnum > 0 and not a.attisdropped and a.attnotnull
            """)
        def registered = SEARCH_NOT_NULL_CASES.collect { "${it.table}.${it.column}" }

        then:
        !required.isEmpty()
        !registered.isEmpty()
        registered.toSet() == required
        registered.size() == required.size()
        registered.toSet().size() == registered.size()
        SEARCH_NOT_NULL_CASES.collect { it.table }.toSet() ==
                ["model_call_tool_uses", "model_call_tool_prices"].toSet()
    }

    def "search accounting or price refuses null in #table.#column on insert without changing the table"() {
        given:
        def before = contentsOf(table)

        when:
        execute(statement)

        then:
        def refused = thrown(PSQLException)
        refused.getSQLState() == "23502"
        refused.serverErrorMessage.schema == "app"
        refused.serverErrorMessage.table == table
        refused.serverErrorMessage.column == column
        contentsOf(table) == before

        where:
        [table, column, statement] << SEARCH_NOT_NULL_CASES.collect { [it.table, it.column, it.statement] }
    }

    def "search accounting or price refuses null in #column on update without changing the table"() {
        given:
        def before = contentsOf(table)
        assert queryForStrings("select model_call_id from app.${table} where ${row}").size() == 1

        when:
        execute("update app.${table} set ${column} = null where ${row}")

        then:
        def refused = thrown(PSQLException)
        refused.getSQLState() == "23502"
        refused.serverErrorMessage.schema == "app"
        refused.serverErrorMessage.table == table
        refused.serverErrorMessage.column == column
        contentsOf(table) == before

        where:
        table                    | column      | row
        "model_call_tool_uses"    | "most_uses" | "model_call_id = '${SEARCH_CALL}'"
        "model_call_tool_prices"  | "per_use"   | "model_call_id = '${SEARCH_CALL}' and currency = 'USD'"
    }

    def "search accounting and price keep a nonnull amount on #operation and roll it back"() {
        given:
        def before = contentsOf(table)

        when:
        def kept = readInside(statement + "; set constraints all immediate", query)

        then:
        kept == expected
        contentsOf(table) == before
        session.autoCommit

        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        where:
        table                    | operation | statement                                                                                           | query                                                                                                                             || expected
        "model_call_tool_uses"    | "insert"  | WITH_PENDING_TOOL_USE                                                                               | "select most_uses::text from app.model_call_tool_uses where model_call_id = '${SPARE_CALL}'"                                         || "5"
        "model_call_tool_uses"    | "update"  | "update app.model_call_tool_uses set most_uses = 7 where model_call_id = '${SEARCH_CALL}'"            | "select most_uses::text from app.model_call_tool_uses where model_call_id = '${SEARCH_CALL}'"                                        || "7"
        "model_call_tool_prices"  | "insert"  | insertInto("model_call_tool_prices", A_TOOL_PRICE)                                                    | "select per_use::text from app.model_call_tool_prices where model_call_id = '${SEARCH_CALL}' and currency = 'EUR'"                    || "0.0040"
        "model_call_tool_prices"  | "update"  | "update app.model_call_tool_prices set per_use = 0.0040 where model_call_id = '${SEARCH_CALL}' and currency = 'USD'" | "select per_use::text from app.model_call_tool_prices where model_call_id = '${SEARCH_CALL}' and currency = 'USD'"        || "0.0040"
    }

    def "a search refuses confidence-dependent standing in #column without changing any step"() {
        given:
        def before = contentsOf("workflow_steps")
        assert queryForStrings("select kind from app.workflow_steps where workflow_step_id = '${SEARCH_STEP}'") ==
                ["search"].toSet()

        when:
        execute("update app.workflow_steps set ${column} = 'above_confidence' where workflow_step_id = '${SEARCH_STEP}'")

        then:
        def refused = thrown(PSQLException)
        refused.getSQLState() == "23514"
        refused.serverErrorMessage.schema == "app"
        refused.serverErrorMessage.table == "workflow_steps"
        refused.serverErrorMessage.constraint == "workflow_steps_search_standing_without_confidence"
        contentsOf("workflow_steps") == before

        where:
        column << ["search_title_standing", "search_body_standing", "search_references_standing", "search_other_standing"]
    }

    def "a search keeps #standing standing in #column and rolls the change back"() {
        given:
        def before = contentsOf("workflow_steps")

        when:
        // DB-SPECIFIC: ::text uses PostgreSQL's cast syntax.
        def kept = readInside("update app.workflow_steps set ${column} = ${standing} where workflow_step_id = '${SEARCH_STEP}'",
                "select coalesce(${column}::text, 'null') from app.workflow_steps where workflow_step_id = '${SEARCH_STEP}'")

        then:
        kept == standing.replace("'", "")
        contentsOf("workflow_steps") == before
        session.autoCommit

        where:
        [column, standing] << [
            ["search_title_standing", "search_body_standing", "search_references_standing", "search_other_standing"],
            ["'always'", "'never'", "null"],
        ].combinations()
    }

    def "a nonsearch step refuses nonnull #column without changing any step"() {
        given:
        def before = contentsOf("workflow_steps")
        assert queryForStrings("select kind from app.workflow_steps where workflow_step_id = '${STEP}'") ==
                ["entry"].toSet()

        when:
        execute("update app.workflow_steps set ${column} = ${value} where workflow_step_id = '${STEP}'")

        then:
        def refused = thrown(PSQLException)
        refused.getSQLState() == "23514"
        refused.serverErrorMessage.schema == "app"
        refused.serverErrorMessage.table == "workflow_steps"
        refused.serverErrorMessage.constraint == "workflow_steps_search_only_for_search"
        contentsOf("workflow_steps") == before

        where:
        column                       | value
        "search_way"                 | "'web'"
        "search_title_standing"       | "'always'"
        "search_body_standing"        | "'always'"
        "search_references_standing"  | "'always'"
        "search_other_standing"       | "'always'"
    }

    def "search rows hold a question, its tool accounting, and the values read into it"() {
        when:
        def refusedBy = attempt(table, statement)

        then:
        refusedBy == null

        where:
        table                    | statement
        "run_step_holds"         | insertInto("run_step_holds", A_SEARCH_HOLD)
        "run_step_failures"      | insertInto("run_step_failures", A_SEARCH_FAILURE)
        "run_step_send_attempts" | insertInto("run_step_send_attempts", A_SEARCH_ATTEMPT)
        "run_step_send_attempts" | insertInto("run_step_send_attempts", A_SEARCH_ATTEMPT +
                [payload: "null", repeats_attempt_id: literal(SEARCH_ATTEMPT)])
        "model_call_tool_uses"  | WITH_PENDING_TOOL_USE
        "model_call_tool_prices"| insertInto("model_call_tool_prices", A_TOOL_PRICE)
        "model_call_searches"   | insertInto("model_call_searches", A_SEARCH_RECORD)
        "production_values"    | insertInto("production_values", A_SEARCH_VALUE)
        "production_inputs"    | insertInto("production_inputs", A_SEARCH_USE + FROM_NO_VALUE +
                [binding_id: literal(SEARCH_INPUT_BINDING)])

    }

    def "tool prices keep their written scale and refuse nonfinite or negative amounts"() {
        when:
        def refusedBy = attempt("model_call_tool_prices", insertInto("model_call_tool_prices",
                A_TOOL_PRICE + [per_use: amount]))

        then:
        refusedBy == refusal
        // DB-SPECIFIC: ::, 'Infinity', numeric, '-Infinity' and 'NaN' use PostgreSQL's syntax and semantics.

        where:
        amount               || refusal
        "0"                  || null
        "0.0040"             || null
        "-0.01"              || "model_call_tool_prices_per_use_not_negative_and_finite"
        "'Infinity'::numeric" || "model_call_tool_prices_per_use_not_negative_and_finite"
        "'-Infinity'::numeric"|| "model_call_tool_prices_per_use_not_negative_and_finite"
        "'NaN'::numeric"      || "model_call_tool_prices_per_use_not_negative_and_finite"
    }

    def "tool price keeps the decimal places given at send time"() {
        expect:
        readInside(insertInto("model_call_tool_prices", A_TOOL_PRICE),
                // DB-SPECIFIC: :: and text use PostgreSQL's syntax and semantics.
                "select per_use::text from app.model_call_tool_prices" +
                        " where model_call_id = '${SEARCH_CALL}' and currency = 'EUR'") == "0.0040"
    }
}
