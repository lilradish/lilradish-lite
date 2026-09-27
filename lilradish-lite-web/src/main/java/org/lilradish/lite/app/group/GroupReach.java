package org.lilradish.lite.app.group;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the roles somebody holds in one group reach a permission there, asked in this one place by
 * whoever asks it — the gate in front of a handler, and a change asking again after its locks.
 *
 * <p>Holding nothing there is not being refused a permission: it is not being able to see the group at
 * all, and it is answered exactly as a group that does not exist is. Holding something that reaches
 * nothing asked is refused as an act is, naming the kind of thing refused and never the permission.
 */
public final class GroupReach {

    private static final Map<GroupPermission, Set<GroupRole>> REACHING = reachingEach();

    private static final String NOT_IN_VIEW = "That group is not in view.";

    private static final Logger logger = LoggerFactory.getLogger(GroupReach.class);

    private GroupReach() {}

    /** What the caller holds in the group, for what is reached by holding anything there at all. */
    public static void requireMember(Set<GroupRole> held) {
        if (held.isEmpty()) {
            throw notInView();
        }
    }

    /** What the caller holds in the group, and the group it was read for, which the log names. */
    public static void requireReached(Set<GroupRole> held, GroupId group, GroupPermission permission) {
        requireMember(held);
        if (!reaches(held, permission)) {
            logger.warn(
                    "Refused a member of group {} holding no role there that reaches {}", group.value(), permission);
            throw new ApiErrorException(RefusalCode.ACT_NOT_PERMITTED, "This caller may not do that.");
        }
    }

    /**
     * Asked again inside a change, after the group's lock: every change to a group's membership takes
     * that lock first, so what the caller holds there cannot move again before the change lands.
     */
    public static void requireStillReached(GroupRoles roles, UserId caller, GroupId group, GroupPermission permission) {
        requireReached(roles.heldBy(caller, group), group, permission);
    }

    /** Every permission the roles reach, judged as {@link #requireReached} judges one; none for no roles. */
    public static Set<GroupPermission> reachedBy(Set<GroupRole> held) {
        EnumSet<GroupPermission> reached = EnumSet.noneOf(GroupPermission.class);
        for (GroupPermission permission : GroupPermission.values()) {
            if (reaches(held, permission)) {
                reached.add(permission);
            }
        }
        return Collections.unmodifiableSet(reached);
    }

    /** Every way of seeing into no group is answered with this one refusal, whoever raises it. */
    public static ApiErrorException notInView() {
        return new ApiErrorException(RefusalCode.GROUP_NOT_IN_VIEW, NOT_IN_VIEW);
    }

    private static boolean reaches(Set<GroupRole> held, GroupPermission permission) {
        return !Collections.disjoint(held, REACHING.get(permission));
    }

    private static Map<GroupPermission, Set<GroupRole>> reachingEach() {
        Map<GroupPermission, Set<GroupRole>> reaching = new EnumMap<>(GroupPermission.class);
        for (GroupPermission permission : GroupPermission.values()) {
            reaching.put(permission, GroupRole.reaching(permission));
        }
        return Collections.unmodifiableMap(reaching);
    }
}
