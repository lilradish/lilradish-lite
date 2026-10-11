package com.lilradish.lite.domain.registry

import spock.lang.Specification

class VersionNameSpec extends Specification {

    def "the optional display name keeps exactly what was typed"() {
        expect:
        new VersionName(value).value() == value

        where:
        value << ['Stricter tone', 'Research claim', 'Straße', 'e\u0301',
                  'Mi' + Character.toString(0x200C) + 'tra',
                  Character.toString(0x202E) + 'Triage',
                  'e'.repeat(64), Character.toString(0x1F4E6).repeat(64)]
    }

    def "a display name refuses under its own name without repairing the value"() {
        when:
        new VersionName(value)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        value                                      || expectedException        | expectedMessage
        null                                       || NullPointerException     | 'VersionName must not be null'
        ''                                         || IllegalArgumentException | 'VersionName must not be empty'
        'Stricter\ntone'                            || IllegalArgumentException | 'VersionName must not contain a control, line or paragraph separator, or surrogate character, but the one at index 8 is U+000A'
        'Stricter\u00A0tone'                        || IllegalArgumentException | 'VersionName must not contain a space other than U+0020, but the one at index 8 is U+00A0'
        ' Tone'                                    || IllegalArgumentException | 'VersionName must not begin with a space'
        'Tone '                                    || IllegalArgumentException | 'VersionName must not end with a space'
        'Stricter  tone'                            || IllegalArgumentException | 'VersionName must not contain two spaces in a row, but a pair begins at index 8'
        Character.toString(0x200C)                  || IllegalArgumentException | 'VersionName must hold something other than space, format and tag characters'
        'e'.repeat(65)                             || IllegalArgumentException | 'VersionName must be at most 64 characters, but this one is 65'
        Character.toString(0x1F4E6).repeat(65)       || IllegalArgumentException | 'VersionName must be at most 64 characters, but this one is 65'
        Character.toString(0xD800)                  || IllegalArgumentException | 'VersionName must not contain a control, line or paragraph separator, or surrogate character, but the one at index 0 is U+D800'
    }
}
