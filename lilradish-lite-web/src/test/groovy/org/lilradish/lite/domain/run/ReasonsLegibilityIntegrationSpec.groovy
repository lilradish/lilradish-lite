package org.lilradish.lite.domain.run

import java.nio.file.Files
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.testutil.ReaderVocabulary
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * Runs the page's reason table through what the server refuses a reason with, so a row the two decide differently
 * fails here.
 */
class ReasonsLegibilityIntegrationSpec extends Specification {

    static final List<Map<String, Object>> REASONS = JsonMapper.builder().build()
            .readValue(Files.readString(ReaderVocabulary.FRONTEND.resolve("lib/text/legibility.cases.json")), Map)
            .reason as List<Map<String, Object>>

    /** What one sent is refused with, by the name the table gives why, which is the refusal the page names it by. */
    static final Map<String, RefusalCode> CODE_OF = [
            (null): null,
            missing: RefusalCode.REASON_MISSING,
            unusable: RefusalCode.REASON_UNUSABLE,
            crlf: RefusalCode.PROSE_LINE_BREAK_CRLF,
            direction_control: RefusalCode.PROSE_DIRECTION_CONTROL,
            tag: RefusalCode.PROSE_TAG_CHARACTER]

    static String typedIn(Map<String, Object> each) {
        (each.typed as String) * ((each.times ?: 1) as int)
    }

    def "the reason table holds a row of every outcome, an empty table agreeing with anything"() {
        expect:
        REASONS*.refused as Set == CODE_OF.keySet()
    }

    def "a reason sent is refused with exactly the code the table names it by, and taken where the table names nothing"() {
        expect:
        Reasons.judged(typedIn(each)) == CODE_OF[each.refused]

        where:
        each << REASONS
    }
}
