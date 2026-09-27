package org.lilradish.lite.domain.identity

import java.lang.reflect.Modifier
import spock.lang.Specification

/**
 * What makes the two parts a property of the type rather than of one route into it takes both
 * halves: a single private constructor, and every method answering with a standing being one of the
 * two named factories. A component list would let a third combination be written down, and one of
 * the two factories exists precisely to build a standing whose parts disagree.
 *
 * <p>Synthetic methods are left out of that reading: the build weaves its own into this very class,
 * and one of them answering with a standing would read here as a factory nobody wrote.
 *
 * <p>The copy the folding factory takes is what a caller could otherwise widen the roles through
 * after the fold had been taken from them, leaving a standing whose roles say one thing and whose
 * permissions say another — the exact disagreement this type exists to make unwritable.
 */
class StandingSpec extends Specification {

    static final Set<GroupPermission> DECLARED = [GroupPermission.START_RUN, GroupPermission.READ_OWN_RUNS] as Set

    static EnumSet<GroupRole> holding(GroupRole... roles) {
        roles.length == 0 ? EnumSet.noneOf(GroupRole.class) : EnumSet.copyOf(roles.toList())
    }

    def "a standing is built through its two named factories alone, and through no component list"() {
        given:
        def constructors = Standing.class.declaredConstructors
        def factories = Standing.class.declaredMethods.findAll { it.returnType == Standing && !it.synthetic }

        expect:
        constructors.length == 1
        Modifier.isPrivate(constructors[0].modifiers)

        and: "and the two that answer with one are named for what each of them makes"
        factories*.name.toSorted() == ["narrowedTo", "of"]

        and: "neither of them reachable from outside the package that folds a holding"
        factories.every { !Modifier.isPublic(it.modifiers) }
        !Modifier.isPublic(Standing.class.modifiers)
    }

    /**
     * The answer a group nobody stands in is given. Held rather than folded per ask, which is what
     * lets every such group be answered with one instance instead of a fresh emptiness each time.
     */
    def "a group nobody stands in is the one held standing, holding nothing at all"() {
        expect:
        Standing.NOWHERE.roles().isEmpty()
        Standing.NOWHERE.permissions().isEmpty()

        and: "and it is one instance rather than an emptiness built wherever it is wanted"
        Standing.NOWHERE.permissions().is(Standing.NOWHERE.permissions())
        Standing.NOWHERE.roles().is(Standing.NOWHERE.roles())
    }

    /**
     * Written over every role rather than a chosen few, so a role added to the table carries its
     * bundle into this fold rather than being missed by whichever rows somebody wrote out.
     */
    def "a holding folds to exactly what its roles bundle, the two parts agreeing"() {
        expect:
        GroupRole.values().collectEntries { [(it): Standing.of(holding(it)).permissions()] } ==
                GroupRole.values().collectEntries { [(it): it.permissions()] }

        and: "and the roles it kept are the ones it was folded from, rather than whatever the fold implies"
        GroupRole.values().every { Standing.of(holding(it)).roles() == [it] as Set }
    }

    def "a standing edited through the holding it was folded from is not the standing that was folded"() {
        given:
        def held = holding(GroupRole.OPERATOR)
        def standing = Standing.of(held)

        when:
        held.add(GroupRole.OWNER)

        then:
        standing.roles() == [GroupRole.OPERATOR] as Set

        and: "with the fold still agreeing with the roles it kept, rather than with the widened set"
        standing.permissions() == GroupRole.OPERATOR.permissions()
        !standing.permissions().contains(GroupPermission.CHANGE_MEMBERSHIP)
    }

    def "a holding that is not there at all is refused by name rather than dereferenced in the fold"() {
        when:
        Standing.of(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Standing held must not be null"
    }

    /**
     * A narrowing grants the intersection and never the declaration: a registration naming what its
     * owner does not hold would otherwise be a way to grant a permission by asking for it.
     */
    def "a narrowing grants what the declaration and the holding both carry, and nothing either of them lacks"() {
        given:
        def narrowed = Standing.of(holding(GroupRole.OPERATOR))
                .narrowedTo(DECLARED + [GroupPermission.REVIEW_AT_GATE])

        expect:
        narrowed.permissions() == DECLARED

        and: "the declared permission the holding never carried being absent rather than granted"
        !narrowed.permissions().contains(GroupPermission.REVIEW_AT_GATE)

        and: "and what the holding carried but the declaration did not withheld as well"
        !narrowed.permissions().contains(GroupPermission.AUTHOR_ENTRY)
        Standing.of(holding(GroupRole.OPERATOR)).permissions().contains(GroupPermission.AUTHOR_ENTRY)
    }

    /**
     * The other part under a narrowing, and the reason this factory exists at all: the roles are
     * carried across whole because the narrowed standing is exercised in its holder's name, so the
     * two parts it hands back disagree on purpose.
     */
    def "a narrowing carries the roles across whole while the permissions it hands back are narrower"() {
        given:
        def held = Standing.of(holding(GroupRole.OPERATOR))
        def narrowed = held.narrowedTo(DECLARED)

        expect:
        narrowed.roles() == held.roles()

        and: "while what it may do stops short of what those very roles bundle"
        narrowed.permissions() != GroupRole.permissionsOf(narrowed.roles())
        GroupRole.permissionsOf(narrowed.roles()).containsAll(narrowed.permissions())
    }

    def "a narrowing missing what it narrows to is refused by name rather than compared against nothing"() {
        when:
        Standing.of(holding(GroupRole.OPERATOR)).narrowedTo(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Standing declaredPermissions must not be null"
    }

    /**
     * What a caller is handed back, from either factory. The fold behind the permissions is built
     * fresh and is the caller's to keep only through this wrapper — so the widening below has to be
     * refused rather than reaching every holder of the standing it came from.
     */
    def "a caller cannot widen a standing through either of the sets it is handed"() {
        given:
        def standing = built

        when:
        standing.permissions().add(GroupPermission.CHANGE_MEMBERSHIP)

        then:
        thrown(UnsupportedOperationException)

        when:
        standing.roles().add(GroupRole.OWNER)

        then:
        thrown(UnsupportedOperationException)

        and: "and neither what it grants nor what it holds was half-widened in the attempt"
        !standing.permissions().contains(GroupPermission.CHANGE_MEMBERSHIP)
        !standing.roles().contains(GroupRole.OWNER)

        where:
        built << [
            Standing.of(holding(GroupRole.OPERATOR)),
            Standing.of(holding(GroupRole.OPERATOR)).narrowedTo(DECLARED)
        ]
    }
}
