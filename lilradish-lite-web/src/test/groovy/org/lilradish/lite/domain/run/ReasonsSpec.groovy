package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.failure.RefusalCode.PROSE_DIRECTION_CONTROL
import static org.lilradish.lite.domain.failure.RefusalCode.PROSE_LINE_BREAK_CRLF
import static org.lilradish.lite.domain.failure.RefusalCode.PROSE_TAG_CHARACTER
import static org.lilradish.lite.domain.failure.RefusalCode.REASON_MISSING
import static org.lilradish.lite.domain.failure.RefusalCode.REASON_UNUSABLE
import static org.lilradish.lite.domain.text.ProseRefusal.CRLF
import static org.lilradish.lite.domain.text.ProseRefusal.DIRECTION_CONTROL
import static org.lilradish.lite.domain.text.ProseRefusal.TAG
import static org.lilradish.lite.domain.text.ProseRefusal.UNUSABLE

import spock.lang.Specification

class ReasonsSpec extends Specification {

    static final String TAB = points(0x09)

    static final String LINE_FEED = points(0x0A)

    static final String CARRIAGE_RETURN = points(0x0D)

    static final String BELL = points(0x07)

    /** A line separator counts as showing, so a reason of it alone is refused for what it holds rather than as none. */
    def "a reason is given only where it holds something that shows in prose"() {
        expect:
        Reasons.given(said(named)) == given

        where:
        named                           || given
        "absent"                        || false
        "empty"                         || false
        "spaces"                        || false
        "no-break spaces"               || false
        "a tab and a line feed"         || false
        "a zero-width space"            || false
        "a direction control alone"     || false
        "a letter"                      || true
        "a letter between spaces"       || true
        "a line separator"              || true
        "a carriage return alone"       || true
    }

    def "a reason is refused for the first of a CRLF, what conceals, and what prose cannot hold, and taken otherwise"() {
        expect:
        Reasons.refusalOf(said(named)) == refusal

        where:
        named                                        || refusal
        "a sentence"                                 || null
        "lines with a tab"                           || null
        "2048 letters"                               || null
        "2048 characters outside the basic plane"    || null
        "a CRLF"                                     || CRLF
        "a CRLF and a direction control"             || CRLF
        "a direction control"                        || DIRECTION_CONTROL
        "a bell, then a direction control"           || DIRECTION_CONTROL
        "a tag character"                            || TAG
        "2049 letters"                               || UNUSABLE
        "2049 characters outside the basic plane"    || UNUSABLE
        "a bell"                                     || UNUSABLE
        "a carriage return"                          || UNUSABLE
        "a line separator"                           || UNUSABLE
        "spaces"                                     || UNUSABLE
        "empty"                                      || UNUSABLE
    }

    def "no reason is judged without one"() {
        when:
        Reasons.refusalOf(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Reasons said must not be null"
    }

    /** What a page names apart can be all that is there, so it is named before one showing nothing is called none. */
    def "a reason sent is refused for a CRLF, then what conceals, then for being none, then for what prose cannot hold"() {
        expect:
        Reasons.judged(said(named)) == refusal

        where:
        named                                        || refusal
        "a sentence"                                 || null
        "lines with a tab"                           || null
        "2048 letters"                               || null
        "a letter between spaces"                    || null
        "a CRLF alone"                               || PROSE_LINE_BREAK_CRLF
        "a CRLF and a direction control"             || PROSE_LINE_BREAK_CRLF
        "a direction control alone"                  || PROSE_DIRECTION_CONTROL
        "a bell, then a direction control"           || PROSE_DIRECTION_CONTROL
        "a tag character alone"                      || PROSE_TAG_CHARACTER
        "a tag character"                            || PROSE_TAG_CHARACTER
        "absent"                                     || REASON_MISSING
        "empty"                                      || REASON_MISSING
        "spaces"                                     || REASON_MISSING
        "no-break spaces"                            || REASON_MISSING
        "a tab and a line feed"                      || REASON_MISSING
        "a zero-width space"                         || REASON_MISSING
        "2049 letters"                               || REASON_UNUSABLE
        "a bell"                                     || REASON_UNUSABLE
        "a carriage return"                          || REASON_UNUSABLE
        "a line separator"                           || REASON_UNUSABLE
    }

    private static String said(String named) {
        switch (named) {
            case "absent": return null
            case "empty": return ""
            case "spaces": return "   "
            case "no-break spaces": return points(0xA0, 0xA0)
            case "a tab and a line feed": return TAB + LINE_FEED
            case "a zero-width space": return points(0x200B)
            case "a direction control": return "Not" + points(0x202E) + "this"
            case "a direction control alone": return points(0x202E)
            case "a letter": return "x"
            case "a letter between spaces": return " x "
            case "a line separator": return points(0x2028)
            case "a carriage return": return "Not" + CARRIAGE_RETURN + "this"
            case "a carriage return alone": return CARRIAGE_RETURN
            case "a sentence": return "Not what was asked."
            case "lines with a tab": return "Not what" + LINE_FEED + TAB + "was asked."
            case "2048 letters": return "x" * 2048
            case "2049 letters": return "x" * 2049
            case "2048 characters outside the basic plane": return points(0x1F600) * 2048
            case "2049 characters outside the basic plane": return points(0x1F600) * 2049
            case "a CRLF": return "Not" + CARRIAGE_RETURN + LINE_FEED + "this"
            case "a CRLF alone": return CARRIAGE_RETURN + LINE_FEED
            case "a tag character alone": return points(0xE0041)
            case "a CRLF and a direction control": return points(0x202E) + CARRIAGE_RETURN + LINE_FEED
            case "a bell, then a direction control": return "Not" + BELL + points(0x202E)
            case "a tag character": return "Not" + points(0xE0041)
            case "a bell": return "Not" + BELL + "this"
            default: throw new IllegalArgumentException(named)
        }
    }

    private static String points(int... codePoints) {
        new String(codePoints, 0, codePoints.length)
    }
}
