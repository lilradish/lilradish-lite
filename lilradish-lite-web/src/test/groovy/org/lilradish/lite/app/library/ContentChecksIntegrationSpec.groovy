package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification


class ContentChecksIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000e01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000e02"

    static final String QUESTION = "00000006-0000-4000-8000-000000000e01"

    static final String QUESTION_VERSION = "00000007-0000-4000-8000-000000000e01"

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000e02"

    static final String WORKFLOW_VERSION = "00000007-0000-4000-8000-000000000e02"

    static final String LIST = "00000006-0000-4000-8000-000000000e03"

    static final String LIST_VERSION = "00000007-0000-4000-8000-000000000e03"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ContentChecks checks

    TransactionTemplate snapshot

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "rechecks_" + (++databasesMade))
        checks = new ContentChecks(store.session, [new QuestionContentCheck(store.session, LibraryStore.FILES), new WorkflowContentCheck(store.session, LibraryStore.MODELS,
                                                   new ReleasedCodeSteps(LibraryStore.CODE_STEPS), LibraryStore.FILES, new SearchWays([])),
                                                   new ReferenceListContentCheck(store.session)])
        snapshot = new TransactionTemplate(store.transactionManager())
        snapshot.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.entry(QUESTION, GROUP, "question", "Summarise")
        store.seeded(QUESTION_VERSION, QUESTION, 1)
        store.content(QUESTION_VERSION, "question")
        store.entry(WORKFLOW, GROUP, "workflow", "Handle")
        store.seeded(WORKFLOW_VERSION, WORKFLOW, 1)
        store.content(WORKFLOW_VERSION, "workflow")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(LIST_VERSION, LIST, 1)
        store.content(LIST_VERSION, "reference_list")
    }

    /** The kind is the store's to say, so no caller can hold a version to another kind's check. */
    def "a version of the group's is held to its own kind's check, the kind read with it"() {
        expect:
        snapshot.execute { checks.recheck(groupId(GROUP), versionId(version)) }.problems()*.code() == codes

        where:
        version          || codes
        QUESTION_VERSION || [ContentProblemCode.INSTRUCTION_MISSING, ContentProblemCode.NOTHING_GIVEN_BACK]
        WORKFLOW_VERSION || [ContentProblemCode.NO_STEPS]
        LIST_VERSION     || [ContentProblemCode.NO_TERMS]
    }

    def "recheck names an old stored question file with sixty-five types"() {
        given:
        def document = "0000000b-0000-4000-8000-000000000e01"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                position, name, kind, file_types, file_maximum_bytes,
                                                must_be_given, standing, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', 1, 'document', 'file',
                        array['application/pdf'], 1024, true, 'always', ?::uuid)
                """).params(document, QUESTION_VERSION, SEEDER).update()
        def before = snapshot.execute { checks.recheck(groupId(GROUP), versionId(QUESTION_VERSION)) }
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; generate_series().
        store.session.sql("""
                update declaration_fields
                   set file_types = ARRAY(select 'application/x' || n from generate_series(0, 64) n)
                 where declaration_field_id = ?::uuid
                """).params(document).update()

        when:
        def after = snapshot.execute { checks.recheck(groupId(GROUP), versionId(QUESTION_VERSION)) }

        then:
        !before.problems()*.code().contains(ContentProblemCode.FILE_TYPES_TOO_MANY)
        after.problems().findAll { it.code() == ContentProblemCode.FILE_TYPES_TOO_MANY } ==
                [new ContentProblem(ContentProblemCode.FILE_TYPES_TOO_MANY,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(document)), null, null)]
    }

    def "a version another group holds, or nobody does, is a store gone wrong for whoever names it"() {
        when:
        snapshot.execute { checks.recheck(groupId(group), versionId(version)) }

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Group ${group} holds no version ${version}" as String

        where:
        group       | version
        OTHER_GROUP | QUESTION_VERSION
        GROUP       | "00000007-0000-4000-8000-000000000e09"
    }
}
