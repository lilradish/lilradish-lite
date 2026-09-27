package org.lilradish.lite.app.library

import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryVersionId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

class ContentChecksSpec extends Specification {

    static final GroupId GROUP = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000801"))

    static final EntryVersionId VERSION = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000801"))

    static final ContentProblem PROBLEM = new ContentProblem(
            ContentProblemCode.NOTHING_GIVEN_BACK, new ContentPlace.Whole(ContentPart.GIVES), null)

    /** Each kind's check, answering with what it was told to and remembering each group and version it was asked of. */
    static final class Answering implements ContentCheck {

        final EntryKind kind

        final List<ContentProblem> answer

        final List<List<Object>> asked = []

        Answering(EntryKind kind, List<ContentProblem> answer = []) {
            this.kind = kind
            this.answer = answer
        }

        @Override
        EntryKind kind() {
            kind
        }

        @Override
        List<ContentProblem> problemsIn(GroupId group, EntryVersionId version) {
            asked.add([group, version])
            answer
        }
    }

    JdbcClient database = Mock()

    def cleanup() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null)
    }

    def "a kind with no check, or with two, refuses the start"() {
        when:
        new ContentChecks(database, kinds.collect { new Answering(it) })

        then:
        def refused = thrown(IllegalStateException)
        refused.message == expectedMessage

        where:
        kinds                                                        || expectedMessage
        [EntryKind.WORKFLOW, EntryKind.QUESTION]                     || "No content check is declared for REFERENCE_LIST"
        [EntryKind.WORKFLOW, EntryKind.QUESTION, EntryKind.QUESTION] || "Two content checks are declared for QUESTION"
        []                                                           || "No content check is declared for WORKFLOW"
    }

    /** Only the kind named is asked, and of the group and version named: another kind's check never reads it. */
    def "a version is held to its own kind's check alone"() {
        given:
        def question = new Answering(EntryKind.QUESTION, [PROBLEM])
        def workflow = new Answering(EntryKind.WORKFLOW)
        def list = new Answering(EntryKind.REFERENCE_LIST)
        def checks = new ContentChecks(database, [workflow, question, list])

        when:
        def found = checks.problemsIn(EntryKind.QUESTION, GROUP, VERSION)

        then:
        found == [PROBLEM]
        question.asked == [[GROUP, VERSION]]
        workflow.asked.isEmpty()
        list.asked.isEmpty()
        0 * database._
    }

    /**
     * Its reads are several: outside a transaction each is its own moment, and inside one begun at the
     * default or at read committed each statement is, so neither is asked anything.
     */
    def "rechecking outside a transaction reading one moment is refused before the store or any check is asked"() {
        given:
        def question = new Answering(EntryKind.QUESTION, [PROBLEM])
        def checks = new ContentChecks(database, [question, new Answering(EntryKind.WORKFLOW),
                                                  new Answering(EntryKind.REFERENCE_LIST)])
        TransactionSynchronizationManager.setActualTransactionActive(active)
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation)

        when:
        checks.recheck(GROUP, VERSION)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "ContentChecks was asked to recheck outside a transaction reading one moment"
        question.asked.isEmpty()
        0 * database._

        where:
        active | isolation
        false  | null
        false  | TransactionDefinition.ISOLATION_REPEATABLE_READ
        true   | null
        true   | TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    def "a recheck names the group and the version"() {
        given:
        def checks = new ContentChecks(database, EntryKind.values().collect { new Answering(it) })

        when:
        checks.recheck(group, version)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        group | version || expectedMessage
        null  | VERSION || "ContentChecks group must not be null"
        GROUP | null    || "ContentChecks version must not be null"
    }
}
