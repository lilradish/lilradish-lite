package org.lilradish.lite.domain.identity

import spock.lang.Specification

class ScopeSpec extends Specification {

    static final GroupId CUSTOMER_SERVICE = new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000001"))
    static final GroupId FINANCE = new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000002"))

    /**
     * Two cases and no third: what a resource binds to is a group or the system itself, and a third
     * added here would widen where everything in this package reads a holding from.
     */
    def "a scope is a group or the estate, and nothing else"() {
        expect:
        Scope.permittedSubclasses.toList() == [Scope.Group, Scope.Estate]
    }

    /**
     * Why this is sealed rather than a group identifier with one value standing for the estate. The
     * estate sits beside groups rather than above them, and separate kinds make "a group's
     * identifier used as the estate" unwritable instead of a value somebody has to know not to use.
     */
    def "the estate is never any group, whichever group is named"() {
        expect:
        new Scope.Estate() != new Scope.Group(CUSTOMER_SERVICE)
        new Scope.Estate() != new Scope.Group(FINANCE)

        and: "while two estates are the one scope, so what binds to the system is not several places"
        new Scope.Estate() == new Scope.Estate()
        new Scope.Estate().hashCode() == new Scope.Estate().hashCode()
    }

    /**
     * What makes a scope usable as the key a holding is read under: two readings of the same group
     * have to meet, and two different groups must never.
     */
    def "two scopes naming the same group are one scope, and two naming different groups are two"() {
        expect:
        new Scope.Group(CUSTOMER_SERVICE) == new Scope.Group(CUSTOMER_SERVICE)
        new Scope.Group(CUSTOMER_SERVICE).hashCode() == new Scope.Group(CUSTOMER_SERVICE).hashCode()

        and:
        new Scope.Group(CUSTOMER_SERVICE) != new Scope.Group(FINANCE)
    }

    /**
     * What makes the held estate safe where a held group would not be: a record with no components
     * has one value, so the constant is the whole of the type rather than one of its values singled
     * out. The day a component is added the constant stops compiling, which is the point of it.
     */
    def "the estate is held as one value because it is the only value it has"() {
        expect:
        Scope.Estate.recordComponents.length == 0

        and: "and what is held is that value rather than merely something of its kind"
        Scope.ESTATE == new Scope.Estate()

        and: "while a group is a value among many, which is why none of them is held anywhere"
        Scope.Group.recordComponents.length == 1
        new Scope.Group(CUSTOMER_SERVICE) != new Scope.Group(FINANCE)
    }

    def "a group scope that names no group at all is refused by name"() {
        when:
        new Scope.Group(null)

        then:
        def error = thrown(NullPointerException)
        error.message == "Group groupId must not be null"
    }
}
