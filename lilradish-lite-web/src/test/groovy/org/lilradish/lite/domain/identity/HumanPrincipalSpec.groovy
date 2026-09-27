package org.lilradish.lite.domain.identity

import java.lang.reflect.Modifier
import spock.lang.Specification

class HumanPrincipalSpec extends Specification {

    static final SubjectId ADA = new SubjectId(UUID.fromString("a0000000-0000-4000-8000-000000000001"))

    static final Scope.Group CUSTOMER_SERVICE =
            new Scope.Group(new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000001")))
    static final Scope.Group FINANCE =
            new Scope.Group(new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000002")))
    static final Scope.Group LEGAL =
            new Scope.Group(new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000003")))
    static final Scope.Group MARKETING =
            new Scope.Group(new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000004")))
    static final Scope ESTATE = new Scope.Estate()

    /** Somebody granted nothing outside any group, which is what almost everybody here is. */
    static final Set<EstateRole> NO_ESTATE_ROLE = [] as Set

    /**
     * An overseer where they oversee and an operator where they occasionally help, and nothing in
     * legal. This is the shape the group argument exists for: a holding that answered the union
     * would carry the overseer's authority into the group they only operate in.
     */
    static HumanPrincipal ada() {
        HumanPrincipal.of(ADA, NO_ESTATE_ROLE,
                [(CUSTOMER_SERVICE): [GroupRole.OVERSEER] as Set, (FINANCE): [GroupRole.OPERATOR] as Set])
    }

    /**
     * What makes the fold a property of the type rather than of one route into it, and it takes
     * both halves: there is a single constructor and it is private; and every method that answers
     * with a person is the one factory, reached with a subject and the roles held in each group and
     * nothing else, so no permission arrives from a third side.
     *
     * <p>The fields are asserted beside the factory because the two say different things: the
     * parameters are what may be handed in, the fields are what nothing afterwards can reach or
     * reassign. What they are called is not asserted — that a fold is kept rather than retaken is
     * read off the answers, further down this file, where renaming one costs nothing.
     *
     * <p>Synthetic members are left out: the build weaves its own into this very class, and one of
     * them answering with a person would read here as a second factory nobody wrote.
     */
    def "a person is built through the factory alone, and keeps what each group folded rather than the roles alone"() {
        given:
        def constructors = HumanPrincipal.class.declaredConstructors
        def factories = HumanPrincipal.class.declaredMethods.findAll {
            it.returnType == HumanPrincipal && !it.synthetic
        }
        def fields = HumanPrincipal.class.declaredFields.findAll {
            !Modifier.isStatic(it.modifiers) && !it.synthetic
        }

        expect:
        constructors.length == 1
        Modifier.isPrivate(constructors[0].modifiers)

        and: "while the one method that answers with a person is the public factory it reads as"
        factories.size() == 1
        factories[0].name == "of"
        Modifier.isStatic(factories[0].modifiers)
        Modifier.isPublic(factories[0].modifiers)
        factories[0].parameterTypes*.simpleName == ["SubjectId", "Set", "Map"]

        and: "and nothing it folded is reachable or reassignable once the factory has handed it back"
        !fields.isEmpty()
        fields.every { Modifier.isFinal(it.modifiers) && Modifier.isPrivate(it.modifiers) }
    }

    def "a person missing who they are or what they hold where is refused by name"() {
        when:
        HumanPrincipal.of(subject, estateRoles, rolesByGroup)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        subject | estateRoles    | rolesByGroup || message
        null    | NO_ESTATE_ROLE | [:]          || "HumanPrincipal subject must not be null"
        ADA     | null           | [:]          || "HumanPrincipal estateRoles must not be null"
        ADA     | NO_ESTATE_ROLE | null         || "HumanPrincipal rolesByGroup must not be null"
    }

    /**
     * Refused by name rather than as the bare dereference the copy raises, which names neither the
     * type nor which of the two levels was holding nothing where something should have been.
     */
    def "a holding with a hole at either level is refused by name rather than inside the fold"() {
        when:
        HumanPrincipal.of(ADA, NO_ESTATE_ROLE, rolesByGroup)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        rolesByGroup                                  || message
        [(null): [GroupRole.OPERATOR] as Set]         || "HumanPrincipal rolesByGroup must not hold a null group"
        [(FINANCE): null]                             || "HumanPrincipal rolesByGroup must not hold a null role set"
        [(FINANCE): [GroupRole.OPERATOR, null] as Set] || "HumanPrincipal roles must not hold a null role"
    }

    /** Somebody holding nothing in a group is not one of its members, so no holding may say they are. */
    def "a holding naming a group with no role in it is refused rather than read as membership"() {
        when:
        HumanPrincipal.of(ADA, NO_ESTATE_ROLE, rolesByGroup)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "HumanPrincipal rolesByGroup must not hold a group with no role"

        where:
        rolesByGroup << [
                [(FINANCE): [] as Set],
                [(CUSTOMER_SERVICE): [GroupRole.OVERSEER] as Set, (FINANCE): [] as Set],
        ]
    }

    def "an estate holding with a hole in it is refused by name rather than inside the fold"() {
        when:
        HumanPrincipal.of(ADA, [EstateRole.STEWARD, null] as Set, [:])

        then:
        def refused = thrown(NullPointerException)
        refused.message == "HumanPrincipal estateRoles must not hold a null role"
    }

    def "a person edited through the holding they were built from is not the person that was built"() {
        given:
        def roles = [GroupRole.OPERATOR] as Set
        def holding = [(CUSTOMER_SERVICE): roles]
        def acrossTheEstate = [] as Set
        def person = HumanPrincipal.of(ADA, acrossTheEstate, holding)

        when:
        roles.add(GroupRole.OVERSEER)
        holding.put(FINANCE, [GroupRole.OWNER] as Set)
        acrossTheEstate.add(EstateRole.STEWARD)

        then:
        !person.roles(CUSTOMER_SERVICE).contains(GroupRole.OVERSEER)
        !person.may(GroupPermission.REVIEW_AT_GATE, CUSTOMER_SERVICE)
        !person.scopes().contains(FINANCE)

        and: "nor does the estate reach them, the axis they were built onto no group being theirs"
        person.estateReach().isEmpty()
        !person.scopes().contains(ESTATE)

        and: "while what they were built with is still held, so both levels were copied rather than emptied"
        person.roles(CUSTOMER_SERVICE) == [GroupRole.OPERATOR] as Set
        person.permissions(CUSTOMER_SERVICE) == GroupRole.OPERATOR.permissions()
    }

    def "a person is charged for their own calls rather than for somebody else's"() {
        given:
        def person = ada()

        expect:
        person.accountableSubject() == ADA

        and: "and it is the subject acting rather than a second name answered from elsewhere"
        person.accountableSubject() == person.subject()
    }

    /**
     * The membership axis, and the one the roles cannot answer. It is what decides which rows exist
     * to be acted on at all, before any role decides what may be done with them.
     */
    def "the scopes a person stands in are the ones they were given roles in, and no others"() {
        given:
        def person = ada()

        expect:
        person.scopes() == [CUSTOMER_SERVICE, FINANCE] as Set

        and: "a group they were given nothing in being absent, which is what keeps its rows out of their sight"
        !person.scopes().contains(LEGAL)

        and: "and the estate with it, a standing in every group they are in adding up to no standing over the system"
        !person.scopes().contains(ESTATE)
        person.scopes().every { it instanceof Scope.Group }
    }

    /**
     * The estate joining the membership axis, which is the whole of what a role held there does to
     * where somebody may see rows. It is beside the groups rather than above them: this person is in
     * no group at all and stands over the system, and ada() is in two groups and stands over nothing.
     */
    def "an estate role puts the estate among the scopes a person stands in, beside their groups"() {
        given:
        def steward = HumanPrincipal.of(ADA, [EstateRole.STEWARD] as Set, [(FINANCE): [GroupRole.OPERATOR] as Set])

        expect:
        steward.scopes() == [FINANCE, ESTATE] as Set

        and: "while somebody holding none of them stands in their groups and nowhere else"
        !ada().scopes().contains(ESTATE)

        and: "and the estate grants nothing in any group, the two axes meeting nowhere"
        steward.permissions(FINANCE) == GroupRole.OPERATOR.permissions()
        steward.roles(FINANCE) == [GroupRole.OPERATOR] as Set

        and: "while the estate it was put onto is the one held value rather than one built per person"
        steward.scopes().find { it instanceof Scope.Estate }.is(Scope.ESTATE)
    }

    /**
     * Written over every role rather than a chosen few, so a role added to the estate's table
     * carries its bundle here rather than being missed by whichever rows somebody wrote out.
     */
    def "what a person reaches across the estate is exactly what their estate roles bundle"() {
        expect:
        EstateRole.values().collectEntries {
            [(it): HumanPrincipal.of(ADA, [it] as Set, [:]).estateReach()]
        } == EstateRole.values().collectEntries { [(it): it.acts()] }

        and: "with both roles reaching the union, which neither of them reaches alone"
        HumanPrincipal.of(ADA, EstateRole.values() as Set, [:]).estateReach() == EstateAct.values() as Set

        and: "and somebody holding none of them reaching nothing, the fold being read rather than defaulted to"
        ada().estateReach().isEmpty()
    }

    /**
     * The estate's vocabulary is answered by the estate's accessor and by nothing else. A group
     * role reaching an act would make the estate something a group could accumulate into, and an
     * estate role granting inside a group would make it an authority over other people's work.
     */
    def "no group role reaches an estate act, and no estate role reaches into any group"() {
        given:
        def owner = HumanPrincipal.of(ADA, NO_ESTATE_ROLE, [(FINANCE): [GroupRole.OWNER] as Set])
        def steward = HumanPrincipal.of(ADA, [EstateRole.STEWARD, EstateRole.WATCHER] as Set, [:])

        expect:
        owner.estateReach().isEmpty()

        and:
        steward.permissions(FINANCE).isEmpty()
        steward.roles(FINANCE).isEmpty()

        and: "over holdings that do reach something, two emptinesses agreeing proving nothing"
        !owner.permissions(FINANCE).isEmpty()
        !steward.estateReach().isEmpty()
    }

    def "a caller cannot widen what a person reaches across the estate through the set they are handed"() {
        given:
        def steward = HumanPrincipal.of(ADA, [EstateRole.STEWARD] as Set, [:])

        when:
        steward.estateReach().add(EstateAct.CHECK_SOUNDNESS)

        then:
        thrown(UnsupportedOperationException)

        and: "and what they reach is intact rather than half-widened"
        steward.estateReach() == EstateRole.STEWARD.acts()
    }

    def "a caller cannot widen where a person stands through the set they are handed"() {
        given:
        def person = ada()

        when:
        person.scopes().add(LEGAL)

        then:
        thrown(UnsupportedOperationException)

        and: "and where they stand is intact rather than half-widened"
        person.scopes() == [CUSTOMER_SERVICE, FINANCE] as Set
    }

    /**
     * Written over every role rather than a chosen few: a role added later carries its bundle into
     * this fold, and a table would iterate only the rows somebody remembered to write.
     */
    def "the permissions a person holds in a group are exactly the ones their roles there bundle"() {
        expect:
        GroupRole.values().collectEntries {
            [(it): HumanPrincipal.of(ADA, NO_ESTATE_ROLE, [(FINANCE): [it] as Set]).permissions(FINANCE)]
        } == GroupRole.values().collectEntries { [(it): it.permissions()] }

        and: "and none of it reaches the group those roles were not held in"
        GroupRole.values().every {
            HumanPrincipal.of(ADA, NO_ESTATE_ROLE, [(FINANCE): [it] as Set]).permissions(LEGAL).isEmpty()
        }
    }

    /**
     * The whole of what the group argument buys, and the reason asking without one is a question
     * with no answer: the union of both groups is neither of these two standings.
     */
    def "a person overseeing one group and operating in another may in each only what they hold there"() {
        given:
        def person = ada()

        expect:
        person.may(GroupPermission.REVIEW_AT_GATE, CUSTOMER_SERVICE)
        person.may(GroupPermission.START_RUN, FINANCE)

        and: "while what only the overseer's bundle carries stops there, which is what the union would have spread"
        !person.may(GroupPermission.REVIEW_AT_GATE, FINANCE)

        and: "and the group they stand in nowhere near granting either of them"
        !person.may(GroupPermission.REVIEW_AT_GATE, LEGAL)
        !person.may(GroupPermission.START_RUN, LEGAL)
    }

    /**
     * The ruling the rest of this rests on. A group somebody is not a member of answers as holding
     * nothing, because what they may do there has a true answer — nothing — and refusing would make
     * every caller ask about membership first, which is a second statement of {@code scopes()} and a
     * failure rather than a refusal wherever it is forgotten.
     */
    def "a group a person is not a member of holds nothing at all"() {
        given:
        def person = ada()

        expect:
        person.roles(LEGAL).isEmpty()
        person.permissions(LEGAL).isEmpty()

        and: "while the group they do stand in answers with what they hold, so the floor is read rather than fixed"
        person.roles(CUSTOMER_SERVICE) == [GroupRole.OVERSEER] as Set
        !person.permissions(CUSTOMER_SERVICE).isEmpty()

        and: "and whether they are a member at all shows on the membership axis"
        !person.scopes().contains(LEGAL)
        person.scopes().contains(CUSTOMER_SERVICE)
    }

    /** Folded once per group at construction: a per-ask fold would make asking what a person holds a cost. */
    def "asking a person what they hold twice hands back the same set rather than folding again"() {
        given:
        def person = ada()

        expect:
        person.permissions(CUSTOMER_SERVICE).is(person.permissions(CUSTOMER_SERVICE))

        and: "and every group they stand nowhere in answers with one held emptiness rather than a fresh one"
        person.permissions(LEGAL).is(person.permissions(MARKETING))
    }

    def "a caller cannot widen a person through the sets they are handed"() {
        given:
        def person = ada()

        when:
        person.permissions(FINANCE).add(GroupPermission.REVIEW_AT_GATE)

        then:
        thrown(UnsupportedOperationException)

        when:
        person.roles(FINANCE).add(GroupRole.OVERSEER)

        then:
        thrown(UnsupportedOperationException)

        and: "and neither what they may do there nor what they hold there was half-widened in the attempt"
        !person.may(GroupPermission.REVIEW_AT_GATE, FINANCE)
        !person.roles(FINANCE).contains(GroupRole.OVERSEER)
    }

    def "a person may surface what they read, because a person is who an answer is read by"() {
        expect:
        HumanPrincipal.of(ADA, NO_ESTATE_ROLE, [:]).maySurfaceContent()

        and: "unlike an actor that exists in code, so the answer is decided per shape rather than fixed"
        !SystemPrincipal.WORKFLOW_RUNNER.maySurfaceContent()
    }

    def "a person asked about no group at all is refused by name rather than answered for nowhere"() {
        when:
        ada().permissions(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "HumanPrincipal group must not be null"
    }
}
