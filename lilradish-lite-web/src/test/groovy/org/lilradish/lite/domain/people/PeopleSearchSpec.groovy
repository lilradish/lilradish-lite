package org.lilradish.lite.domain.people

import spock.lang.Specification

class PeopleSearchSpec extends Specification {

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final String NO_BREAK_SPACE = Character.toString(0x00A0)

    static final String GRINNING_FACE = Character.toString(0x1F600)

    /**
     * Held exactly as typed: a space around or inside a part of a name is part of what is looked for,
     * a character a pattern would treat specially is itself, and so is a format character some names
     * are written with.
     */
    def "a search holds what was typed, exactly as it was typed"() {
        expect:
        new PeopleSearch(typed).text() == typed

        where:
        typed << ["000101", "ada", " Ada", "Ada ", "Ada  Lovelace", "山田" + IDEOGRAPHIC_SPACE + "太郎", "%", "_",
                  "\\", "Mi" + Character.toString(0x200C) + "tra", GRINNING_FACE * 256, " a" + IDEOGRAPHIC_SPACE]
    }

    /**
     * Nothing at all, and spaces alone of whatever kind, would each match every name holding one:
     * the directory listed rather than searched. Judged by category, so a space the runtime's own notion
     * of blank misses is refused all the same.
     */
    def "a search holding nothing but space is refused, whatever the space"() {
        when:
        new PeopleSearch(typed)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PeopleSearch must hold something other than space"

        where:
        typed << ["", " ", "   ", IDEOGRAPHIC_SPACE, NO_BREAK_SPACE, " " + IDEOGRAPHIC_SPACE + NO_BREAK_SPACE]
    }

    def "a search that is not one well-formed line of text is refused, not tidied"() {
        when:
        new PeopleSearch(typed)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith("PeopleSearch must not contain a control, line or paragraph separator")

        where:
        typed << ["Ada" + Character.toString(0x07), "Ada\nLovelace", "Ada\t", "Ada" + Character.toString(0x2028),
                  "Ada" + Character.toString(0x2029), "Ada" + Character.toString(0xD800)]
    }

    /**
     * Counted in code points. The bound is what one search may cost and not the longest value it could
     * match: folded, a value can come out longer than it went in.
     */
    def "a search one past the bound on what one search may cost is refused"() {
        when:
        new PeopleSearch("a" * 257)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PeopleSearch must be at most 256 characters, but this one is 257"
    }

    def "a search at the bound is held whole, though it runs to twice as many chars"() {
        when:
        def search = new PeopleSearch(GRINNING_FACE * 256)

        then:
        search.text() == GRINNING_FACE * 256
        search.text().length() == 512
    }

    def "a search cannot be built of nothing"() {
        when:
        new PeopleSearch(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "PeopleSearch must not be null"
    }
}
