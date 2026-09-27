package org.lilradish.lite.domain.identity

import java.lang.reflect.Modifier
import java.time.Instant
import spock.lang.Specification

class DelegationSpec extends Specification {

    static final SubjectId ADA = new SubjectId(UUID.fromString("a0000000-0000-4000-8000-000000000001"))
    static final SubjectId BOB = new SubjectId(UUID.fromString("a0000000-0000-4000-8000-000000000002"))
    static final SubjectId LABEL = new SubjectId(UUID.fromString("de1e0000-0000-4000-8000-000000000001"))
    static final UUID DELEGATION_ID = UUID.fromString("0d1f2a3b-4c5d-6e7f-8a9b-0c1d2e3f4a5b")
    static final Instant EXPIRY = Instant.parse("2026-01-01T00:00:00Z")
    static final Instant REVOCATION = EXPIRY.minusSeconds(3600)
    static final Instant NOW = EXPIRY.minusSeconds(7200)

    static final Scope.Group FINANCE =
            new Scope.Group(new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000002")))
    static final Scope.Group CUSTOMER_SERVICE =
            new Scope.Group(new GroupId(UUID.fromString("5e1f0000-0000-4000-8000-000000000001")))

    static final Set<GroupPermission> DECLARED = Set.of(GroupPermission.START_RUN)

    /** The smallest holding that covers {@link #DECLARED}, so a delegation drawn on it is granted it whole. */
    static final Set<GroupRole> HELD_ROLES = Set.of(GroupRole.OPERATOR)

    /**
     * The permission sets a registration is drawn from. Immutable, so one feature narrowing or
     * widening a shared set cannot decide what a later one is handed.
     */
    static final List<Set<GroupPermission>> DECLARATIONS = List.of(
            Set.of(),
            Set.of(GroupPermission.START_RUN),
            Set.of(GroupPermission.START_RUN, GroupPermission.READ_OWN_RUNS),
            Set.of(GroupPermission.READ_ALL_RUNS, GroupPermission.REVIEW_AT_GATE))

    /**
     * The holdings an owner is drawn from, chosen so that each declaration above is sometimes the
     * wider side and sometimes the narrower: an owner holds a bundle of roles, so the declaration is
     * the only side that can be written down permission by permission.
     */
    static final List<Set<GroupRole>> HOLDINGS = List.of(
            Set.of(GroupRole.OPERATOR),
            Set.of(GroupRole.OPERATOR, GroupRole.OVERSEER),
            Set.of(GroupRole.OWNER))

    static Delegation.Registration registrationOf(Set<GroupPermission> declaredPermissions) {
        registeredTo(ADA, FINANCE, declaredPermissions)
    }

    static Delegation.Registration registeredTo(SubjectId owner, Scope.Group group, Set<GroupPermission> declared) {
        new Delegation.Registration(DELEGATION_ID, LABEL, owner, group, EXPIRY, null, declared)
    }

    static Delegation.Registration revokedAt(Instant revokedAt) {
        new Delegation.Registration(DELEGATION_ID, LABEL, ADA, FINANCE, EXPIRY, revokedAt, DECLARED)
    }

    /** A delegation narrows what its owner holds in one group; nothing the estate grants is in reach of one. */
    static final Set<EstateRole> NO_ESTATE_ROLE = [] as Set

    static HumanPrincipal ownerHolding(Set<GroupRole> roles) {
        HumanPrincipal.of(ADA, NO_ESTATE_ROLE, [(FINANCE): roles])
    }

    /**
     * What makes the bounds below a property of the type rather than of one route into it, and it
     * takes both halves: there is a single constructor and it is private; and every method anywhere
     * in the nest that answers with a delegation — a nest-mate reaches that constructor too, and a
     * second factory need not be called {@code of} — is the one factory, reached with a registration,
     * an owner and the instant the call is decided against and nothing else, so no permission arrives
     * from a third side and no caller supplies the time bound after the fact.
     *
     * <p>Synthetic methods are left out: the build weaves its own into this very class, and one of
     * them answering with a delegation would read here as a second factory nobody wrote.
     */
    def "a delegation is built through the factory alone, from a registration, an owner and an instant"() {
        given:
        def constructors = Delegation.class.declaredConstructors
        def factories = (Delegation.class.declaredMethods + Delegation.Registration.class.declaredMethods)
                .findAll { it.returnType == Delegation && !it.synthetic }

        expect:
        constructors.length == 1
        Modifier.isPrivate(constructors[0].modifiers)

        and: "while the one method that answers with a delegation is the public factory it reads as"
        factories.size() == 1
        factories[0].name == "of"
        Modifier.isStatic(factories[0].modifiers)
        Modifier.isPublic(factories[0].modifiers)
        factories[0].parameterTypes*.simpleName == ["Registration", "HumanPrincipal", "Instant"]

        and: "and a registration declares no roles either, so the owner is the only source of those"
        Delegation.Registration.class.recordComponents*.name ==
                ["delegationId", "label", "owner", "group", "expiresAt", "revokedAt", "declaredPermissions"]
    }

    /**
     * The owner is the sole source of the permissions, so one that is not there is refused at the
     * door rather than dereferenced while the intersection is being taken — where the failure would
     * name nothing and would happen before any constructor that could name it runs.
     */
    def "a delegation built from a registration, an owner or an instant that is not there is refused by name"() {
        when:
        Delegation.of(registration, owner, now)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        and: "and it is refused before the declaration is intersected, rather than inside it"
        !refused.stackTrace.any { it.className == HumanPrincipal.name }

        where:
        registration              | owner                    | now  || message
        null                      | ownerHolding(HELD_ROLES) | NOW  || "Delegation registration must not be null"
        registrationOf(DECLARED)  | null                     | NOW  || "Delegation owner must not be null"
        registrationOf(DECLARED)  | ownerHolding(HELD_ROLES) | null || "Delegation now must not be null"
    }

    /**
     * The permissions are taken from the owner handed in, while the call is charged to the person
     * the registration names. Were those two allowed to differ, one person would be charged for what
     * another was granted, and the bounds the rest of this file checks would hold of nobody.
     */
    def "a registration registered to somebody else is refused rather than exercised in their name"() {
        given:
        def registration = registeredTo(BOB, FINANCE, DECLARED)

        when:
        Delegation.of(registration, ownerHolding(HELD_ROLES), NOW)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Delegation is registered to " + BOB.value() + ", not to " + ADA.value()

        when:
        def held = Delegation.of(registration, HumanPrincipal.of(BOB, NO_ESTATE_ROLE, [(FINANCE): HELD_ROLES]), NOW)

        then: "the person it names does build one from it, so a mismatch is refused rather than the registration"
        held.accountableSubject() == BOB
        held.permissions(FINANCE) == DECLARED
    }

    /**
     * The third bound, and the one a caller would otherwise have to remember to ask about: a spent
     * registration yields no delegation at all rather than a fully granted one whose holder is
     * trusted to check the time afterwards. An expiry and a revocation are refused the same way,
     * which is what folding them into one predicate is for.
     */
    def "a registration that is no longer live yields no delegation, whichever way it ended"() {
        when:
        Delegation.of(revokedAt(revocation), ownerHolding(HELD_ROLES), now)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Delegation " + LABEL.value() + " is no longer live at " + now

        and: "and nothing was granted along the way, the refusal standing before the declaration is intersected"
        !refused.stackTrace.any { it.className == HumanPrincipal.name }

        where:
        revocation | now
        null       | EXPIRY
        null       | EXPIRY.plusSeconds(1)
        REVOCATION | REVOCATION
        REVOCATION | EXPIRY.minusSeconds(1)
    }

    def "a registration still live yields the delegation it was registered for"() {
        expect:
        Delegation.of(revokedAt(null), ownerHolding(HELD_ROLES), EXPIRY.minusSeconds(1))
                .permissions(FINANCE) == DECLARED

        and: "and one revoked later is live until then, so the revocation narrows the life rather than ending it"
        Delegation.of(revokedAt(REVOCATION), ownerHolding(HELD_ROLES), REVOCATION.minusSeconds(1))
                .permissions(FINANCE) == DECLARED
    }

    /**
     * The fourth bound. A delegation narrows what its owner holds somewhere, so one drawn where its
     * owner stands in nothing narrows nothing: it would be a principal that could act nowhere and
     * still read as a live delegation. Refused at the door for the same reason a spent registration
     * is, rather than yielding one that answers emptily wherever it is asked.
     */
    def "a registration drawn in a group its owner does not stand in yields no delegation"() {
        when:
        Delegation.of(registrationOf(DECLARED),
                HumanPrincipal.of(ADA, NO_ESTATE_ROLE, [(CUSTOMER_SERVICE): HELD_ROLES]), NOW)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message ==
                "Delegation " + LABEL.value() + " is registered in a group " + ADA.value() + " does not stand in"

        and: "while the same registration drawn on an owner who does stand there yields one"
        Delegation.of(registrationOf(DECLARED), ownerHolding(HELD_ROLES), NOW)
                .permissions(FINANCE) == DECLARED
    }

    /**
     * Every pairing of a declaration with an owner's holding, so neither side is only ever the wider
     * one: the equality is what says the result is the intersection rather than merely a subset of
     * one.
     */
    def "a delegation is granted what the declaration and its owner both hold there, and nothing else"() {
        expect:
        [DECLARATIONS, HOLDINGS].combinations().every { declared, held ->
            Delegation.of(registrationOf(declared), ownerHolding(held), NOW).permissions(FINANCE) ==
                    declared.intersect(GroupRole.permissionsOf(held))
        }

        and: "so a declaration naming what its owner does not hold is narrowed rather than granted"
        Delegation.of(registrationOf([GroupPermission.START_RUN, GroupPermission.REVIEW_AT_GATE] as Set),
                ownerHolding(HELD_ROLES), NOW).permissions(FINANCE) == [GroupPermission.START_RUN] as Set

        and: "and an owner holding what was never declared for it does not pass that along either"
        Delegation.of(registrationOf([GroupPermission.START_RUN] as Set),
                ownerHolding([GroupRole.OPERATOR, GroupRole.OVERSEER] as Set), NOW)
                .permissions(FINANCE) == [GroupPermission.START_RUN] as Set
    }

    /**
     * The sentence one grant across every group could not say. The owner holds the same role in two
     * groups and delegates in one of them; a delegation bounded without a group would have carried
     * the narrowed grant into the other, and the unnarrowed roles with it.
     */
    def "a delegation narrows within the one group it was registered for and holds nothing outside it"() {
        given:
        def owner = HumanPrincipal.of(ADA, NO_ESTATE_ROLE,
                [(FINANCE): [GroupRole.OVERSEER] as Set, (CUSTOMER_SERVICE): [GroupRole.OVERSEER] as Set])

        when:
        def delegation = Delegation.of(registrationOf([GroupPermission.REVIEW_AT_GATE] as Set), owner, NOW)

        then:
        delegation.scopes() == [FINANCE] as Set
        delegation.may(GroupPermission.REVIEW_AT_GATE, FINANCE)

        and: "while its owner's identical standing in the other group goes undelegated"
        owner.may(GroupPermission.REVIEW_AT_GATE, CUSTOMER_SERVICE)
        !delegation.may(GroupPermission.REVIEW_AT_GATE, CUSTOMER_SERVICE)
        delegation.roles(CUSTOMER_SERVICE).isEmpty()

        and: "and it can see nothing there either, the row filter being the axis the grant does not answer"
        !delegation.scopes().contains(CUSTOMER_SERVICE)
    }

    def "a caller cannot widen where a delegation may be exercised through the set it is handed"() {
        given:
        def delegation = Delegation.of(registrationOf(DECLARED), ownerHolding(HELD_ROLES), NOW)

        when:
        delegation.scopes().add(CUSTOMER_SERVICE)

        then:
        thrown(UnsupportedOperationException)

        and: "and it is still exercisable exactly where it was registered"
        delegation.scopes() == [FINANCE] as Set
    }

    def "a delegation holds its owner's roles there unnarrowed, being exercised in the owner's name"() {
        given:
        def owner = ownerHolding([GroupRole.OPERATOR, GroupRole.OVERSEER] as Set)

        when:
        def delegation = Delegation.of(registrationOf([] as Set), owner, NOW)

        then:
        delegation.roles(FINANCE) == owner.roles(FINANCE)

        and: "while the declaration it was drawn with still bounds it, so nothing was widened alongside them"
        delegation.permissions(FINANCE).isEmpty()
        !owner.permissions(FINANCE).isEmpty()
    }

    /**
     * The sharpest edge of carrying the roles across whole: the two halves disagree on purpose, so
     * folding the roles back into permissions — which reads as a simplification, and agrees wherever
     * a person is the principal — hands a delegation back exactly what it was registered without.
     */
    def "what a delegation may do is narrower than its roles bundle, so recomputing from them would widen it"() {
        given:
        def delegation = Delegation.of(registrationOf(DECLARED), ownerHolding(HELD_ROLES), NOW)
        def recomputed = GroupRole.permissionsOf(delegation.roles(FINANCE))

        expect:
        delegation.permissions(FINANCE) != recomputed

        and: "the roles reaching strictly further, so the declaration is what decided and not the fold"
        recomputed.containsAll(delegation.permissions(FINANCE))
        !delegation.permissions(FINANCE).containsAll(recomputed)

        and: "while the owner it was drawn on has the two agreeing, which is what keeps this invisible"
        def owner = ownerHolding(HELD_ROLES)
        owner.permissions(FINANCE) == GroupRole.permissionsOf(owner.roles(FINANCE))
    }

    def "a caller cannot widen what a delegation was bounded to through the sets it is handed"() {
        given:
        def delegation = Delegation.of(registrationOf(DECLARED), ownerHolding(HELD_ROLES), NOW)

        when:
        delegation.permissions(FINANCE).add(GroupPermission.REVIEW_AT_GATE)

        then:
        thrown(UnsupportedOperationException)

        when:
        delegation.roles(FINANCE).add(GroupRole.OVERSEER)

        then:
        thrown(UnsupportedOperationException)

        and: "and neither the grant nor the roles it was bounded to was half-widened in the attempt"
        !delegation.may(GroupPermission.REVIEW_AT_GATE, FINANCE)
        !delegation.roles(FINANCE).contains(GroupRole.OVERSEER)
    }

    def "an owner edited after the delegation was built is not the owner it was built from"() {
        given:
        def roles = [GroupRole.OPERATOR] as Set
        def delegation = Delegation.of(
                registrationOf(DECLARED), HumanPrincipal.of(ADA, NO_ESTATE_ROLE, [(FINANCE): roles]), NOW)

        when:
        roles.add(GroupRole.OVERSEER)

        then:
        !delegation.roles(FINANCE).contains(GroupRole.OVERSEER)
        !delegation.may(GroupPermission.REVIEW_AT_GATE, FINANCE)

        and: "while what it was built from is still held, so the set was copied rather than emptied"
        delegation.roles(FINANCE) == [GroupRole.OPERATOR] as Set
        delegation.permissions(FINANCE) == [GroupPermission.START_RUN] as Set
    }

    def "a delegation carries what it was registered as, so a holder can ask whether it is spent"() {
        given:
        def delegation = Delegation.of(registrationOf(DECLARED), ownerHolding(HELD_ROLES), NOW)

        expect:
        !delegation.registration().isLive(EXPIRY)

        and: "and the same registration answers for the moment before it, so the question is asked rather than fixed"
        delegation.registration().isLive(EXPIRY.minusSeconds(1))
    }

    def "a delegation acts under its own label while the call is charged to the owner behind it"() {
        given:
        def delegation = Delegation.of(registrationOf(DECLARED), ownerHolding(HELD_ROLES), NOW)

        expect:
        delegation.subject() == LABEL
        delegation.accountableSubject() == ADA

        and: "and the two answers differ, which is what asking both exists for"
        delegation.subject() != delegation.accountableSubject()
    }

    def "a delegation may surface what it read, because a person reads its output as an answer"() {
        expect:
        Delegation.of(registrationOf(DECLARED), ownerHolding(HELD_ROLES), NOW).maySurfaceContent()

        and: "unlike an actor that exists in code, so the answer is decided per shape rather than fixed"
        !SystemPrincipal.WORKFLOW_RUNNER.maySurfaceContent()
    }

    def "a delegation asked about no group at all is refused by name rather than answered for nowhere"() {
        when:
        Delegation.of(registrationOf(DECLARED), ownerHolding(HELD_ROLES), NOW).permissions(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Delegation group must not be null"
    }

    def "a registration missing what identifies, places or bounds it is refused by name"() {
        when:
        new Delegation.Registration(delegationId, label, owner, group, expiresAt, null, DECLARED)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        delegationId  | label | owner | group   | expiresAt || message
        null          | LABEL | ADA   | FINANCE | EXPIRY    || "Registration delegationId must not be null"
        DELEGATION_ID | null  | ADA   | FINANCE | EXPIRY    || "Registration label must not be null"
        DELEGATION_ID | LABEL | null  | FINANCE | EXPIRY    || "Registration owner must not be null"
        DELEGATION_ID | LABEL | ADA   | null    | EXPIRY    || "Registration group must not be null"
        DELEGATION_ID | LABEL | ADA   | FINANCE | null      || "Registration expiresAt must not be null"
    }

    def "a registration declaring no permissions at all is refused by name rather than dereferenced in the copy"() {
        when:
        new Delegation.Registration(DELEGATION_ID, LABEL, ADA, FINANCE, EXPIRY, null, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Registration declaredPermissions must not be null"
    }

    /**
     * A declaration holding nothing where a permission should be is refused by name rather than as
     * the bare dereference the copy would raise, which names neither the type nor what it was
     * holding.
     */
    def "a declaration holding a permission that is not there is refused by name rather than inside the copy"() {
        when:
        new Delegation.Registration(DELEGATION_ID, LABEL, ADA, FINANCE, EXPIRY, null,
                [GroupPermission.START_RUN, null] as Set)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Registration declaredPermissions must not hold a null permission"
    }

    def "a registration edited through the permissions it was declared with keeps the ones it was declared with"() {
        given:
        def declared = [GroupPermission.START_RUN] as Set
        def registration = registrationOf(declared)

        when:
        declared.add(GroupPermission.REVIEW_AT_GATE)

        then:
        !registration.declaredPermissions().contains(GroupPermission.REVIEW_AT_GATE)

        and: "and a delegation built from it is granted what was declared rather than what was edited in"
        Delegation.of(registration, ownerHolding(GroupRole.values() as Set), NOW).permissions(FINANCE) ==
                [GroupPermission.START_RUN] as Set
    }

    /**
     * One predicate for two ways of ending, and both boundaries are exclusive: a registration is not
     * exercised on the instant it is spent. The revocation is checked against a registration that
     * has not expired and the expiry against one that was never revoked, so neither answer can be
     * the other one standing in for it.
     */
    def "a registration is live until the earlier of its expiry and its revocation, and not on either"() {
        expect:
        revokedAt(revocation).isLive(now) == live

        where:
        revocation | now                        || live
        null       | EXPIRY.minusSeconds(1)     || true
        null       | EXPIRY                     || false
        null       | EXPIRY.plusSeconds(1)      || false
        REVOCATION | REVOCATION.minusSeconds(1) || true
        REVOCATION | REVOCATION                 || false
        REVOCATION | REVOCATION.plusSeconds(1)  || false
    }

    /**
     * An instant nobody established is not a moment a delegation can be judged spent or live at, and
     * the answer must not be read off a comparison whose failure names the JDK rather than the caller.
     */
    def "an instant that is not there at all is refused by name rather than compared against"() {
        when:
        registrationOf(DECLARED).isLive(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Registration now must not be null"
    }
}
