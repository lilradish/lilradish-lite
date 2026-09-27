package org.lilradish.lite.domain.identity

import spock.lang.Specification

class GroupKeySpec extends Specification {

    /** Held in capitals, so a key read back out of the store is the key it was given. */
    def "a key held in two to sixteen capital English letters is carried exactly as given"() {
        expect:
        new GroupKey(value).value() == value

        where:
        value << ["HR", "TRIAGE", "Q" * 16]
    }

    /**
     * The constructor also runs when a row is read back, so it refuses rather than folds: a key held
     * in another case would otherwise come back as a twin of the one held in capitals.
     */
    def "anything but two to sixteen capital English letters is refused rather than folded"() {
        when:
        new GroupKey(value)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        value                           || expected                 | message
        null                            || NullPointerException     | "GroupKey must not be null"
        ""                              || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
        "Q"                             || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
        "Q" * 17                        || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
        "triage"                        || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
        "TRIAGE42"                      || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
        "TRI AGE"                       || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
        "TRIAGE\n"                      || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
        Character.toString(0xC4) + "B"  || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
        Character.toString(0xFF21) * 2  || IllegalArgumentException | "GroupKey must be two to sixteen capital English letters"
    }

    /** Whatever case a key was typed in, it is held in the one spelling that decides uniqueness. */
    def "a key typed in any case of English letters is held in capitals"() {
        expect:
        GroupKey.typed(typed) == new GroupKey(held)

        where:
        typed              || held
        "triage"           || "TRIAGE"
        "TrIaGe"           || "TRIAGE"
        "TRIAGE"           || "TRIAGE"
        "hr"               || "HR"
        "q" * 16           || "Q" * 16
    }

    /**
     * English letters are asked for before anything is capitalised: a dotless i and a long s each
     * capitalise to an English letter, and a sharp s to two of them, so capitalising first would hold
     * a key nobody typed.
     */
    def "a key typed with anything but two to sixteen English letters is refused, however it would capitalise"() {
        when:
        GroupKey.typed(typed)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        typed                                 || expected                 | message
        null                                  || NullPointerException     | "GroupKey typed must not be null"
        "t"                                   || IllegalArgumentException | "GroupKey must be typed as two to sixteen English letters"
        "t" * 17                              || IllegalArgumentException | "GroupKey must be typed as two to sixteen English letters"
        "tr" + Character.toString(0x0131)     || IllegalArgumentException | "GroupKey must be typed as two to sixteen English letters"
        "tr" + Character.toString(0x017F)     || IllegalArgumentException | "GroupKey must be typed as two to sixteen English letters"
        Character.toString(0xDF)              || IllegalArgumentException | "GroupKey must be typed as two to sixteen English letters"
        " triage"                             || IllegalArgumentException | "GroupKey must be typed as two to sixteen English letters"
        "triage-42"                           || IllegalArgumentException | "GroupKey must be typed as two to sixteen English letters"
        ""                                    || IllegalArgumentException | "GroupKey must be typed as two to sixteen English letters"
    }
}
