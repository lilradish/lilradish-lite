package org.lilradish.lite.app.listing

import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.listing.ListShape
import org.lilradish.lite.testutil.listing.SampleColumn
import spock.lang.Specification

/**
 * What arrives is the container's own map of every value sent under each name, so a name sent twice
 * is a name holding two values here.
 */
class ListParametersSpec extends Specification {

    static final ListOrder<SampleColumn> BY_KEY = new ListOrder<>(SampleColumn.KEY, false)

    static final ListOrder<SampleColumn> BY_NAME = new ListOrder<>(SampleColumn.NAME, false)

    static final ListOrder<SampleColumn> BY_SIZE_DESCENDING = new ListOrder<>(SampleColumn.SIZE, true)

    static final ListShape<SampleColumn> LISTED = new ListShape<>("listed", SampleColumn.KEY, BY_KEY)

    static final ListQuery<SampleColumn> WHOLE_BY_KEY = new ListQuery<>("", BY_KEY, null)

    static final ListPosition ENDED_ON = new ListPosition("k130", "k130")

    static final String MINTED_FOR_THIS_QUERY = ListCursor.mint(LISTED, WHOLE_BY_KEY, ENDED_ON)

    static final String MINTED_BY_ANOTHER_ORDER =
            ListCursor.mint(LISTED, new ListQuery<>("", BY_NAME, null), new ListPosition(null, "k130"))

    static final String SORT_REFUSED = "This list is sorted by one sortable column at a time."

    static final String FILTER_REFUSED = "That filter is too long, or holds a character that cannot be searched for."

    static final String CURSOR_REFUSED = "That position does not belong to this query of the list."

    /** Nothing sent is trimmed, folded or read as a pattern: whatever a reader typed is the filter. */
    def "what was sent is read as exactly the query it asked for, within what the list is read within"() {
        expect:
        ListParameters.query(LISTED, scope, sent) == asked

        where:
        sent                                                          | scope     || asked
        [:]                                                           | ""        || new ListQuery<>("", BY_KEY, null)
        [sort: ["-size"] as String[]]                                 | "group 7" || new ListQuery<>("group 7", BY_SIZE_DESCENDING, null)
        [sort: ["name"] as String[], filter: [" Gr%_\\ "] as String[]] | ""        || new ListQuery<>("", BY_NAME, new ListFilter(" Gr%_\\ "))
        [filter: [""] as String[]]                                    | ""        || new ListQuery<>("", BY_KEY, null)
    }

    /**
     * The order is read before the filter, so a request wrong in both is refused for its order. The
     * sentence is fixed per code and hands back nothing that was sent.
     */
    def "a query that cannot be read as asked is refused with the code saying which part could not be"() {
        when:
        ListParameters.query(LISTED, "", sent)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code
        refused.message == detail

        and: "a value that was read and refused is kept as the cause, and a value sent twice was not read at all"
        (refused.cause instanceof IllegalArgumentException) == parsed

        where:
        sent                                                            || code                             | detail         | parsed
        [sort: ["roles"] as String[]]                                   || RefusalCode.LIST_SORT_UNUSABLE   | SORT_REFUSED   | true
        [sort: ["key,name"] as String[]]                                || RefusalCode.LIST_SORT_UNUSABLE   | SORT_REFUSED   | true
        [sort: [""] as String[]]                                        || RefusalCode.LIST_SORT_UNUSABLE   | SORT_REFUSED   | true
        [sort: ["key", "key"] as String[]]                              || RefusalCode.LIST_SORT_UNUSABLE   | SORT_REFUSED   | false
        [filter: ["Ada" + Character.toString(0x2028)] as String[]]      || RefusalCode.LIST_FILTER_UNUSABLE | FILTER_REFUSED | true
        [filter: ["a" * 257] as String[]]                               || RefusalCode.LIST_FILTER_UNUSABLE | FILTER_REFUSED | true
        [filter: ["a", "b"] as String[]]                                || RefusalCode.LIST_FILTER_UNUSABLE | FILTER_REFUSED | false
        [sort: ["roles"] as String[], filter: ["a", "b"] as String[]]   || RefusalCode.LIST_SORT_UNUSABLE   | SORT_REFUSED   | true
    }

    def "no cursor sent is a reading from the start, and a cursor this query minted resumes where it ended"() {
        expect:
        ListParameters.after(LISTED, WHOLE_BY_KEY, sent) == after

        where:
        sent                                          || after
        [:]                                           || null
        [sort: ["key"] as String[]]                   || null
        [cursor: [MINTED_FOR_THIS_QUERY] as String[]] || ENDED_ON
    }

    def "a cursor this query did not mint, or one sent twice, is refused as a position that is not this query's"() {
        when:
        ListParameters.after(LISTED, WHOLE_BY_KEY, sent)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.LIST_CURSOR_UNUSABLE
        refused.message == CURSOR_REFUSED

        and:
        (refused.cause instanceof IllegalArgumentException) == parsed

        where:
        sent                                                                 || parsed
        [cursor: ["not a cursor"] as String[]]                               || true
        [cursor: [""] as String[]]                                           || true
        [cursor: [MINTED_BY_ANOTHER_ORDER] as String[]]                      || true
        [cursor: [MINTED_FOR_THIS_QUERY, MINTED_FOR_THIS_QUERY] as String[]] || false
    }
}
