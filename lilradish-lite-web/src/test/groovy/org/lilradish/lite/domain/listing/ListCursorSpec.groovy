package org.lilradish.lite.domain.listing

import static java.nio.charset.StandardCharsets.UTF_8

import java.nio.ByteBuffer
import org.lilradish.lite.testutil.listing.SampleColumn
import spock.lang.Specification

/**
 * A cursor is read by the one query of the one list that minted it and refused by every other, and
 * whatever this system did not mint is refused the same way — by one exception type, which is what
 * lets the edge turn every such cursor into a refusal of the request rather than a fault of the
 * server. A position no row could have left is refused when minted too, as a fault of this system's,
 * so nothing is ever minted that resuming would refuse.
 *
 * <p>Some features below lay a cursor out by hand after a genuine cursor's binding. That is the
 * layout this class owns, written out here so that a cursor holding what no row can hold can be built
 * at all: nothing that mints one would produce it. Damage is done to bytes rather than to the text,
 * because more than one text decodes to the same bytes.
 */
class ListCursorSpec extends Specification {

    /** The same names, the size now held as text: a position laid out for one is not one for the other. */
    enum Resized implements ListColumn {
        KEY("key", ListValueKind.TEXT),
        NAME("name", ListValueKind.NULLABLE_TEXT),
        SIZE("size", ListValueKind.TEXT)

        final String spelt

        final ListValueKind valueKind

        Resized(String spelt, ListValueKind valueKind) {
            this.spelt = spelt
            this.valueKind = valueKind
        }

        @Override
        String published() {
            spelt
        }

        @Override
        ListValueKind kind() {
            valueKind
        }
    }

    /** The same names, the key now held as a count, and still what breaks every tie. */
    enum Rekeyed implements ListColumn {
        KEY("key", ListValueKind.LONG),
        NAME("name", ListValueKind.NULLABLE_TEXT),
        SIZE("size", ListValueKind.LONG)

        final String spelt

        final ListValueKind valueKind

        Rekeyed(String spelt, ListValueKind valueKind) {
            this.spelt = spelt
            this.valueKind = valueKind
        }

        @Override
        String published() {
            spelt
        }

        @Override
        ListValueKind kind() {
            valueKind
        }
    }

    /** A key breaking every tie, and a fact each row holds or does not. */
    enum Flagged implements ListColumn {
        KEY("key", ListValueKind.TEXT),
        FLAG("flag", ListValueKind.BOOLEAN)

        final String spelt

        final ListValueKind valueKind

        Flagged(String spelt, ListValueKind valueKind) {
            this.spelt = spelt
            this.valueKind = valueKind
        }

        @Override
        String published() {
            spelt
        }

        @Override
        ListValueKind kind() {
            valueKind
        }
    }

    static final ListShape<Flagged> FLAGS = new ListShape<>("flags", Flagged.KEY, new ListOrder<>(Flagged.KEY, false))

    static final ListQuery<Flagged> BY_FLAG = new ListQuery<>("", new ListOrder<>(Flagged.FLAG, false), null)

    static final String GENUINE_BY_FLAG = ListCursor.mint(FLAGS, BY_FLAG, new ListPosition(true, "000150"))

    static final String UNHELD_FLAG = "ListCursor position holds no yes or no where one is sorted by"

    static final ListOrder<SampleColumn> BY_KEY = new ListOrder<>(SampleColumn.KEY, false)

    static final ListOrder<SampleColumn> BY_KEY_DESCENDING = new ListOrder<>(SampleColumn.KEY, true)

    static final ListOrder<SampleColumn> BY_NAME = new ListOrder<>(SampleColumn.NAME, false)

    static final ListOrder<SampleColumn> BY_SIZE = new ListOrder<>(SampleColumn.SIZE, false)

    static final ListOrder<SampleColumn> BY_SIZE_DESCENDING = new ListOrder<>(SampleColumn.SIZE, true)

    static final List<ListOrder<SampleColumn>> EVERY_ORDER = SampleColumn.values().collectMany {
        [new ListOrder<>(it, false), new ListOrder<>(it, true)]
    }

    static final ListShape<SampleColumn> PEOPLE = new ListShape<>("people", SampleColumn.KEY, BY_KEY)

