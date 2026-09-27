package org.lilradish.lite.domain.wire

import java.util.Locale
import org.lilradish.lite.domain.identity.EstateRole
import spock.lang.Specification

class StoreLabelsSpec extends Specification {

    /** Written under the one spelling it is read back by, which is what makes a stored row readable. */
    def "every constant is written under its name in lower case, and read back from what was written"() {
        expect:
        StoreLabels.label(constant) == constant.name().toLowerCase(Locale.ROOT)
        StoreLabels.parse(type, StoreLabels.label(constant)) == constant

        where:
        [type, constant] << EstateRole.values().collect { [EstateRole, it] }
    }

    def "no two constants of a type are written under one label"() {
        expect:
        EstateRole.values().collect { StoreLabels.label(it) }.toSet().size() == EstateRole.values().length
    }

    def "no constant may be nothing"() {
        when:
        StoreLabels.label(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "StoreLabels constant must not be null"
    }

    /**
     * Every constant of every type read through here, so a constant added to one is read back the
     * moment it exists. The label written out below is the rule itself, not a copy of a table.
     */
    def "every constant is read back from its name in lower case"() {
        expect:
        StoreLabels.parse(type, constant.name().toLowerCase(Locale.ROOT)) == constant

        where:
        [type, constant] << EstateRole.values().collect { [EstateRole, it] }
    }

    /**
     * Read exactly: a label differing by case, or carrying a space the store would not, is a label
     * the store does not hold, and reading it as the nearest constant would hide a row written round
     * the schema.
     */
    def "a label the store does not hold is refused, naming the type and the label"() {
        when:
        StoreLabels.parse(EstateRole, label)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "No EstateRole is stored under the label '" + label + "'"

        where:
        label << ["auditor", "STEWARD", "Steward", " steward", "steward ", ""]
    }

    def "neither the type nor the label may be nothing"() {
        when:
        StoreLabels.parse(type, label)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        type       | label     || message
        null       | "steward" || "StoreLabels type must not be null"
        EstateRole | null      || "StoreLabels label must not be null"
    }
}
