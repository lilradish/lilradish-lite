package org.lilradish.lite.app.members;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.listing.KeysetPages;
import org.lilradish.lite.app.listing.KeysetStatements;
import org.lilradish.lite.app.listing.SearchFolding;
import org.lilradish.lite.app.listing.SortExpression;
import org.lilradish.lite.app.pool.PersonRows;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.listing.ListOrder;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListRow;
import org.lilradish.lite.domain.listing.ListShape;
import org.lilradish.lite.domain.members.MemberSortColumn;
import org.lilradish.lite.domain.people.PersonName;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who is in one group now and every role each of them holds there, read afresh on every call. A member
 * is one row however many roles they hold, and somebody holding no role there is no member of it.
 *
 * <p>Every comparison names its collation. A filter matches a name or a user number as {@link
 * SearchFolding} does; a user number orders by code point, a name by the server's ICU. Somebody no name
 * is held for sorts after everybody named, in both directions, and every tie is broken by user number
 * ascending, as {@link KeysetStatements} reads any list.
 *
 * <p>A stored value its type refuses fails the whole read rather than being shown or left out.
 */
@Component
final class Members {

    private static final String[] CHANGING = GroupRole.reaching(GroupPermission.CHANGE_MEMBERSHIP).stream()
            .map(StoreLabels::label)
            .toArray(String[]::new);

    // DB-SPECIFIC: array_agg, array(subquery), an enum cast to text, a row compared whole, an array cast to
    // an array of an enum and every collation named below are PostgreSQL's.
    private static final String MEMBERS = """
            select member.subject_id, member.user_id, member.display_name, member.roles
              from (select person.subject_id,
                           person.user_id,
                           person.display_name,
                           person.user_id_folded,
                           person.display_name_folded,
                           array_agg(holding.role::text order by holding.role) as roles
                      from group_members holding
                      join subjects person on person.subject_id = holding.subject_id
                     where holding.group_id = cast(%s as uuid)
                       and holding.removed_at is null
                     group by person.subject_id) member
             where true
            """.formatted(KeysetStatements.WITHIN);

    private static final String NARROWED = "%s or %s"
            .formatted(
                    SearchFolding.holds("member.display_name_folded", KeysetStatements.TYPED),
                    SearchFolding.holds("member.user_id_folded", KeysetStatements.TYPED));

    /* The table opens on a column no row leaves empty, read in the direction that comes first. */
    private static final KeysetStatements<MemberSortColumn> PAGES = new KeysetStatements<>(
            "group members",
            MemberSortColumn.USER_ID,
            new ListOrder<>(MemberSortColumn.USER_ID, false),
            MEMBERS,
            NARROWED,
            List.of(
                    new SortExpression<>(MemberSortColumn.USER_ID, "member.user_id", "ucs_basic"),
                    new SortExpression<>(MemberSortColumn.DISPLAY_NAME, "member.display_name", "\"unicode\"")),
            Map.of());

    /** The list a group's members are read as, which every cursor it hands out is bound to. */
    static final ListShape<MemberSortColumn> LISTED = PAGES.shape();

    private static final String HELD_HERE = """
            holding.group_id = :group
                            and holding.subject_id = person.subject_id
                            and holding.removed_at is null""";

    /* A holding is the last where no other open holding of a role that may change the membership stands
     * beside it, the person's own included; removable asks that of all their holdings taken at once. */
    private static final String PERSON = """
            select person.subject_id,
                   person.user_id,
                   person.display_name,
                   array(select holding.role::text
                           from group_members holding
                          where %1$s
                          order by holding.role) as roles,
                   array(select holding.role::text
                           from group_members holding
                          where %1$s
                            and holding.role = any (cast(:changing as group_role[]))
                            and not exists (select 1
                                              from group_members other
                                             where other.group_id = holding.group_id
                                               and other.removed_at is null
                                               and other.role = any (cast(:changing as group_role[]))
                                               and (other.subject_id, other.role) <> (holding.subject_id, holding.role))
                          order by holding.role) as last_changing,
                   not exists (select 1
                                 from group_members holding
                                where %1$s
                                  and holding.role = any (cast(:changing as group_role[])))
                       or exists (select 1
                                    from group_members other
                                   where other.group_id = :group
                                     and other.removed_at is null
                                     and other.role = any (cast(:changing as group_role[]))
                                     and other.subject_id <> person.subject_id) as removable
              from pool_members stay
              join subjects person on person.subject_id = stay.subject_id
             where stay.removed_at is null
               and stay.subject_id = :subject
            """.formatted(HELD_HERE);

