package org.lilradish.lite.domain.people

import spock.lang.Specification

class PeopleFoundSpec extends Specification {

    /** One beyond the most shown is what is fetched, which is how whether more exist is learnt. */
    def "a search fetches one beyond the most it shows"() {
        expect:
        PeopleFound.fetching(most) == fetched

        where:
        most || fetched
        1    || 2
        20   || 21
    }

    def "a search asked to show fewer than one is refused rather than run"() {
        when:
        PeopleFound.fetching(most)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A search must find at least one, not " + most

        where:
        most << [0, -1]
    }

    /** At the most shown nothing lies beyond, and one past it is the only way more is ever said. */
    def "what was fetched is cut at the most shown, saying more exist only where one beyond was fetched"() {
        when:
        def found = PeopleFound.of(fetched, 2)

        then:
        found.people() == shown
        found.more() == more

        where:
        fetched         || shown      | more
        []              || []         | false
        ["a"]           || ["a"]      | false
        ["a", "b"]      || ["a", "b"] | false
        ["a", "b", "c"] || ["a", "b"] | true
    }

    def "what was found cannot be widened by whoever it is handed to"() {
        given:
        def found = PeopleFound.of(["a", "b", "c"], 2)

        when:
        found.people().add("d")

        then:
        thrown(UnsupportedOperationException)

        and:
        found.people() == ["a", "b"]
    }

    def "nothing fetched at all is refused by name rather than cut"() {
        when:
        PeopleFound.of(null, 2)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "PeopleFound fetched must not be null"
    }

    def "no list found at all is refused by name rather than read as nobody"() {
        when:
        new PeopleFound(null, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "PeopleFound people must not be null"
    }
}
