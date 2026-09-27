package org.lilradish.lite.app.groupregister;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.listing.KeysetPages;
import org.lilradish.lite.app.listing.KeysetStatements;
import org.lilradish.lite.app.listing.SearchFolding;
import org.lilradish.lite.app.listing.SortExpression;
import org.lilradish.lite.domain.groupregister.GroupSortColumn;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupKey;
import org.lilradish.lite.domain.identity.GroupName;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.listing.ListOrder;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListRow;
import org.lilradish.lite.domain.listing.ListShape;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every group there is, read afresh on every call. It narrows nothing to the caller, so the act the
 * handler asks is its only guard and nothing outside this package reaches it.
 *
 * <p>A row is a fact about a group and names nobody in it: whether some current member holds a role
 * that may change its membership, and how many people are in it, each counted once however many roles
 * they hold. Which roles may is read off the roles' own bundles, never listed here.
 *
 * <p>Every comparison names its collation. A filter matches a name as {@link SearchFolding} does; a key
 * orders by code point, a name by the server's ICU. Every tie is broken by key ascending, which no two
 * groups share.
 *
 * <p>A stored key or name its type refuses fails the whole read rather than being shown or left out.
 */
@Component
final class GroupRegister {

    /** The register is read whole, never within anything. */
    static final String WHOLE_REGISTER = "";

    private static final String[] ADMINISTERING = GroupRole.reaching(GroupPermission.CHANGE_MEMBERSHIP).stream()
            .map(StoreLabels::label)
            .toArray(String[]::new);

    private static final Map<String, Object> BOUND = Map.of("administering", ADMINISTERING);

    // DB-SPECIFIC: exists(…) selected as a boolean, an array cast to an array of an enum and every collation
    // named below are PostgreSQL's.
    private static final String REGISTER = """
            select register.group_id, register.key, register.name, register.can_be_administered, register.member_count
              from (select circle.group_id,
                           circle.key,
                           circle.name,
                           circle.name_folded,
                           exists (select 1
                                     from group_members holding
                                    where holding.group_id = circle.group_id
                                      and holding.removed_at is null
                                      and holding.role = any (cast(:administering as group_role[])))
                               as can_be_administered,
                           (select count(distinct membership.subject_id)
                              from group_members membership
                             where membership.group_id = circle.group_id
                               and membership.removed_at is null) as member_count
                      from groups circle) register
             where true
            """;

    private static final String ONE_GROUP = REGISTER + " and register.group_id = :group";

    /* The table opens on a column no row leaves empty, read in the direction that comes first. */
    private static final KeysetStatements<GroupSortColumn> PAGES = new KeysetStatements<>(
            "group register",
            GroupSortColumn.KEY,
            new ListOrder<>(GroupSortColumn.NAME, false),
            REGISTER,
            SearchFolding.holds("register.name_folded", KeysetStatements.TYPED),
            List.of(
                    new SortExpression<>(GroupSortColumn.KEY, "register.key", "ucs_basic"),
                    new SortExpression<>(GroupSortColumn.NAME, "register.name", "\"unicode\""),
                    new SortExpression<>(GroupSortColumn.CAN_BE_ADMINISTERED, "register.can_be_administered", null),
                    new SortExpression<>(GroupSortColumn.MEMBER_COUNT, "register.member_count", null)),
            BOUND);

    /** The list the register is read as, which every cursor it hands out is bound to. */
    static final ListShape<GroupSortColumn> LISTED = PAGES.shape();

    private final JdbcClient database;

    GroupRegister(JdbcClient database) {
        this.database = database;
    }

    /**
     * One page of groups after {@code after}, or from the start where there is none, and where the next
     * page would begin if any row is left beyond them.
     */
    ListPage<GroupRow> page(ListQuery<GroupSortColumn> query, @Nullable ListPosition after) {
        requireNonNull(query, "GroupRegister query must not be null");
        return KeysetPages.read(database, PAGES, query, after, (result, number) -> row(result));
    }

    /** One group as the register reads it, or none. */
    Optional<GroupRow> group(GroupId group) {
        requireNonNull(group, "GroupRegister group must not be null");
        return database.sql(ONE_GROUP)
                .params(BOUND)
                .param("group", group.value())
                .query((result, number) -> row(result))
                .optional();
    }

    private static GroupRow row(ResultSet result) throws SQLException {
        GroupId group = new GroupId(result.getObject("group_id", UUID.class));
        GroupKey key;
        GroupName name;
        try {
            key = new GroupKey(result.getString("key"));
            name = new GroupName(result.getString("name"));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Group " + group.value() + " holds a key or a name this system will not show", refused);
        }
        return new GroupRow(group, key, name, result.getBoolean("can_be_administered"), result.getLong("member_count"));
    }

    /** @param canBeAdministered whether some current member holds a role that may change its membership */
    record GroupRow(GroupId groupId, GroupKey key, GroupName name, boolean canBeAdministered, long memberCount)
            implements ListRow<GroupSortColumn> {

        @Override
        public Object valueIn(GroupSortColumn column) {
            return switch (column) {
                case KEY -> key.value();
                case NAME -> name.value();
                case CAN_BE_ADMINISTERED -> canBeAdministered;
                case MEMBER_COUNT -> memberCount;
            };
        }
    }
}
