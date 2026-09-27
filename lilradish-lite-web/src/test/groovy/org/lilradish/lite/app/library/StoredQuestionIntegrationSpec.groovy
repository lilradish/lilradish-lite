package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A question version's content as a real server running the real baseline holds it, rows written round the
 * application as a migration or a hand might write them, and read as nothing a type refuses.
 */
class StoredQuestionIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000c01"

    static final String QUESTION = "00000006-0000-4000-8000-000000000c01"

    static final String VERSION = "00000007-0000-4000-8000-000000000c01"

    static final String DETAILS = "0000000b-0000-4000-8000-000000000c01"

    static final String PRODUCT = "0000000b-0000-4000-8000-000000000c02"

    static final String SKU = "0000000b-0000-4000-8000-000000000c03"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "stored_" + (++databasesMade))
        store.group(GROUP, "SUPPORT", "Customer support")
        store.entry(QUESTION, GROUP, "question", "Summarise a complaint")
        store.seeded(VERSION, QUESTION, 1)
        store.content(VERSION, "question", "Say what it names.")
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                parent_field_id, position, name, kind, text_limit, must_be_given,
                                                standing, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', null, 1, 'details', 'fields', null, true, 'never',
                        ?::uuid),
                       (?::uuid, ?::uuid, 'question', 'gives', ?::uuid, 1, 'product', 'fields', null, true, null,
                        ?::uuid),
                       (?::uuid, ?::uuid, 'question', 'gives', ?::uuid, 1, 'sku', 'text', 32, false, null, ?::uuid)
                """).params(DETAILS, VERSION, SEEDER, PRODUCT, VERSION, DETAILS, SEEDER, SKU, VERSION, PRODUCT, SEEDER)
                .update()
    }

    def "a field held inside others is found by where it sits, by its key"() {
        when:
        def half = StoredQuestion.read(store.session, versionId(VERSION)).get().gives()

        then:
        half.keyAt([0, 0, 0]) == UUID.fromString(SKU)
        half.keyAt([0, 0]) == UUID.fromString(PRODUCT)
        half.keyAt([0]) == UUID.fromString(DETAILS)
        half.declaration().fields()*.name() == [new FieldName("details")]
    }

    /**
     * The store holds a parent to the same version, half and kind, and to not being its own, and nothing more:
     * two fields each holding the other is a cycle no first-level field reaches, and reading one ends.
     */
    def "fields holding each other round a cycle fail the read rather than being walked or left out"() {
        given:
        store.session.sql("update declaration_fields set standing = null where declaration_field_id = ?::uuid")
                .param(DETAILS).update()
        store.session.sql("""
                update declaration_fields set parent_field_id = ?::uuid, position = 2 where declaration_field_id = ?::uuid
                """).params(PRODUCT, DETAILS).update()

        when:
        StoredQuestion.read(store.session, versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version ${VERSION} holds fields no first-level field reaches" as String
    }

    /** Held by the store, which refuses only controls; a line separator is refused here as every line refuses it. */
    def "a stored value its type refuses fails the read rather than being shown or left out"() {
        given:
        store.session.sql(change).update()

        when:
        StoredQuestion.read(store.session, versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version ${VERSION} holds ${what} this system will not read" as String
        failed.cause instanceof IllegalArgumentException

        where:
        change                                                                                        || what
        "update declaration_fields set label = 'Two' || chr(8232) || 'lines' where name = 'sku'"     || "a field"
        "update question_versions set instruction = 'Two' || chr(8232) || 'lines'"                    || "an instruction"
    }

    def "a version holding no question content is read as none"() {
        expect:
        StoredQuestion.read(store.session, versionId("00000007-0000-4000-8000-000000000c09")).isEmpty()
    }
}
