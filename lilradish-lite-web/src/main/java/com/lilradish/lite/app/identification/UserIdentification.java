package com.lilradish.lite.app.identification;

import com.lilradish.lite.domain.identity.UserId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * One implementation per deployment owns all knowledge of credentials and their carrier; the rest
 * of the application receives only a user or nothing.
 *
 * <p>Absent and unreadable credentials both yield nothing: distinguishing them would disclose carrier
 * details to anybody who can change what is sent.
 */
public interface UserIdentification {

    Optional<UserId> identify(HttpServletRequest request);
}