    private static final String NOT_IN_VIEW = "That person is not a member of this group.";

    private final JdbcClient database;

    Members(JdbcClient database) {
        this.database = database;
    }

    /**
     * One page of the members of the group the query is read within, after {@code after} or from the
     * start where there is none, and where the next page would begin if any row is left beyond them.
     */
    ListPage<MemberRow> page(ListQuery<MemberSortColumn> query, @Nullable ListPosition after) {
        requireNonNull(query, "Members query must not be null");
        return KeysetPages.read(database, PAGES, query, after, (result, number) -> row(result));
    }

    /** Somebody holding a role in the group now, or nobody. */
    Optional<Member> member(GroupId group, SubjectId subject) {
        return standing(group, subject).filter(member -> !member.roles().isEmpty());
    }

    /**
     * Somebody in the pool now, as the group holds them: with every role they hold there, and none where
     * they hold none. Nobody where they are not in the pool.
     */
    Optional<Member> standing(GroupId group, SubjectId subject) {
        requireNonNull(group, "Members group must not be null");
        requireNonNull(subject, "Members subject must not be null");
        return database.sql(PERSON)
                .param("changing", CHANGING)
                .param("group", group.value())
                .param("subject", subject.value())
                .query((result, number) -> member(result))
                .optional();
    }

    /** Every way of naming nobody in the group is answered with this one refusal, whoever raises it. */
    static ApiErrorException notInView() {
        return new ApiErrorException(RefusalCode.MEMBER_NOT_IN_VIEW, NOT_IN_VIEW);
    }

    private static MemberRow row(ResultSet result) throws SQLException {
        SubjectId subject = PersonRows.subjectOf(result);
        return new MemberRow(
                subject,
                PersonRows.userIdOf(result, subject),
                PersonRows.nameOf(result, subject),
                PersonRows.labelled(GroupRole.class, result.getArray("roles")));
    }

    private static Member member(ResultSet result) throws SQLException {
        SubjectId subject = PersonRows.subjectOf(result);
        return new Member(
                subject,
                PersonRows.userIdOf(result, subject),
                PersonRows.nameOf(result, subject),
                PersonRows.labelled(GroupRole.class, result.getArray("roles")),
                PersonRows.labelled(GroupRole.class, result.getArray("last_changing")),
                result.getBoolean("removable"));
    }

    record MemberRow(
            SubjectId subjectId, UserId userId, @Nullable PersonName displayName, Set<GroupRole> roles)
            implements ListRow<MemberSortColumn> {

        @Override
        public @Nullable Object valueIn(MemberSortColumn column) {
            return switch (column) {
                case USER_ID -> userId.value();
                case DISPLAY_NAME -> displayName == null ? null : displayName.value();
            };
        }
    }

    /**
     * Somebody as the group holds them, whose roles there are none once their membership has ended.
     *
     * @param lastChangingRoles the roles they hold without which nobody here could change the membership
     * @param removable whether somebody here could still change the membership once every role they hold
     *     is taken, which is what taking them out of the group asks
     */
    record Member(
            SubjectId subjectId,
            UserId userId,
            @Nullable PersonName displayName,
            Set<GroupRole> roles,
            Set<GroupRole> lastChangingRoles,
            boolean removable) {}
}
