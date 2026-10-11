package com.lilradish.lite.web;

import com.lilradish.lite.app.files.TransferPace;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.filling.Filling;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
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

    private static final Duration LONGEST_READ =
            Duration.ofSeconds(1).multipliedBy(Filling.LARGEST_REQUEST_BYTES).dividedBy(TransferPace.BYTES_PER_SECOND);

    /* Every waiter holds a container worker for the whole wait. */
    private static final int MOST_WAITING = 16;

    private static final Set<String> CARRYING_A_BODY = Set.of("POST", "PUT", "PATCH");

    private static final String HOLDING = LargeBodyAdmission.class.getName() + ".holding";
    private static final String STARTED = LargeBodyAdmission.class.getName() + ".started";

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

    static long bodyStarted(HttpServletRequest request, LongSupplier nanoTime) {
        return request.getAttribute(STARTED) instanceof Long started ? started : nanoTime.getAsLong();
    }

    static boolean workerStarted(@Nullable ReadDeadline deadline) {
        return deadline == null || deadline.workerStarted();
    }

    static void workerFinished(@Nullable ReadDeadline deadline) {
        if (deadline != null) {
            deadline.workerFinished();
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain onward)
            throws ServletException, IOException {
        if (MultipartFiles.sent(request)) {
            request.setAttribute(STARTED, nanoTime.getAsLong());
        }
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
        ReadDeadline deadline = new ReadDeadline(nanoTime, nanoTime.getAsLong() + LONGEST_READ.toNanos(), turn::give);
        request.setAttribute(HOLDING, deadline);
        try {
            onward.doFilter(request, response);
        } finally {
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(deadline, request, response);
            } else {
                try {
                    request.removeAttribute(HOLDING);
                } finally {
                    deadline.requestFinished();
                }
            }
        }
    }

    private static boolean large(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        return declared > ONE_MEBIBYTE || (declared < 0 && CARRYING_A_BODY.contains(request.getMethod()));
    }

    static final class ReadDeadline implements AsyncListener {

        private final LongSupplier nanoTime;
        private final long endsAtNanos;
        private final Runnable release;
        private boolean workerDone = true;
        private boolean requestDone;
        private boolean released;

        private ReadDeadline(LongSupplier nanoTime, long endsAtNanos, Runnable release) {
            this.nanoTime = nanoTime;
            this.endsAtNanos = endsAtNanos;
            this.release = release;
        }

        boolean passed() {
            return nanoTime.getAsLong() - endsAtNanos > 0;
        }

        private synchronized boolean workerStarted() {
            if (requestDone) {
                return false;
            }
            workerDone = false;
            return true;
        }

        private synchronized void workerFinished() {
            workerDone = true;
            releaseIfFinished();
        }

        private synchronized void requestFinished() {
            requestDone = true;
            releaseIfFinished();
        }

        private void releaseIfFinished() {
            if (workerDone && requestDone && !released) {
                released = true;
                release.run();
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            try {
                event.getSuppliedRequest().removeAttribute(HOLDING);
            } finally {
                requestFinished();
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) {}

        @Override
        public void onError(AsyncEvent event) {}

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this, event.getSuppliedRequest(), event.getSuppliedResponse());
        }
    }
}
