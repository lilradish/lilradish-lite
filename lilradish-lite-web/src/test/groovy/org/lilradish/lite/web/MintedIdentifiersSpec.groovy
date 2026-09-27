package org.lilradish.lite.web

import spock.lang.Specification

class MintedIdentifiersSpec extends Specification {

    /** Clients and stores write an identifier in either case, and both are the one identifier. */
    def "an identifier in its standard form is read, in either case, as the one identifier it is"() {
        expect:
        MintedIdentifiers.read(spelled) == Optional.of(UUID.fromString("0000000a-000b-4000-8000-00000000000c"))

        where:
        spelled << ["0000000a-000b-4000-8000-00000000000c", "0000000A-000B-4000-8000-00000000000C"]
    }

    /**
     * The runtime's parser also reads a shorter or a signed spelling as some identifier, so one minted
     * here would otherwise travel under spellings that are not its own.
     */
    def "anything but the standard form reads as no identifier, however the runtime would read it"() {
        expect:
        MintedIdentifiers.read(spelled) == Optional.empty()

        where:
        spelled << ["", "not-an-identifier", "a-b-4000-8000-c", "0000000a-000b-4000-8000-00000000000c0",
                    "+000000a-000b-4000-8000-00000000000c", "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz",
                    "0000000a000b40008000000000000000c", " 0000000a-000b-4000-8000-00000000000c",
                    "0000000a-000b-4000-8000-00000000000" + Character.toString(0xFF43)]
    }
}