    static final ListShape<SampleColumn> MEMBERS = new ListShape<>("members", SampleColumn.KEY, BY_KEY)

    static final ListShape<SampleColumn> PEOPLE_TIED_BY_SIZE = new ListShape<>("people", SampleColumn.SIZE, BY_KEY)

    static final ListShape<SampleColumn> NAMED_AB = new ListShape<>("ab", SampleColumn.KEY, BY_KEY)

    static final ListShape<SampleColumn> NAMED_A = new ListShape<>("a", SampleColumn.KEY, BY_KEY)

    static final ListShape<Resized> PEOPLE_RESIZED =
            new ListShape<>("people", Resized.KEY, new ListOrder<>(Resized.KEY, false))

    static final ListShape<Rekeyed> PEOPLE_REKEYED =
            new ListShape<>("people", Rekeyed.KEY, new ListOrder<>(Rekeyed.KEY, false))

    static final ListFilter GRACE = new ListFilter("grace")

    static final ListQuery<SampleColumn> BY_NAME_FOR_GRACE = new ListQuery<>("", BY_NAME, GRACE)

    static final ListQuery<SampleColumn> BY_SIZE_FOR_GRACE = new ListQuery<>("", BY_SIZE, GRACE)

    static final ListQuery<SampleColumn> BY_KEY_WHOLE = new ListQuery<>("", BY_KEY, null)

    static final ListPosition NAMED = new ListPosition("grace hopper", "000150")

    static final ListPosition NAMELESS = new ListPosition(null, "000130")

    static final ListPosition SIZED = new ListPosition(1L, "000150")

    static final ListPosition BY_ITS_KEY = new ListPosition("000150", "000150")

    static final String GRINNING_FACE = Character.toString(0x1F600)

    /**
     * The key, the name where one is held, and the size. Every kind of value a stored row can hand a
     * position: a name outside the basic plane, one joined by a format character the store admits, one
     * spaced by U+3000, a key outside ASCII and one with a space in front, both at their bound in
     * characters four bytes long, and sizes at either end of what the store could count.
     */
    static final List<List<Object>> ROWS = [
            ["000150", "grace hopper", 1L],
            ["000130", null, 1L],
            [Character.toString(0xC5) + "SA-7", null, 0L],
            [GRINNING_FACE * 256, GRINNING_FACE * 256, 0L],
            ["000160", "Ada" + Character.toString(0x200D) + "Lovelace", 3L],
            ["000180", "山田" + Character.toString(0x3000) + "太郎", Long.MAX_VALUE],
            [" 000190", Character.toString(0xC9) + "mile", Long.MIN_VALUE],
    ]

    static final List<Object> LONGEST_ROW = ROWS[3]

    /** A binding and two texts at their bound, each with its length and its presence, every character four bytes long. */
    static final int LONGEST = 2766

    static final int BINDING_BYTES = 16

    static final String GENUINE = ListCursor.mint(PEOPLE, BY_NAME_FOR_GRACE, NAMED)

    static final String GENUINE_BY_SIZE = ListCursor.mint(PEOPLE, BY_SIZE_FOR_GRACE, SIZED)

    static final String GENUINE_BY_KEY = ListCursor.mint(PEOPLE, BY_KEY_WHOLE, BY_ITS_KEY)

    /** Two characters past its last whole group, which is where padding and unused low bits can hide. */
    static final String TWO_OVER = twoCharactersOver()

    static final String URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    static final String NOT_MINTED = "ListCursor cursor is not one this system mints"

    static final String CUT_SHORT = "ListCursor cursor ends before its position does"

    static final String ANOTHER_QUERY = "ListCursor cursor was minted by another query"

    static final String NO_STORED_TEXT = "ListCursor cursor holds text no stored value can"

    static final String NOT_UTF_8 = "ListCursor cursor holds text that is not UTF-8"

    static final String TWO_TIEBREAKS = "ListCursor position holds two values for the column that breaks every tie"

    static final String UNHELD_TEXT = "ListCursor position holds text no stored value can"

    static final String UNHELD_COUNT = "ListCursor position holds no count where one is sorted by"

