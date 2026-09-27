package org.lilradish.lite.domain.registry

import org.lilradish.lite.domain.listing.ListValueKind
import spock.lang.Specification

class LibrarySortColumnSpec extends Specification {

    /** A constant renamed without its name staying put would leave every saved address sorting by nothing. */
    def "each sortable column is asked for under the name the reader sends, and there are no others"() {
        expect:
        LibrarySortColumn.values().collectEntries { [(it): it.published()] } == [
                (LibrarySortColumn.NAME)      : "name",
                (LibrarySortColumn.IN_SERVICE): "inService",
                (LibrarySortColumn.SUBMITTED) : "submitted",
                (LibrarySortColumn.STOPPED)   : "stopped",
        ]
    }

    /** Every entry has a name, and whether one of its versions is in service, waiting or stopped holds or not. */
    def "each sortable column holds the kind of value the store sorts it by, none of them ever empty"() {
        expect:
        LibrarySortColumn.values().collectEntries { [(it): it.kind()] } == [
                (LibrarySortColumn.NAME)      : ListValueKind.TEXT,
                (LibrarySortColumn.IN_SERVICE): ListValueKind.BOOLEAN,
                (LibrarySortColumn.SUBMITTED) : ListValueKind.BOOLEAN,
                (LibrarySortColumn.STOPPED)   : ListValueKind.BOOLEAN,
        ]
    }
}
