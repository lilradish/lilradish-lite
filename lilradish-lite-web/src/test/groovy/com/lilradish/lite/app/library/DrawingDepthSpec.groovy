package com.lilradish.lite.app.library

import spock.lang.Specification

/**
 * Drawings the case table shared with the reader lacks, that table being run by {@link DrawingDepthIntegrationSpec};
 * the two underscore rows beside letters of both cases and digits are pinned here as well.
 */
class DrawingDepthSpec extends Specification {

    static final String BYTE_ORDER_MARK = String.valueOf(0xFEFF as char)

    static final String NO_BREAK_SPACE = String.valueOf(0xA0 as char)

    static final String CAPITAL_A_WITH_DIAERESIS = String.valueOf(0xC4 as char)

    static final String ARABIC_INDIC_DIGIT_ONE = String.valueOf(0x661 as char)

    def "each quote and list marker opening a line counts one, and a list marker needs a space, a tab or the line's end after it"() {
        expect:
        DrawingDepth.of(drawing) == depth

        where:
        drawing  || depth
        "plain"  || 0
        ">"      || 1
        ">>"     || 2
        "- a"    || 1
        "* a"    || 1
        "+ a"    || 1
        "*\r\na" || 1
        "- - - " || 3
        "1. a"   || 1
        "1) a"   || 1
    }

    def "every run of spaces and tabs before or between the markers adds half its columns, a tab taken as four and a run after a marker giving one back"() {
        expect:
        DrawingDepth.of(drawing) == depth

        where:
        drawing      || depth
        " - a"       || 1
        "  - a"      || 2
        "   - a"     || 2
        "-\ta"       || 2
        ">  - b"     || 2
        ">   - b"    || 3
        "> >  1. b"  || 3
        ">\t- b"     || 3
        "> > - 1. x" || 4
        "-   * x"    || 3
        "1.    - x"  || 3
    }

    def "the deepest line is the drawing's depth, a line ending at a line feed, a carriage return or both"() {
        expect:
        DrawingDepth.of(drawing) == depth

        where:
        drawing           || depth
        "a\n> b"          || 1
        "> a\n"           || 1
        ">>\n>\n>>>>"     || 4
        "a\n>>>"          || 3
        "> a\n\n   plain" || 1
    }

    def "a drawing at the most deep it may hold is counted exactly, and one level more counts past it"() {
        expect:
        DrawingDepth.of(drawing) == depth

        where:
        drawing            || depth
        ">" * 32 + "1)\na" || 33
        "> " * 32 + "a"    || 32
        "  " * 31 + "- a"  || 32
        "  " * 32 + "- a"  || 33
        "> " * 4096        || 4096
        "a\n" * 4096       || 0
    }

    def "every asterisk counts toward emphasis, and every underscore but one between two ASCII letters or digits, two to a level"() {
        expect:
        DrawingDepth.emphasisOf(drawing) == emphasis

        where:
        drawing                                    || emphasis
        "A_B 1_2"                                  || 0
        "a_. a_."                                  || 1
        "A_Z A_Z"                                  || 0
        "a_z a_z"                                  || 0
        "0_9 0_9"                                  || 0
        "@_A Z_["                                  || 1
        "`_a z_{"                                  || 1
        "/_0 9_:"                                  || 1
        "A_" + CAPITAL_A_WITH_DIAERESIS + "_A"     || 1
        "1_" + ARABIC_INDIC_DIGIT_ONE + "_1"       || 1
        "a*b*c"                                    || 1
        "*a x_y"                                   || 0
    }

    def "a line empty or of only spaces and tabs cuts the emphasis, each stretch halved on its own and the deepest counting"() {
        expect:
        DrawingDepth.emphasisOf(drawing) == emphasis

        where:
        drawing                                             || emphasis
        "***\n\n***"                                        || 1
        "**\n\n****\n\n**"                                  || 2
        "**\n "                                             || 1
        "*a\n " + NO_BREAK_SPACE + "\t\na*"                 || 1
        "*a\r \t\ra*"                                       || 0
        "*a\r\n\t\r\na*"                                    || 0
        "*a\r\r\na*"                                        || 0
        "**\r\n**"                                          || 2
        BYTE_ORDER_MARK + "**\n" + BYTE_ORDER_MARK + "\n**" || 2
    }
}
