package org.lilradish.lite.app.pool;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.estate.EstateRoleGrants;
import org.lilradish.lite.app.listing.KeysetPages;
import org.lilradish.lite.app.listing.KeysetStatements;
import org.lilradish.lite.app.listing.SearchFolding;
import org.lilradish.lite.app.listing.SortExpression;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.EstateRole;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupName;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.listing.ListOrder;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListRow;
import org.lilradish.lite.domain.listing.ListShape;
import org.lilradish.lite.domain.people.PeopleFound;
import org.lilradish.lite.domain.people.PeopleSearch;
import org.lilradish.lite.domain.people.PersonName;
import org.lilradish.lite.domain.pool.PoolSortColumn;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who is in the pool now, read afresh on every call. It narrows nothing to the caller, so the act the
 * handler asks is its only guard and nothing outside this package reaches it.
 *
 * <p>Every comparison names its collation, the database's default not being this code's to choose.
 * A filter matches as {@link SearchFolding} does; a user number orders by code point; a name orders
 * by the server's ICU, whose upgrades may reorder names without changing who is listed.
 *
 * <p>Somebody no name is held for sorts after everybody named, in both directions, and every tie is
 * broken by user number ascending, as {@link KeysetStatements} reads any list.
 *
 * <p>A stored value its type refuses — written round the type, by hand or by a restore — fails the
 * whole read rather than being shown or left out: either would put a list that is not the pool in
 * front of somebody who may read it.
 */
@Component
final class PoolPeople {

    /** The pool is read whole, never within anything. */
    static final String WHOLE_POOL = "";

    private static final Set<EstateRole> GRANTING_ROLES = EstateRole.reaching(EstateAct.GRANT_ESTATE_ROLE);

    private static final String[] GRANTING =
            GRANTING_ROLES.stream().map(StoreLabels::label).toArray(String[]::new);

    // DB-SPECIFIC: array(subquery), an enum cast to text, exists(…) selected as a boolean, limit, a row
    // compared whole, a comparison ordered as a boolean, an array cast to an array of an enum and every
    // collation named below are PostgreSQL's.
    private static final String HELD_NOW = """
            array(select holding.role::text
                    from estate_role_grants holding
                   where %s)""".formatted(EstateRoleGrants.HELD_BY_PERSON);

    /* Counted over the pool alone, so it says nothing a reader of the pool's list could not work out.
     * A grant held outside the pool can only withhold a control that a change would have allowed. */
    private static final String LAST_GRANTING = """
            array(select holding.role::text
                    from estate_role_grants holding
                   where %s
                     and holding.role = any (cast(:granting as estate_role[]))
                     and not exists (select 1
                                       from estate_role_grants other
                                       join pool_members other_stay on other_stay.subject_id = other.subject_id
                                      where other_stay.removed_at is null
                                        and other.removed_at is null
                                        and other.role = any (cast(:granting as estate_role[]))
                                        and (other.subject_id, other.role) <> (holding.subject_id, holding.role)))""".formatted(EstateRoleGrants.HELD_BY_PERSON);

    private static final String MEMBERSHIPS_NOW = """
            from group_members membership
                   where membership.subject_id = person.subject_id
                     and membership.removed_at is null""";

    private static final String IN_THE_POOL_NOW = """
            from pool_members stay
              join subjects person on person.subject_id = stay.subject_id
             where stay.removed_at is null""";

    private static final String CURRENT_POOL = """
            select pool.subject_id, pool.user_id, pool.display_name, pool.estate_roles, pool.group_count
              from (select person.subject_id,
                           person.user_id,
                           person.display_name,
                           person.user_id_folded,
                           person.display_name_folded,
                           %s as estate_roles,
                           (select count(distinct membership.group_id) %s) as group_count
                      %s) pool
             where true
            """.formatted(HELD_NOW, MEMBERSHIPS_NOW, IN_THE_POOL_NOW);