    def "a cursor cannot be minted for no list, no query or no position"() {
        when:
        ListCursor.mint(shape, query, position)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        shape  | query             | position || message
        null   | BY_NAME_FOR_GRACE | NAMED    || "ListCursor shape must not be null"
        PEOPLE | null              | NAMED    || "ListCursor query must not be null"
        PEOPLE | BY_NAME_FOR_GRACE | null     || "ListCursor position must not be null"
    }

    /**
     * Every one of these is a position no stored row could leave, so minting it is a fault of this
     * system's and not a reader's, and a cursor minted from it would only be refused on its way back.
     */
    def "a position no row could have left is refused when minted, as this system's fault"() {
        when:
        ListCursor.mint(PEOPLE, new ListQuery<>("", order, null), position)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == message

        where:
        order   | position                                                              || message
        BY_KEY  | new ListPosition("000150", "000151")                                  || TWO_TIEBREAKS
        BY_KEY  | new ListPosition(null, "000150")                                      || TWO_TIEBREAKS
        BY_KEY  | new ListPosition(150L, 150L)                                          || UNHELD_TEXT
        BY_NAME | new ListPosition("", "000150")                                        || UNHELD_TEXT
        BY_NAME | new ListPosition("a" * 257, "000150")                                 || UNHELD_TEXT
        BY_NAME | new ListPosition(GRINNING_FACE * 257, "000150")                       || UNHELD_TEXT
        BY_NAME | new ListPosition("grace" + Character.toString(0) + "hopper", "000150") || UNHELD_TEXT
        BY_NAME | new ListPosition("grace" + Character.toString(0x9F), "000150")        || UNHELD_TEXT
        BY_NAME | new ListPosition("grace" + Character.toString(0xD800), "000150")      || UNHELD_TEXT
        BY_NAME | new ListPosition(5L, "000150")                                        || UNHELD_TEXT
        BY_NAME | new ListPosition("grace hopper", 150L)                                || UNHELD_TEXT
        BY_SIZE | new ListPosition("1", "000150")                                       || UNHELD_COUNT
        BY_SIZE | new ListPosition(null, "000150")                                      || UNHELD_COUNT
        BY_SIZE | new ListPosition(1, "000150")                                         || UNHELD_COUNT
    }

    def "a position holding anything but a yes or a no where one is sorted by is refused when minted, as this system's fault"() {
        when:
        ListCursor.mint(FLAGS, BY_FLAG, position)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == UNHELD_FLAG

        where:
        position << [new ListPosition("true", "000150"), new ListPosition(null, "000150"), new ListPosition(1L, "000150")]
    }

    def "a cursor carries a yes or a no back as itself, whichever way the list runs and however it is narrowed"() {
        given:
        def query = new ListQuery<>("", new ListOrder<>(Flagged.FLAG, descending), filter)
        def position = new ListPosition(held, "000150")

        expect:
        ListCursor.resume(FLAGS, query, ListCursor.mint(FLAGS, query, position)) == position

        where:
        [held, descending, filter] << [[true, false], [false, true], [null, GRACE]].combinations()
    }

    def "a cursor carries the query that minted it back to exactly the row the page ended on"() {
        given:
        def query = new ListQuery<>(scope, order, filter)
        def position = positionOf(order, row)

        expect:
        ListCursor.resume(PEOPLE, query, ListCursor.mint(PEOPLE, query, position)) == position

        where:
        [order, filter, scope, row] << [EVERY_ORDER, [null, GRACE, new ListFilter("%_\\")], ["", "group 7"], ROWS]
                .combinations()
    }

    /** It travels in a query string, where anything else would have to be escaped by every caller. */
    def "a cursor is written only in characters a query string carries unescaped"() {
        expect:
        ListCursor.mint(PEOPLE, new ListQuery<>("", order, GRACE), positionOf(order, row)) ==~ /[A-Za-z0-9_-]+/

        where:
        [order, row] << [EVERY_ORDER, ROWS].combinations()
    }

    def "the longest position any row can leave mints a cursor no longer than the longest one resumed"() {
        expect:
        ListCursor.mint(PEOPLE, new ListQuery<>("", order, GRACE), positionOf(order, LONGEST_ROW)).length() <= LONGEST

        where:
        order << EVERY_ORDER
    }

