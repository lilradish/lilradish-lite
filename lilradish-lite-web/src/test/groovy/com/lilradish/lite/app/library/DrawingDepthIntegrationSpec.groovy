package com.lilradish.lite.app.library

import com.lilradish.lite.testutil.ReaderVocabulary
import java.nio.file.Files
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * Runs the reader's depth table through the server's estimates, so a drawing the two count differently fails here.
 */
class DrawingDepthIntegrationSpec extends Specification {

    static final List<Map<String, Object>> DEPTHS = JsonMapper.builder().build()
            .readValue(Files.readString(ReaderVocabulary.FRONTEND.resolve("lib/text/drawingDepth.cases.json")), Map)
            .depth as List<Map<String, Object>>

    static String typedIn(Map<String, Object> each) {
        (each.typed as String) * ((each.times ?: 1) as int)
    }

    def "the depth table holds both drawings taken and drawings refused, an empty table agreeing with anything"() {
        expect:
        DEPTHS*.accepted as Set == [true, false] as Set
    }

    def "a drawing's lists and quotes are estimated exactly as deep as the table counts them"() {
        expect:
        DrawingDepth.of(typedIn(each)) == each.depth

        where:
        each << DEPTHS
    }

    def "a drawing's emphasis is estimated exactly as deep as the table counts it"() {
        expect:
        DrawingDepth.emphasisOf(typedIn(each)) == each.emphasis

        where:
        each << DEPTHS
    }

    def "a drawing is too deep exactly where the table refuses it"() {
        expect:
        !DrawingDepth.tooDeep(typedIn(each)) == each.accepted

        where:
        each << DEPTHS
    }
}
