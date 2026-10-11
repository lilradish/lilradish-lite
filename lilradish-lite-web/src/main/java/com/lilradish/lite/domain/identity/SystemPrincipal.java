package com.lilradish.lite.domain.identity;

import java.util.UUID;

/**
 * Exists in code and has no revocable human owner, so it is accountable for itself.
 * It has no {@link ScopedPrincipal} axis: an empty standing would misrepresent that absence.
 *
 * <p>A private constructor limits instances to this file's constants and accepts no roles.
 * Role matching requires {@link ScopedPrincipal}, so this type cannot enter it; {@link #maySurfaceContent}
 * is an additional guard against surfacing content, not the sole protection.
 */
public final class SystemPrincipal implements Principal {

    public static final SystemPrincipal WORKFLOW_RUNNER = new SystemPrincipal("00000000-0000-4000-8000-000000000001");

    private final SubjectId subject;

    private SystemPrincipal(String subject) {
        this.subject = new SubjectId(UUID.fromString(subject));
    }

    @Override
    public SubjectId subject() {
        return subject;
    }

    @Override
    public SubjectId accountableSubject() {
        return subject;
    }

    @Override
    public boolean maySurfaceContent() {
        return false;
    }
}
