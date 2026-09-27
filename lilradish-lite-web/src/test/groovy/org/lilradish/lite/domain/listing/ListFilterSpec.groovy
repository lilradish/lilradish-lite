package org.lilradish.lite.domain.listing

import spock.lang.Specification

class ListFilterSpec extends Specification {

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final String GRINNING_FACE = Character.toString(0x1F600)

    /**
     * What a reader holds while still typing is part of what a filter is, so a space at either end or
     * doubled is kept, and so is a space other than U+0020 that a name may carry. Characters that
     * would be wildcards in a pattern are kept too: a filter is matched as text. So are the format
     * characters some names are written with — a non-joiner, a joiner, a soft hyphen.
     */
    def "a filter holds what was typed, exactly as it was typed"() {
        expect:
        new ListFilter(typed).text() == typed

        where:
        typed << [" Ada", "Ada ", "Ada  Lovelace", "山田" + IDEOGRAPHIC_SPACE + "太郎", "%", "_", "\\",
                  "a%_\\b", GRINNING_FACE * 256, "Mi" + Character.toString(0x200C) + "tra",
                  "Ada" + Character.toString(0x200D), "Love" + Character.toString(0x00AD) + "lace"]
    }

    /**
     * Counted in code points: a limit in the runtime's characters would admit half as many of
     * anything outside the basic plane. A bound on what matching costs, not on what can be matched:
     * folding lengthens some text, so a longer filter could still be found in a value at the column's
     * own bound.
     */
    def "a filter past the bound on what matching it may cost is refused, and one at the bound admitted"() {
        when:
        new ListFilter("a" * 257)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "ListFilter must be at most 256 characters, but this one is 257"

        and: "while one at the bound is held whole, though it runs to twice as many chars"
        new ListFilter(GRINNING_FACE * 256).text() == GRINNING_FACE * 256
    }

    def "a filter that is not one well-formed line of text is refused, not tidied"() {
        when:
        new ListFilter(typed)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith("ListFilter must not contain a control, line or paragraph separator")

        where:
        typed << ["Ada" + Character.toString(0x07), "Ada\nLovelace", "Ada" + Character.toString(0x2028),
                  "Ada" + Character.toString(0x2029), "Ada" + Character.toString(0xD800)]
    }

    def "an empty filter cannot be built, and a filter cannot be built of nothing"() {
        when:
        new ListFilter(typed)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        typed || expected                 | message
        ""    || IllegalArgumentException | "ListFilter must not be empty"
        null  || NullPointerException     | "ListFilter must not be null"
    }

    /** Nothing typed narrows nothing, which is a reading of the whole list rather than a mistake. */
    def "nothing typed is no filter at all rather than a refusal"() {
        expect:
        ListFilter.parse(typed) == null

        where:
        typed << [null, ""]
    }

    def "something typed is parsed into the filter holding it, exactly as typed"() {
        expect:
        ListFilter.parse(" grace ") == new ListFilter(" grace ")
    }

    def "something typed that could not be a filter is refused by the parse, not read as no filter"() {
        when:
        ListFilter.parse("a" * 257)

        then:
        thrown(IllegalArgumentException)
    }
}
