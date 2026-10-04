package org.lilradish.lite.domain.search

import spock.lang.Specification

class SearchSaidSpec extends Specification {

    def "what an answer said of a search renders as its kind and drops each text"() {
        when:
        def said = new SearchSaid(searchedFor, found)
        def rendered = said.toString()

        then:
        rendered == "SearchSaid()"
        said.searchedFor() == searchedFor
        said.found() == found

        where:
        searchedFor       | found
        "weather in Rome" | "Sunny."
        null              | "Sunny."
        "weather in Rome" | null
        null              | null
    }
}
