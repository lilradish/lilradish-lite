package org.lilradish.lite.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.filling.Filling;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * One large body at a time, read and handled for one admitted caller while any other waits its turn.
 *
 * <p>Large is a declared length past a mebibyte, or no declared length on a method that may carry a body: a
 * body's length may be undeclared however it is framed, so what frames it is never asked.
 *
 * <p>Behind the door and only for a caller it admitted, so nobody unidentified ever holds or waits for the
 * turn. That is still ahead of the gate asking what the handler asks, so a caller the gate refuses may wait
 * first; nothing is read before the turn is taken.
 *
 * <p>A wait that runs out, is interrupted, or would be one more than may wait at once, is refused as busy
 * through the outlet every refusal of the door goes through: nothing was read or changed, and the same request
 * may be sent again.
 */
@Order(LargeBodyAdmission.ORDER)
final class LargeBodyAdmission extends OncePerRequestFilter {

    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    static final long ONE_MEBIBYTE = 1024L * 1024L;

    static final Duration LONGEST_WAIT = Duration.ofSeconds(30);

    private static final long MINIMUM_BYTES_PER_SECOND = 256L * 1024L;

    private static final Duration LONGEST_READ =
            Duration.ofSeconds(1).multipliedBy(Filling.LARGEST_REQUEST_BYTES).dividedBy(MINIMUM_BYTES_PER_SECOND);

    /* Every waiter holds a container worker for the whole wait. */
    private static final int MOST_WAITING = 16;

    private static final Set<String> CARRYING_A_BODY = Set.of("POST", "PUT", "PATCH");

    private static final String HOLDING = LargeBodyAdmission.class.getName() + ".holding";

    private final LargeBodyTurn turn = new LargeBodyTurn(LONGEST_WAIT, MOST_WAITING);

    private final HandlerExceptionResolver refusals;

    private final LongSupplier nanoTime;

    LargeBodyAdmission(HandlerExceptionResolver refusals, LongSupplier nanoTime) {
        this.refusals = refusals;
        this.nanoTime = nanoTime;
    }

    /**
     * What a body read holding the turn is read whole by, the one way a body past a mebibyte may be read; none
     * where the turn is not held.
     */
    static @Nullable ReadDeadline readDeadline(HttpServletRequest request) {
        return request.getAttribute(HOLDING) instanceof ReadDeadline deadline ? deadline : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain onward)
            throws ServletException, IOException {
        if (CallerAdmission.admitted(request) == null || !large(request)) {
            onward.doFilter(request, response);
            return;
        }
        if (!turn.take()) {
            CallerAdmission.refuse(
                    refusals,
                    request,
                    response,
                    new ApiErrorException(
                            RefusalCode.SERVICE_BUSY, "Another large request is being handled; send this one again."));
            return;
        }
        try {
            request.setAttribute(HOLDING, new ReadDeadline(nanoTime, nanoTime.getAsLong() + LONGEST_READ.toNanos()));
            onward.doFilter(request, response);
        } finally {
            request.removeAttribute(HOLDING);
            turn.give();
        }
    }

    private static boolean large(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        return declared > ONE_MEBIBYTE || (declared < 0 && CARRYING_A_BODY.contains(request.getMethod()));
    }

    record ReadDeadline(LongSupplier nanoTime, long endsAtNanos) {

        boolean passed() {
            return nanoTime.getAsLong() - endsAtNanos > 0;
        }
    }
}
