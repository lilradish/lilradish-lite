package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonBoolean;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/**
 * What a field is, which is separate from how many of it it holds, and how a value of it is written.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum FieldKind {
    TEXT("text"),
    NUMBER("number"),
    DATE("date"),
    /** One instant, carrying the offset it was given in. */
    MOMENT("moment"),
    YES_NO("yes_no"),
    TERM("term"),
    FIELDS("fields");

    public static final int MOST_DIGITS = 38;

    public static final int MOST_FRACTION_DIGITS = 6;

    /** The furthest an offset runs from zero either way, in whole hours. */
    public static final int FURTHEST_OFFSET_HOURS = 14;

    public static final int FIRST_YEAR = 1;

    public static final int LAST_YEAR = 9999;

    private static final int MINUTES_IN_AN_HOUR = 60;

    private static final String YEAR = "uuuu";

    private static final String MONTH = "MM";

    private static final String DAY_OF_MONTH = "dd";

    private static final String HOURS = "HH";

    private static final String MINUTES = "mm";

    private static final String SECONDS = "ss";

    /* In each form a letter stands for one ASCII digit, and anything else for itself. */
    private static final String DAY = YEAR + "-" + MONTH + "-" + DAY_OF_MONTH;

    private static final String TIME = HOURS + ":" + MINUTES + ":" + SECONDS;

    private static final String OFFSET = HOURS + ":" + MINUTES;

    private static final char TIME_MARK = 'T';

    private static final char FRACTION_MARK = '.';

    private static final int YEAR_AT = DAY.indexOf(YEAR);

    private static final int MONTH_AT = DAY.indexOf(MONTH);

    private static final int DAY_OF_MONTH_AT = DAY.indexOf(DAY_OF_MONTH);

    private static final int TIME_AT = DAY.length() + 1;

    private static final int HOURS_AT = TIME_AT + TIME.indexOf(HOURS);

    private static final int MINUTES_AT = TIME_AT + TIME.indexOf(MINUTES);

    private static final int SECONDS_AT = TIME_AT + TIME.indexOf(SECONDS);

    private static final int FRACTION_AT = TIME_AT + TIME.length();

    private static final int OFFSET_HOURS_AT = 1 + OFFSET.indexOf(HOURS);

    private static final int OFFSET_MINUTES_AT = 1 + OFFSET.indexOf(MINUTES);

    private static final String LONGEST_DAY = LAST_YEAR + "-12-31";

    private static final JsonValue LONGEST_NUMBER = new JsonNumber(new BigDecimal("-9." + "9".repeat(MOST_DIGITS - 1)));

    private static final JsonValue LONGEST_DATE = new JsonString(LONGEST_DAY);

    private static final JsonValue LONGEST_MOMENT = new JsonString(LONGEST_DAY + TIME_MARK + "23:59:59" + FRACTION_MARK
            + "9".repeat(MOST_FRACTION_DIGITS) + "-" + FURTHEST_OFFSET_HOURS + ":00");

    private static final JsonValue LONGEST_YES_NO = new JsonBoolean(false);

    private final String published;

    FieldKind(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    /** Whether a value is written as this kind is, apart from any limit its field declares; none is no value. */
    public boolean writes(JsonValue value) {
        requireNonNull(value, "FieldKind value must not be null");
        return switch (this) {
            case TEXT, TERM -> value instanceof JsonString;
            case NUMBER -> value instanceof JsonNumber number && plain(number.value());
            case DATE ->
                value instanceof JsonString date && date.value().length() == DAY.length() && dated(date.value());
            case MOMENT -> value instanceof JsonString moment && momentary(moment.value());
            case YES_NO -> value instanceof JsonBoolean;
            case FIELDS -> value instanceof JsonObject;
        };
    }

    /**
     * The longest value {@link #writes} takes of a kind whose field declares no limit of it. Text is as long as
     * its field says, a term as its list's terms are, and fields as what they hold, so of those it throws.
     *
     * @throws IllegalStateException of text, a term and fields
     */
    public JsonValue longestWritten() {
        return switch (this) {
            case NUMBER -> LONGEST_NUMBER;
            case DATE -> LONGEST_DATE;
            case MOMENT -> LONGEST_MOMENT;
            case YES_NO -> LONGEST_YES_NO;
            case TEXT, TERM, FIELDS ->
                throw new IllegalStateException("FieldKind " + published + " is as long as its field makes it");
        };
    }

    /**
     * Why a number, in the text of it JSON reads, is not written as a value's number is, or none where it is. Asked
     * of the text by whatever reads it, since the decimal read from it keeps neither an exponent nor a zero's sign.
     */
    public static @Nullable NumberUnwritten numberUnwritten(String written) {
        requireNonNull(written, "FieldKind written must not be null");
        if (written.indexOf('e') >= 0 || written.indexOf('E') >= 0) {
            return NumberUnwritten.WITH_EXPONENT;
        }
        return written.startsWith("-") && written.chars().noneMatch(digit -> digit >= '1' && digit <= '9')
                ? NumberUnwritten.ZERO_WITH_MINUS
                : null;
    }

    /* Precision counts no zero a negative scale stands for: 1E+3 is written plainly in four digits, not one. */
    private static boolean plain(BigDecimal number) {
        return number.scale() >= 0 && Math.max(number.precision(), number.scale() + 1) <= MOST_DIGITS;
    }

    private static boolean dated(String text) {
        if (!shaped(text, 0, DAY)) {
            return false;
        }
        int year = number(text, YEAR_AT, YEAR.length());
        int month = number(text, MONTH_AT, MONTH.length());
        int day = number(text, DAY_OF_MONTH_AT, DAY_OF_MONTH.length());
        return year >= FIRST_YEAR
                && year <= LAST_YEAR
                && month >= 1
                && month <= 12
                && day >= 1
                && day <= YearMonth.of(year, month).lengthOfMonth();
    }

    private static boolean momentary(String text) {
        int length = text.length();
        if (length <= FRACTION_AT
                || !dated(text)
                || text.charAt(DAY.length()) != TIME_MARK
                || !shaped(text, TIME_AT, TIME)
                || number(text, HOURS_AT, HOURS.length()) > 23
                || number(text, MINUTES_AT, MINUTES.length()) >= MINUTES_IN_AN_HOUR
                || number(text, SECONDS_AT, SECONDS.length()) > 59) {
            return false;
        }
        int offset = FRACTION_AT;
        if (text.charAt(offset) == FRACTION_MARK) {
            offset++;
            while (offset < length && digit(text.charAt(offset))) {
                offset++;
            }
            int fraction = offset - FRACTION_AT - 1;
            if (fraction < 1 || fraction > MOST_FRACTION_DIGITS) {
                return false;
            }
        }
        if (length - offset != 1 + OFFSET.length() || !shaped(text, offset + 1, OFFSET)) {
            return false;
        }
        char sign = text.charAt(offset);
        int offsetMinutes = number(text, offset + OFFSET_MINUTES_AT, MINUTES.length());
        int away = number(text, offset + OFFSET_HOURS_AT, HOURS.length()) * MINUTES_IN_AN_HOUR + offsetMinutes;
        // Nothing is written -00:00, so a moment at no offset has the one written form.
        return (sign == '+' || (sign == '-' && away > 0))
                && offsetMinutes < MINUTES_IN_AN_HOUR
                && away <= FURTHEST_OFFSET_HOURS * MINUTES_IN_AN_HOUR;
    }

    private static boolean shaped(String text, int from, String form) {
        if (text.length() < from + form.length()) {
            return false;
        }
        for (int index = 0; index < form.length(); index++) {
            char standing = form.charAt(index);
            char character = text.charAt(from + index);
            if (Character.isLetter(standing) ? !digit(character) : character != standing) {
                return false;
            }
        }
        return true;
    }

    /** That many ASCII digits from {@code from}, which {@link #shaped} found there, read as a number. */
    private static int number(String text, int from, int count) {
        int value = 0;
        for (int index = from; index < from + count; index++) {
            value = value * 10 + (text.charAt(index) - '0');
        }
        return value;
    }

    private static boolean digit(char character) {
        return character >= '0' && character <= '9';
    }

    /** What a number read from JSON text is refused for, which only its text shows. */
    public enum NumberUnwritten {
        WITH_EXPONENT,
        ZERO_WITH_MINUS
    }
}
