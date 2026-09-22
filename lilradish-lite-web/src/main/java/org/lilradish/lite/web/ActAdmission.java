package org.lilradish.lite.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.identity.EstateRole;
import org.lilradish.lite.estate.EstateRoleGrants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Whether the caller the door let through may do what the handler they reached is for, decided afresh
 * for the one request asking. Nothing is kept: a grant withdrawn takes hold on the very next call.
 *
 * <p>This runs at dispatch and not at the door, because what a handler asks is written on the
 * handler and there is no handler until one has been selected. That is also why it cannot be the
 * whole gate: an address that selects nothing is never seen here, and being identified is settled
 * one layer out for exactly that reason.
 *
 * <p>Which does not make the door a premise this may rest on. Whether the address is one the door
 * guards is asked again here, from the same judgement, and a caller it did not admit is refused here
 * whatever the handler declared — two layers that assume each other fail open the day either moves,
 * and what a handler outside the prefix would be asked of is a caller nothing ever resolved.
 *
 * <p>A handler under the prefix that declares neither what it asks nor that it asks nothing is a
 * decision somebody forgot, and it fails rather than passing. Nobody is refused by that: it is this
 * application's own omission and carries no refusal code, so what reaches the caller says only that
 * the request could not be served.
 *
 * <p>An act nobody holds and an act that does not exist are the same refusal, carrying one code
 * rather than one per act — a vocabulary of per-act codes would be a hand-kept copy of this
 * application's surface, stale the first time an act is added.
 *
 * <p>What that refusal tells the caller is the kind of thing they may not do and never which act was
 * asked of them. The published spelling of an act is the contract a reader gates its own screens on,
 * and handed back on a refusal it becomes a fact about this system's rules that anybody may collect
 * by asking. The act is written to the log instead, where the question of which rule refused is
 * answered for whoever operates this rather than for whoever called it. Should a reader ever need
 * the kinds apart, that is a second refusal code — the thing it already keys its sentences on.
 *
 * <p>It registers itself rather than being registered by a configuration of its own. The one thing
 * such a configuration would say is this line, and a second type to hold it is a second place for
 * the registration to be forgotten.
 */
@Component
public final class ActAdmission implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(ActAdmission.class);

    private final EstateRoleGrants grants;

    ActAdmission(EstateRoleGrants grants) {
        this.grants = grants;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Every handler this applies to is a method; the static handling claims the rest.
        if (!(handler instanceof HandlerMethod selected) || !CallerAdmission.answersThisApplication(request)) {
            return true;
        }
        UserId caller = CallerAdmission.admitted(request);
        if (caller == null) {
            throw new ApiErrorException(RefusalCode.NOT_SIGNED_IN, "Nobody is signed in.");
        }
        ActRequired required = selected.getMethodAnnotation(ActRequired.class);
        if (required == null) {
            if (selected.getMethodAnnotation(NoActRequired.class) == null) {
                throw new IllegalStateException(
                        selected.getMethod() + " declares neither the act it asks nor that it asks none");
            }
            return true;
        }
        if (!EstateRole.actsOf(grants.heldBy(caller)).contains(required.value())) {
            logger.warn("Refused a caller holding no role that reaches {}", required.value());
            throw new ApiErrorException(RefusalCode.ACT_NOT_PERMITTED, "This caller may not do that.");
        }
        return true;
    }
}
