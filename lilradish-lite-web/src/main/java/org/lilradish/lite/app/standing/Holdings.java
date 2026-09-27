package org.lilradish.lite.app.standing;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.estate.EstateRoleGrants;
import org.lilradish.lite.domain.identity.EstateRole;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupKey;
import org.lilradish.lite.domain.identity.GroupName;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.HumanPrincipal;
import org.lilradish.lite.domain.identity.Scope;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Everything a user holds now, estate roles and every open group role, read afresh in one statement;
 * somebody unknown and somebody holding nothing both answer with nothing.
 */
@Component
final class Holdings {

    // DB-SPECIFIC: a lateral join, :: casts and the collation named are PostgreSQL's.
    private static final String HELD = """
            select person.subject_id, held.group_id, held.key, held.name, held.role
              from subjects person
              join lateral (select null::uuid as group_id, null::text as key, null::text as name,
                                   holding.role::text as role
                              from estate_role_grants holding
                             where %s
                            union all
                            select circle.group_id, circle.key, circle.name, membership.role::text
                              from group_members membership
                              join groups circle on circle.group_id = membership.group_id
                             where membership.subject_id = person.subject_id
                               and membership.removed_at is null) held
                on true
             where person.user_id = ?
             order by held.name collate "unicode", held.group_id
            """.formatted(EstateRoleGrants.HELD_BY_PERSON);

    private final JdbcClient database;

    Holdings(JdbcClient database) {
        this.database = database;
    }

    /** What the user holds, or nothing where they hold nothing at all. */
    Optional<Held> heldBy(UserId user) {
        requireNonNull(user, "Holdings user must not be null");
        return database.sql(HELD).param(user.value()).query(Holdings::held);
    }

    private static Optional<Held> held(ResultSet result) throws SQLException {
        if (!result.next()) {
            return Optional.empty();
        }
        SubjectId subject = new SubjectId(result.getObject("subject_id", UUID.class));
        EnumSet<EstateRole> estateRoles = EnumSet.noneOf(EstateRole.class);
        Map<Scope.Group, Set<GroupRole>> rolesByGroup = new HashMap<>();
        List<GroupInView> groups = new ArrayList<>();
        do {
            @Nullable UUID stored = result.getObject("group_id", UUID.class);
            String role = result.getString("role");
            if (stored == null) {
                estateRoles.add(StoreLabels.parse(EstateRole.class, role));
                continue;
            }
            GroupId groupId = new GroupId(stored);
            // Relies on HELD ordering each group's rows together.
            if (groups.isEmpty() || !groups.getLast().groupId().equals(groupId)) {
                groups.add(inView(groupId, result));
            }
            rolesByGroup
                    .computeIfAbsent(new Scope.Group(groupId), group -> EnumSet.noneOf(GroupRole.class))
                    .add(StoreLabels.parse(GroupRole.class, role));
        } while (result.next());
        return Optional.of(new Held(HumanPrincipal.of(subject, estateRoles, rolesByGroup), List.copyOf(groups)));
    }

    // A stored key or name its type refuses fails the reader's whole standing, frame and all: loud
    // rather than a group silently missing from it.
    private static GroupInView inView(GroupId group, ResultSet result) throws SQLException {
        try {
            return new GroupInView(
                    group, new GroupKey(result.getString("key")), new GroupName(result.getString("name")));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Group " + group.value() + " holds a key or a name this system will not show", refused);
        }
    }

    /** @param groups every group they are in, by name under ICU and then by identifier */
    record Held(HumanPrincipal principal, List<GroupInView> groups) {}

    record GroupInView(GroupId groupId, GroupKey key, GroupName name) {}
}
