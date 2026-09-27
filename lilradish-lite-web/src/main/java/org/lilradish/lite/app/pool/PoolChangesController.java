package org.lilradish.lite.app.pool;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.EstateRole;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.JsonBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Bringing somebody into the pool and taking them out, and granting and withdrawing the estate's roles
 * for somebody in it. Each answers with the person as the pool now reads them, except a removal, after
 * which there is nobody in the pool to read.
 *
 * <p>A role is addressed by the spelling it is published under, and is read before the person: one
 * no role has names nothing here, whoever the address names, and is answered as an address that
 * names nothing. A person is addressed as the pool's reading addresses them, and nobody in view is
 * refused as it refuses them.
 *
 * <p>No change takes a parameter, and only bringing somebody in takes a body, read as {@link JsonBody}
 * reads one rather than bound: bound, a member this does not take would be dropped without a word, and
 * it is refused instead.
 */
@RestController
final class PoolChangesController {

    private static final String USER_ID = "userId";

    /** Room for the longest user number this takes with every character of it escaped, and its member. */
    private static final int LARGEST_BODY = 4096;

    private static final EstateRole[] ROLES = EstateRole.values();

    private static final String BODY_REFUSED = "This takes a JSON object naming one user by their user number.";

    private final PoolChanges changes;

    PoolChangesController(PoolChanges changes) {
        this.changes = changes;
    }

    @PostMapping(PoolPeopleController.POOL_PEOPLE)
    @ActRequired(EstateAct.KEEP_POOL)
    ResponseEntity<PoolPeopleController.PoolPersonPanelAnswer> bringIn(HttpServletRequest request) throws IOException {
        ChangeRequests.requireNoParameter(request);
        PoolPeople.PoolPersonPanel panel = changes.bringIn(userNamedIn(request), CallerAdmission.callerOf(request));
        return ResponseEntity.created(URI.create(PoolPeopleController.POOL_PEOPLE + "/"
                        + panel.subjectId().value()))
                .body(PoolPeopleController.panelAnswer(panel));
    }

    @DeleteMapping(PoolPeopleController.POOL_PERSON)
    @ActRequired(EstateAct.KEEP_POOL)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String subjectId, HttpServletRequest request) throws IOException {
        SubjectId person = personAt(subjectId);
        ChangeRequests.requireNothingSent(request);
        changes.remove(person, CallerAdmission.callerOf(request));
    }

    // This and the withdrawal answer with the pool's reading: every role granting one also keeps the pool.
    @PutMapping(PoolPeopleController.POOL_PERSON_ESTATE_ROLE)
    @ActRequired(EstateAct.GRANT_ESTATE_ROLE)
    PoolPeopleController.PoolPersonPanelAnswer grant(
            @PathVariable String subjectId, @PathVariable String role, HttpServletRequest request) throws IOException {
        EstateRole granted = roleAt(role);
        SubjectId person = personAt(subjectId);
        ChangeRequests.requireNothingSent(request);
        return PoolPeopleController.panelAnswer(changes.grant(person, granted, CallerAdmission.callerOf(request)));
    }

    @DeleteMapping(PoolPeopleController.POOL_PERSON_ESTATE_ROLE)
    @ActRequired(EstateAct.GRANT_ESTATE_ROLE)
    PoolPeopleController.PoolPersonPanelAnswer withdraw(
            @PathVariable String subjectId, @PathVariable String role, HttpServletRequest request) throws IOException {
        EstateRole withdrawn = roleAt(role);
        SubjectId person = personAt(subjectId);
        ChangeRequests.requireNothingSent(request);
        return PoolPeopleController.panelAnswer(changes.withdraw(person, withdrawn, CallerAdmission.callerOf(request)));
    }

    private static SubjectId personAt(String subjectId) {
        return PoolPeopleController.addressed(subjectId).orElseThrow(PoolPeople::notInView);
    }

    private static EstateRole roleAt(String spelled) {
        for (EstateRole role : ROLES) {
            if (role.published().equals(spelled)) {
                return role;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static UserId userNamedIn(HttpServletRequest request) throws IOException {
        JsonNode document = JsonBody.read(request, LARGEST_BODY, BODY_REFUSED);
        JsonNode named = document.get(USER_ID);
        if (!document.isObject() || document.size() != 1 || named == null || !named.isString()) {
            throw bodyRefused(null);
        }
        try {
            return new UserId(named.asString());
        } catch (IllegalArgumentException refused) {
            throw bodyRefused(refused);
        }
    }

    private static ApiErrorException bodyRefused(@Nullable Throwable cause) {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, BODY_REFUSED, cause);
    }
}
