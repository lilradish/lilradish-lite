package org.lilradish.lite.domain.identity

import spock.lang.Specification

class ScopedPrincipalSpec extends Specification {

    static final SubjectId ADA = new SubjectId(UUID.fromString("a0000000-0000-4000-8000-000000000001"))
    static final Scope.Group FINANCE =
            new Scope.Group(new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000002")))
    static final Scope.Group LEGAL =
            new Scope.Group(new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000003")))

    /**
     * The split the rest of this package rests on: an actor that exists in code answers who it is
     * and whether it may surface what it read, and is not on the axis at all — so a scoped question
     * put to one does not compile rather than coming back empty and reading like an answer.
     */
    def "the shapes that stand in a scope are the person and the delegation drawn on one, and not the actor in code"() {
        expect:
        ScopedPrincipal.permittedSubclasses.toList() == [HumanPrincipal, Delegation]

        and: "which together with the actor in code is the whole of what a principal can be"
        Principal.permittedSubclasses.toList() == [SystemPrincipal, ScopedPrincipal]
    }

    /**
     * Written as a total map rather than a data table: a member added to either side would carry
     * whatever signature it liked, and one that a role decides but that takes no group is exactly
     * the question with no answer this split exists to make unwritable.
     *
     * <p>Both types in those signatures are load-bearing. What a role decides takes a
     * {@link Scope.Group}, so a role read in the estate is not a wrong answer but an unwritable
     * question; and the permission is the group's vocabulary, never the estate's. Where rows may be
     * seen keeps answering in {@link Scope},
     * because that is what a resource binds to.
     */
    def "what a role decides is asked with a group, while where the caller stands is answered in scopes"() {
        expect:
        ScopedPrincipal.declaredMethods.findAll { !it.synthetic }
                .collectEntries { [(it.name): it.parameterTypes.toList()] } ==
                [scopes: [], permissions: [Scope.Group], roles: [Scope.Group],
                 may: [GroupPermission, Scope.Group]]

        and: "while nothing left on the principal itself takes one, those three answering the same everywhere"
        Principal.declaredMethods.findAll { !it.synthetic }
                .collectEntries { [(it.name): it.parameterTypes.toList()] } ==
                [subject: [], accountableSubject: [], maySurfaceContent: []]
    }

    /**
     * The estate's vocabulary is asked of the person and of nothing else. A delegation is exercised
     * in its owner's name inside one group and holds no estate role, so the question put to one has
     * only an emptiness for an answer — which would read as a standing rather than as the absence of
     * the axis, the very reason these questions were split off {@link Principal} to begin with.
     */
    def "what the estate grants is asked of a person alone, and not of every shape that stands in a scope"() {
        expect:
        HumanPrincipal.declaredMethods.any { !it.synthetic && it.name == "estateReach" }

        and:
        ScopedPrincipal.declaredMethods.every { it.name != "estateReach" }
        Delegation.declaredMethods.every { it.name != "estateReach" }

        and: "so it cannot be reached through the interface at all, which is what makes that structural"
        !ScopedPrincipal.methods*.name.contains("estateReach")
    }

    /**
     * The membership test behind {@code may} is read off the same fold the permissions are, so
     * there is no second statement of what a group granted that could disagree with the first.
     */
    def "whether a permission is held in a group is read off what that group folded, in both directions"() {
        given:
        def person = HumanPrincipal.of(ADA, [] as Set, [(FINANCE): [GroupRole.OVERSEER] as Set])

        expect:
        GroupPermission.values().every { person.may(it, FINANCE) == person.permissions(FINANCE).contains(it) }

        and: "in a group they stand nowhere in as much as in the one they do"
        GroupPermission.values().every { person.may(it, LEGAL) == person.permissions(LEGAL).contains(it) }

        and: "and neither side is vacuous, one group granting something and the other nothing at all"
        GroupPermission.values().any { person.may(it, FINANCE) }
        GroupPermission.values().every { !person.may(it, LEGAL) }
    }
}
