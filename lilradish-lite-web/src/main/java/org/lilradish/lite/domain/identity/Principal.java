package org.lilradish.lite.domain.identity;

/**
 * Who is making this one call, decided for this one call. One decided from a presented credential
 * may not outlive that call and may not be cached, so that a claim withdrawn upstream takes hold on
 * the very next call rather than on whatever a holder kept. An actor that exists in code is not one
 * of those: nothing was presented for it and there is nothing to withdraw, which is why its own type
 * may publish it as a constant.
 *
 * <p>{@link #subject()} and {@link #accountableSubject()} are one type answering two questions — who
 * is acting, and who the call is charged to. They coincide for some shapes and not for others, which
 * is the whole reason both are asked. Three shapes answer them: a person, a delegation exercised in
 * a person's name, and an actor that exists in code — the one whose output no person reads as an
 * answer.
 *
 * <p>Only what is true of all three is asked here. Everything a scope decides is asked of {@link
 * ScopedPrincipal}, which the actor existing in code does not implement.
 */
public sealed interface Principal permits SystemPrincipal, ScopedPrincipal {

    SubjectId subject();

    SubjectId accountableSubject();

    /** False for an actor whose output no person reads as an answer, however much it may read. */
    boolean maySurfaceContent();
}
