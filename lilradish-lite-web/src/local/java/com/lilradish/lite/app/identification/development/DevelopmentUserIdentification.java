package com.lilradish.lite.app.identification.development;

import com.lilradish.lite.app.identification.UserIdentification;
import com.lilradish.lite.development.DevelopmentOnly;
import com.lilradish.lite.domain.identity.UserId;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Selects an identity for local development only; it does not authenticate a caller. This implementation
 * is compiled apart from the application and must never be included in a published archive. The build
 * checks the archive itself before it is shipped.
 */
@DevelopmentOnly
@Component
public final class DevelopmentUserIdentification implements UserIdentification {

    private static final String USER_COOKIE = "lilradish_user";

    @Override
    public Optional<UserId> identify(HttpServletRequest request) {
        Cookie[] presented = request.getCookies();
        if (presented == null) {
            return Optional.empty();
        }
        for (Cookie cookie : presented) {
            if (USER_COOKIE.equals(cookie.getName())) {
                return userNamedBy(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /*
     * A value no user could be named by is no identity at all. Raised as a malformed request it
     * would tell the reader their input was wrong, and the reader typed nothing.
     */
    private static Optional<UserId> userNamedBy(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UserId(value));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }
}
