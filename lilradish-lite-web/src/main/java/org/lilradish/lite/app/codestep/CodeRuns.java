package org.lilradish.lite.app.codestep;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.codestep.CodeCall;
import org.lilradish.lite.domain.codestep.CodeOutcome;
import org.lilradish.lite.domain.codestep.CodeStep;
import org.lilradish.lite.domain.wire.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs the code step a call names and says what it came to. Never inside a transaction: the code may take as long as
 * it takes, and a transaction held open that long holds its locks and a connection with it.
 */
@Component
public final class CodeRuns {

    private static final Logger logger = LoggerFactory.getLogger(CodeRuns.class);

    private final CodeSteps codeSteps;

    CodeRuns(CodeSteps codeSteps) {
        this.codeSteps = codeSteps;
    }

    public CodeOutcome run(CodeCall call) {
        requireNonNull(call, "CodeRuns call must not be null");
        String name = call.name().value();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Code step " + name + " is run outside any transaction, not inside one");
        }
        CodeStep code = codeSteps
                .held(name)
                .orElseThrow(() -> new IllegalStateException("This release holds no code step " + name));
        JsonValue.@Nullable JsonObject returned;
        try {
            returned = code.run(call.takes());
        } catch (Exception | LinkageError | StackOverflowError | AssertionError thrown) {
            if (thrown instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // The message is the code's, and may repeat what it took: it is kept on the try and never logged.
            logger.warn("Code step {} threw {}", name, thrown.getClass().getName());
            return CodeOutcome.thrown(messageOf(thrown));
        }
        return CodeOutcome.returned(call, returned);
    }

    /* The code's own class says what its message is, and saying it may throw in turn. */
    private static @Nullable String messageOf(Throwable thrown) {
        try {
            return thrown.getMessage();
        } catch (Exception | LinkageError | StackOverflowError | AssertionError unreadable) {
            return null;
        }
    }
}
