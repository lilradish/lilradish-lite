package com.lilradish.lite.domain.identity;

/**
 * A principal decided from credentials lasts only for its call and must not be cached, so upstream revocation
 * takes effect on the next call. Code-defined actors have no presented claims to withdraw and may be constants.
 *
 * <p>{@link #subject()} identifies the actor; {@link #accountableSubject()} identifies who is charged.
 * They may differ for a delegation acting for a person. A code-defined actor's output is no person's answer.
 * Only shared questions belong here; scoped questions belong on {@link ScopedPrincipal}, which code actors do not implement.
 */
public sealed interface Principal permits SystemPrincipal, ScopedPrincipal {

    SubjectId subject();

    SubjectId accountableSubject();

    /** False for an actor whose output no person reads as an answer, however much it may read. */
    boolean maySurfaceContent();
}