    /**
     * Filters differing only by case or by a space are two filters, being matched as typed. Lists
     * sharing a column name, and one list read within two things, are as many queries; so is a list
     * whose ties are broken by another column, or whose column of one name holds another kind of value,
     * its positions being laid out differently. A name and a scope that spell one string between them
     * are still two.
     */
    def "a cursor minted by one query is refused by any other, and still honoured by its own"() {
        given:
        def cursor = ListCursor.mint(mintedShape, mintedQuery, position)

        when:
        ListCursor.resume(askedShape, askedQuery, cursor)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == ANOTHER_QUERY

        and:
        ListCursor.resume(mintedShape, mintedQuery, cursor) == position

        where:
        mintedShape         | mintedQuery                                   | position                             || askedShape     | askedQuery
        PEOPLE              | BY_KEY_WHOLE                                  | BY_ITS_KEY                           || PEOPLE         | new ListQuery<>("", BY_KEY_DESCENDING, null)
        PEOPLE              | BY_KEY_WHOLE                                  | BY_ITS_KEY                           || PEOPLE         | new ListQuery<>("", BY_NAME, null)
        PEOPLE              | new ListQuery<>("", BY_SIZE_DESCENDING, null) | SIZED                                || PEOPLE         | new ListQuery<>("", BY_SIZE, null)
        PEOPLE              | BY_SIZE_FOR_GRACE                             | SIZED                                || PEOPLE         | new ListQuery<>("", BY_NAME, GRACE)
        PEOPLE              | BY_NAME_FOR_GRACE                             | NAMED                                || PEOPLE         | new ListQuery<>("", BY_NAME, null)
        PEOPLE              | new ListQuery<>("", BY_NAME, null)            | NAMED                                || PEOPLE         | BY_NAME_FOR_GRACE
        PEOPLE              | BY_NAME_FOR_GRACE                             | NAMED                                || PEOPLE         | new ListQuery<>("", BY_NAME, new ListFilter("Grace"))
        PEOPLE              | BY_NAME_FOR_GRACE                             | NAMED                                || PEOPLE         | new ListQuery<>("", BY_NAME, new ListFilter("grace "))
        PEOPLE              | new ListQuery<>("group 7", BY_NAME, null)     | NAMED                                || PEOPLE         | new ListQuery<>("group 8", BY_NAME, null)
        PEOPLE              | new ListQuery<>("", BY_NAME, null)            | NAMED                                || PEOPLE         | new ListQuery<>("group 7", BY_NAME, null)
        PEOPLE              | new ListQuery<>("", BY_NAME, null)            | NAMED                                || MEMBERS        | new ListQuery<>("", BY_NAME, null)
        PEOPLE_TIED_BY_SIZE | new ListQuery<>("", BY_NAME, null)            | new ListPosition("grace hopper", 1L) || PEOPLE         | new ListQuery<>("", BY_NAME, null)
        PEOPLE              | BY_SIZE_FOR_GRACE                             | SIZED                                || PEOPLE_RESIZED | new ListQuery<>("", new ListOrder<>(Resized.SIZE, false), GRACE)
        PEOPLE              | new ListQuery<>("", BY_NAME, null)            | NAMED                                || PEOPLE_REKEYED | new ListQuery<>("", new ListOrder<>(Rekeyed.NAME, false), null)
        NAMED_AB            | new ListQuery<>("c", BY_NAME, null)           | NAMED                                || NAMED_A        | new ListQuery<>("bc", BY_NAME, null)
    }

    def "a cursor cannot be resumed for no list, no query, or from nothing"() {
        when:
        ListCursor.resume(shape, query, cursor)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        shape  | query             | cursor  || message
        null   | BY_NAME_FOR_GRACE | GENUINE || "ListCursor shape must not be null"
        PEOPLE | null              | GENUINE || "ListCursor query must not be null"
        PEOPLE | BY_NAME_FOR_GRACE | null    || "ListCursor cursor must not be null"
    }

