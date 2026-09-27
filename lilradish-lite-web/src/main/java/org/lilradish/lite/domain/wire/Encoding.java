package org.lilradish.lite.domain.wire;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.jspecify.annotations.Nullable;

/** Fixed here rather than at each call site: timestamps publish in UTC at one width. */
public final class Encoding {

    /**
     * Six digits always: the ISO formatter prints 0, 3, 6 or 9 digits as needed, so one field would
     * have up to four widths.
     */
    private static final DateTimeFormatter UTC =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    private Encoding() {}

    /** An absent timestamp publishes as absent rather than as a formatted stand-in. */
    public static @Nullable String utc(@Nullable Instant at) {
        return at == null ? null : UTC.format(at);
    }
}
