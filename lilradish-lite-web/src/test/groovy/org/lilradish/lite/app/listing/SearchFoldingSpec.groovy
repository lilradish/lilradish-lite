package org.lilradish.lite.app.listing

import spock.lang.Specification

/**
 * The condition's text, which is all that can be asked without a server; what it finds is asked of one
 * in {@code SearchFoldingIntegrationSpec}.
 */
class SearchFoldingSpec extends Specification {

    /** The held side is read as it is stored and never folded again; the typed side is folded once, as a subquery. */
    def "a condition finds the typed text anywhere in the stored fold, folding only what was typed"() {
        expect:
        SearchFolding.holds("person.display_name_folded", ":search") ==
                "strpos(person.display_name_folded, (select search_fold(:search))) > 0"
    }
}
