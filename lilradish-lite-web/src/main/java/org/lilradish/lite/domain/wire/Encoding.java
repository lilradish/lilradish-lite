package org.lilradish.lite.domain.wire;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Fixed here rather than at each call site: timestamps publish in UTC at one width, and token and
 * cost figures stay exact — a count is a {@code long}, a rate or an amount a scaled BigDecimal.
 */
public final class Encoding {

    public static final int MONEY_SCALE = 6;

    public static final int RATIO_SCALE = 4;

    /**
     * Six digits always: the ISO formatter emits nought to nine and trims trailing zeroes, so one
     * field published at two widths — three, counting a value re-read at microsecond resolution.
     */
    private static final DateTimeFormatter UTC =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    private Encoding() {}

    /** An absent timestamp publishes as absent rather than as a formatted stand-in. */
    public static @Nullable String utc(@Nullable Instant at) {
        return at == null ? null : UTC.format(at);
    }

    /** Nothing of nothing is zero; something of nothing is a contradiction, not a ratio. */
    public static BigDecimal ratio(long part, long whole) {
        if (part < 0 || whole < 0) {
            throw new IllegalArgumentException("Encoding ratio is of counts, not of " + part + " and " + whole);
        }
        if (whole == 0) {
            if (part != 0) {
                throw new IllegalArgumentException("Encoding ratio has " + part + " of a whole of nothing");
            }
            return BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), RATIO_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal money(BigDecimal amount) {
        requireNonNull(amount, "Encoding money amount must not be null");
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Multiplied before divided, and never through a double: dividing the rate first rounds it once
     * and then scales that error by the token count.
     */
    public static BigDecimal perMillionTokens(BigDecimal ratePerMillion, long tokens) {
        requireNonNull(ratePerMillion, "Encoding rate must not be null");
        return ratePerMillion
                .multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000L), MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
