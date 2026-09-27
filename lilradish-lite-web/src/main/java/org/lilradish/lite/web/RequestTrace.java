package org.lilradish.lite.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.observability.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * What a request under this application's prefix is traced by, carried where every line it logs reads
 * it, and the one line each such request leaves, a refused one included.
 *
 * <p>A filter, because the door's refusals never reach the dispatcher and a method an address does not
 * take is refused before any handler is selected.
 *
 * <p>Only under the prefix: minting draws from a shared generator every file of every page would pay for.
 *
 * <p>The failure dispatch arrives at the container's own address, so the identifier left on the request
 * is what marks it traced.
 *
 * <p>The path is parsed once here for this filter and the door; whatever was cached before is put back
 * after, the cache being the request's and not this filter's.
 *
 * <p>Who is calling is carried quoted, its quotes escaped: its spelling is the identity provider's, and
 * it is printed between brackets.
 *
 * <p>An inbound identifier that cannot be carried is replaced rather than failing the request, and an
 * address is logged as the pattern it matched, never as it was sent.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class RequestTrace extends OncePerRequestFilter {

    private static final String FILTERED = RequestTrace.class.getName() + ".FILTERED";

    private static final String TRACE = RequestTrace.class.getName() + ".trace";

    /* Both named again in the log pattern the configuration sets; renamed here alone, lines stop showing them. */
    private static final String LOGGED_TRACE = "correlationId";

    private static final String LOGGED_CALLER = "userId";

    private static final Set<String> STANDARD_METHODS =
            Arrays.stream(HttpMethod.values()).map(HttpMethod::name).collect(Collectors.toUnmodifiableSet());

    /* A method token is the caller's and unbounded, so one the framework does not name is logged as this. */
    private static final String OTHER_METHOD = "OTHER";

    private static final Logger logger = LoggerFactory.getLogger(RequestTrace.class);

    /** Whoever the door let through, carried where every line the request logs from here on reads it. */
    static void carryCaller(UserId caller) {
        String spelled = caller.value();
        StringBuilder quoted = new StringBuilder(spelled.length() + 2).append('"');
        for (int index = 0; index < spelled.length(); index++) {
            char character = spelled.charAt(index);
            if (character == '"' || character == '\\') {
                quoted.append('\\');
            }
            quoted.append(character);
        }
        MDC.put(LOGGED_CALLER, quoted.append('"').toString());
    }

    @Override
    protected String getAlreadyFilteredAttributeName() {
        return FILTERED;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain onward)
            throws ServletException, IOException {
        @Nullable RequestPath found = (RequestPath) request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        RequestPath parsed = ServletRequestPathUtils.parseAndCache(request);
        try {
            boolean guarded = CallerAdmission.answersThisApplication(parsed.pathWithinApplication());
            if (guarded || request.getAttribute(TRACE) != null) {
                trace(request, response, onward, guarded);
            } else {
                onward.doFilter(request, response);
            }
        } finally {
            ServletRequestPathUtils.setParsedRequestPath(found, request);
        }
    }

    private static void trace(
            HttpServletRequest request, HttpServletResponse response, FilterChain onward, boolean guarded)
            throws ServletException, IOException {
        long started = System.nanoTime();
        @Nullable Throwable escaped = null;
        try {
            carryTrace(request);
            UserId admittedBefore = CallerAdmission.admitted(request);
            if (admittedBefore != null) {
                carryCaller(admittedBefore);
            }
            onward.doFilter(request, response);
        } catch (Throwable failure) {
            escaped = failure;
            // Kept although the container logs it again: that line comes after the trace here is cleared.
            logger.error("A request failed before it could be answered", failure);
            throw failure;
        } finally {
            try {
                if (guarded) {
                    answered(request, response, started, escaped);
                }
            } finally {
                MDC.remove(LOGGED_CALLER);
                MDC.remove(LOGGED_TRACE);
                Correlation.clear();
            }
        }
    }

    private static void carryTrace(HttpServletRequest request) {
        Object settled = request.getAttribute(TRACE);
        String offered = settled instanceof String held ? held : request.getHeader(Correlation.HEADER);
        String traced = offered == null ? Correlation.currentOrNew() : carried(offered);
        request.setAttribute(TRACE, traced);
        MDC.put(LOGGED_TRACE, traced);
    }

    private static String carried(String offered) {
        try {
            Correlation.set(offered);
            return offered;
        } catch (IllegalArgumentException unusable) {
            return Correlation.currentOrNew();
        }
    }

    /* An escaped failure is answered by the container after this has run, and that answer is a fault. */
    private static void answered(
            HttpServletRequest request, HttpServletResponse response, long started, @Nullable Throwable escaped) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        String method = request.getMethod();
        int status = escaped == null ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        LoggingEventBuilder line =
                logger.atInfo().addKeyValue("method", STANDARD_METHODS.contains(method) ? method : OTHER_METHOD);
        if (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String route) {
            line = line.addKeyValue("route", route);
        }
        line.addKeyValue("status", status)
                .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .log("Answered");
    }
}
