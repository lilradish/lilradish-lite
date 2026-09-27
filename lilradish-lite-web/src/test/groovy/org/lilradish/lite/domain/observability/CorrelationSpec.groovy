package org.lilradish.lite.domain.observability

import spock.lang.Specification

class CorrelationSpec extends Specification {

    def cleanup() {
        Correlation.clear()
    }

    /**
     * The read must not mint: a logging hook wants a {@code public static} no-arg method, and
     * hanging one with a side effect on it would have the log line create the business identifier
     * it is reporting.
     */
    def "reading before anything is set answers absent rather than inventing one"() {
        expect:
        Correlation.current() == null

        and: "and asking again still answers absent, so the read left nothing behind"
        Correlation.current() == null
    }

    def "a job with no inbound request mints one and keeps it for the rest of its work"() {
        when:
        def minted = Correlation.currentOrNew()

        then:
        Correlation.currentOrNew() == minted

        and: "and the plain read now sees what was minted, so the two agree"
        Correlation.current() == minted
    }

    def "an inbound identifier is carried as given, not replaced by one of ours"() {
        given:
        def inbound = "2f1c9a7e-upstream-0001"

        when:
        Correlation.set(inbound)

        then:
        Correlation.current() == inbound
        Correlation.currentOrNew() == inbound
    }

    /**
     * Printed beside who is calling on every line, so a character outside a token lets it end the line
     * or pose as the caller printed next to it.
     */
    def "an identifier holding anything but token characters is refused rather than trimmed"() {
        given:
        def hostile = "good" + Character.toString(codePoint) + "tail"

        when:
        Correlation.set(hostile)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Correlation id must hold token characters only"

        and: "and nothing was stored, so a refused value cannot reach a log line"
        Correlation.current() == null

        where:
        codePoint << [0x000A, 0x000D, 0x0000, 0x001F, 0x007F, 0x0085, 0x00A0, 0x00E9, 0x0020, 0x0022, 0x005B,
                      0x005D, 0x003D, 0x002C, 0x003B, 0x0028, 0x0040, 0x002F, 0x005C, 0x007B, 0x003A]
    }

    def "an identifier made of token characters is carried as sent"() {
        when:
        Correlation.set(sent)

        then:
        Correlation.current() == sent

        where:
        sent << ["2f1c9a7e-5b1d-4c2e-9f00-0a1b2c3d4e5f", "a", "z", "A", "Z", "0", "9", "!#\$%&'*+-.^_`|~", "trace.id_01~a"]
    }

    def "an identifier that is empty or longer than the bound is refused"() {
        when:
        Correlation.set(rejected)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Correlation id must be 1 to 64 characters"

        and: "and nothing was stored"
        Correlation.current() == null

        where:
        rejected << ["", "a" * 65]
    }

    def "an identifier at exactly the bound is accepted"() {
        given:
        def longest = "a" * 64

        when:
        Correlation.set(longest)

        then:
        Correlation.current() == longest
    }

    def "a missing identifier is refused by name rather than dereferenced"() {
        when:
        Correlation.set(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Correlation id must not be null"
    }

    def "clearing returns the thread to having none, so a pooled thread carries nothing onward"() {
        given:
        Correlation.set("a-request-that-has-finished")

        when:
        Correlation.clear()

        then:
        Correlation.current() == null
    }
}
