package org.lilradish.lite.domain.run

import spock.lang.Specification

class CeilingSpec extends Specification {

    def "a ceiling from one to the largest a reader's numbers hold exactly is carried as given, two of one count being one"() {
        expect:
        new Ceiling(value).value() == value
        new Ceiling(value) == new Ceiling(value)
        new Ceiling(value) != new Ceiling(value == 1L ? 2L : 1L)

        where:
        value << [1L, 5000L, 9_007_199_254_740_991L]
    }

    /** No ceiling is held as none rather than as zero, and one past the largest would be shown or typed rounded. */
    def "a ceiling of nothing or less, or past the largest, is refused, naming the bound and what was given"() {
        when:
        new Ceiling(value)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Ceiling must be from one to 9007199254740991, but this one is " + value

        where:
        value << [0L, -1L, Long.MIN_VALUE, 9_007_199_254_740_992L, Long.MAX_VALUE]
    }

    def "the largest ceiling is two to the fifty-third, less one"() {
        expect:
        Ceiling.LARGEST == (2L ** 53) - 1
    }

    def "a ceiling typed as a whole number in digits is held as that number"() {
        expect:
        Ceiling.typed(typed) == new Ceiling(held)

        where:
        typed              || held
        "1"                || 1L
        "5000"             || 5000L
        "9007199254740991" || 9_007_199_254_740_991L
    }

    def "anything typed but digits leading with no zero is refused, naming what a ceiling is typed as"() {
        when:
        Ceiling.typed(typed)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Ceiling must be typed as a whole number in digits, leading with none"

        where:
        typed << ["", "0", "007", "-5", "+5", "5.0", "5e3", " 5", "5 ", "1_000", "1,000",
                  Character.toString(0x0665), "12345678901234567"]
    }

    /** Sixteen digits may still be past the largest, which is refused as any other count past it. */
    def "digits past the largest ceiling are refused as the count they spell"() {
        when:
        Ceiling.typed(typed)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Ceiling must be from one to 9007199254740991, but this one is " + typed

        where:
        typed << ["9007199254740992", "9999999999999999"]
    }

    def "nothing typed at all is refused rather than read as none"() {
        when:
        Ceiling.typed(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Ceiling typed must not be null"
    }
}
