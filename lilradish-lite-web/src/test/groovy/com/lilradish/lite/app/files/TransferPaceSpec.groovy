package com.lilradish.lite.app.files

import spock.lang.Specification

class TransferPaceSpec extends Specification {

    def 'the same size allowance includes grace and keeps the exact boundary within pace'() {
        given:
        long started = 19L

        expect:
        TransferPace.allowedNanos(bytes) == allowed
        !TransferPace.tooSlow(started, bytes, started + allowed)
        TransferPace.tooSlow(started, bytes, started + allowed + 1)

        where:
        bytes                                   | allowed
        0L                                      | 30_000_000_000L
        TransferPace.BYTES_PER_SECOND            | 31_000_000_000L
        TransferPace.BYTES_PER_SECOND.intdiv(2)   | 30_500_000_000L
        2 * TransferPace.BYTES_PER_SECOND + 1     | 32_000_003_814L
    }

    def 'a transfer size whose allowance does not fit saturates without becoming an immediate cutoff'() {
        expect:
        TransferPace.allowedNanos(Long.MAX_VALUE) == Long.MAX_VALUE
        !TransferPace.tooSlow(0, Long.MAX_VALUE, Long.MAX_VALUE)
    }

    def 'the elapsed interval is judged across a monotonic clock counter wrapping'() {
        given:
        long started = Long.MAX_VALUE - 1_000_000L
        long allowed = TransferPace.allowedNanos(0)

        expect:
        !TransferPace.tooSlow(started, 0, started + allowed)
        TransferPace.tooSlow(started, 0, started + allowed + 1)
    }

    def 'a negative size is no transfer allowance'() {
        when:
        TransferPace.allowedNanos(-1)

        then:
        thrown(IllegalArgumentException)
    }
}
