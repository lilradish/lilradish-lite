package org.lilradish.lite.domain.wire

import spock.lang.Specification

class DigestSpec extends Specification {

    /**
     * Published SHA-256 vectors rather than a self-comparison: these fingerprints are compared
     * across processes and across restarts, so the test has to fail if the algorithm, the encoding
     * or the hex casing is ever swapped for another that is merely self-consistent.
     */
    def "a fingerprint matches the published vector for its input"() {
        expect:
        Digest.sha256Hex(value) == expected

        where:
        value  || expected
        ""     || "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        "abc"  || "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    def "the same input fingerprints identically however many times it is asked"() {
        expect:
        Digest.sha256Hex("lilradish") == Digest.sha256Hex("lilradish")

        and: "and a different input does not collide with it"
        Digest.sha256Hex("lilradish") != Digest.sha256Hex("lilradisH")
    }

    /**
     * The vector is of the UTF-8 bytes {@code C3 A9}. An implementation that read the platform
     * charset would hash the single byte {@code E9} and produce {@code de2e331d…}, so two
     * deployments with different defaults would stop sharing a cache — this fails instead.
     */
    def "text outside ASCII is hashed as its UTF-8 bytes rather than the platform's"() {
        expect:
        Digest.sha256Hex("é") == "4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c"
    }

    def "every fingerprint is lowercase hex of the full digest width"() {
        expect:
        Digest.sha256Hex(value) ==~ /[0-9a-f]{64}/

        where:
        value << ["", "abc", "lilradish", "é"]
    }

    /**
     * {@code getBytes(UTF_8)} substitutes {@code '?'} for an unpaired surrogate, so without this
     * guard {@code "\uD800"} and {@code "?"} fingerprint identically — a collision on a value whose
     * whole purpose is to be compared across processes.
     */
    def "an unpaired surrogate is refused rather than folded onto a question mark"() {
        when:
        Digest.sha256Hex(hostile)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Digest value must not contain an unpaired surrogate"

        where:
        hostile << ["\uD800", "\uDC00", "a\uD800b", "\uD800\uD800"]
    }

    def "a well-formed surrogate pair is hashed as the character it spells"() {
        expect:
        Digest.sha256Hex("😀") != Digest.sha256Hex("??")

        and: "and it is stable, so a pair is not merely tolerated but carried"
        Digest.sha256Hex("😀") == Digest.sha256Hex("😀")
    }

    def "a missing value is refused by name rather than dereferenced"() {
        when:
        Digest.sha256Hex(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Digest value must not be null"
    }

    /** The same published vectors, so the bytes cannot drift from the text they are the fingerprint of. */
    def "a fingerprint as bytes is the full-width digest the published vector spells"() {
        expect:
        Digest.sha256(value) == HexFormat.of().parseHex(expected)

        where:
        value                      || expected
        ""                         || "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        "abc"                      || "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        Character.toString(0xE9)   || "4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c"
    }

    def "a value the text fingerprint refuses is refused as bytes too"() {
        when:
        Digest.sha256(hostile)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        hostile                    || expected                 | message
        null                       || NullPointerException     | "Digest value must not be null"
        Character.toString(0xD800) || IllegalArgumentException | "Digest value must not contain an unpaired surrogate"
    }
}
