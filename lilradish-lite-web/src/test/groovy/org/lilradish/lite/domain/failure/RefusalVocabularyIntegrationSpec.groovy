package org.lilradish.lite.domain.failure

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import org.libprunus.core.error.FallbackErrorCode
import org.lilradish.lite.testutil.ReaderVocabulary
import org.springframework.http.HttpStatus
import spock.lang.Specification

/**
 * The published code a refusal carries, and the sentence a person is shown for it. The first is a
 * constant compiled into this application; the second is a key in the reader's catalogue, and the
 * key is the code itself. Neither artefact can see the other, so a constant renamed here degrades
 * silently over there: the reader meets the wall and is told the generic sentence instead of the one
 * written for that wall, which reads as an unhandled case rather than as a lost pairing.
 *
 * <p>Both sides are discovered — the catalogue is read as text, the constants answer for themselves
 * — so neither can be brought into agreement by editing this file.
 *
 * <p>Three keys are worded here and are no constant of this system's: they are what a response that
 * declared no code of its own is stamped with. They are named as such rather than left out, so a key
 * gaining a constant is a change to this file and not a silent pairing — and each is held to the
 * thing that really produces it rather than to a string written out beside it.
 */
class RefusalVocabularyIntegrationSpec extends Specification {

    static final Path FRONTEND = ReaderVocabulary.FRONTEND

    static final Pattern WORDED = Pattern.compile(/"refusal\.(\w+)"\s*:/)

    /** What the library stamps on a fault nobody planned for, and what the transport names itself. */
    static final Map<String, String> STAMPED_ON_A_CODELESS_RESPONSE = [
        "a fault nobody planned for": FallbackErrorCode.INTERNAL.code(),
        "a rejected field": HttpStatus.BAD_REQUEST.name(),
        "an address naming no operation": HttpStatus.NOT_FOUND.name(),
    ]

    static final List<String> WORDED_CODES = codesTheReaderWords()

    static final List<String> DECLARED = RefusalCode.values().collect { it.name() }

    def "every refusal this system can raise has a sentence written for it, and the catalogue words no other"() {
        given:
        def stamped = STAMPED_ON_A_CODELESS_RESPONSE.values() as Set

        expect:
        (WORDED_CODES as Set) - stamped == DECLARED as Set

        and: "and no code is both declared here and treated as one this system never raises"
        (DECLARED as Set).disjoint(stamped)

        and: "over vocabularies that hold something, two nothings agreeing proving nothing"
        !DECLARED.isEmpty()
        WORDED_CODES.size() == DECLARED.size() + stamped.size()
    }

    /**
     * The sentence a reader is shown has to say something. An id worded with the empty string is a
     * pairing that exists and a wall the reader meets in silence.
     */
    def "no refusal is worded with nothing at all"() {
        given:
        def catalogue = Files.readString(FRONTEND.resolve("i18n/en.ts"))

        expect:
        (catalogue =~ /"refusal\.\w+"\s*:\s*""/).size() == 0

        and: "asserted of a catalogue that was actually read, rather than of an empty string"
        catalogue.contains("refusal.")
    }

    private static List<String> codesTheReaderWords() {
        def catalogue = FRONTEND.resolve("i18n/en.ts")
        assert Files.isRegularFile(catalogue): "the build did not point frontend.location at the reader's tree"
        def keys = (Files.readString(catalogue) =~ WORDED).collect { it[1] }
        assert !keys.isEmpty(): "no refusal was worded in ${catalogue}"
        keys
    }
}
