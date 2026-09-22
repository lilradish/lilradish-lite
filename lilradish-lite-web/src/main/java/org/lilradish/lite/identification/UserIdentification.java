package org.lilradish.lite.identification;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.lilradish.lite.domain.identity.UserId;

/**
 * How this deployment learns which user is calling. One implementation stands behind it per
 * deployment, and it is the only thing that knows what a credential looks like or how it travelled
 * — a gateway may put it on the request, a handshake may establish it, the agent may already be
 * bound to the origin. Whatever it is, it is read here and named nowhere else.
 *
 * <p>The answer is a user or it is nothing, which is what keeps the rest of the system from
 * learning any of that. A credential that never arrived and one that cannot be read as a user
 * are the same nothing: they differ only in a fact about the carrier, and publishing the difference
 * hands that fact to anybody who can change what is sent.
 */
public interface UserIdentification {

    Optional<UserId> identify(HttpServletRequest request);
}
