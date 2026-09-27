package org.lilradish.lite.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.estate.EstateReach;
import org.lilradish.lite.app.estate.EstateRoleGrants;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.UserId;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.PreFlightRequestHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

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
 * <p>A handler under the prefix that declares none of what it asks — an act, a permission inside a
 * group, membership of a group, or that it asks nothing — or more than one, is a decision somebody
 * forgot, and it fails rather than passing. Nobody is refused by that: it is this application's own
 * omission and carries no refusal code, so what reaches the caller says only that the request could not
 * be served. So does anything under the prefix with no handler method; {@link AskDeclarationVeto} refuses at
 * start what the mappings enumerate, and this asks again on every request for the rest.
 *
 * <p>An act nobody holds and an act that does not exist are the same refusal, carrying one code
 * rather than one per act — a vocabulary of per-act codes would be a hand-kept copy of this
 * application's surface, stale the first time an act is added.
 *
 * <p>What that refusal tells the caller is the kind of thing they may not do and never which act was
 * asked of them, for the reason {@link EstateReach} gives. The published spelling of an act is the
 * contract a reader gates its own screens on, and handed back on a refusal it becomes a fact about
 * this system's rules that anybody may collect by asking. Should a reader ever need the kinds apart,
 * that is a second refusal code — the thing it already keys its sentences on.
 *
 * <p>A permission inside a group, and membership of one, are asked of the group the address names, read
 * off the variables the dispatcher matched the address by, so the group judged here is the group the
 * handler was selected for. An address naming no identifier, a group nobody holds, and a group the
 * caller holds no role in are answered alike, as {@link GroupReach} answers them; whatever the caller
 * holds in the estate counts for nothing there. The group admitted is left on the request for the
 * handler, which reads that one.
 *
 * <p>It registers itself rather than being registered by a configuration of its own. The one thing
 * such a configuration would say is this line, and a second type to hold it is a second place for
 * the registration to be forgotten.
 */
@Component
public final class ActAdmission implements HandlerInterceptor, WebMvcConfigurer {

    private static final String GROUP_ID = "groupId";

    /** Where every handler asking anything inside a group answers, under the group it asks it in. */
    public static final String IN_A_GROUP = CallerAdmission.THIS_APPLICATION_ANSWERS + "/groups/{" + GROUP_ID + "}";

    private static final String ADMITTED_GROUP = ActAdmission.class.getName() + ".group";

    private final EstateRoleGrants grants;

    private final GroupRoles groupRoles;

    ActAdmission(EstateRoleGrants grants, GroupRoles groupRoles) {
        this.grants = grants;
        this.groupRoles = groupRoles;
    }

    /** The group the gate admitted the caller into. Every handler asking anything inside one has one. */
    public static GroupId admittedGroup(HttpServletRequest request) {
        GroupId admitted = (GroupId) request.getAttribute(ADMITTED_GROUP);
        if (admitted == null) {
            throw new IllegalStateException("No group was admitted for this request");
        }
        return admitted;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws NoResourceFoundException {
        if (!CallerAdmission.answersThisApplication(request)) {
            return true;
        }
        UserId caller = CallerAdmission.admitted(request);
        if (caller == null) {
            throw new ApiErrorException(RefusalCode.NOT_SIGNED_IN, "Nobody is signed in.");
        }
        if (!(handler instanceof HandlerMethod selected)) {
            return answeredWithoutAMethod(request, handler);
        }
        // The framework's own answer to OPTIONS lists methods and runs nothing; one of ours is asked its act.
        if (HttpMethod.OPTIONS.matches(request.getMethod()) && answersOptionsForTheFramework(selected)) {
            return true;
        }
        ActRequired act = selected.getMethodAnnotation(ActRequired.class);
        GroupPermissionRequired inGroup = selected.getMethodAnnotation(GroupPermissionRequired.class);
        boolean asksMembership = selected.getMethodAnnotation(GroupMembershipRequired.class) != null;
        boolean asksNothing = selected.getMethodAnnotation(NoActRequired.class) != null;
        if (!declaresExactlyOne(act, inGroup, asksMembership, asksNothing)) {
            throw misdeclared(selected);
        }
        if (act != null) {
            EstateReach.requireReached(grants.heldBy(caller), act.value());
        } else if (inGroup != null) {
            request.setAttribute(ADMITTED_GROUP, admittedInto(request, selected, caller, inGroup.value()));
        } else if (asksMembership) {
            request.setAttribute(ADMITTED_GROUP, admittedInto(request, selected, caller, null));
        }
        return true;
    }

    /** Whether a handler says exactly one thing it asks, as every handler under the prefix has to. */
    static boolean declaresExactlyOne(HandlerMethod handler) {
        return declaresExactlyOne(
                handler.getMethodAnnotation(ActRequired.class),
                handler.getMethodAnnotation(GroupPermissionRequired.class),
                handler.getMethodAnnotation(GroupMembershipRequired.class) != null,
                handler.getMethodAnnotation(NoActRequired.class) != null);
    }

    private static boolean declaresExactlyOne(
            @Nullable ActRequired act,
            @Nullable GroupPermissionRequired inGroup,
            boolean asksMembership,
            boolean asksNothing) {
        return (act == null ? 0 : 1) + (inGroup == null ? 0 : 1) + (asksMembership ? 1 : 0) + (asksNothing ? 1 : 0)
                == 1;
    }

    static IllegalStateException misdeclared(HandlerMethod handler) {
        return new IllegalStateException(handler.getMethod()
                + " declares not exactly one of the act it asks, the permission it asks in a group, membership"
                + " of a group, or that it asks none");
    }

    /**
     * A preflight runs nothing, so it passes. The static handling claims every address nothing else does and
     * serves nothing under the prefix, so it is answered as missing before it can.
     */
    private static boolean answeredWithoutAMethod(HttpServletRequest request, Object handler)
            throws NoResourceFoundException {
        if (handler instanceof PreFlightRequestHandler) {
            return true;
        }
        if (handler instanceof ResourceHttpRequestHandler) {
            throw new NoResourceFoundException(
                    HttpMethod.valueOf(request.getMethod()), request.getRequestURI(), request.getRequestURI());
        }
        throw methodless(handler);
    }

    static IllegalStateException methodless(Object handler) {
        return new IllegalStateException(
                handler + " answers under the prefix with no handler method to declare what it asks on");
    }

    /** Asked for membership alone where the handler asks no permission there. */
    private GroupId admittedInto(
            HttpServletRequest request, HandlerMethod selected, UserId caller, @Nullable GroupPermission permission) {
        String named = matchedVariable(request);
        if (named == null) {
            throw new IllegalStateException(
                    selected.getMethod() + " asks something in a group at an address naming none");
        }
        GroupId group = MintedIdentifiers.read(named).map(GroupId::new).orElseThrow(GroupReach::notInView);
        Set<GroupRole> held = groupRoles.heldBy(caller, group);
        if (permission == null) {
            GroupReach.requireMember(held);
        } else {
            GroupReach.requireReached(held, group, permission);
        }
        return group;
    }

    private static @Nullable String matchedVariable(HttpServletRequest request) {
        return request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> matched
                        && matched.get(GROUP_ID) instanceof String named
                ? named
                : null;
    }

    /* That handler is a private type nested in the framework's mapping, so it is known by where it is nested. */
    private static boolean answersOptionsForTheFramework(HandlerMethod selected) {
        return selected.getBeanType().getEnclosingClass() == RequestMappingInfoHandlerMapping.class;
    }
}
