package com.lilradish.lite.app.library

import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class ContentChecksSpec extends Specification {

    static final GroupId GROUP = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000801"))

    static final EntryVersionId VERSION = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000801"))

    static final ContentProblem PROBLEM = new ContentProblem(
            ContentProblemCode.NOTHING_GIVEN_BACK, new ContentPlace.Whole(ContentPart.GIVES), null, null)

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
        ContentProblemReport.Result problemsIn(GroupId group, EntryVersionId version) {
            asked.add([group, version])
            new ContentProblemReport.Result(answer, answer.size())
        }
    }

    def "a kind with no check, or with two, refuses the start"() {
        when:
        new ContentChecks(kinds.collect { new Answering(it) })

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
        def checks = new ContentChecks([workflow, question, list])

        when:
        def found = checks.problemsIn(EntryKind.QUESTION, GROUP, VERSION)

        then:
        found.problems() == [PROBLEM]
        found.total() == 1
        question.asked == [[GROUP, VERSION]]
        workflow.asked.isEmpty()
        list.asked.isEmpty()
    }
}
