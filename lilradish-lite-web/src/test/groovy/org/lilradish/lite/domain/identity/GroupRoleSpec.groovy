package org.lilradish.lite.domain.identity

import spock.lang.Specification

class GroupRoleSpec extends Specification {

    /** What a group's table gives its operator, and what every role above it carries too. */
    static final Set<GroupPermission> OPERATORS = [
            GroupPermission.READ_MEMBERSHIP,
            GroupPermission.START_RUN,
            GroupPermission.ANSWER_STEP,
            GroupPermission.READ_OWN_RUNS,
            GroupPermission.AUTHOR_ENTRY,
            GroupPermission.READ_INFERENCE_CONTENT,
    ] as Set

    /** What the table adds where somebody also oversees other people's work. */
    static final Set<GroupPermission> OVERSEERS = OPERATORS + ([
            GroupPermission.READ_ALL_RUNS,
            GroupPermission.REVIEW_AT_GATE,
            GroupPermission.APPROVE_ENTRY,
            GroupPermission.REVOKE_ENTRY,
    ] as Set)

    /** What nobody but an owner holds: changing who is in the group. */
    static final Set<GroupPermission> OWNERS_ALONE = [
            GroupPermission.CHANGE_MEMBERSHIP,
    ] as Set

    /**
     * Locked as a set rather than as a list: nothing reads a role's ordinal, so the order they are
     * declared in is not part of what anything holding a role may rely on. Widening this set is
     * widening what a person can be granted, which is why it is asserted at all — and a role added
     * here would have to be named wherever roles come to be held before the two could agree.
     */
    def "the roles a group starts with are exactly the three declared"() {
        expect:
        GroupRole.values() as Set == [GroupRole.OPERATOR, GroupRole.OVERSEER, GroupRole.OWNER] as Set
    }

    /**
     * Written as a total map, so a role added later carries the spelling it was given here. These are
     * what a reader is sent and what an address names a role by, so a constant renamed without its
     * spelling staying put would break every reader and every saved address at once.
     */
    def "each role is published under the spelling a reader knows it by, and no two share one"() {
        given:
        def expected = [
                (GroupRole.OPERATOR): "operator",
                (GroupRole.OVERSEER): "overseer",
                (GroupRole.OWNER): "owner",
        ]

        expect:
        GroupRole.values().collectEntries { [(it): it.published()] } == expected
    }

    /**
     * Written as a total map rather than a data table: a table iterates only the rows somebody
     * wrote, so a role added without one would grant whatever it liked, unexamined.
     */
    def "each role grants exactly the permissions the group's table gives it and no others"() {
        given:
        def expected = [
                (GroupRole.OPERATOR): OPERATORS,
                (GroupRole.OVERSEER): OVERSEERS,
                (GroupRole.OWNER): OVERSEERS + OWNERS_ALONE,
        ]

        expect:
        GroupRole.values().collectEntries { [(it): it.permissions()] } == expected
    }

    def "a caller cannot widen its own grant through the set the role hands it"() {
        given:
        def held = role.permissions() as Set

        when:
        role.permissions().add(GroupPermission.CHANGE_MEMBERSHIP)

        then:
        thrown(UnsupportedOperationException)

        and: "and the role is left holding what it held rather than a half-widened set"
        role.permissions() == held

        where:
        role << GroupRole.values()
    }

    /** Wrapped once when the constant is built: a per-call copy would make the set a cost to ask for. */
    def "asking a role twice hands back the same set rather than a fresh copy"() {
        expect:
        GroupRole.values().every { it.permissions().is(it.permissions()) }
    }

    /**
     * The owner carries the whole vocabulary, so a union taken over every role is satisfied by the
     * owner alone and would hold with the other two granting nothing at all. Read without the owner,
     * the union says what a group can do without the role it cannot do without, and the complement
     * names what it needs that role for.
     */
    def "what the roles beside the owner reach is the vocabulary less what only an owner may do"() {
        given:
        def withoutOwner = (GroupRole.values() as Set) - GroupRole.OWNER
        def reachable = withoutOwner.collectMany { it.permissions() } as Set

        expect:
        reachable == (GroupPermission.values() as Set) - OWNERS_ALONE

        and: "none of which any of them reaches, so the complement is the owner's alone rather than merely unlisted"
        withoutOwner.every { it.permissions().intersect(OWNERS_ALONE).isEmpty() }

        and: "while the owner does reach them, so nothing in the vocabulary is stranded beyond every role"
        GroupRole.OWNER.permissions().containsAll(OWNERS_ALONE)
    }

    def "holding no role at all grants nothing rather than everything"() {
        expect:
        GroupRole.permissionsOf([]).isEmpty()

        and: "and a single role does grant, so the floor is emptiness rather than a fold that never fills"
        !GroupRole.permissionsOf([GroupRole.OPERATOR]).isEmpty()
    }

    /**
     * Written over every role rather than a chosen few: a role added later carries its own bundle
     * into this fold, and a table would iterate only the rows somebody remembered to write.
     */
    def "a single role grants exactly what that role bundles"() {
        expect:
        GroupRole.values().collectEntries { [(it): GroupRole.permissionsOf([it])] }
                == GroupRole.values().collectEntries { [(it): it.permissions()] }
    }

