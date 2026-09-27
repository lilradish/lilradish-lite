package org.lilradish.lite.app.inference;

/**
 * Ends a call whose last turnaway was already passed on, with nothing sent after it. Unchecked and not
 * an I/O failure, so the client passes it on as it is rather than as a failed send.
 */
final class CallNotResent extends RuntimeException {

    CallNotResent() {
        super("the call was not sent again", null, false, false);
    }
}
