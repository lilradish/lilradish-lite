package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.failure.RefusalCode;

/**
 * Answering a code step here refused because what it gives back no longer matches, carrying what found it so: the
 * field that refuses it, and what reads that field where that is why. Answered with those by
 * {@link GivesOtherwiseOutlet}, wherever it is raised.
 */
final class GivesOtherwiseRefusal extends ApiErrorException {

    private final transient CodeError.Fault fault;

    GivesOtherwiseRefusal(RefusalCode code, String sentence, CodeError.Fault fault) {
        super(code, sentence);
        this.fault = requireNonNull(fault, "GivesOtherwiseRefusal fault must not be null");
    }

    CodeError.Fault fault() {
        return fault;
    }
}
