package org.lilradish.lite.app.library

import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class ContentProblemsRefusalSpec extends Specification {

    static final ContentProblem PROBLEM = new ContentProblem(
            ContentProblemCode.INSTRUCTION_MISSING, new ContentPlace.Whole(ContentPart.INSTRUCTION), null)

    static final RetiredPinsRefusal.RetiredPin PIN = new RetiredPinsRefusal.RetiredPin(
            new EntryId(UUID.fromString("00000006-0000-4000-8000-000000000101")), EntryKind.REFERENCE_LIST,
            new EntryName("Categories"),
            new RetiredPinsRefusal.NumberedVersion(
                    new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000101")), 1),
            null)

    /** The places travel beside the sentence and never in it, so nothing a draft holds reaches a log line. */
    def "a refusal for what a version holds carries every place and every pin beside the one sentence written for it"() {
        given:
        def problems = [PROBLEM]
        def pins = [PIN]

        when:
        def refused = new ContentProblemsRefusal(problems, pins)
        problems.clear()
        pins.clear()

        then:
        refused.errorCode() == RefusalCode.VERSION_CONTENT_DOES_NOT_HOLD
        refused.message == "Some of what this version holds does not hold yet, and each place is named."
        refused.problems() == [PROBLEM]
        refused.pins() == [PIN]
    }

    def "a refusal naming no problem is no refusal, and is refused"() {
        when:
        new ContentProblemsRefusal(problems, pins)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        problems  | pins || expectedException        | expectedMessage
        []        | []   || IllegalArgumentException | "ContentProblemsRefusal names at least one problem"
        null      | []   || NullPointerException     | "ContentProblemsRefusal problems must not be null"
        [PROBLEM] | null || NullPointerException     | "ContentProblemsRefusal pins must not be null"
    }
}
