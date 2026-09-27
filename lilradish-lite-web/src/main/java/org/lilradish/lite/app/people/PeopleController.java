package org.lilradish.lite.app.people;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.listing.SearchParameters;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.people.PeopleFound;
import org.lilradish.lite.domain.people.PersonName;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A search of the directory, answered only to a caller holding the act of keeping the pool: finding
 * somebody there is the first half of bringing them in, and nobody else is owed a look.
 *
 * <p>Somebody already in the pool is found like anybody else, carrying the identifier that addresses
 * them there, which is how they are said to be there. The search is read as {@link SearchParameters}
 * reads one.
 */
@RestController
final class PeopleController {

    private final People people;

    PeopleController(People people) {
        this.people = people;
    }

    @GetMapping(CallerAdmission.THIS_APPLICATION_ANSWERS + "/people")
    @ActRequired(EstateAct.KEEP_POOL)
    FoundAnswer people(HttpServletRequest request) {
        PeopleFound<People.InDirectory> found =
                people.search(SearchParameters.searchSent(request), SearchParameters.MOST_FOUND);
        return new FoundAnswer(
                found.people().stream().map(PeopleController::answer).toList(), found.more());
    }

    private static InDirectoryAnswer answer(People.InDirectory person) {
        PersonName name = person.displayName();
        SubjectId pooled = person.subjectId();
        return new InDirectoryAnswer(
                person.userId().value(), name == null ? null : name.value(), pooled == null ? null : pooled.value());
    }

    /** @param more whether the directory holds somebody else the search finds, beyond those listed */
    record FoundAnswer(List<InDirectoryAnswer> items, boolean more) {}

    /**
     * @param displayName absent rather than empty where no name is held, so nothing stands in its place
     * @param subjectId what addresses them in the pool, present exactly while they are in it, which is
     *     how a reader is told they are
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InDirectoryAnswer(
            String userId,
            @Nullable String displayName,
            @Nullable UUID subjectId) {}
}
