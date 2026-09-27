package org.lilradish.lite.app.inference

import java.time.Duration
import java.time.Instant
import org.springframework.http.HttpHeaders
import spock.lang.Specification

class RetryAfterSpec extends Specification {

    static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z")

    def "takes the first readable of milliseconds, seconds and a date, passing over whatever cannot be read"() {
        given:
        def headers = new HttpHeaders()
        if (milliseconds != null) {
            headers.add(RetryAfter.MILLISECONDS, milliseconds)
        }
        if (retryAfter != null) {
            headers.add(HttpHeaders.RETRY_AFTER, retryAfter)
        }

        expect:
        RetryAfter.asked(headers, NOW) == expected

        where:
        milliseconds           | retryAfter                                || expected
        null                   | null                                      || null
        "1500"                 | null                                      || Duration.ofMillis(1500)
        "1.5"                  | null                                      || Duration.ofNanos(1_500_000)
        "0.0000001"            | null                                      || Duration.ofNanos(1)
        "0"                    | null                                      || Duration.ZERO
        "200"                  | "5"                                       || Duration.ofMillis(200)
        "-5"                   | "3"                                       || Duration.ofSeconds(3)
        "1e3"                  | "3"                                       || Duration.ofSeconds(3)
        " 5"                   | "3"                                       || Duration.ofSeconds(3)
        ""                     | "3"                                       || Duration.ofSeconds(3)
        "NaN"                  | null                                      || null
        "99999999999999999999" | null                                      || Duration.ofNanos(Long.MAX_VALUE)
        null                   | "0"                                       || Duration.ZERO
        null                   | "120"                                     || Duration.ofSeconds(120)
        null                   | "99999999999999999999"                    || Duration.ofNanos(Long.MAX_VALUE)
        null                   | "-1"                                      || null
        null                   | "1.5"                                     || null
        null                   | "soon"                                    || null
        null                   | ""                                        || null
        null                   | "Sat, 26 Sep 2026 10:00:30 GMT"           || Duration.ofSeconds(30)
        null                   | "Sat, 26 Sep 2026 10:00:00 GMT"           || null
        null                   | "Sat, 26 Sep 2026 09:59:00 GMT"           || null
        null                   | "Saturday, 26-Sep-26 10:00:30 GMT"        || Duration.ofSeconds(30)
        null                   | "Saturday, 26-Sep-26 09:59:00 GMT"        || null
        null                   | "Sat Sep 26 10:00:30 2026"                || Duration.ofSeconds(30)
        null                   | "Sat Sep 26 09:59:00 2026"                || null
        null                   | "Sat, 26 Sep 2026 10:00:30"               || null
        "later"                | "Sat, 26 Sep 2026 10:01:00 GMT"           || Duration.ofMinutes(1)
    }
}
