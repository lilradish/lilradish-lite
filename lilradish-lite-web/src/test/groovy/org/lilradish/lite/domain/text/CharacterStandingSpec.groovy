package org.lilradish.lite.domain.text

import groovy.transform.CompileStatic
import spock.lang.Specification

class CharacterStandingSpec extends Specification {

    /** Hand-kept on purpose, from the Unicode tables rather than the code, so the two cannot drift together. */
    def "over every code point, each standing holds exactly the code points the Unicode tables give it"() {
        when:
        def held = heldByEachStanding()

        then:
        held == expectedByEachStanding()
    }

    /**
     * The one place the writings part company, pinned constant by constant: a tab and a line feed are
     * refused in a line and kept in prose, what conceals is kept in a line and refused in a visible line and
     * in prose, every space, U+0020 or not, is blank wherever it stands, and a tab and a line feed are
     * blank only in prose.
     */
    def "each standing is refused, kept and blank exactly as the writing it stands in requires"() {
        expect:
        standing.refusedInALine() == line
        standing.refusedInAVisibleLine() == visibleLine
        standing.refusedInProse() == prose
        standing.blankInALine() == blankInALine
        standing.blankInProse() == blankInProse

        where:
        standing                                      || line  | visibleLine | prose | blankInALine | blankInProse
        CharacterStanding.TAB_OR_LINE_FEED            || true  | true        | false | false        | true
        CharacterStanding.OTHER_CONTROL               || true  | true        | true  | false        | false
        CharacterStanding.LINE_OR_PARAGRAPH_SEPARATOR || true  | true        | true  | false        | false
        CharacterStanding.SURROGATE                   || true  | true        | true  | false        | false
        CharacterStanding.DIRECTION_CONTROL           || false | true        | true  | true         | true
        CharacterStanding.TAG                         || false | true        | true  | true         | true
        CharacterStanding.FORMAT                      || false | true        | false | true         | true
        CharacterStanding.SPACE                       || false | false       | false | true         | true
        CharacterStanding.OTHER_SPACE                 || false | false       | false | true         | true
        CharacterStanding.SHOWS                       || false | false       | false | false        | false
    }

    /**
     * The ranges are copied from Unicode 16.0's DerivedCoreProperties.txt, which the JDK exposes no predicate
     * for; each end and the count pin the copy, 4174 code points less the two joiners.
     */
    def "what may show nothing is Default_Ignorable_Code_Point of Unicode 16.0, each range to its ends, less the joiners"() {
        expect:
        CharacterStanding.ignorable(first) && CharacterStanding.ignorable(last)
        !CharacterStanding.ignorable(first - 1) && !CharacterStanding.ignorable(last + 1)

        where:
        first   | last
        0x00AD  | 0x00AD
        0x034F  | 0x034F
        0x061C  | 0x061C
        0x115F  | 0x1160
        0x17B4  | 0x17B5
        0x180B  | 0x180F
        0x200B  | 0x200B
        0x200E  | 0x200F
        0x202A  | 0x202E
        0x2060  | 0x206F
        0x3164  | 0x3164
        0xFE00  | 0xFE0F
        0xFEFF  | 0xFEFF
        0xFFA0  | 0xFFA0
        0xFFF0  | 0xFFF8
        0x1BCA0 | 0x1BCA3
        0x1D173 | 0x1D17A
        0xE0000 | 0xE0FFF
    }

    def "nothing outside those ranges is counted as able to show nothing"() {
        expect:
        (0..Character.MAX_CODE_POINT).count { CharacterStanding.ignorable(it) } == 4172
    }

    @CompileStatic
    private static Map<CharacterStanding, BitSet> heldByEachStanding() {
        Map<CharacterStanding, BitSet> held = new EnumMap<>(CharacterStanding)
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            held.computeIfAbsent(CharacterStanding.of(codePoint)) { new BitSet() }.set(codePoint)
        }
        held
    }

    /**
     * UnicodeData.txt of the JDK this runs on: Cc, Zl and Zp, Cs, Cf, Zs, and bidi classes LRE to PDI. A JDK
     * on a newer Unicode fails this by design, until these ranges are copied again from its own file.
     */
    private static Map<CharacterStanding, BitSet> expectedByEachStanding() {
        def tabOrLineFeed = bits([0x09..0x0A])
        def otherControl = bits([0x00..0x08, 0x0B..0x1F, 0x7F..0x9F])
        def separator = bits([0x2028..0x2029])
        def surrogate = bits([0xD800..0xDFFF])
        def directionControl = bits([0x202A..0x202E, 0x2066..0x2069])
        def tag = bits([0xE0000..0xE007F])
        def format = bits([0xAD..0xAD, 0x600..0x605, 0x61C..0x61C, 0x6DD..0x6DD, 0x70F..0x70F, 0x890..0x891,
                           0x8E2..0x8E2, 0x180E..0x180E, 0x200B..0x200F, 0x2060..0x2064, 0x206A..0x206F,
                           0xFEFF..0xFEFF, 0xFFF9..0xFFFB, 0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F,
                           0x1BCA0..0x1BCA3, 0x1D173..0x1D17A])
        def space = bits([0x20..0x20])
        def otherSpace = bits([0xA0..0xA0, 0x1680..0x1680, 0x2000..0x200A, 0x202F..0x202F, 0x205F..0x205F,
                               0x3000..0x3000])
        def shows = new BitSet()
        shows.set(0, Character.MAX_CODE_POINT + 1)
        [tabOrLineFeed, otherControl, separator, surrogate, directionControl, tag, format, space, otherSpace].each {
            shows.andNot(it)
        }
        [(CharacterStanding.TAB_OR_LINE_FEED)           : tabOrLineFeed,
         (CharacterStanding.OTHER_CONTROL)              : otherControl,
         (CharacterStanding.LINE_OR_PARAGRAPH_SEPARATOR): separator,
         (CharacterStanding.SURROGATE)                  : surrogate,
         (CharacterStanding.DIRECTION_CONTROL)          : directionControl,
         (CharacterStanding.TAG)                        : tag,
         (CharacterStanding.FORMAT)                     : format,
         (CharacterStanding.SPACE)                      : space,
         (CharacterStanding.OTHER_SPACE)                : otherSpace,
         (CharacterStanding.SHOWS)                      : shows]
    }

    private static BitSet bits(List<IntRange> ranges) {
        def set = new BitSet()
        ranges.each { set.set(it.from, it.to + 1) }
        set
    }
}
