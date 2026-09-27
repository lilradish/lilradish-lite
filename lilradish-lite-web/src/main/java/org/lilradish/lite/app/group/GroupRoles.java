package org.lilradish.lite.app.group;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which roles a user holds in one group right now, asked again on every call for the reason {@link
 * org.lilradish.lite.app.estate.EstateRoleGrants} gives. A group that does not exist, a user this system
 * has never heard of, and a user in no role there all answer alike, with no roles at all.
 */
@Component
public final class GroupRoles {

    // DB-SPECIFIC: an enum cast to text is PostgreSQL's.
    private static final String HELD_THERE = """
            select holding.role::text
              from subjects person
              join group_members holding on holding.subject_id = person.subject_id
             where person.user_id = :user
               and holding.group_id = :group
               and holding.removed_at is null
            """;

    // DB-SPECIFIC: for share is PostgreSQL's.
    /* For share: a change to the group's membership waits on it, and no change asked in the group on another. */
    private static final String GROUP_HELD = "select 1 from groups circle where circle.group_id = :group for share";

    private final JdbcClient database;

    GroupRoles(JdbcClient database) {
        this.database = database;
    }

    public Set<GroupRole> heldBy(UserId user, GroupId group) {
        requireNonNull(user, "GroupRoles user must not be null");
        requireNonNull(group, "GroupRoles group must not be null");
        List<GroupRole> held = database.sql(HELD_THERE)
                .param("user", user.value())
                .param("group", group.value())
                .query((row, number) -> StoreLabels.parse(GroupRole.class, row.getString(1)))
                .list();
        EnumSet<GroupRole> roles = EnumSet.noneOf(GroupRole.class);
        roles.addAll(held);
        return Collections.unmodifiableSet(roles);
    }

    /**
     * Inside a change, once whatever it locks first is held: the group's row is held for share, so what the
     * caller holds there cannot move again before the change ends. Refused as {@link GroupReach} refuses.
     */
    public Set<GroupPermission> stillReaching(UserId caller, GroupId group, GroupPermission permission) {
        requireNonNull(permission, "GroupRoles permission must not be null");
        database.sql(GROUP_HELD)
                .param("group", group.value())
                .query(Integer.class)
                .optional();
        Set<GroupRole> held = heldBy(caller, group);
        GroupReach.requireReached(held, group, permission);
        return GroupReach.reachedBy(held);
    }

    /** As {@link #stillReaching} asks it, answered with what only this can make: the group so held and reached. */
    public StillReached stillReached(UserId caller, GroupId group, GroupPermission permission) {
        return new StillReached(group, permission, stillReaching(caller, group, permission));
    }

    /**
     * A group whose row a change holds, the caller's reach there asked again after it for one permission. Nothing
     * binds one to the transaction it was made in: kept past it, the group's row is held no longer.
     */
    public static final class StillReached {

        private final GroupId group;

        private final GroupPermission permission;

        private final Set<GroupPermission> permitted;

        private StillReached(GroupId group, GroupPermission permission, Set<GroupPermission> permitted) {
            this.group = group;
            this.permission = permission;
            this.permitted = permitted;
        }

        public GroupId group() {
            return group;
        }

        public GroupPermission permission() {
            return permission;
        }

        public Set<GroupPermission> permitted() {
            return permitted;
        }
    }
}
