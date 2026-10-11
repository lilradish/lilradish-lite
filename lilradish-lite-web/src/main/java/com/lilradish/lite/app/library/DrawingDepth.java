package com.lilradish.lite.app.library;

/**
 * How deep a drawing's lists and quotes, and its emphasis, could nest, each estimated never to come out shallower
 * than Markdown would nest them. As in Markdown, a carriage return ends a line, the lists and quotes pass over one
 * byte order mark opening the text, and a list marker with the line's end straight after it still opens an item. An
 * underscore is spared only between two ASCII letters or digits: Unicode classes can differ between the JDK and the
 * browser.
 *
 * <p>Sound only while the reader opens no container but quotes and list items, and no inline nests in itself but
 * emphasis.
 */
final class DrawingDepth {

    private static final int MOST_LEVELS = 32;

    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private static final int TAB_COLUMNS = 4;

    private static final int MOST_ORDERED_DIGITS = 9;

    private DrawingDepth() {}

    static int of(String drawing) {
        int length = drawing.length();
        int at = length > 0 && drawing.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
        int deepest = 0;
        while (true) {
            int depth = 0;
            boolean afterMarker = false;
            while (true) {
                int columns = 0;
                while (at < length && isSpaceOrTab(drawing.charAt(at))) {
                    columns += drawing.charAt(at) == '\t' ? TAB_COLUMNS : 1;
                    at++;
                }
                depth += (afterMarker && columns > 0 ? columns - 1 : columns) / 2;
                int after = markerEnd(drawing, at, length);
                if (after < 0) {
                    break;
                }
                depth++;
                at = after;
                afterMarker = true;
            }
            deepest = Math.max(deepest, depth);
            while (at < length && !isLineEnd(drawing.charAt(at))) {
                at++;
            }
            if (at == length) {
                return deepest;
            }
            at++;
        }
    }

    static int emphasisOf(String drawing) {
        int length = drawing.length();
        int deepest = 0;
        int delimiters = 0;
        boolean blank = true;
        for (int at = 0; at < length; at++) {
            char here = drawing.charAt(at);
            if (isLineEnd(here)) {
                if (blank) {
                    deepest = Math.max(deepest, delimiters / 2);
                    delimiters = 0;
                }
                blank = true;
                if (here == '\r' && at + 1 < length && drawing.charAt(at + 1) == '\n') {
                    at++;
                }
            } else {
                blank &= isSpaceOrTab(here);
                if (here == '*' || (here == '_' && !isWithinWord(drawing, at, length))) {
                    delimiters++;
                }
            }
        }
        return Math.max(deepest, delimiters / 2);
    }

    static boolean tooDeep(String drawing) {
        return of(drawing) > MOST_LEVELS || emphasisOf(drawing) > MOST_LEVELS;
    }

    private static boolean isWithinWord(String drawing, int at, int length) {
        return at > 0
                && at + 1 < length
                && isAsciiLetterOrDigit(drawing.charAt(at - 1))
                && isAsciiLetterOrDigit(drawing.charAt(at + 1));
    }

    /** Where a container marker starting at {@code at} ends, or -1 where none starts there. */
    private static int markerEnd(String drawing, int at, int length) {
        if (at == length) {
            return -1;
        }
        char here = drawing.charAt(at);
        if (here == '>') {
            return at + 1;
        }
        int after;
        if (here == '-' || here == '*' || here == '+') {
            after = at + 1;
        } else {
            int digit = at;
            while (digit < length && digit - at < MOST_ORDERED_DIGITS && isDigit(drawing.charAt(digit))) {
                digit++;
            }
            if (digit == at || digit == length || (drawing.charAt(digit) != '.' && drawing.charAt(digit) != ')')) {
                return -1;
            }
            after = digit + 1;
        }
        if (after == length) {
            return after;
        }
        char next = drawing.charAt(after);
        return isSpaceOrTab(next) || isLineEnd(next) ? after : -1;
    }

    private static boolean isSpaceOrTab(char here) {
        return here == ' ' || here == '\t';
    }

    private static boolean isLineEnd(char here) {
        return here == '\n' || here == '\r';
    }

    private static boolean isDigit(char here) {
        return here >= '0' && here <= '9';
    }

    private static boolean isAsciiLetterOrDigit(char here) {
        return isDigit(here) || (here >= 'A' && here <= 'Z') || (here >= 'a' && here <= 'z');
    }
}
