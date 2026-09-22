package org.lilradish.lite.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.observability.Correlation;
import org.lilradish.lite.identification.UserIdentification;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The one door every request comes through, and the only place this application asks who is calling.
 *
 * <p>A filter rather than anything that runs at dispatch, and the difference is not a preference. An
 * address under this application's own prefix that names no operation is matched by no handler at
 * all, so it falls through to the static handling that claims every other address and is answered
 * that there is no static resource of that name — a sentence that names the framework's own
 * machinery and hands the caller back the path they sent. Nothing selected by a handler can reach
 * that address, because nothing was selected.
 *
 * <p>Whether an address is under that prefix is the dispatcher's own judgement rather than a reading
 * of the request line. The container hands back the URI as it arrived — undecoded, path parameters
 * and all — while a handler is selected by matching a {@link PathPattern} against the segments of a
 * parsed {@link RequestPath}, which are decoded and stripped of those parameters first. A door that
 * compared the raw string would leave {@code /%61pi/…} and {@code /api;x=y/…} unguarded and still
 * routed, so the same pattern is matched against the same parsed path here. The parse is the one the
 * {@code DispatcherServlet} cached where it has already run, and taken afresh where it has not: a
 * filter stands in front of it, so out here there is nothing yet to read.
 *
 * <p>Who is calling is asked under that prefix and nowhere else. Every other address is the document
 * and the files it loads, served to whoever asks, so resolving an identity there would be work done
 * on every asset of every page load for an answer that nothing reads.
 *
 * <p>What a request is traced by is settled under that prefix for the same reason, minting costing a
 * draw from a shared generator that every asset of every page load would otherwise pay for. It is
 * let go of in a {@code finally}: the thread is pooled and outlives the request it served, so an
 * identifier left behind is carried into the next request as though it belonged to it. That let-go
 * has to cover the error dispatch the container runs after the first one unwinds, which is why this
 * runs on that dispatch too — and because that dispatch arrives at the container's own address
 * rather than at the one that failed, what says it belongs to a guarded request is the identifier
 * the first dispatch left on it. Held there, a request and the page reporting its failure are one
 * trace rather than two.
 *
 * <p>An inbound identifier is the caller's to write, and the type that holds it refuses an unusable
 * one by raising rather than by trimming. Raised from here that would turn a header anybody can send
 * into a fault of this system, so one that cannot be carried is replaced with a minted one instead.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class CallerAdmission extends OncePerRequestFilter {

    /**
     * The prefix this application answers under. Named once and read by every handler that answers
     * there and by {@link ClientRouteFallback}, which has to leave exactly these addresses missing.
     */
    public static final String THIS_APPLICATION_ANSWERS = "/api";

    private static final PathPattern EVERY_ADDRESS_UNDER_IT =
            PathPatternParser.defaultInstance.parse(THIS_APPLICATION_ANSWERS + "/**");

    private static final String CALLER = CallerAdmission.class.getName() + ".caller";

    private static final String TRACE = CallerAdmission.class.getName() + ".trace";

    private final UserIdentification identification;

    private final HandlerExceptionResolver refusals;

    CallerAdmission(
            UserIdentification identification,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver refusals) {
        this.identification = identification;
        this.refusals = refusals;
    }

    /** Whether this address is one this application answers, decided as the dispatcher decides it. */
    public static boolean answersThisApplication(HttpServletRequest request) {
        RequestPath parsed = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parse(request);
        return answersThisApplication(parsed.pathWithinApplication());
    }

    /** The same judgement for whoever already holds the path, rather than the request it came on. */
    static boolean answersThisApplication(PathContainer path) {
        return EVERY_ADDRESS_UNDER_IT.matches(path);
    }

    /** Whoever this door let through. Every handler under the prefix it guards has one. */
    public static UserId callerOf(HttpServletRequest request) {
        UserId admitted = admitted(request);
        if (admitted == null) {
            throw new IllegalStateException("No caller was admitted for this request");
        }
        return admitted;
    }

    /** What the gate asks, a handler never having to: absent here is a refusal rather than a fault. */
    static @Nullable UserId admitted(HttpServletRequest request) {
        return (UserId) request.getAttribute(CALLER);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain onward)
            throws ServletException, IOException {
        boolean guarded = answersThisApplication(request);
        if (!guarded && request.getAttribute(TRACE) == null) {
            onward.doFilter(request, response);
            return;
        }
        carryTrace(request);
        try {
            if (!guarded || admits(request, response)) {
                onward.doFilter(request, response);
            }
        } finally {
            Correlation.clear();
        }
    }

    private boolean admits(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        Optional<UserId> caller = identification.identify(request);
        if (caller.isEmpty()) {
            /* This refusal owes a challenge naming the scheme a credential arrives under; one is
             * added with the carrier. */
            ApiErrorException refusal = new ApiErrorException(RefusalCode.NOT_SIGNED_IN, "Nobody is signed in.");
            if (refusals.resolveException(request, response, null, refusal) == null) {
                // Nothing rendered it, which the chain says by answering null; returning is an empty 200.
                throw new ServletException(refusal);
            }
            return false;
        }
        request.setAttribute(CALLER, caller.get());
        return true;
    }

    private static void carryTrace(HttpServletRequest request) {
        /* Held whichever it is: the later dispatch arrives at another address, so what it reads off
         * the request is the only thing saying this one was traced and under what. */
        Object settled = request.getAttribute(TRACE);
        String offered = settled instanceof String held ? held : request.getHeader(Correlation.HEADER);
        if (offered != null) {
            try {
                Correlation.set(offered);
                request.setAttribute(TRACE, offered);
                return;
            } catch (IllegalArgumentException unusable) {
                // Replaced rather than raised; the class comment says why.
            }
        }
        request.setAttribute(TRACE, Correlation.currentOrNew());
    }
}
