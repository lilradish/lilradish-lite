package com.lilradish.lite.domain.registry

import com.lilradish.lite.domain.listing.ListValueKind
import spock.lang.Specification

class LibrarySortColumnSpec extends Specification {

    /** A constant renamed without its name staying put would leave every saved address sorting by nothing. */
    def "each sortable column is asked for under the name the reader sends, and there are no others"() {
        expect:
        LibrarySortColumn.values().collectEntries { [(it): it.published()] } == [
                (LibrarySortColumn.NAME)      : "name",
                (LibrarySortColumn.IN_SERVICE): "inService",
                (LibrarySortColumn.STATUS)    : "status",
        ]
    }

    /** Every entry has a name, in-service presence, and one of three status groups. */
    def "each sortable column holds the kind of value the store sorts it by, none of them ever empty"() {
        expect:
        LibrarySortColumn.values().collectEntries { [(it): it.kind()] } == [
                (LibrarySortColumn.NAME)      : ListValueKind.TEXT,
                (LibrarySortColumn.IN_SERVICE): ListValueKind.BOOLEAN,
                (LibrarySortColumn.STATUS)    : ListValueKind.LONG,
        ]
    }
}
