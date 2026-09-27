package org.lilradish.lite.app.identification.development;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.identification.UserIdentification;
import org.lilradish.lite.development.DevelopmentOnly;
import org.lilradish.lite.domain.identity.UserId;
import org.springframework.stereotype.Component;

/**
 * What identifies a developer running this on their own machine: a cookie they set for themselves,
 * naming the user they want to be. It authenticates nobody — anyone who can reach the port can
 * write it — so there is no deployment it is right for. It is compiled apart from the application,
 * which cannot see it, and the build refuses to package anything under this package into an archive.
 *
 * <p>That refusal is checked against the archive rather than trusted to the setting that produces
 * it. A setting is edited, and an edit that puts this back would otherwise ship a system where
 * naming yourself is enough.
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
