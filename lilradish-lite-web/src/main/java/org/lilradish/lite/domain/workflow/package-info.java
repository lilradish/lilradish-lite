/**
 * Pinning belongs to a registry reference, never to {@link StepConstraints}: a model step has no
 * workflow and a sub-workflow step has no prompt, so a shared constraint could not be type-checked.
 *
 * <p>Immutable set and map components here are built with {@code copyOf}, whose iteration order is
 * randomized per JVM run: neither a serialization nor a {@code toString()} taken from one is stable,
 * so sort explicitly wherever order is observed. {@link ApprovalGate} is the deliberate exception —
 * its permissions are an EnumSet, ordered as the enum is, because which permission a refusal names
 * must not vary from one run to the next.
 */
@NullMarked
package org.lilradish.lite.domain.workflow;

import org.jspecify.annotations.NullMarked;
