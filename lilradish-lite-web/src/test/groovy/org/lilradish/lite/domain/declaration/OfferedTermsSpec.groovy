package org.lilradish.lite.domain.declaration

import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import spock.lang.Specification

class OfferedTermsSpec extends Specification {

    static final OfferedTerms.Offered BILLING =
            new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge is what is disputed."))

    static final OfferedTerms.Offered DELIVERY =
            new OfferedTerms.Offered(new Term("Delivery"), new TermMeaning("Where or when it arrived is disputed."))

    static final ListNote CHOOSING = new ListNote("Choose what is to be put right.")

    static final String COMPOSED = "Caf" + Character.toString(0xE9)

    /** The same word to a reader, spelt with a combining accent, which no term is folded to. */
    static final String DECOMPOSED = "Cafe" + Character.toString(0x301)

    static final OfferedTerms.Offered CAFE =
            new OfferedTerms.Offered(new Term(COMPOSED), new TermMeaning("The order was taken at the counter."))

    def "terms are offered in the list's order, apart from the list they were handed in, with its note or none"() {
        given:
        def handed = [DELIVERY, BILLING]

        when:
        def offered = new OfferedTerms(handed, note)
        handed.clear()
        handed.add(BILLING)

        then:
        offered.terms() == [DELIVERY, BILLING]
        offered.note() == note
        offered.offers("Delivery")

        where:
        note << [CHOOSING, null]
    }

    def "offered terms are refused without the list of them"() {
        when:
        new OfferedTerms(null, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "OfferedTerms terms must not be null"
    }

    def "a word is offered only where it is one of the terms exactly as written"() {
        when:
        def offers = new OfferedTerms(terms, null).offers(said)

        then:
        offers == expected

        where:
        terms               | said                            || expected
        [DELIVERY, BILLING] | "Billing"                       || true
        [DELIVERY, BILLING] | "Delivery"                      || true
        [DELIVERY, BILLING] | "billing"                       || false
        [DELIVERY, BILLING] | " Billing"                      || false
        [DELIVERY, BILLING] | "Bill"                          || false
        [DELIVERY, BILLING] | ""                              || false
        [DELIVERY, BILLING] | "A charge is what is disputed." || false
        []                  | "Billing"                       || false
        [CAFE]              | COMPOSED                        || true
        [CAFE]              | DECOMPOSED                      || false
    }

    def "whether a word is offered is refused without the word"() {
        when:
        new OfferedTerms([DELIVERY, BILLING], null).offers(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "OfferedTerms said must not be null"
    }

    def "offered terms are equal, and hash alike, where their terms in order and their note are"() {
        given:
        def one = new OfferedTerms([DELIVERY, BILLING], note)
        def other = new OfferedTerms(new ArrayList([DELIVERY, BILLING]), note == null ? null : new ListNote(note.value()))

        expect:
        one == other
        other == one
        one.hashCode() == other.hashCode()

        where:
        note << [new ListNote("Choose what is to be put right."), null]
    }

    def "offered terms differ where their terms, their order or their note do, and from anything but offered terms"() {
        given:
        def one = new OfferedTerms([DELIVERY, BILLING], CHOOSING)

        expect:
        !one.equals(other)
        other == null || !other.equals(one)

        where:
        other << [
                new OfferedTerms([BILLING, DELIVERY], CHOOSING),
                new OfferedTerms([DELIVERY], CHOOSING),
                new OfferedTerms([DELIVERY, BILLING], null),
                new OfferedTerms([DELIVERY, BILLING], new ListNote("Choose what went wrong first.")),
                [DELIVERY, BILLING],
                null,
        ]
    }

    def "offered terms are told as their terms in order and their note, or a note of none"() {
        expect:
        new OfferedTerms([DELIVERY, BILLING], note).toString() == told

        where:
        note     || told
        CHOOSING || "OfferedTerms[terms=[Offered[term=Term[value=Delivery], meaning=TermMeaning[value=Where or when it arrived is disputed.]], Offered[term=Term[value=Billing], meaning=TermMeaning[value=A charge is what is disputed.]]], note=ListNote[value=Choose what is to be put right.]]"
        null     || "OfferedTerms[terms=[Offered[term=Term[value=Delivery], meaning=TermMeaning[value=Where or when it arrived is disputed.]], Offered[term=Term[value=Billing], meaning=TermMeaning[value=A charge is what is disputed.]]], note=null]"
    }

    def "a term is refused without its word or without what it means"() {
        when:
        new OfferedTerms.Offered(term, meaning)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        term                | meaning                   || expectedMessage
        null                | new TermMeaning("Means.") || "OfferedTerms.Offered term must not be null"
        new Term("Billing") | null                      || "OfferedTerms.Offered meaning must not be null"
    }
}
