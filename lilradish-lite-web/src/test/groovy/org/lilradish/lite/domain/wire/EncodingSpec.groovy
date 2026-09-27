package org.lilradish.lite.domain.wire

import java.time.Instant
import spock.lang.Specification

class EncodingSpec extends Specification {

    /**
     * The exact text, not a shape: a formatter emitting the wrong zone or the wrong second at the
     * right width would pass a regex. The three rows also pin the width invariant — an integral
     * second pads to six digits, and a nanosecond instant truncates to the six Postgres keeps.
     */
    def "an instant publishes as exactly this text"() {
        expect:
        Encoding.utc(Instant.parse(at)) == published

        where:
        at                                || published
        "2026-09-19T10:15:30Z"            || "2026-09-19T10:15:30.000000Z"
        "2026-09-19T10:15:30.100Z"        || "2026-09-19T10:15:30.100000Z"
        "2026-09-19T10:15:30.123456Z"     || "2026-09-19T10:15:30.123456Z"
        "2026-09-19T10:15:30.123456789Z"  || "2026-09-19T10:15:30.123456Z"
        "1969-12-31T23:59:59.999999Z"     || "1969-12-31T23:59:59.999999Z"
    }

    /**
     * A nanosecond instant and the microsecond value Postgres gives back for it must publish
     * identically, or one logical moment reads as two depending on which side answered.
     */
    def "an instant and its microsecond round trip publish the same text"() {
        expect:
        Encoding.utc(Instant.parse("2026-09-19T10:15:30.123456789Z"))
                == Encoding.utc(Instant.parse("2026-09-19T10:15:30.123456Z"))
    }

    def "an absent timestamp publishes as absent"() {
        expect:
        Encoding.utc(null) == null
    }
}
