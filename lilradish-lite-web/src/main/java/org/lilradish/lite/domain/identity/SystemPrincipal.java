package org.lilradish.lite.domain.identity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * An actor that exists in code rather than in configuration — index rebuild, retention expiry. It
 * has no owner, because there is no person whose revocation would stop it, and it is therefore
 * charged for what it does to itself.
 *
 * <p>It stands in no scope and is not a {@link ScopedPrincipal}: retention is keyed by category and
 * an index is rebuilt whole, so nothing it does varies by group. Its permissions are declared once
 * in code and answered without one, and a scoped question cannot be put to it at all — an emptiness
 * returned there would read as a standing rather than as the absence of the axis.
 *
 * <p>A class with a private constructor rather than a record: the constants below are the only
 * system principals there are, and no caller outside this file can declare a wider grant.
 *
 * <p>That shape is also what answers "may never surface content to anyone" structurally rather than
 * by convention, and it takes both halves. The constructor takes a subject and permissions and
 * nothing else, so there is no parameter through which any system principal — present or added
 * later — could be handed a role; and whatever matches material to a role is written against
 * {@link ScopedPrincipal}, which this is not, so one cannot be put in front of such a predicate at
 * all. The {@link #maySurfaceContent} guard is the second of two answers rather than the only one.
 */
public final class SystemPrincipal implements Principal {

    /* Each identifier is one half of a pair whose other half is a row the baseline seeds; nothing
     * here can see that file, so SeededSubjectIntegrationSpec is what holds the two level. */
    public static final SystemPrincipal INDEX_REBUILD =
            new SystemPrincipal("00000000-0000-4000-8000-000000000001", SystemPermission.READ_BULK_CONTENT);

    /* Expiring stored content is deciding that it is past its retention, which is a fact about when
     * it was written; nothing has yet shown that reading it is needed to decide that. */
    public static final SystemPrincipal RETENTION_EXPIRY =
            new SystemPrincipal("00000000-0000-4000-8000-000000000002", SystemPermission.EXPIRE_RETENTION);

    private final SubjectId subject;
    private final Set<SystemPermission> permissions;

    private SystemPrincipal(String subject, SystemPermission... granted) {
        this.subject = new SubjectId(UUID.fromString(subject));
        EnumSet<SystemPermission> declared = EnumSet.noneOf(SystemPermission.class);
        Collections.addAll(declared, granted);
        this.permissions = Collections.unmodifiableSet(declared);
    }

    @Override
    public SubjectId subject() {
        return subject;
    }

    @Override
    public SubjectId accountableSubject() {
        return subject;
    }

    public Set<SystemPermission> permissions() {
        return permissions;
    }

    @Override
    public boolean maySurfaceContent() {
        return false;
    }

    public boolean may(SystemPermission permission) {
        return permissions.contains(permission);
    }
}
