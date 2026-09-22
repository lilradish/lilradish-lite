package org.lilradish.lite.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.EstateRole;
import org.lilradish.lite.estate.EstateRoleGrants;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everywhere this caller may go, decided afresh for the one request asking. There is no session in
 * this system and nothing below is cached, so a role granted or withdrawn holds from the next call.
 *
 * <p>That holds only as far as the answer is not stored on the way out. A 200 carrying no freshness
 * of its own may be held and reused on a guess, and what keeps a shared cache off an authenticated
 * answer is the {@code Authorization} field. So the answer says {@code no-store}, which forbids
 * keeping any part of it and forbids answering anybody else with it.
 *
 * <p>Two answers and no third. A caller nothing identified is refused, because there is nobody to
 * decide about; a caller who was identified is answered, whether or not this system has ever heard
 * of them: an unknown user and a known one holding nothing both come back with an empty set.
 *
 * <p>The empty set is also the whole of what a reader holding nothing is told. Nothing is refused
 * to them and nothing is named as withheld, so no part of the answer says what else exists.
 *
 * <p>This is the one handler that can ask for no act, because what it answers is which acts the
 * caller has: gated behind one, a reader would have to already know they hold it to be told so.
 */
@RestController
final class StandingController {

    private final EstateRoleGrants grants;

    StandingController(EstateRoleGrants grants) {
        this.grants = grants;
    }

    @GetMapping(CallerAdmission.THIS_APPLICATION_ANSWERS + "/standing")
    @NoActRequired
    ResponseEntity<Standing> standing(HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new Standing(EstateRole.actsOf(grants.heldBy(CallerAdmission.callerOf(request))).stream()
                        .map(EstateAct::published)
                        .toList()));
    }

    /** A set the reader holds, sent as an array: order carries nothing and is promised to nobody. */
    record Standing(List<String> acts) {}
}
