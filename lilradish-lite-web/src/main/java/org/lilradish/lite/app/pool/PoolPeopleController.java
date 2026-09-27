package org.lilradish.lite.app.pool;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.listing.ListParameters;
import org.lilradish.lite.app.listing.PageAnswer;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.EstateRole;
import org.lilradish.lite.domain.identity.GroupName;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.listing.ListCursor;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.people.PersonName;
import org.lilradish.lite.domain.pool.PoolSortColumn;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.MintedIdentifiers;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pool one page at a time, and one person in it, answered only to a caller holding the act of
 * keeping the pool.
 *
 * <p>A person is addressed by the identifier this system minted for them and not by their user
 * number. The number's format is the identity provider's, free to hold characters a path segment
 * reads as structure of its own or that the container refuses outright, and an address every caller
 * has to escape is one some caller will not. The identifier is read as {@link MintedIdentifiers} reads
 * one, and anything else addresses nobody.
 *
 * <p>The query parameters are read as {@link QueryParameters} reads them, so a parameter sent twice is
 * refused, and so is one this list does not take.
 */
@RestController
final class PoolPeopleController {

    /** The pool's addresses, which every handler answering at one of them is mapped by. */
    static final String POOL_PEOPLE = CallerAdmission.THIS_APPLICATION_ANSWERS + "/pool/people";

    static final String POOL_PERSON = POOL_PEOPLE + "/{subjectId}";

    static final String POOL_PERSON_ESTATE_ROLE = POOL_PERSON + "/estate-roles/{role}";

    private final PoolPeople people;

    PoolPeopleController(PoolPeople people) {
        this.people = people;
    }

    @GetMapping(POOL_PEOPLE)
    @ActRequired(EstateAct.KEEP_POOL)
    PageAnswer<PoolPersonAnswer> people(HttpServletRequest request) {
        Map<String, String[]> sent =
                QueryParameters.sent(request, ListParameters.TAKEN, ListParameters.PARAMETER_REFUSED);
        ListQuery<PoolSortColumn> query =
                ListParameters.spacedAsNames(ListParameters.query(PoolPeople.LISTED, PoolPeople.WHOLE_POOL, sent));
        ListPosition after = ListParameters.after(PoolPeople.LISTED, query, sent);
        ListPage<PoolPeople.PoolPerson> page = people.page(query, after);
        ListPosition next = page.next();
        return new PageAnswer<>(
                page.rows().stream().map(PoolPeopleController::answer).toList(),
                next == null ? null : ListCursor.mint(PoolPeople.LISTED, query, next));
    }

    @GetMapping(POOL_PERSON)
    @ActRequired(EstateAct.KEEP_POOL)
    PoolPersonPanelAnswer person(@PathVariable String subjectId) {
        return panelAnswer(addressed(subjectId).flatMap(people::person).orElseThrow(PoolPeople::notInView));
    }

    static Optional<SubjectId> addressed(String spelled) {
        return MintedIdentifiers.read(spelled).map(SubjectId::new);
    }

    static PoolPersonPanelAnswer panelAnswer(PoolPeople.PoolPersonPanel panel) {
        return new PoolPersonPanelAnswer(
                panel.subjectId().value(),
                panel.userId().value(),
                spelled(panel.displayName()),
                published(panel.estateRoles()),
                published(panel.lastGrantingRoles()),
                panel.groups().stream().map(GroupName::value).toList(),
                panel.seeded());
    }

    private static PoolPersonAnswer answer(PoolPeople.PoolPerson person) {
        return new PoolPersonAnswer(
                person.subjectId().value(),
                person.userId().value(),
                spelled(person.displayName()),
                published(person.estateRoles()),
                person.groupCount());
    }

    private static @Nullable String spelled(@Nullable PersonName name) {
        return name == null ? null : name.value();
    }

    private static List<String> published(Set<EstateRole> roles) {
        return roles.stream().map(EstateRole::published).toList();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PoolPersonAnswer(
            UUID subjectId, String userId, @Nullable String displayName, List<String> estateRoles, long groupCount) {}

    /**
     * Whether a migration seeded the row and never who brought them in: a seeded row was made by nobody.
     *
     * @param lastGrantingRoles the held roles withdrawing which is not offered, as nobody else in the pool
     *     could grant an estate role without them
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PoolPersonPanelAnswer(
            UUID subjectId,
            String userId,
            @Nullable String displayName,
            List<String> estateRoles,
            List<String> lastGrantingRoles,
            List<String> groups,
            boolean seeded) {}
}
