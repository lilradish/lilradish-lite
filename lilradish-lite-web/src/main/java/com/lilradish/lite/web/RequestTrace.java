package com.lilradish.lite.web;

import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.observability.Correlation;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
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
    private static final String ANSWER = RequestTrace.class.getName() + ".answer";

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
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    static void carry(HttpServletRequest request) {
        carry(captured(request));
    }

    /** The logging context kept while its worker may outlive the container request. */
    record Carried(String trace, @Nullable UserId caller) {}

    static Carried captured(HttpServletRequest request) {
        carryTrace(request);
        return new Carried(MDC.get(LOGGED_TRACE), CallerAdmission.admitted(request));
    }

    static void carry(Carried carried) {
        Correlation.set(carried.trace());
        MDC.put(LOGGED_TRACE, carried.trace());
        if (carried.caller() != null) {
            carryCaller(carried.caller());
        }
    }

    static void clear() {
        MDC.remove(LOGGED_CALLER);
        MDC.remove(LOGGED_TRACE);
        Correlation.clear();
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
        Answer answer = request.getAttribute(ANSWER) instanceof Answer held ? held : new Answer(request, response);
        if (guarded) {
            request.setAttribute(ANSWER, answer);
        }
        @Nullable Throwable escaped = null;
        try {
            carry(request);
            onward.doFilter(request, response);
        } catch (Throwable failure) {
            escaped = failure;
            answer.failure = failure;
            // Kept although the container logs it again: that line comes after the trace here is cleared.
            logger.error("A request failed before it could be answered", failure);
            throw failure;
        } finally {
            try {
                if (guarded) {
                    if (request.isAsyncStarted()) {
                        answer.asynchronous = true;
                        request.getAsyncContext().addListener(answer);
                    } else if (!answer.asynchronous) {
                        answer.finish(escaped);
                    }
                }
            } finally {
                clear();
            }
        }
    }

    private static final class Answer implements AsyncListener {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final long started = System.nanoTime();
        private final AtomicBoolean logged = new AtomicBoolean();
        private volatile boolean asynchronous;
        private volatile @Nullable Throwable failure;

        private Answer(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        private void finish(@Nullable Throwable escaped) {
            if (logged.compareAndSet(false, true)) {
                try {
                    carry(request);
                    answered(request, response, started, escaped);
                } finally {
                    clear();
                }
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            finish(failure);
        }

        @Override
        public void onError(AsyncEvent event) {
            failure = event.getThrowable();
        }

        @Override
        public void onTimeout(AsyncEvent event) {}

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
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
