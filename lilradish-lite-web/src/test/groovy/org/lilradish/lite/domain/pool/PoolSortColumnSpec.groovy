package org.lilradish.lite.domain.pool

import org.lilradish.lite.domain.listing.ListValueKind
import spock.lang.Specification

class PoolSortColumnSpec extends Specification {

    /**
     * Written as a total map, so a column added later carries the name it was given here rather than
     * whatever nobody wrote a row for. These names are what a reader sorts by, so a constant renamed
     * without its name staying put would leave every saved address sorting by nothing.
     */
    def "each sortable column is asked for under the name the reader sends, and there are no others"() {
        given:
        def expected = [
                (PoolSortColumn.USER_ID): "userId",
                (PoolSortColumn.DISPLAY_NAME): "displayName",
                (PoolSortColumn.GROUP_COUNT): "groupCount",
        ]

        expect:
        PoolSortColumn.values().collectEntries { [(it): it.published()] } == expected
    }

    /**
     * What a cursor holds for each column and whether its statement sorts the empty after the rest:
     * only a name can be absent, and a user number, which breaks every tie, never is.
     */
    def "each sortable column holds the kind of value the store holds in it"() {
        given:
        def expected = [
                (PoolSortColumn.USER_ID): ListValueKind.TEXT,
                (PoolSortColumn.DISPLAY_NAME): ListValueKind.NULLABLE_TEXT,
                (PoolSortColumn.GROUP_COUNT): ListValueKind.LONG,
        ]

        expect:
        PoolSortColumn.values().collectEntries { [(it): it.kind()] } == expected
    }
}
