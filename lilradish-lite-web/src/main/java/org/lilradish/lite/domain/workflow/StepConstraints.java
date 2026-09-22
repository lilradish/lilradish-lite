package org.lilradish.lite.domain.workflow;

import org.jspecify.annotations.Nullable;

/**
 * A constraint is admitted only once something here can enforce it.
 *
 * <p>Absent on most steps, and carried as absence rather than as a gate shaped to gate nobody: a
 * gate states an authority, a qualification, a separation and a clock, and there is no answer to any
 * of those four that means "nobody is asked".
 */
public record StepConstraints(@Nullable ApprovalGate approvalGate) {}
