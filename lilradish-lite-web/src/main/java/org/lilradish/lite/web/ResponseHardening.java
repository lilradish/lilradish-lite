package org.lilradish.lite.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every answer carries these, a refusal and a missing address included; an answer the container gives
 * before any filter runs is not one this sees.
 *
 * <p>Ordered first, so that no filter behind it can answer without them. The framework's character-encoding
 * filter holds the same order, and which of the two runs first decides nothing: that one sets encodings and
 * never answers. It also runs on the dispatch the container makes for a failure, which may clear headers to
 * serve its page.
 *
 * <p>The frame rule is said twice because a browser reading the policy ignores the older header, and
 * one that cannot read the policy has only the older header. The policy says nothing else: anything
 * more is a decision about what the pages may load, and it is not made here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ResponseHardening extends OncePerRequestFilter {

    private static final String FILTERED = ResponseHardening.class.getName() + ".FILTERED";

    private static final String TYPE_OPTIONS = "X-Content-Type-Options";

    private static final String NOT_SNIFFED = "nosniff";

    private static final String FRAME_OPTIONS = "X-Frame-Options";

    private static final String NEVER_FRAMED = "DENY";

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

    private static final String NO_FRAME_ANCESTORS = "frame-ancestors 'none'";

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
        response.setHeader(TYPE_OPTIONS, NOT_SNIFFED);
        response.setHeader(FRAME_OPTIONS, NEVER_FRAMED);
        response.setHeader(CONTENT_SECURITY_POLICY, NO_FRAME_ANCESTORS);
        onward.doFilter(request, response);
    }
}
