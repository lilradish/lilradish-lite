package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Rechecking a stored version of a group's, on a real server running the real baseline, as Soundness would. */
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
        checks = new ContentChecks(store.session, [new QuestionContentCheck(store.session), new WorkflowContentCheck(store.session, LibraryStore.MODELS,
                                                   new ReleasedCodeSteps(LibraryStore.CODE_STEPS)),
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
        snapshot.execute { checks.recheck(groupId(GROUP), versionId(version)) }*.code() == codes

        where:
        version          || codes
        QUESTION_VERSION || [ContentProblemCode.INSTRUCTION_MISSING, ContentProblemCode.NOTHING_GIVEN_BACK]
        WORKFLOW_VERSION || [ContentProblemCode.NO_STEPS]
        LIST_VERSION     || [ContentProblemCode.NO_TERMS]
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
