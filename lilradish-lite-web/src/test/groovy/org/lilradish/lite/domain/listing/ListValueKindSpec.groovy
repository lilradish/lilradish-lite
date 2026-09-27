package org.lilradish.lite.domain.listing

import spock.lang.Specification

class ListValueKindSpec extends Specification {

    /**
     * Written as a total map, so a kind added later says whether a row may hold nothing there rather
     * than inheriting whatever nobody wrote a row for: that is what decides whether a position records
     * a value's presence, and whether a statement sorts the empty after the rest.
     */
    def "only the text a row may leave empty is a kind a row may hold nothing in, and there are no other kinds"() {
        expect:
        ListValueKind.values().collectEntries { [(it): it.nullable()] } ==
                [(ListValueKind.TEXT): false, (ListValueKind.NULLABLE_TEXT): true, (ListValueKind.LONG): false,
                 (ListValueKind.BOOLEAN): false]
    }
}