    /**
     * Stated as the union, so it holds whichever of the two roles is declared first: roles add
     * grants, and reading two together must not withdraw what one of them alone carried.
     */
    def "two roles grant what either of them bundles"() {
        given:
        def pairs = [GroupRole.values().toList(), GroupRole.values().toList()].combinations()

        expect:
        pairs.every { one, other ->
            GroupRole.permissionsOf([one, other]) == one.permissions() + other.permissions()
        }

        and: "and a second role does add a grant the first did not carry, so the fold is not vacuous"
        GroupRole.permissionsOf([GroupRole.OPERATOR, GroupRole.OVERSEER]).contains(GroupPermission.REVIEW_AT_GATE)
        !GroupRole.permissionsOf([GroupRole.OPERATOR]).contains(GroupPermission.REVIEW_AT_GATE)
    }

    /**
     * The precondition the feature above rests on, and the reason it cannot prove itself. While
     * every bundle contains or is contained by every other, no holding exists on which taking the
     * union differs from answering with the widest role's bundle — the fold is a theorem over this
     * table rather than a rule it enforces, and the suite would stay green if it were replaced.
     * The day this goes red is the day the union starts deciding something and has to be re-read.
     */
    def "the bundles form a chain under inclusion, which is what leaves the union fold unprovable"() {
        expect:
        [GroupRole.values().toList(), GroupRole.values().toList()].combinations().every { one, other ->
            one.permissions().containsAll(other.permissions()) || other.permissions().containsAll(one.permissions())
        }

        and: "over a chain that does climb, a table of equal bundles satisfying the above and meaning nothing"
        GroupRole.OPERATOR.permissions().size() < GroupRole.OVERSEER.permissions().size()
        GroupRole.OVERSEER.permissions().size() < GroupRole.OWNER.permissions().size()
    }

    /**
     * The fold is built fresh and handed over mutable, which is what keeps it a bit-mask the one
     * caller can wrap without copying. What that buys has to be paid for here: writing to what it
     * handed back must reach neither the bundles it read nor the next fold taken from them. What a
     * caller outside this package is ever handed is the wrapper, which StandingSpec holds closed.
     */
    def "a fold that was written to reaches neither the bundles it read nor the next fold over them"() {
        given:
        def granted = GroupRole.permissionsOf([GroupRole.OPERATOR])

        when:
        granted.add(GroupPermission.CHANGE_MEMBERSHIP)

        then:
        !GroupRole.OPERATOR.permissions().contains(GroupPermission.CHANGE_MEMBERSHIP)
        !GroupRole.permissionsOf([GroupRole.OPERATOR]).contains(GroupPermission.CHANGE_MEMBERSHIP)

        and: "while the role's own bundle stays whole rather than being emptied alongside"
        GroupRole.permissionsOf([GroupRole.OPERATOR]) == GroupRole.OPERATOR.permissions()
    }

    def "roles that are not there at all are refused by name rather than folded into a grant"() {
        when:
        GroupRole.permissionsOf(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "GroupRole roles must not be null"
    }

    /**
     * A null is not a role granting nothing — it is not a role — so it is refused rather than folded
     * in as the empty bundle, which would read as a holding nobody configured.
     */
    def "a null among the roles is refused by name rather than treated as granting nothing"() {
        when:
        GroupRole.permissionsOf(roles)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "GroupRole roles must not hold a null role"

        where:
        roles << [[null], [GroupRole.OPERATOR, null], [null, GroupRole.OVERSEER]]
    }

    /**
     * Over every permission rather than a chosen few, so a role or a permission added later is read
     * off the table here too instead of off a row somebody remembered to write.
     */
    def "the roles reaching a permission are exactly those whose bundle carries it"() {
        expect:
        GroupPermission.values().collectEntries { [(it): GroupRole.reaching(it)] } ==
                GroupPermission.values().collectEntries { permission ->
                    [(permission): GroupRole.values().findAll { it.permissions().contains(permission) } as Set]
                }
    }

    /** What the estate sets when it starts a group, and what says whether one can still be administered. */
    def "changing membership is reached by the owner alone, and reading it by every role"() {
        expect:
        GroupRole.reaching(GroupPermission.CHANGE_MEMBERSHIP) == [GroupRole.OWNER] as Set

        and: "while a permission every role carries is reached by all three, so the answer is no fixed single role"
        GroupRole.reaching(GroupPermission.READ_MEMBERSHIP) == GroupRole.values() as Set
    }

    def "a caller cannot widen the roles reaching a permission through the set it is handed"() {
        given:
        def reaching = GroupRole.reaching(GroupPermission.CHANGE_MEMBERSHIP)

        when:
        reaching.add(GroupRole.OPERATOR)

        then:
        thrown(UnsupportedOperationException)

        and:
        GroupRole.reaching(GroupPermission.CHANGE_MEMBERSHIP) == [GroupRole.OWNER] as Set
    }

    def "a permission that is not there at all is refused by name rather than reached by nobody"() {
        when:
        GroupRole.reaching(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "GroupRole permission must not be null"
    }
}
