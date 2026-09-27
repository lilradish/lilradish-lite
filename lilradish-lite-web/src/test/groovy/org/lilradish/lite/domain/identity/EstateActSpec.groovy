package org.lilradish.lite.domain.identity

import spock.lang.Specification

class EstateActSpec extends Specification {

    /**
     * Locked as a set because nothing reads an act's ordinal. What the lock is for is the other
     * direction: another act declared here is another thing a reader could be told it may do, and
     * every one of them belongs with something that answers for it.
     */
    def "the estate publishes exactly the acts a reader is told about"() {
        expect:
        EstateAct.values() as Set == [EstateAct.KEEP_POOL, EstateAct.GRANT_ESTATE_ROLE,
                                      EstateAct.KEEP_GROUP_REGISTER, EstateAct.CHECK_SOUNDNESS,
                                      EstateAct.READ_MEASUREMENTS] as Set
    }

    /**
     * The one thing here that is a contract with somebody else. A reader gates its screens on these
     * strings, so a constant renamed without its spelling changing with it leaves both sides
     * compiling and every gated screen shut.
     */
    def "each act is published under the spelling the reader gates on"() {
        given:
        def expected = [
                (EstateAct.KEEP_POOL): "keep_pool",
                (EstateAct.GRANT_ESTATE_ROLE): "grant_estate_role",
                (EstateAct.KEEP_GROUP_REGISTER): "keep_group_register",
                (EstateAct.CHECK_SOUNDNESS): "check_soundness",
                (EstateAct.READ_MEASUREMENTS): "read_measurements",
        ]

        expect:
        EstateAct.values().collectEntries { [(it): it.published()] } == expected
    }

    /**
     * A published spelling two acts shared would let a screen gated on one open on the other, and a
     * client mints its own failure codes in lowercase — so a published act must be tellable from
     * both. Asserted over the whole vocabulary rather than the pairs anybody thought to compare.
     */
    def "no two acts are published under one spelling, and none of them is screaming snake"() {
        expect:
        EstateAct.values()*.published().toSet().size() == EstateAct.values().length

        and:
        EstateAct.values().every { it.published() ==~ /^[a-z][a-z0-9_]*$/ }
    }

    /**
     * Vocabularies that never meet. The estate manages the system's shape and never anybody's work,
     * and a group's grants are answered inside one group — so a name shared between them would read
     * as one authority reaching across a boundary that the types exist to hold.
     */
    def "no act is spelt as a group's permission, the two being separate tables"() {
        given:
        def estate = EstateAct.values()*.published() as Set

        expect:
        estate.disjoint(GroupPermission.values()*.published() as Set)

        and: "over vocabularies that are both populated, two empties being disjoint and proving nothing"
        !estate.isEmpty()
        GroupPermission.values().length > 0
    }
}
