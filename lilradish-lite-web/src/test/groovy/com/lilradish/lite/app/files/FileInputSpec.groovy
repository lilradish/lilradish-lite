package com.lilradish.lite.app.files

import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.file.FileName
import com.lilradish.lite.domain.file.MediaTypeName
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

    def "extensions normalize ASCII letters and count every character after the first dot"() {
        expect:
        new FileExtension(written).value() == normalized

        where:
        written                   || normalized
        '.PDF'                    || '.pdf'
        '.Tar.GZ'                 || '.tar.gz'
        '.7z'                     || '.7z'
        '.' + 'a' * 32            || '.' + 'a' * 32
        '.' + 'a' * 15 + '.' + 'b' * 16 || '.' + 'a' * 15 + '.' + 'b' * 16
    }

    def "extensions refuse missing segments, non-ASCII letters and more than 32 characters after the first dot"() {
        when:
        new FileExtension(written)

        then:
        thrown(IllegalArgumentException)

        where:
        written << ['', '.', 'pdf', '.tar.', '.tar..gz', '..pdf', '.a-b', '.a_b', '.é', '.K',
                    '.pdf\n', ' .pdf', '.pdf ', '.' + 'a' * 33, '.' + 'a' * 16 + '.' + 'b' * 16]
    }

    def "an extension matches only the original name's full ending with ASCII case ignored"() {
        expect:
        new FileExtension(extension).accepts(new FileName(name)) == accepted

        where:
        extension | name                   || accepted
        '.pdf'    | 'Report.PDF'           || true
        '.tar.gz' | 'Backups.TAR.GZ'       || true
        '.gz'     | 'Backups.tar.gz'       || true
        '.tar.gz' | 'Backups.gz'           || false
        '.pdf'    | 'Report.pdf.bak'       || false
        '.pdf'    | 'Report'               || false
        '.pdf'    | 'Report.'              || false
        '.k'      | 'Report.K'             || false
        '.k'      | 'Report.K'             || true
        '.png'    | '👩‍💻.PNG'             || true
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
