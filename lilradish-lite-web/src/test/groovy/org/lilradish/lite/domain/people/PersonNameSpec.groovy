package org.lilradish.lite.domain.people

import spock.lang.Specification

class PersonNameSpec extends Specification {

    static final String NO_BREAK_SPACE = Character.toString(0x00A0)

    static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000)

    static final String LINE_SEPARATOR = Character.toString(0x2028)

    static final String ZERO_WIDTH_JOINER = Character.toString(0x200D)

    static final String ZERO_WIDTH_NON_JOINER = Character.toString(0x200C)

    static final String GRINNING_FACE = Character.toString(0x1F600)

    static final String ZERO_WIDTH_SPACE = Character.toString(0x200B)

    static final String NOTHING_VISIBLE = "PersonName must hold something other than space, format and tag characters"

    static final String CONTROL_REFUSED = "PersonName must not contain a control, line or paragraph separator, or surrogate" +
            " character, but the one at index 3 is U+"

    def "a name spaced as settled is held exactly as given, a format character included"() {
        when:
        def name = new PersonName(value)

        then:
        name.value() == value

        where:
        value << ["Ada Lovelace", "A", "Ada" + ZERO_WIDTH_JOINER + "Lovelace", "Mi" + ZERO_WIDTH_NON_JOINER + "tra",
                  "山田 太郎", GRINNING_FACE * 256]
    }

    def "a name not spaced as settled, or not one legible line, is refused rather than tidied"() {
        when:
        new PersonName(value)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        value                                                  || message
        " Ada"                                                 || "PersonName must not begin with a space"
        "Ada "                                                 || "PersonName must not end with a space"
        "Ada  Lovelace"                                        || "PersonName must not contain two spaces in a row, but a pair begins at index 3"
        "Ada" + NO_BREAK_SPACE + "Lovelace"                    || "PersonName must not contain a space other than U+0020, but the one at index 3 is U+00A0"
        "Ada" + IDEOGRAPHIC_SPACE + "Lovelace"                 || "PersonName must not contain a space other than U+0020, but the one at index 3 is U+3000"
        "Ada\tLovelace"                                        || CONTROL_REFUSED + "0009"
        "Ada" + LINE_SEPARATOR + "Lovelace"                    || CONTROL_REFUSED + "2028"
        "Ada" + Character.toString(0x1C)                       || CONTROL_REFUSED + "001C"
        "Ada" + Character.toString(0x1D)                       || CONTROL_REFUSED + "001D"
        "Ada" + Character.toString(0x1E)                       || CONTROL_REFUSED + "001E"
        "Ada" + Character.toString(0x1F)                       || CONTROL_REFUSED + "001F"
        "Ada" + Character.toString(0xD800)                     || CONTROL_REFUSED + "D800"
        ""                                                     || "PersonName must not be empty"
        ZERO_WIDTH_SPACE                                       || NOTHING_VISIBLE
        ZERO_WIDTH_SPACE + " " + ZERO_WIDTH_JOINER             || NOTHING_VISIBLE
        "a" * 257                                              || "PersonName must be at most 256 characters, but this one is 257"
    }

    def "a name cannot be built of nothing"() {
        when:
        new PersonName(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "PersonName must not be null"
    }

    /** Every name read from the directory passes through here, so one already settled is not copied. */
    def "a name the directory holds already spaced is the very string it held"() {
        when:
        def name = PersonName.fromDirectory(held)

        then:
        name.value().is(held)

        where:
        held << [new String("Ada Lovelace"), new String("A"), new String("山田 太郎")]
    }

    /** Held to the bound once spaced, which is the value kept, and not as the directory held it. */
    def "a name the directory holds past the bound only by whitespace at its ends is held at the bound"() {
        when:
        def name = PersonName.fromDirectory(" " + "a" * 256 + NO_BREAK_SPACE)

        then:
        name.value() == "a" * 256
    }

    /**
     * What the directory's own checks already keep out, or cannot store, and spacing leaves in place:
     * refused as the type refuses it, for whoever read it to fail that read.
     */
    def "a name the directory holds that its type refuses is refused once spaced, not tidied further"() {
        when:
        PersonName.fromDirectory(held)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == refusal

        where:
        held                                            || refusal
        "Ada" + Character.toString(0x00) + "Lovelace"   || CONTROL_REFUSED + "0000"
        "Ada" + Character.toString(0x7F) + "Lovelace"   || CONTROL_REFUSED + "007F"
        "Ada" + Character.toString(0xDC00) + " "        || CONTROL_REFUSED + "DC00"
        "a" * 257 + " "                                 || "PersonName must be at most 256 characters, but this one is 257"
    }

    def "a name the directory holds cannot be nothing"() {
        when:
        PersonName.fromDirectory(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "PersonName held in the directory must not be null"
    }

    /** Every filter typed over the pool passes through here, so one already spaced is not copied. */
    def "text already spaced as names are is the very string given"() {
        when:
        def spaced = PersonName.spacedAsNamesAre(typed)

        then:
        spaced.is(typed)

        where:
        typed << [new String(""), new String("ADA "), new String(" Ada Lovelace "), new String("山田 太郎")]
    }

    def "text spaced as names are cannot be nothing"() {
        when:
        PersonName.spacedAsNamesAre(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Text spaced as names are must not be null"
    }
}