    private static final String PERSON = """
            select person.subject_id,
                   person.user_id,
                   person.display_name,
                   %s as estate_roles,
                   %s as last_granting,
                   array(select circle.name
                           from groups circle
                          where circle.group_id in (select membership.group_id %s)
                          order by circle.name collate "unicode", circle.group_id) as group_names,
                   exists (select 1
                             from subjects author
                            where author.subject_id = stay.created_by
                              and author.kind = 'seeder') as seeded
              %s
               and stay.subject_id = :subject
            """.formatted(HELD_NOW, LAST_GRANTING, MEMBERSHIPS_NOW, IN_THE_POOL_NOW);

    private static final String FOUND = found("true");

    private static final String FOUND_OUTSIDE = found("""
            not exists (select 1
                                 from group_members membership
                                where membership.subject_id = person.subject_id
                                  and membership.group_id = :group
                                  and membership.removed_at is null)""");

    private static final String NARROWED = "%s or %s"
            .formatted(
                    SearchFolding.holds("pool.display_name_folded", KeysetStatements.TYPED),
                    SearchFolding.holds("pool.user_id_folded", KeysetStatements.TYPED));

    /* The table opens on a column no row leaves empty, read in the direction that comes first. */
    private static final KeysetStatements<PoolSortColumn> PAGES = new KeysetStatements<>(
            "pool people",
            PoolSortColumn.USER_ID,
            new ListOrder<>(PoolSortColumn.USER_ID, false),
            CURRENT_POOL,
            NARROWED,
            List.of(
                    new SortExpression<>(PoolSortColumn.USER_ID, "pool.user_id", "ucs_basic"),
                    new SortExpression<>(PoolSortColumn.DISPLAY_NAME, "pool.display_name", "\"unicode\""),
                    new SortExpression<>(PoolSortColumn.GROUP_COUNT, "pool.group_count", null)),
            Map.of());

    /** The list the pool is read as, which every cursor it hands out is bound to. */
    static final ListShape<PoolSortColumn> LISTED = PAGES.shape();

    private static final String NOT_IN_VIEW = "That person is not in view.";

    private final JdbcClient database;

    PoolPeople(JdbcClient database) {
        this.database = database;
    }

    /**
     * One page of people after {@code after}, or from the start where there is none, and where the next
     * page would begin if any row is left beyond them.
     */
    ListPage<PoolPerson> page(ListQuery<PoolSortColumn> query, @Nullable ListPosition after) {
        requireNonNull(query, "PoolPeople query must not be null");
        return KeysetPages.read(database, PAGES, query, after, (result, number) -> listed(result));
    }

    /** Somebody in the pool now, or nobody. */
    Optional<PoolPersonPanel> person(SubjectId subject) {
        requireNonNull(subject, "PoolPeople subject must not be null");
        return withGranting(database.sql(PERSON))
                .param("subject", subject.value())
                .query((result, number) -> panel(result))
                .optional();
    }

    /**
     * At most {@code most} of those in the pool whom a search finds, and whether it finds more: tried
     * whole and as typed against a user number, and in part against a name once spaced as names are.
     */
    PeopleFound<InPool> search(PeopleSearch search, int most) {
        requireNonNull(search, "PoolPeople search must not be null");
        return PeopleFound.of(searched(database.sql(FOUND), search, most), most);
    }

    /** The same search, leaving out whoever holds a role in the group now. */
    PeopleFound<InPool> searchOutside(GroupId group, PeopleSearch search, int most) {
        requireNonNull(group, "PoolPeople group must not be null");
        requireNonNull(search, "PoolPeople search must not be null");
        return PeopleFound.of(searched(database.sql(FOUND_OUTSIDE).param("group", group.value()), search, most), most);
    }

    /** Whether holding the role lets somebody grant an estate role. */
    static boolean grants(EstateRole role) {
        return GRANTING_ROLES.contains(role);
    }