    /**
     * Each is read as far as the one thing wrong with it, and refused for that thing. A spelling of the
     * right bytes that this system would not have written — padded, or with the unused low bits of its
     * last character set — is refused as not minted here. The hand-laid ones follow the genuine
     * binding, so what they hold is what refuses them; text that is empty, past its bound, or holding
     * U+0000 or any other control character is a position no stored row could produce.
     */
    def "whatever this query did not mint is refused as an argument, saying what was wrong with it"() {
        when:
        ListCursor.resume(PEOPLE, query, cursor)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        query             | cursor                                                                                   || message
        BY_NAME_FOR_GRACE | ""                                                                                       || CUT_SHORT
        BY_NAME_FOR_GRACE | "!"                                                                                      || NOT_MINTED
        BY_NAME_FOR_GRACE | "not a cursor"                                                                           || NOT_MINTED
        BY_NAME_FOR_GRACE | "+" + GENUINE.substring(1)                                                               || NOT_MINTED
        BY_KEY_WHOLE      | TWO_OVER + "=="                                                                          || NOT_MINTED
        BY_KEY_WHOLE      | withUnusedBitsSet(TWO_OVER)                                                              || NOT_MINTED
        BY_NAME_FOR_GRACE | "AAAA"                                                                                   || CUT_SHORT
        BY_NAME_FOR_GRACE | "A" * 22                                                                                 || ANOTHER_QUERY
        BY_NAME_FOR_GRACE | "A" * LONGEST                                                                            || ANOTHER_QUERY
        BY_NAME_FOR_GRACE | "A" * (LONGEST + 1)                                                                      || "ListCursor cursor is longer than any this system mints"
        BY_NAME_FOR_GRACE | cutShort(GENUINE, 3)                                                                     || CUT_SHORT
        BY_NAME_FOR_GRACE | GENUINE + "AAAA"                                                                         || "ListCursor cursor carries more than a position"
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [2 as byte], text("grace hopper"), text("000150"))                      || "ListCursor cursor says neither that a value is held nor not"
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], length(-1), text("000150"))                                || CUT_SHORT
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], length(4096) + bytes("grace hopper"), text("000150"))      || CUT_SHORT
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], text("grace" + Character.toString(0) + "hopper"), text("000150"))    || NO_STORED_TEXT
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], text("grace" + Character.toString(0x85) + "hopper"), text("000150")) || NO_STORED_TEXT
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], text(""), text("000150"))                                  || NO_STORED_TEXT
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], text("a" * 257), text("000150"))                           || NO_STORED_TEXT
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], length(2) + [0xC3 as byte, 0x28 as byte], text("000150")) || NOT_UTF_8
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], length(3) + [0xED as byte, 0xA0 as byte, 0x80 as byte], text("000150")) || NOT_UTF_8
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [0 as byte], text(""))                                                  || NO_STORED_TEXT
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [0 as byte])                                                            || CUT_SHORT
        BY_SIZE_FOR_GRACE | laidOut(GENUINE_BY_SIZE, count(1).take(7))                                               || CUT_SHORT
        BY_KEY_WHOLE      | laidOut(GENUINE_BY_KEY, text("000150"), text("000150"))                                  || "ListCursor cursor carries more than a position"
    }

    /** A yes and a no are each one byte, and no other byte is either; the layout around it is genuine. */
    def "a cursor sorted by a yes or a no is refused where it holds neither"() {
        when:
        ListCursor.resume(FLAGS, BY_FLAG, laidOut(GENUINE_BY_FLAG, *parts))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        parts                          || message
        [[2 as byte], text("000150")]  || "ListCursor cursor says neither yes nor no"
        [[-1 as byte], text("000150")] || "ListCursor cursor says neither yes nor no"
        [[]]                           || CUT_SHORT
    }

    def "the same layout holding a yes or a no is read as that, so the refusals above are about what they hold"() {
        expect:
        ListCursor.resume(FLAGS, BY_FLAG, laidOut(GENUINE_BY_FLAG, [held as byte], text("000150"))) ==
                new ListPosition(position, "000150")

        where:
        held || position
        1    || true
        0    || false
    }

    /**
     * The store admits a key with a space in front, so a position holding one is read like any other:
     * text is refused only on the terms the store refuses it.
     */
    def "the same layout holding what a row can is read, so the refusals above are about what they hold"() {
        expect:
        ListCursor.resume(PEOPLE, query, cursor) == position

        where:
        query             | cursor                                                              || position
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [1 as byte], text("grace hopper"), text("000150")) || NAMED
        BY_NAME_FOR_GRACE | laidOut(GENUINE, [0 as byte], text("000130"))                     || NAMELESS
        BY_SIZE_FOR_GRACE | laidOut(GENUINE_BY_SIZE, count(1), text("000150"))                || SIZED
        BY_KEY_WHOLE      | laidOut(GENUINE_BY_KEY, text("000150"))                           || BY_ITS_KEY
        BY_KEY_WHOLE      | laidOut(GENUINE_BY_KEY, text(" 000150"))                          || new ListPosition(" 000150", " 000150")
        BY_KEY_WHOLE      | TWO_OVER                                                          || new ListPosition("00", "00")
    }

    /**
     * Whatever arrives as a cursor, the edge has one exception type to turn into a refusal; any
     * other reaching it is a fault of this server's and answers as one. Damaging a genuine cursor
     * past its binding is what reaches every read the parse makes, where random bytes would stop
     * at the first.
     */
    def "a damaged cursor is refused as not minted here, or read as some other position, and fails no other way"() {
        given:
        def genuine = Base64.urlDecoder.decode(cursor)
        def draws = new Random(20260924)

        when:
        def outcomes = (1..4000).collect {
            byte[] damaged = Arrays.copyOf(genuine, BINDING_BYTES + 1 + draws.nextInt(genuine.length - BINDING_BYTES + 8))
            (1..(1 + draws.nextInt(3))).each {
                damaged[BINDING_BYTES + draws.nextInt(damaged.length - BINDING_BYTES)] = draws.nextInt(256) as byte
            }
            try {
                ListCursor.resume(PEOPLE, query, Base64.urlEncoder.withoutPadding().encodeToString(damaged))
                return "read"
            } catch (IllegalArgumentException refused) {
                return "refused"
            } catch (RuntimeException other) {
                return other.class.name
            }
        }

        then:
        outcomes.toSet() == ["read", "refused"] as Set

        where:
        query             | cursor
        BY_NAME_FOR_GRACE | GENUINE
        BY_SIZE_FOR_GRACE | GENUINE_BY_SIZE
        BY_KEY_WHOLE      | GENUINE_BY_KEY
    }

    private static String twoCharactersOver() {
        def cursor = ListCursor.mint(PEOPLE, BY_KEY_WHOLE, new ListPosition("00", "00"))
        assert cursor.length() % 4 == 2
        cursor
    }

    private static String withUnusedBitsSet(String cursor) {
        def last = URL_ALPHABET.indexOf(cursor[-1])
        cursor[0..-2] + URL_ALPHABET[last | 1]
    }

    private static String cutShort(String cursor, int bytes) {
        def decoded = Base64.urlDecoder.decode(cursor)
        Base64.urlEncoder.withoutPadding().encodeToString(Arrays.copyOf(decoded, decoded.length - bytes))
    }

    private static ListPosition positionOf(ListOrder<SampleColumn> order, List<Object> row) {
        def sorted = [(SampleColumn.KEY): row[0], (SampleColumn.NAME): row[1], (SampleColumn.SIZE): row[2]]
        new ListPosition(sorted[order.column()], row[0])
    }

    private static String laidOut(String genuine, List<Byte>... parts) {
        def written = Base64.urlDecoder.decode(genuine).toList().take(BINDING_BYTES)
        parts.each { written += it }
        Base64.urlEncoder.withoutPadding().encodeToString(written as byte[])
    }

    private static List<Byte> text(String text) {
        def encoded = bytes(text)
        length(encoded.size()) + encoded
    }

    private static List<Byte> bytes(String text) {
        text.getBytes(UTF_8).toList()
    }

    private static List<Byte> length(int length) {
        ByteBuffer.allocate(Integer.BYTES).putInt(length).array().toList()
    }

    private static List<Byte> count(long count) {
        ByteBuffer.allocate(Long.BYTES).putLong(count).array().toList()
    }
}
