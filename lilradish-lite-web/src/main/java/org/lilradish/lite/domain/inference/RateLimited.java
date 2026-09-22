package org.lilradish.lite.domain.inference;

/**
 * The provider asking to be asked later. A type of its own because the distinction decides two
 * things: a healthy endpoint enforcing its quota must not open the breaker, and must not spend the
 * step's retry budget.
 */
public final class RateLimited extends RuntimeException {

    public RateLimited(String message) {
        super(message);
    }
}
