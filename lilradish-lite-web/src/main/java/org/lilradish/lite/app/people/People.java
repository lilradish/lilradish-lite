package org.lilradish.lite.app.people;

import static java.util.Objects.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.listing.SearchFolding;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.people.PeopleFound;
import org.lilradish.lite.domain.people.PeopleSearch;
import org.lilradish.lite.domain.people.PersonName;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who in the directory a search finds, read afresh on every call, and whether each of them is in the
 * pool now. It narrows nothing to the caller, so the act the handler asks is its only guard and
 * nothing outside this package reaches it.
 *
 * <p>What was typed is tried both ways at once and never chosen between by its look: whole against a
 * user number, whose case is the identity provider's and is compared exactly, and in part against a
 * name, as {@link SearchFolding} matches. A part of a user number therefore finds nobody by number.
 *
 * <p>Somebody whose user number is what was typed comes first, so no number of names holding it can
 * push them past the most a search shows. The rest are ordered by name under the server's ICU, and by
 * user number where two names tie, so the same search answers in the same order.
 *
 * <p>Shown as {@link PersonName#fromDirectory} spaces it but matched and ordered as held, the database
 * knowing nothing of that spacing: what is shown, typed back, can miss.
 *
 * <p>A stored user number or name its type refuses fails the whole search rather than being shown or
 * left out, for the reason the pool gives.
 */
@Component
final class People {

    // DB-SPECIFIC: limit, a comparison ordered as a boolean, and the collations named are PostgreSQL's.
    private static final String ORDER = """
            %1$s.user_id = :search desc, %1$s.display_name collate "unicode", %1$s.user_id collate ucs_basic""";

    /* The most shown are picked before anybody is looked for in the pool, so the joins below run for
     * those rows alone rather than for everybody the search finds. */
    private static final String FOUND = """
            select found.user_id, found.display_name, stay.subject_id
              from (select person.user_id, person.display_name
                      from people person
                     where person.user_id = :search or %1$s
                     order by %2$s
                     limit :limit) found
              left join subjects known on known.user_id = found.user_id
              left join pool_members stay on stay.subject_id = known.subject_id and stay.removed_at is null
             order by %3$s
            """.formatted(
                    SearchFolding.holds("person.display_name_folded", ":search"),
                    ORDER.formatted("person"),
                    ORDER.formatted("found"));

    private final JdbcClient database;

    People(JdbcClient database) {
        this.database = database;
    }

    /** At most {@code most} people, and whether the directory holds more that the search finds. */
    PeopleFound<InDirectory> search(PeopleSearch search, int most) {
        requireNonNull(search, "People search must not be null");
        List<InDirectory> found = database.sql(FOUND)
                .param("search", search.text())
                .param("limit", PeopleFound.fetching(most))
                .query((result, number) -> inDirectory(result))
                .list();
        return PeopleFound.of(found, most);
    }

    private static InDirectory inDirectory(ResultSet result) throws SQLException {
        UserId user;
        try {
            user = new UserId(result.getString("user_id"));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Somebody in the directory holds a user number this system will not show", refused);
        }
        PersonName name;
        try {
            name = PersonName.fromDirectory(result.getString("display_name"));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "User " + user.value() + " holds a name in the directory this system will not show", refused);
        }
        UUID pooled = result.getObject("subject_id", UUID.class);
        return new InDirectory(user, name, pooled == null ? null : new SubjectId(pooled));
    }

    /**
     * @param displayName absent where the directory holds nothing visible for them
     * @param subjectId who they are in the pool, absent where they are not in it now
     */
    record InDirectory(
            UserId userId,
            @Nullable PersonName displayName,
            @Nullable SubjectId subjectId) {}
}
