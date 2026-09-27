package org.lilradish.lite.app.inference;

import java.io.IOException;
import java.time.Duration;
import java.time.InstantSource;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.CallProgress;
import org.lilradish.lite.domain.inference.TurnAway;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The one place a turnaway is told apart and sent again, telling the call's {@link CallProgress}
 * each step as it promises. A 429 or a 503 is a turnaway; a 429 whose error says what may be spent is
 * used up ends the call at once; nothing else is, and every other response is handed on untouched.
 * The call ends by throwing {@link CallTurnedAway} or {@link CallNotResent}, which the adapter maps.
 *
 * <p>Registered alone on its client: an interceptor nearer the wire could send again unannounced.
 */
final class TurnAwayResend implements ClientHttpRequestInterceptor {

    static final String PROGRESS = CallProgress.class.getName();

    private static final int TOO_MANY_REQUESTS = 429;

    private static final int SERVICE_UNAVAILABLE = 503;

    private final ResendPolicy policy;

    private final ResendWait wait;

    private final InstantSource clock;

    TurnAwayResend(ResendPolicy policy, ResendWait wait, InstantSource clock) {
        this.policy = policy;
        this.wait = wait;
        this.clock = clock;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!(request.getAttributes().get(PROGRESS) instanceof CallProgress progress)) {
            throw new IllegalStateException(
                    "A model call carries no CallProgress, so nothing it sends would be on record");
        }
        progress.aboutToSend();
        int resend = 0;
        while (true) {
            ClientHttpResponse response = execution.execute(request, body);
            Heard heard;
            try {
                heard = heard(response);
            } catch (IOException | RuntimeException failure) {
                response.close();
                throw failure;
            }
            if (heard == null) {
                return response;
            }
            response.close();
            resend++;
            if (heard.turnAway().spentUp() || resend > policy.times() || wait.stopped()) {
                throw new CallTurnedAway(heard.turnAway());
            }
            progress.turnedAway(heard.turnAway());
            boolean waitedOut;
            try {
                waitedOut = wait.waitedOut(policy.waitBefore(resend, heard.asked()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CallNotResent();
            }
            if (!waitedOut || !progress.mayResend()) {
                throw new CallNotResent();
            }
        }
    }

    /** Absent where the response is not a turnaway; its body is read only where it is one. */
    private @Nullable Heard heard(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status != TOO_MANY_REQUESTS && status != SERVICE_UNAVAILABLE) {
            return null;
        }
        TurnAway turnAway;
        try {
            ErrorBody said = ErrorBody.read(response);
            turnAway = new TurnAway(said.said(), status == TOO_MANY_REQUESTS && said.spentUp());
        } catch (IOException unread) {
            // Turned away all the same: a body that failed to arrive is no reason to spend a try.
            turnAway = new TurnAway(null, false);
        }
        return new Heard(turnAway, RetryAfter.asked(response.getHeaders(), clock.instant()));
    }

    private record Heard(TurnAway turnAway, @Nullable Duration asked) {}
}
