package org.lilradish.lite.domain.listing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.wire.Digest;

/**
 * The cursor a reader carries from one page of a list to the next: the position a page ended on, and
 * which query minted it.
 *
 * <p>Only that query honours it. Handed to another, a position means something else: what follows a
 * row in one order, or within one scope, is a different set of rows in the next, and honoured anyway it
 * would answer with rows belonging to neither query under the heading of the one asked. So a cursor
 * opens with a digest of the list's name, the query, and the layout the position is written in; one
 * minted by any other query, or laid out by any other release, is refused like anything else this did
 * not mint.
 *
 * <p>Nothing signs a cursor, and the values it holds are readable to whoever holds it. It holds a
 * position and nothing else, so a forged one reaches no row that reading on would not have reached.
 *
 * <p>Static throughout, so that no cursor is ever an argument a woven method logs.
 */
public final class ListCursor {

    /** Raised whenever the layout below changes, which refuses every cursor laid out before it. */
    private static final int LAYOUT = 1;

    private static final int BINDING_BYTES = 16;

    private static final int LONGEST_TEXT = 256;

    /** A present-or-absent byte, a length and the longest text in UTF-8, for each of two values. */
    private static final int LONGEST_POSITION = 2 * (1 + Integer.BYTES + LONGEST_TEXT * 4);

    private static final int LONGEST_CURSOR = ((BINDING_BYTES + LONGEST_POSITION) * 4 + 2) / 3;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private static final byte ABSENT = 0;

    private static final byte PRESENT = 1;

    private static final byte HOLDS_NOT = 0;

    private static final byte HOLDS = 1;

    private ListCursor() {}

    /** Refuses as a fault of this system's a position no row could have left, which resuming would refuse. */
    public static <C extends Enum<C> & ListColumn> String mint(
            ListShape<C> shape, ListQuery<C> query, ListPosition last) {
        requireNonNull(shape, "ListCursor shape must not be null");
        requireNonNull(query, "ListCursor query must not be null");
        requireNonNull(last, "ListCursor position must not be null");
        C column = query.order().column();
        byte[] sorted;
        if (column != shape.tiebreak()) {
            sorted = written(column.kind(), last.sortValue());
        } else if (last.tiebreakValue().equals(last.sortValue())) {
            sorted = new byte[0];
        } else {
            throw new IllegalStateException(
                    "ListCursor position holds two values for the column that breaks every tie");
        }
        byte[] tiebreak = written(shape.tiebreak().kind(), last.tiebreakValue());
        ByteBuffer cursor = ByteBuffer.allocate(BINDING_BYTES + sorted.length + tiebreak.length)
                .put(binding(shape, query))
                .put(sorted)
                .put(tiebreak);
        return ENCODER.encodeToString(cursor.array());
    }

    /** Every read below is bounded first, so a cursor this did not mint fails only as an argument. */
    public static <C extends Enum<C> & ListColumn> ListPosition resume(
            ListShape<C> shape, ListQuery<C> query, String cursor) {
        requireNonNull(shape, "ListCursor shape must not be null");
        requireNonNull(query, "ListCursor query must not be null");
        requireNonNull(cursor, "ListCursor cursor must not be null");
        if (cursor.length() > LONGEST_CURSOR) {
            throw new IllegalArgumentException("ListCursor cursor is longer than any this system mints");
        }
        ByteBuffer read = ByteBuffer.wrap(decoded(cursor));
        byte[] binding = new byte[BINDING_BYTES];
        take(read, BINDING_BYTES).get(binding);
        if (!MessageDigest.isEqual(binding, binding(shape, query))) {
            throw new IllegalArgumentException("ListCursor cursor was minted by another query");
        }
        C column = query.order().column();
        boolean byTiebreak = column == shape.tiebreak();
        Object sortValue = byTiebreak ? null : value(read, column.kind());
        Object tiebreakValue = held(read, shape.tiebreak().kind());
        if (read.hasRemaining()) {
            throw new IllegalArgumentException("ListCursor cursor carries more than a position");
        }
        return new ListPosition(byTiebreak ? tiebreakValue : sortValue, tiebreakValue);
    }

