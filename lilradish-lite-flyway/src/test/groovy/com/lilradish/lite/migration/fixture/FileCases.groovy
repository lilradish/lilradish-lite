package com.lilradish.lite.migration.fixture

import static com.lilradish.lite.migration.fixture.SchemaKeys.*
import static com.lilradish.lite.migration.fixture.SchemaRows.*
import static com.lilradish.lite.migration.fixture.StepAndDeclarationCases.A_FIELD

/** Rows and constraint attacks for file declarations and run-held content. */
final class FileCases {

    static final String FILE = "0000001f-0000-4000-8000-000000000001"
    static final String OTHER_FILE = "0000001f-0000-4000-8000-000000000002"
    static final String FILE_FIELD = "0000000b-0000-4000-8000-0000000000f1"
    static final Map<String, String> ERRORED_FILE = [production_id: literal(CODE_FAILED),
            run_id: literal(CODE_RUN), file_id: literal(FILE), field_path: "'documents[0]'"]

    static final Map<String, String> METADATA = [file_id: literal(FILE), group_id: literal(GROUP),
                                                   original_name: "'Demo Sample.pdf'",
                                                   byte_size: "3"]

    // DB-SPECIFIC: the file-declaration fixture uses a PostgreSQL native field_kind enum literal.
    static final List<String> FIXTURE = [
        insertInto("declaration_fields", A_FIELD + [declaration_field_id: literal(FILE_FIELD), kind: "'file'",
                position: "4", name: "'documents'", file_types: "array['.pdf', '.png']",
                file_maximum_bytes: "5"]),
        // DB-SPECIFIC: decode(), data-modifying with and returning use PostgreSQL's syntax and semantics.
        """
        with contents as (
            insert into app.file_contents (file_id, content) values ('${FILE}', decode('010203', 'hex'))
            returning file_id
        ), metadata as (
            insert into app.stored_files (file_id, group_id, original_name, byte_size)
            select file_id, '${GROUP}', 'Demo Sample.pdf', 3 from contents
            returning file_id
        )
        insert into app.run_file_holders (run_id, file_id, group_id)
        select '${RUN}', file_id, '${GROUP}' from metadata
        """,
        "insert into app.run_file_holders (run_id, file_id, group_id)" +
                " values ('${CODE_RUN}', '${FILE}', '${GROUP}')",
        insertInto("errored_code_returned_files", ERRORED_FILE),
    ]

    // DB-SPECIFIC: the code-only constraint attack uses a PostgreSQL native step_producer enum literal.
    static final List<Map<String, String>> CASES = [
        attack("declaration_fields_file_types_only_for_file", "declaration_fields",
                setting("declaration_fields", "file_types = array['.pdf']")),
        attack("declaration_fields_file_types_valid", "declaration_fields",
                "update app.declaration_fields set file_types = array['.pdf', '.pdf']" +
                " where declaration_field_id = '${FILE_FIELD}'"),
        attack("declaration_fields_file_maximum_only_for_file", "declaration_fields",
                setting("declaration_fields", "file_maximum_bytes = 5")),
        attack("declaration_fields_file_maximum_positive", "declaration_fields",
                "update app.declaration_fields set file_maximum_bytes = 0" +
                " where declaration_field_id = '${FILE_FIELD}'"),

        attack("stored_files_pk", "stored_files", insertInto("stored_files", METADATA)),
        attack("stored_files_group_fk", "stored_files", insertInto("stored_files",
                METADATA + [file_id: literal(OTHER_FILE), group_id: literal(ABSENT)])),
        attack("stored_files_name_bounded", "stored_files", insertInto("stored_files",
                METADATA + [file_id: literal(OTHER_FILE), original_name: "''"])),
        // DB-SPECIFIC: E' uses PostgreSQL's syntax and semantics.
        attack("stored_files_name_one_line", "stored_files", insertInto("stored_files",
                METADATA + [file_id: literal(OTHER_FILE), original_name: "E'Line\\nBreak'"])),
        attack("stored_files_byte_size_nonnegative", "stored_files", insertInto("stored_files",
                METADATA + [file_id: literal(OTHER_FILE), byte_size: "-1"])),
        attack("stored_files_content_fk", "stored_files", insertInto("stored_files",
                METADATA + [file_id: literal(OTHER_FILE)])),

        // DB-SPECIFIC: decode() uses PostgreSQL's syntax and semantics.
        attack("file_contents_pk", "file_contents",
                insertInto("file_contents", [file_id: literal(FILE), content: "decode('00', 'hex')"])),
        // DB-SPECIFIC: decode() uses PostgreSQL's syntax and semantics.
        attack("file_contents_metadata_fk", "file_contents",
                insertInto("file_contents", [file_id: literal(OTHER_FILE), content: "decode('00', 'hex')"])),

        attack("run_file_holders_pk", "run_file_holders", insertInto("run_file_holders",
                [run_id: literal(RUN), file_id: literal(FILE), group_id: literal(GROUP)])),
        attack("run_file_holders_run_fk", "run_file_holders", insertInto("run_file_holders",
                [run_id: literal(ABSENT), file_id: literal(FILE), group_id: literal(GROUP)])),
        attack("run_file_holders_file_fk", "run_file_holders", insertInto("run_file_holders",
                [run_id: literal(RUN), file_id: literal(OTHER_FILE), group_id: literal(GROUP)])),

        attack("errored_code_returned_files_pk", "errored_code_returned_files",
                insertInto("errored_code_returned_files", ERRORED_FILE)),
        attack("errored_code_returned_files_code_only", "errored_code_returned_files",
                insertInto("errored_code_returned_files", ERRORED_FILE +
                        [field_path: "'documents[1]'", producer: "'model'"])),
        attack("errored_code_returned_files_path_bounded", "errored_code_returned_files",
                insertInto("errored_code_returned_files", ERRORED_FILE +
                        [field_path: "'" + ("a." * 1024) + "a'"])),
        attack("errored_code_returned_files_path_shape", "errored_code_returned_files",
                insertInto("errored_code_returned_files", ERRORED_FILE + [field_path: "'documents[01]'"])),
        attack("errored_code_returned_files_try_fk", "errored_code_returned_files",
                insertInto("errored_code_returned_files", ERRORED_FILE +
                        [production_id: literal(CODE_PRODUCTION), field_path: "'documents[1]'"])),
        attack("errored_code_returned_files_holder_fk", "errored_code_returned_files",
                insertInto("errored_code_returned_files", ERRORED_FILE +
                        [file_id: literal(OTHER_FILE), field_path: "'documents[1]'"])),
    ]

    // DB-SPECIFIC: decode() uses PostgreSQL's binary decoding semantics.
    static final List<Map<String, String>> NOT_NULL_CASES = [
        [table: "file_contents", column: "file_id",
         statement: insertInto("file_contents", [file_id: "null", content: "decode('00', 'hex')"])],
        [table: "file_contents", column: "content",
         statement: insertInto("file_contents", [file_id: literal(OTHER_FILE), content: "null"])],
    ]

    private FileCases() {}
}
