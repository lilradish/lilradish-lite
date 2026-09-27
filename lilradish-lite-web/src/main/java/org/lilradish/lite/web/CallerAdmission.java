package org.lilradish.lite.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.app.identification.UserIdentification;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.WebUtils;
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
 * compared the raw string would leave other spellings of the prefix unguarded and still routed, so the
 * same pattern is matched against the same parsed path here. The parse is the one cached ahead of the
 * reader — by {@link RequestTrace} out here, by the {@code DispatcherServlet} within — and taken afresh
 * only where nothing cached one.
 *
 * <p>Who is calling is asked under that prefix and nowhere else. Every other address is the document
 * and the files it loads, served to whoever asks, so resolving an identity there would be work done
 * on every asset of every page load for an answer that nothing reads.
 *
 * <p>Nothing answered under that prefix may be stored on the way out, a refusal included, and that is
 * said here, before anything behind the door answers. Every such answer is decided afresh for the one
 * caller asking; one carrying no freshness of its own may be held and reused on a guess — a missing
 * address among them — and what keeps a shared cache off an authenticated answer is the
 * {@code Authorization} field, and no answer here may rely on it. {@code no-store} forbids keeping
 * any part of the answer and forbids answering anybody else with it. It is a floor: no handler behind
 * the door may answer with a freshness of its own. The page reporting such a request's failure is held
 * to it too, which is why this runs on that dispatch as well.
 *
 * <p>A request under that prefix whose method is not safe has to show it came from this
 * application's own pages, because whatever identifies a caller arrives with a request another site
 * made their browser send. Where the browser says which site made it, that is believed and has to be
 * this origin; where it says nothing, the origin it names has to be this request's own, which is the
 * scheme, host and port the container reports and so whatever forwarded-header handling is
 * configured decides; where neither is said, it is refused. Asked before anybody is identified: the
 * answer turns on where the request came from and not on who sent it, and a request refused here reads
 * alike whether or not anybody is signed in.
 */
@Component
// LargeBodyAdmission, which only an admitted caller may reach, has to stand right behind it.
@Order(LargeBodyAdmission.ORDER - 1)
@Import(LargeBodyAdmissionConfiguration.class)
public final class CallerAdmission extends OncePerRequestFilter {

    /**
     * The prefix this application answers under. Named once and read by every handler that answers
     * there and by {@link ClientRouteFallback}, which has to leave exactly these addresses missing.
     */
    public static final String THIS_APPLICATION_ANSWERS = "/api";

    private static final PathPattern EVERY_ADDRESS_UNDER_IT =
            PathPatternParser.defaultInstance.parse(THIS_APPLICATION_ANSWERS + "/**");

    private static final String FILTERED = CallerAdmission.class.getName() + ".FILTERED";

    private static final String CALLER = CallerAdmission.class.getName() + ".caller";

    /* Left on the request, because the failure dispatch arrives at the container's own address. */
    private static final String GUARDED = CallerAdmission.class.getName() + ".guarded";

    private static final String NEVER_KEPT = "no-store";

    /* The safe methods this application answers. A method token is case-sensitive, so any other
     * spelling of these is a method that is not safe. */
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private static final String FETCHED_FROM = "Sec-Fetch-Site";

    private static final String THIS_ORIGIN = "same-origin";

    private static final Logger logger = LoggerFactory.getLogger(CallerAdmission.class);

    private final UserIdentification identification;

    private final HandlerExceptionResolver refusals;

    CallerAdmission(
            UserIdentification identification,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver refusals) {
        this.identification = identification;
        this.refusals = refusals;
    }

    /** Whether this address is one this application answers, decided as the dispatcher decides it. */
    static boolean answersThisApplication(HttpServletRequest request) {
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
        boolean guarded = answersThisApplication(request);
        if (!guarded && request.getAttribute(GUARDED) == null) {
            onward.doFilter(request, response);
            return;
        }
        request.setAttribute(GUARDED, Boolean.TRUE);
        // An answer the container gives before any filter runs never passes here, and is left without it.
        response.setHeader(HttpHeaders.CACHE_CONTROL, NEVER_KEPT);
        if (!guarded || (sentFromHere(request, response) && admits(request, response))) {
            onward.doFilter(request, response);
        }
    }

    private boolean sentFromHere(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        if (SAFE.contains(request.getMethod()) || fromThisOrigin(request)) {
            return true;
        }
        logger.warn("Refused a request changing something that did not show it came from this application");
        refuse(
                refusals,
                request,
                response,
                new ApiErrorException(
                        RefusalCode.ORIGIN_UNVERIFIED, "This request did not come from this application's own pages."));
        return false;
    }

    private static boolean fromThisOrigin(HttpServletRequest request) {
        String site = request.getHeader(FETCHED_FROM);
        if (site != null) {
            return THIS_ORIGIN.equals(site);
        }
        if (request.getHeader(HttpHeaders.ORIGIN) == null) {
            return false;
        }
        // An origin the parser cannot read raises either of these.
        try {
            return WebUtils.isSameOrigin(new ServletServerHttpRequest(request));
        } catch (IllegalArgumentException | IllegalStateException unparseable) {
            return false;
        }
    }

    private boolean admits(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        Optional<UserId> caller = identification.identify(request);
        if (caller.isEmpty()) {
            /* This refusal owes a challenge naming the scheme a credential arrives under; one is
             * added with the carrier. */
            refuse(
                    refusals,
                    request,
                    response,
                    new ApiErrorException(RefusalCode.NOT_SIGNED_IN, "Nobody is signed in."));
            return false;
        }
        request.setAttribute(CALLER, caller.get());
        RequestTrace.carryCaller(caller.get());
        return true;
    }

    static void refuse(
            HandlerExceptionResolver refusals,
            HttpServletRequest request,
            HttpServletResponse response,
            ApiErrorException refusal)
            throws ServletException {
        if (refusals.resolveException(request, response, null, refusal) == null) {
            // Nothing rendered it, which the chain says by answering null; returning is an empty 200.
            throw new ServletException(refusal);
        }
    }
}
