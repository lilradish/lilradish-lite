package org.lilradish.lite.domain.failure;

import static org.libprunus.core.error.ErrorCategory.INVALID_ARGUMENT;
import static org.libprunus.core.error.ErrorCategory.NOT_FOUND;
import static org.libprunus.core.error.ErrorCategory.PERMISSION_DENIED;
import static org.libprunus.core.error.ErrorCategory.UNAUTHENTICATED;

import org.libprunus.core.error.ErrorCategory;
import org.libprunus.core.error.ErrorCode;

/**
 * Why a refusal happened. The category is what a transport turns into a status; the constant name
 * is the published code, so renaming one breaks a contract rather than refactoring one.
 *
 * <p>Only refusals belong here. An infrastructure fault is not one — carried as an
 * {@code ApiErrorException} it would publish both its code and its message on the 500, while the
 * library's own unhandled path deliberately publishes neither.
 *
 * <p>No type here enforces the ordering it depends on: a {@code _NOT_IN_VIEW} constant withholds
 * that a row exists and every other refusal concedes it, so visibility is decided before eligibility.
 */
public enum RefusalCode implements ErrorCode {
    /**
     * One constant, not one per act: per-act constants would be a hand-kept copy of the surface's
     * own list, stale the first time an act is added.
     */
    ACT_NOT_PERMITTED(PERMISSION_DENIED),

    CREDENTIAL_EXPIRED(UNAUTHENTICATED),
    CREDENTIAL_SIGNATURE_INVALID(UNAUTHENTICATED),
    /** A carrier the client could have written for itself counts as no credential at all. */
    CREDENTIAL_CARRIER_UNTRUSTED(UNAUTHENTICATED),

    /** A credential the caller presents, not a resource it asked for: refused, not reported missing. */
    DELEGATION_UNKNOWN(PERMISSION_DENIED),
    DELEGATION_EXPIRED(PERMISSION_DENIED),
    DELEGATION_REVOKED(PERMISSION_DENIED),
    DELEGATION_NOT_OWNED(PERMISSION_DENIED),

    GATE_APPROVER_INELIGIBLE(PERMISSION_DENIED),
    GATE_HOLDER_INELIGIBLE(PERMISSION_DENIED),
    GATE_RUN_NOT_IN_VIEW(NOT_FOUND),

    INFERENCE_NOT_IN_VIEW(NOT_FOUND),
    INFERENCE_READ_NOT_PERMITTED(PERMISSION_DENIED),
    INFERENCE_READ_PURPOSE_REQUIRED(INVALID_ARGUMENT),
    INFERENCE_READ_WOULD_SURFACE_CONTENT(PERMISSION_DENIED),

    INITIATOR_NOT_IN_VIEW(NOT_FOUND),

    /**
     * Nothing said who is calling — nothing arrived, or what arrived names no user. One code
     * for both, because which of the two it was is a fact about the carrier and handing it back
     * hands it to whoever can change what the carrier sends. Not an invalid argument either: the
     * reader typed nothing, so a complaint about their input is a lie about where the fault is.
     */
    NOT_SIGNED_IN(UNAUTHENTICATED),

    OBJECT_NOT_IN_VIEW(NOT_FOUND),
    OBJECT_REFERENCE_ABSENT(INVALID_ARGUMENT),
    OBJECT_REFERENCE_EXPIRED(PERMISSION_DENIED),
    OBJECT_REFERENCE_MALFORMED(INVALID_ARGUMENT),
    OBJECT_REFERENCE_MISMATCH(PERMISSION_DENIED),
    OBJECT_REFERENCE_SIGNATURE_INVALID(PERMISSION_DENIED),
    OBJECT_REFERENCE_WOULD_SURFACE_CONTENT(PERMISSION_DENIED),
    OBJECT_UPLOAD_NOT_PERMITTED(PERMISSION_DENIED),

    RETENTION_NOT_PERMITTED(PERMISSION_DENIED),
    REVIEW_WOULD_SURFACE_CONTENT(PERMISSION_DENIED);

    private final ErrorCategory category;

    RefusalCode(ErrorCategory category) {
        this.category = category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
