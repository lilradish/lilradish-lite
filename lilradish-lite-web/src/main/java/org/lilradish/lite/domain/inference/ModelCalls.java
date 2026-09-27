package org.lilradish.lite.domain.inference;

/**
 * Where a model is called. Blocks until the call has ended one of the ways {@link CallOutcome} names,
 * reporting its steps to {@code progress} as {@link CallProgress} says.
 *
 * <p>Whatever goes wrong on the model's side is returned as {@link CallOutcome.Errored} and never
 * thrown. What is thrown is the caller's own: a failure in {@code progress}, which ends the call with
 * nothing more sent.
 */
public interface ModelCalls {

    CallOutcome call(CallRequest request, CallProgress progress);
}
