package org.lilradish.lite.domain.identity

import spock.lang.Specification

class EstateRoleSpec extends Specification {

    /** Who is in the pool, which estate roles they hold, and which groups exist. */
    static final List<EstateAct> STEWARD_ACTS = [EstateAct.KEEP_POOL, EstateAct.GRANT_ESTATE_ROLE,
                                                 EstateAct.KEEP_GROUP_REGISTER]

    static final List<EstateAct> WATCHER_ACTS = [EstateAct.CHECK_SOUNDNESS, EstateAct.READ_MEASUREMENTS]

    /**
     * Locked as a set: nothing reads an estate role's ordinal, and what matters is that widening
     * this is widening what somebody can be granted.
     */
    def "the estate has exactly the two roles declared"() {
        expect:
        EstateRole.values() as Set == [EstateRole.STEWARD, EstateRole.WATCHER] as Set
    }

    /**
     * A contract with the reader, which shows each role somebody holds under this spelling. A
     * constant renamed without its spelling staying put would leave both sides compiling and every
     * role on every row shown as one the reader has never heard of.
     */
    def "each role is published under the spelling the reader shows it by"() {
        given:
        def expected = [
                (EstateRole.STEWARD): "steward",
                (EstateRole.WATCHER): "watcher",
        ]

        expect:
        EstateRole.values().collectEntries { [(it): it.published()] } == expected
    }

    /**
     * Written as a total map rather than a data table, so a role added later carries whatever
     * bundle it was given here rather than whatever nobody wrote a row for.
     */
    def "each role bundles the acts its standing is for and no others"() {
        given:
        def expected = [
                (EstateRole.STEWARD): STEWARD_ACTS as Set,
                (EstateRole.WATCHER): WATCHER_ACTS as Set,
        ]

        expect:
        EstateRole.values().collectEntries { [(it): it.acts()] } == expected
    }

    /**
     * The standing-in for a protection that cannot be tested directly. Where somebody stands is read
     * off the roles they hold and never off the acts folded from them, because a role bundling
     * nothing would still put them on the estate while an emptiness would take them off it. No such
     * role can be built — this is an enum — so what is asserted is that none exists, and the day
     * this reddens is the day that protection starts deciding something.
     */
    def "every role bundles at least one act, which is what keeps the empty bundle out of reach"() {
        expect:
        EstateRole.values().every { !it.acts().isEmpty() }

        and: "asserted of a table that has roles in it, rather than passing over an empty one"
        EstateRole.values().length > 0
    }

    /**
     * The ruling, not a shape the table happens to have: no role holds both an act that grants — an
     * estate role, or the first role in a group — and the one that reads what the estate measures.
     */
    def "no role both grants and reads what the estate measures"() {
        given:
        def granting = [EstateAct.GRANT_ESTATE_ROLE, EstateAct.KEEP_GROUP_REGISTER] as Set

        expect:
        EstateRole.values().every {
            !(it.acts().contains(EstateAct.READ_MEASUREMENTS) && !it.acts().intersect(granting).isEmpty())
        }

        and: "over a table where some role grants and some role reads, so neither half passes by being absent"
        EstateRole.values().any { !it.acts().intersect(granting).isEmpty() }
        EstateRole.values().any { it.acts().contains(EstateAct.READ_MEASUREMENTS) }
    }

    /**
     * Were one bundle to contain another, holding the wider would silently confer the narrower and
     * the second grant would stop being a decision.
     */
    def "no role's bundle contains another's"() {
        given:
        def pairs = [EstateRole.values().toList(), EstateRole.values().toList()].combinations()
                .findAll { one, other -> one != other }

        expect:
        pairs.every { one, other -> !one.acts().containsAll(other.acts()) }

        and: "over a table holding more than one role, so the pairs are not an empty list passing"
        !pairs.isEmpty()

        and: "while between them they publish the whole vocabulary, so no act is granted by nobody"
        EstateRole.values().collectMany { it.acts() } as Set == EstateAct.values() as Set
    }

    /** What granting or withdrawing an estate role answers with is the person as the pool reads them. */
    def "every role that grants an estate role also keeps the pool"() {
        given:
        def granting = EstateRole.values().findAll { it.acts().contains(EstateAct.GRANT_ESTATE_ROLE) }

        expect:
        granting.every { it.acts().contains(EstateAct.KEEP_POOL) }

        and: "over a table where some role does grant, so the rule is not passed by nobody"
        !granting.isEmpty()
    }

    /**
     * Roles add grants, so two of them are read as the union. Unlike a group's, these bundles form
     * no chain, so the fold decides something: neither role alone answers what both together do.
     */
    def "the acts of a holding are the union of what its roles bundle"() {
        expect:
        EstateRole.actsOf(held) == reached as Set

        where:
        held                                        || reached
        []                                          || []
        [EstateRole.STEWARD]                        || STEWARD_ACTS
        [EstateRole.WATCHER]                        || WATCHER_ACTS
        [EstateRole.STEWARD, EstateRole.WATCHER]    || STEWARD_ACTS + WATCHER_ACTS
    }

    def "a holding a caller was handed cannot be widened through the set it came back in"() {
        given:
        def reached = EstateRole.actsOf([EstateRole.WATCHER])

        when:
        reached.add(EstateAct.KEEP_POOL)

        then:
        thrown(UnsupportedOperationException)

        and: "and what the watcher reaches is intact rather than half-widened"
        reached == WATCHER_ACTS as Set
    }

    def "a role's own bundle cannot be widened either, which would reach every holder of it"() {
        when:
        EstateRole.STEWARD.acts().add(EstateAct.CHECK_SOUNDNESS)

        then:
        thrown(UnsupportedOperationException)

        and:
        EstateRole.STEWARD.acts() == STEWARD_ACTS as Set
    }

    /**
     * Refused by name rather than as the bare dereference the fold would raise, which names neither
     * the type nor which of the two levels was holding nothing.
     */
    def "a holding with a hole at either level is refused by name rather than inside the fold"() {
        when:
        EstateRole.actsOf(roles)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        roles                          || message
        null                           || "EstateRole roles must not be null"
        [EstateRole.STEWARD, null]     || "EstateRole roles must not hold a null role"
    }

    /** Written as a total map over the acts, so an act added later is read off the bundles and not left out. */
    def "the roles reaching an act are exactly those whose bundle holds it"() {
        given:
        def expected = [
                (EstateAct.KEEP_POOL)          : [EstateRole.STEWARD] as Set,
                (EstateAct.GRANT_ESTATE_ROLE)  : [EstateRole.STEWARD] as Set,
                (EstateAct.KEEP_GROUP_REGISTER): [EstateRole.STEWARD] as Set,
                (EstateAct.CHECK_SOUNDNESS)    : [EstateRole.WATCHER] as Set,
                (EstateAct.READ_MEASUREMENTS)  : [EstateRole.WATCHER] as Set,
        ]

        expect:
        EstateAct.values().collectEntries { [(it): EstateRole.reaching(it)] } == expected
    }

    def "the roles reaching an act come back in a set that cannot be widened"() {
        given:
        def reaching = EstateRole.reaching(EstateAct.CHECK_SOUNDNESS)

        when:
        reaching.add(EstateRole.STEWARD)

        then:
        thrown(UnsupportedOperationException)

        and:
        reaching == [EstateRole.WATCHER] as Set
    }

    def "asking which roles reach no act at all is refused by name"() {
        when:
        EstateRole.reaching(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "EstateRole act must not be null"
    }
}
