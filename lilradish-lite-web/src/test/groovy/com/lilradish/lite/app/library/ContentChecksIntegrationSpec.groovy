package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
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
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ContentChecks checks

    TransactionTemplate snapshot

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "rechecks_" + (++databasesMade))
        checks = new ContentChecks([new QuestionContentCheck(store.session, LibraryStore.FILES, new SearchWays([])), new WorkflowContentCheck(store.session, LibraryStore.MODELS,
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

    def "each stored kind is held to its content check"() {
        expect:
        snapshot.execute { checks.problemsIn(kind, groupId(GROUP), versionId(version)) }.problems()*.code() == codes

        where:
        kind                     | version          || codes
        EntryKind.QUESTION       | QUESTION_VERSION || [ContentProblemCode.INSTRUCTION_MISSING, ContentProblemCode.NOTHING_GIVEN_BACK]
        EntryKind.WORKFLOW       | WORKFLOW_VERSION || [ContentProblemCode.NO_STEPS]
        EntryKind.REFERENCE_LIST | LIST_VERSION     || [ContentProblemCode.NO_TERMS]
    }

    def "checking stored question content names a file with sixty-five types"() {
        given:
        def document = "0000000b-0000-4000-8000-000000000e01"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid. Native entry_kind, declaration_side, field_kind and field_standing enum literals.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                position, name, kind, file_types, file_maximum_bytes,
                                                must_be_given, standing, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', 1, 'document', 'file',
                        array['.pdf'], 1024, true, 'always', ?::uuid)
                """).params(document, QUESTION_VERSION, SEEDER).update()
        def before = snapshot.execute { checks.problemsIn(EntryKind.QUESTION, groupId(GROUP), versionId(QUESTION_VERSION)) }
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; generate_series(); || converts integer operands to text.
        store.session.sql("""
                update declaration_fields
                   set file_types = ARRAY(select '.x' || n from generate_series(0, 64) n)
                 where declaration_field_id = ?::uuid
                """).params(document).update()

        when:
        def after = snapshot.execute { checks.problemsIn(EntryKind.QUESTION, groupId(GROUP), versionId(QUESTION_VERSION)) }

        then:
        !before.problems()*.code().contains(ContentProblemCode.FILE_TYPES_TOO_MANY)
        after.problems().findAll { it.code() == ContentProblemCode.FILE_TYPES_TOO_MANY } ==
                [new ContentProblem(ContentProblemCode.FILE_TYPES_TOO_MANY,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(document)), null, null)]
    }

}
