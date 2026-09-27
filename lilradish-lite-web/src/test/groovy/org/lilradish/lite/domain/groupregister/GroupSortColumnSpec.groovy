package org.lilradish.lite.domain.groupregister

import org.lilradish.lite.domain.listing.ListValueKind
import spock.lang.Specification

class GroupSortColumnSpec extends Specification {

    /**
     * Written as a total map, so a column added later carries the name it was given here rather than
     * whatever nobody wrote a row for. These names are what a reader sorts by, so a constant renamed
     * without its name staying put would leave every saved address sorting by nothing.
     */
    def "each sortable column is asked for under the name the reader sends, and there are no others"() {
        given:
        def expected = [
                (GroupSortColumn.KEY): "key",
                (GroupSortColumn.NAME): "name",
                (GroupSortColumn.CAN_BE_ADMINISTERED): "canBeAdministered",
                (GroupSortColumn.MEMBER_COUNT): "memberCount",
        ]

        expect:
        GroupSortColumn.values().collectEntries { [(it): it.published()] } == expected
    }

    /**
     * What a cursor holds for each column and whether its statement sorts the empty after the rest:
     * every group has a key and a name, is administrable or not, and has a count, so none is ever empty.
     */
    def "each sortable column holds the kind of value the store holds in it"() {
        given:
        def expected = [
                (GroupSortColumn.KEY): ListValueKind.TEXT,
                (GroupSortColumn.NAME): ListValueKind.TEXT,
                (GroupSortColumn.CAN_BE_ADMINISTERED): ListValueKind.BOOLEAN,
                (GroupSortColumn.MEMBER_COUNT): ListValueKind.LONG,
        ]

        expect:
        GroupSortColumn.values().collectEntries { [(it): it.kind()] } == expected
    }
}
