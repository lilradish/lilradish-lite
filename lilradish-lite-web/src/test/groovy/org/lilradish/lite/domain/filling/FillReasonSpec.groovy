package org.lilradish.lite.domain.filling

import org.lilradish.lite.domain.text.ProseRefusal
import spock.lang.Specification

class FillReasonSpec extends Specification {

    /** A page says why under each control by these spellings, so a constant renamed with its spelling breaks it. */
    def "each reason a filled value does not fit is published under the spelling a reader names it by, and no other"() {
        expect:
        FillReason.values().collectEntries { [(it): it.published()] } == [
                (FillReason.MISSING)          : "missing",
                (FillReason.MALFORMED)        : "malformed",
                (FillReason.TOO_LONG)         : "too_long",
                (FillReason.TOO_MANY)         : "too_many",
                (FillReason.NOT_A_TERM)       : "not_a_term",
                (FillReason.CRLF)             : "crlf",
                (FillReason.DIRECTION_CONTROL): "direction_control",
                (FillReason.TAG)              : "tag",
                (FillReason.UNUSABLE)         : "unusable",
        ]
    }

    /** A page names why text is refused by one spelling wherever it is refused, a field's value included. */
    def "why text is refused is spelt as it is spelt wherever prose is refused"() {
        expect:
        reason.published() == prose.name().toLowerCase(Locale.ROOT)

        where:
        reason                       || prose
        FillReason.CRLF              || ProseRefusal.CRLF
        FillReason.DIRECTION_CONTROL || ProseRefusal.DIRECTION_CONTROL
        FillReason.TAG               || ProseRefusal.TAG
        FillReason.UNUSABLE          || ProseRefusal.UNUSABLE
    }
}
