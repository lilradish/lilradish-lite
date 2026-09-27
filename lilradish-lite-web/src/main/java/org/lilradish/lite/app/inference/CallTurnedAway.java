package org.lilradish.lite.app.inference;

import org.lilradish.lite.domain.inference.TurnAway;

/**
 * Carries the turnaway that ends a call out of the resend, past the client, to the adapter, which never
 * judges a status itself. Unchecked and not an I/O failure, so the client passes it on as it is.
 */
final class CallTurnedAway extends RuntimeException {

    private final transient TurnAway last;

    CallTurnedAway(TurnAway last) {
        super("the call was turned away and is not sent again", null, false, false);
        this.last = last;
    }

    TurnAway last() {
        return last;
    }
}
