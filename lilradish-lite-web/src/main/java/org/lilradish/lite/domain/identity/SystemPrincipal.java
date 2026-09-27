package org.lilradish.lite.domain.identity;

import java.util.UUID;

/**
 * An actor that exists in code rather than in configuration. It has no owner, because there is no
 * person whose revocation would stop it, and it is therefore charged for what it does to itself.
 *
 * <p>It stands in no scope and is not a {@link ScopedPrincipal}: a scoped question cannot be put to
 * it at all — an emptiness returned there would read as a standing rather than as the absence of the
 * axis.
 *
 * <p>A class with a private constructor rather than a record: the constants below are the only
 * system principals there are, and no caller outside this file can declare another.
 *
 * <p>That shape is also what answers "may never surface content to anyone" structurally rather than
 * by convention, and it takes both halves. The constructor takes a subject and nothing else, so
 * there is no parameter through which any system principal — present or added later — could be
 * handed a role; and whatever matches material to a role is written against {@link
 * ScopedPrincipal}, which this is not, so one cannot be put in front of such a predicate at all. The
 * {@link #maySurfaceContent} guard is the second of two answers rather than the only one.
 */
public final class SystemPrincipal implements Principal {

    /* Paired with a row the baseline seeds, held level by SeededSubjectIntegrationSpec. It holds no
     * permission: what running a workflow asks of one is not yet decided. */
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
