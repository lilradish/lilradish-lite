package org.lilradish.lite.domain.listing

import groovy.transform.TupleConstructor
import org.lilradish.lite.testutil.listing.SampleColumn
import spock.lang.Specification

class ListPageSpec extends Specification {

    /** What a list's own row answers: the value it was sorted by, read back. */
    @TupleConstructor
    static class Row implements ListRow<SampleColumn> {
        String key
        String name
        Long size

        @Override
        Object valueIn(SampleColumn column) {
            switch (column) {
                case SampleColumn.KEY: return key
                case SampleColumn.NAME: return name
                default: return size
            }
        }
    }

    static final ListOrder<SampleColumn> BY_KEY = new ListOrder<>(SampleColumn.KEY, false)

    static final ListOrder<SampleColumn> BY_NAME = new ListOrder<>(SampleColumn.NAME, false)

    static final ListOrder<SampleColumn> BY_NAME_DESCENDING = new ListOrder<>(SampleColumn.NAME, true)

    static final ListOrder<SampleColumn> BY_SIZE = new ListOrder<>(SampleColumn.SIZE, false)

    static final ListShape<SampleColumn> SHAPE = new ListShape<>("listed", SampleColumn.KEY, BY_KEY)

    /** A page and one beyond it; the last row a page shows is the one at {@code SIZE}. */
    static final List<Row> PAGE_AND_ONE = (1..ListPage.SIZE + 1).collect { new Row(key(it), "Name " + it, it as Long) }

    static final List<Row> PAGE_AND_ONE_ENDING_NAMELESS = PAGE_AND_ONE.collect {
        it.size == ListPage.SIZE ? new Row(it.key, null, it.size) : it
    }

    static final List<Row> PAGE_AND_ONE_ENDING_ON_NOBODY = PAGE_AND_ONE.collect {
        it.size == ListPage.SIZE ? new Row(null, it.name, it.size) : it
    }

    static String key(int index) {
        String.format(Locale.ROOT, "k%03d", index)
    }

    def "a page cannot be built without its rows"() {
        when:
        new ListPage(null, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ListPage rows must not be null"
    }

    /** A full page with nothing fetched beyond it is the last, so no reader is sent on to an empty one. */
    def "a page holding everything fetched is the last page, whether or not it is full"() {
        when:
        def page = ListPage.of(fetched, SHAPE, BY_NAME)

        then:
        page.rows() == fetched
        page.next() == null

        where:
        fetched << [[], PAGE_AND_ONE.take(1), PAGE_AND_ONE.take(ListPage.SIZE)]
    }

    def "the row fetched past a page is left to the next, which begins after the last row shown in the order read"() {
        when:
        def page = ListPage.of(fetched, SHAPE, order)

        then:
        page.rows() == fetched.take(ListPage.SIZE)
        page.next() == next

        and: "the row beyond it is not shown on this page"
        !page.rows().contains(fetched.last())

        where:
        fetched                      | order              || next
        PAGE_AND_ONE                 | BY_KEY             || new ListPosition(key(ListPage.SIZE), key(ListPage.SIZE))
        PAGE_AND_ONE                 | BY_NAME_DESCENDING || new ListPosition("Name " + ListPage.SIZE, key(ListPage.SIZE))
        PAGE_AND_ONE_ENDING_NAMELESS | BY_NAME            || new ListPosition(null, key(ListPage.SIZE))
        PAGE_AND_ONE                 | BY_SIZE            || new ListPosition(ListPage.SIZE as Long, key(ListPage.SIZE))
    }

    def "a row holding nothing in the column that breaks every tie cannot end a page"() {
        when:
        ListPage.of(PAGE_AND_ONE_ENDING_ON_NOBODY, SHAPE, BY_NAME)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "ListPage row holds nothing in the column that breaks every tie"
    }

    def "a page cannot be cut from nothing, for no list, or in no order"() {
        when:
        ListPage.of(fetched, shape, order)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        fetched      | shape | order  || message
        null         | SHAPE | BY_KEY || "ListPage fetched rows must not be null"
        PAGE_AND_ONE | null  | BY_KEY || "ListPage shape must not be null"
        PAGE_AND_ONE | SHAPE | null   || "ListPage order must not be null"
    }
}