    /** The statement with {@code :granting} bound to every role that may grant an estate role. */
    static JdbcClient.StatementSpec withGranting(JdbcClient.StatementSpec statement) {
        return statement.param("granting", GRANTING);
    }

    /** Every way of naming nobody in view is answered with this one refusal, whoever raises it. */
    static ApiErrorException notInView() {
        return new ApiErrorException(RefusalCode.PERSON_NOT_IN_VIEW, NOT_IN_VIEW);
    }

    /* Whoever's user number is what was typed comes first, so no number of names holding it can push
     * them past the most a search shows. */
    private static String found(String alsoHolding) {
        return """
                select person.subject_id, person.user_id, person.display_name
                  %s
                   and (person.user_id = :search or %s)
                   and %s
                 order by person.user_id = :search desc,
                          person.display_name collate "unicode",
                          person.user_id collate ucs_basic
                 limit :limit
                """.formatted(
                        IN_THE_POOL_NOW, SearchFolding.holds("person.display_name_folded", ":spaced"), alsoHolding);
    }

    private static List<InPool> searched(JdbcClient.StatementSpec statement, PeopleSearch search, int most) {
        return statement
                .param("search", search.text())
                .param("spaced", PersonName.spacedAsNamesAre(search.text()))
                .param("limit", PeopleFound.fetching(most))
                .query((result, number) -> inPool(result))
                .list();
    }

    private static PoolPerson listed(ResultSet result) throws SQLException {
        SubjectId subject = PersonRows.subjectOf(result);
        return new PoolPerson(
                subject,
                PersonRows.userIdOf(result, subject),
                PersonRows.nameOf(result, subject),
                PersonRows.labelled(EstateRole.class, result.getArray("estate_roles")),
                result.getLong("group_count"));
    }

    private static InPool inPool(ResultSet result) throws SQLException {
        SubjectId subject = PersonRows.subjectOf(result);
        return new InPool(subject, PersonRows.userIdOf(result, subject), PersonRows.nameOf(result, subject));
    }

    private static PoolPersonPanel panel(ResultSet result) throws SQLException {
        SubjectId subject = PersonRows.subjectOf(result);
        List<GroupName> groups = new ArrayList<>();
        for (String name : PersonRows.texts(result.getArray("group_names"))) {
            try {
                groups.add(new GroupName(name));
            } catch (IllegalArgumentException refused) {
                throw new IllegalStateException(
                        "A group subject " + subject.value() + " is in holds a name this system will not show",
                        refused);
            }
        }
        return new PoolPersonPanel(
                subject,
                PersonRows.userIdOf(result, subject),
                PersonRows.nameOf(result, subject),
                PersonRows.labelled(EstateRole.class, result.getArray("estate_roles")),
                PersonRows.labelled(EstateRole.class, result.getArray("last_granting")),
                List.copyOf(groups),
                result.getBoolean("seeded"));
    }

    record PoolPerson(
            SubjectId subjectId,
            UserId userId,
            @Nullable PersonName displayName,
            Set<EstateRole> estateRoles,
            long groupCount)
            implements ListRow<PoolSortColumn> {

        @Override
        public @Nullable Object valueIn(PoolSortColumn column) {
            return switch (column) {
                case USER_ID -> userId.value();
                case DISPLAY_NAME -> displayName == null ? null : displayName.value();
                case GROUP_COUNT -> groupCount;
            };
        }
    }

    /**
     * Somebody in the pool as their panel reads them.
     *
     * @param lastGrantingRoles the roles they hold without which nobody in the pool may grant an estate role
     */
    record PoolPersonPanel(
            SubjectId subjectId,
            UserId userId,
            @Nullable PersonName displayName,
            Set<EstateRole> estateRoles,
            Set<EstateRole> lastGrantingRoles,
            List<GroupName> groups,
            boolean seeded) {}

    record InPool(
            SubjectId subjectId, UserId userId, @Nullable PersonName displayName) {}
}
