package org.lilradish.lite.app.files

import org.lilradish.lite.domain.file.FileName
import org.lilradish.lite.domain.file.MediaTypeName
import spock.lang.Specification

class FileInputSpec extends Specification {

    def "bare media type names use restricted ASCII and normalize without losing punctuation"() {
        expect:
        new MediaTypeName(written).value() == normalized

        where:
        written                    || normalized
        "IMAGE/PNG"                || "image/png"
        "application/vnd.test+json" || "application/vnd.test+json"
        "a/b"                      || "a/b"
        "A!#\$&^_.+-/B!#\$&^_.+-" || "a!#\$&^_.+-/b!#\$&^_.+-"
    }

    def "a bare media type refuses wildcards, parameters and malformed or overlong names"() {
        when:
        new MediaTypeName(written)

        then:
        thrown(IllegalArgumentException)

        where:
        written << ["*/*", "image/*", "image/png; charset=utf-8", "image", "/png", "image/",
                    "image//png", "image/é", "a" * 128 + "/b", "a/" + "b" * 128,
                    "text/plain\r\n"]
    }

    def "complete sender type parses valid parameters but keeps only its bare name"() {
        expect:
        MediaTypeName.fromHeader(written).value() == bare

        where:
        written                                      || bare
        "IMAGE/PNG; charset=UTF-8"                   || "image/png"
        " application/pdf ; name=\"Demo Sample\" " || "application/pdf"
        "text/plain; a=\"x\\\"y\"; b=token"    || "text/plain"
    }

    def "complete sender type refuses bad parameter syntax without falling back"() {
        when:
        MediaTypeName.fromHeader(written)

        then:
        thrown(IllegalArgumentException)

        where:
        written << ["image/png;", "image/png; name", "image/png; name=", "image/png; name=\"open",
                    "image/png; name=ok garbage", "image/png\r\n", "image/*; q=1", ""]
    }

    def "a file name keeps its original spelling and visible Unicode"() {
        expect:
        new FileName(written).value() == written

        where:
        written << ["Demo Sample.pdf", "  Übersicht.pdf  ", "photo/one.png", "👩‍💻.png"]
    }

    def "a file name counts Unicode code points at its limit"() {
        expect:
        new FileName("😀" * 255).value() == "😀" * 255
        new FileName("x" * 254 + "😀").value() == "x" * 254 + "😀"

        when:
        new FileName("😀" * 256)

        then:
        thrown(IllegalArgumentException)
    }

    def "a file name refuses missing, invisible, multiline or control text"() {
        when:
        new FileName(written)

        then:
        thrown(IllegalArgumentException)

        where:
        written << ["", " " * 255, "x" * 256, "\u00a0", "\u2007", "\u00a0\u2007",
                    "a\nb", "a\rb", "a\u2028b", "a\u2029b", "a\u0000b", "\u200d",
                    "\uD800", "\uDC00"]
    }
}
