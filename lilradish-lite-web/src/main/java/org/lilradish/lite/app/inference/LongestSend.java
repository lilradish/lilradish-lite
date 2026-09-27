package org.lilradish.lite.app.inference;

import java.time.Duration;

/**
 * An upper bound for one send to the endpoint, not for a call with its resends and the waits between them. There
 * is one only where the endpoint is configured, so it is taken through an {@code ObjectProvider}.
 */
public final class LongestSend {

    private final Duration duration;

    private LongestSend(Duration duration) {
        this.duration = duration;
    }

    // The read timer starts as the request is handed to the client and ends with the body closed, connecting
    // within it, so it alone bounds a send.
    static LongestSend of(ModelEndpoint endpoint) {
        return new LongestSend(endpoint.readTimeout());
    }

    public Duration duration() {
        return duration;
    }
}
