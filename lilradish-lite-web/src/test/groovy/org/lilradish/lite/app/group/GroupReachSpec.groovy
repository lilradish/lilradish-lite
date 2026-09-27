package org.lilradish.lite.app.group

import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import spock.lang.Specification

class GroupReachSpec extends Specification {

    static final UserId CALLER = new UserId("000001")

    static final GroupId GROUP = new GroupId(UUID.fromString("00000003-0000-4000-8000-000000000b01"))

    /** Membership asks nothing a role reaches, so any role is enough and holding none is no group in view. */
    def "any role held there is membership, and holding none is answered as no group in view"() {
        expect:
        refusalOf { GroupReach.requireMember(held) }?.errorCode() == refusal

        where:
        held                           || refusal
        EnumSet.of(GroupRole.OPERATOR) || null
        EnumSet.of(GroupRole.OVERSEER) || null
        EnumSet.of(GroupRole.OWNER)    || null
        EnumSet.noneOf(GroupRole)      || RefusalCode.GROUP_NOT_IN_VIEW
    }

    /**
     * Over every role and every permission, the answer read off the roles' own table: a bundle changed
     * there changes what passes here, and nothing here restates which role reaches what.
     */
    def "a role reaching the permission lets its holder through, and one that does not is refused as an act is"() {
        when:
        def refused = refusalOf { GroupReach.requireReached(EnumSet.of(role), GROUP, permission) }

        then:
        (refused == null) == GroupRole.reaching(permission).contains(role)
        refused == null || refused.errorCode() == RefusalCode.ACT_NOT_PERMITTED

        and: "naming neither the permission nor the role, which are the reader's contract and not a fact about them"
        refused == null || !refused.message.contains(permission.published()) && !refused.message.contains(role.published())

        where:
        [role, permission] << [GroupRole.values(), GroupPermission.values()].combinations()
    }

    /** Roles add up, so one that reaches is enough whatever else is held beside it. */
    def "a holding reaches whatever any role in it reaches"() {
        expect:
        refusalOf { GroupReach.requireReached(EnumSet.of(GroupRole.OPERATOR, GroupRole.OWNER), GROUP,
                GroupPermission.CHANGE_MEMBERSHIP) } == null
    }

    /** Holding nothing is not seeing the group at all, which is how a group that does not exist is answered. */
    def "holding nothing there is answered as no group in view, whatever is asked"() {
        when:
        GroupReach.requireReached(EnumSet.noneOf(GroupRole), GROUP, permission)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        refused.message == GroupReach.notInView().message

        where:
        permission << GroupPermission.values()
    }

    def "asked again inside a change, what the caller holds there is read afresh and judged the same way"() {
        given:
        def roles = Mock(GroupRoles)

        when:
        def refused = refusalOf {
            GroupReach.requireStillReached(roles, CALLER, GROUP, GroupPermission.CHANGE_MEMBERSHIP)
        }

        then:
        1 * roles.heldBy(CALLER, GROUP) >> held
        0 * _

        and:
        refused?.errorCode() == refusal

        where:
        held                            || refusal
        EnumSet.of(GroupRole.OWNER)     || null
        EnumSet.of(GroupRole.OVERSEER)  || RefusalCode.ACT_NOT_PERMITTED
        EnumSet.noneOf(GroupRole)       || RefusalCode.GROUP_NOT_IN_VIEW
    }

    /** One judgement read two ways: what a holding reaches is exactly what it would be let through for. */
    def "what a holding reaches is every permission it would be let through for, and nothing else"() {
        when:
        def reached = GroupReach.reachedBy(held)

        then:
        reached == GroupPermission.values().findAll { permission ->
            refusalOf { GroupReach.requireReached(held, GROUP, permission) } == null
        } as Set

        where:
        held << [EnumSet.of(GroupRole.OPERATOR), EnumSet.of(GroupRole.OVERSEER), EnumSet.of(GroupRole.OPERATOR, GroupRole.OWNER)]
    }

    def "a holding of nothing reaches nothing, and what is reached is nobody's to widen"() {
        given:
        def reached = GroupReach.reachedBy(EnumSet.of(GroupRole.OPERATOR))

        expect:
        GroupReach.reachedBy(EnumSet.noneOf(GroupRole)).isEmpty()

        when:
        reached.add(GroupPermission.APPROVE_ENTRY)

        then:
        thrown(UnsupportedOperationException)
        !reached.contains(GroupPermission.APPROVE_ENTRY)
    }

    def "no group in view is one refusal, the same code and the same words whoever raises it"() {
        expect:
        GroupReach.notInView().errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        GroupReach.notInView().message == "That group is not in view."

        and: "a fresh one each time, so no raiser shares a stack trace with another"
        !GroupReach.notInView().is(GroupReach.notInView())
    }

    private static ApiErrorException refusalOf(Closure asked) {
        try {
            asked()
            null
        } catch (ApiErrorException refused) {
            refused
        }
    }
}