    /**
     * Each part is led by its length, so no two ways of splitting one string are the same query; an
     * absent filter is spelt empty, which no filter is.
     */
    private static <C extends Enum<C> & ListColumn> byte[] binding(ListShape<C> shape, ListQuery<C> query) {
        ListFilter filter = query.filter();
        StringBuilder bound = new StringBuilder();
        for (String part : new String[] {
            shape.name(),
            query.scope(),
            Integer.toString(LAYOUT),
            query.order().published(),
            query.order().column().kind().name(),
            shape.tiebreak().published(),
            shape.tiebreak().kind().name(),
            filter == null ? "" : filter.text()
        }) {
            bound.append(part.length()).append(':').append(part);
        }
        return Arrays.copyOf(Digest.sha256(bound.toString()), BINDING_BYTES);
    }

    private static byte[] written(ListValueKind kind, @Nullable Object value) {
        if (kind.nullable() && value == null) {
            return new byte[] {ABSENT};
        }
        int presence = kind.nullable() ? 1 : 0;
        ByteBuffer written =
                switch (kind) {
                    case TEXT, NULLABLE_TEXT -> {
                        if (!(value instanceof String text) || !heldByAStore(text)) {
                            throw new IllegalStateException("ListCursor position holds text no stored value can");
                        }
                        byte[] encoded = text.getBytes(UTF_8);
                        yield ByteBuffer.allocate(presence + Integer.BYTES + encoded.length)
                                .position(presence)
                                .putInt(encoded.length)
                                .put(encoded);
                    }
                    case LONG -> {
                        if (!(value instanceof Long count)) {
                            throw new IllegalStateException(
                                    "ListCursor position holds no count where one is sorted by");
                        }
                        yield ByteBuffer.allocate(presence + Long.BYTES)
                                .position(presence)
                                .putLong(count);
                    }
                    case BOOLEAN -> {
                        if (!(value instanceof Boolean holds)) {
                            throw new IllegalStateException(
                                    "ListCursor position holds no yes or no where one is sorted by");
                        }
                        yield ByteBuffer.allocate(presence + 1)
                                .position(presence)
                                .put(holds ? HOLDS : HOLDS_NOT);
                    }
                };
        return presence == 0 ? written.array() : written.put(0, PRESENT).array();
    }

    private static @Nullable Object value(ByteBuffer read, ListValueKind kind) {
        if (!kind.nullable()) {
            return held(read, kind);
        }
        return switch (take(read, 1).get()) {
            case ABSENT -> null;
            case PRESENT -> held(read, kind);
            default ->
                throw new IllegalArgumentException("ListCursor cursor says neither that a value is held nor not");
        };
    }

    private static Object held(ByteBuffer read, ListValueKind kind) {
        return switch (kind) {
            case TEXT, NULLABLE_TEXT -> text(read);
            case LONG -> take(read, Long.BYTES).getLong();
            case BOOLEAN ->
                switch (take(read, 1).get()) {
                    case HOLDS -> true;
                    case HOLDS_NOT -> false;
                    default -> throw new IllegalArgumentException("ListCursor cursor says neither yes nor no");
                };
        };
    }

    /**
     * Only the one spelling this system writes is read. The decoder also takes padding and ignores the
     * unused low bits of the last character, which would let one position travel under several cursors.
     */
    private static byte[] decoded(String cursor) {
        byte[] decoded;
        try {
            decoded = DECODER.decode(cursor);
        } catch (IllegalArgumentException unreadable) {
            throw new IllegalArgumentException("ListCursor cursor is not one this system mints", unreadable);
        }
        if (!ENCODER.encodeToString(decoded).equals(cursor)) {
            throw new IllegalArgumentException("ListCursor cursor is not one this system mints");
        }
        return decoded;
    }

    private static ByteBuffer take(ByteBuffer read, int bytes) {
        if (bytes < 0 || read.remaining() < bytes) {
            throw new IllegalArgumentException("ListCursor cursor ends before its position does");
        }
        return read;
    }

    /** Strictly decoded, so no two byte sequences read as one text. */
    private static String text(ByteBuffer read) {
        int length = take(read, Integer.BYTES).getInt();
        take(read, length);
        String text;
        try {
            text = UTF_8.newDecoder()
                    .decode(read.slice(read.position(), length))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("ListCursor cursor holds text that is not UTF-8", malformed);
        }
        read.position(read.position() + length);
        if (!heldByAStore(text)) {
            throw new IllegalArgumentException("ListCursor cursor holds text no stored value can");
        }
        return text;
    }

    private static boolean heldByAStore(String text) {
        return !text.isEmpty()
                && text.codePointCount(0, text.length()) <= LONGEST_TEXT
                && text.codePoints()
                        .noneMatch(codePoint -> Character.isISOControl(codePoint)
                                || Character.getType(codePoint) == Character.SURROGATE);
    }
}
