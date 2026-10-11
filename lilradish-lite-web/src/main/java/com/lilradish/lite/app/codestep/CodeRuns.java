package com.lilradish.lite.app.codestep;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.codestep.CodeCall;
import com.lilradish.lite.domain.codestep.CodeOutcome;
import com.lilradish.lite.domain.codestep.CodeStep;
import com.lilradish.lite.domain.filling.FillPath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Never inside a transaction: the code may take as long as
 * it takes, and a transaction held open that long holds its locks and a connection with it.
 */
@Component
public final class CodeRuns {

    private static final Logger logger = LoggerFactory.getLogger(CodeRuns.class);

    private final CodeSteps codeSteps;

    CodeRuns(CodeSteps codeSteps) {
        this.codeSteps = codeSteps;
    }

    public Ran run(CodeCall call) {
        requireNonNull(call, "CodeRuns call must not be null");
        String name = call.name().value();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Code step " + name + " is run outside any transaction, not inside one");
        }
        CodeStep code = codeSteps
                .held(name)
                .orElseThrow(() -> new IllegalStateException("This release holds no code step " + name));
        List<InputStream> opened = new ArrayList<>();
        InputFailure inputFailure = new InputFailure();
        ManagedInput[] active = new ManagedInput[1];
        Map<FillPath, CodeStep.FileInput> handles = new java.util.HashMap<>();
        for (Map.Entry<FillPath, CodeStep.FileInput> entry : call.files().entrySet()) {
            handles.put(entry.getKey(), () -> {
                if (active[0] != null && active[0].isOpen()) {
                    throw new IllegalStateException(
                            "A code step may open only one file at a time; close it before opening another.");
                }
                try {
                    ManagedInput stream = new ManagedInput(entry.getValue().open(), inputFailure, opened);
                    opened.add(stream);
                    active[0] = stream;
                    return stream;
                } catch (RuntimeException failure) {
                    inputFailure.failed = failure;
                    throw failure;
                }
            });
        }
        CodeStep.@Nullable Output returned = null;
        try {
            returned = code.run(new CodeStep.Input(call.takes(), handles));
            if (inputFailure.failed != null) {
                throw inputFailure.failed;
            }
        } catch (Exception | LinkageError | StackOverflowError | AssertionError thrown) {
            if (inputFailure.failed != null) {
                try {
                    close(opened, returned);
                } catch (RuntimeException closing) {
                    if (closing != inputFailure.failed) {
                        inputFailure.failed.addSuppressed(closing);
                    }
                }
                throw inputFailure.failed;
            }
            if (thrown instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // The message is the code's, and may repeat what it took: it is kept on the try and never logged.
            logger.warn("Code step {} threw {}", name, thrown.getClass().getName());
            return new Ran(null, CodeOutcome.thrown(messageOf(thrown)), opened);
        } catch (Error fatal) {
            try {
                close(opened, null);
            } catch (RuntimeException closing) {
                fatal.addSuppressed(closing);
            }
            throw fatal;
        }
        return new Ran(returned, null, opened);
    }

    private static final class ManagedInput extends InputStream {

        private @Nullable InputStream source;
        private final InputFailure failure;
        private final List<InputStream> opened;

        private ManagedInput(InputStream source, InputFailure failure, List<InputStream> opened) {
            this.source = requireNonNull(source, "CodeRuns file input must not be null");
            this.failure = failure;
            this.opened = opened;
        }

        private boolean isOpen() {
            return source != null;
        }

        private InputStream source() throws IOException {
            if (source == null) {
                throw new IOException("This file input was already closed");
            }
            return source;
        }

        @Override
        public int read() throws IOException {
            try {
                return source().read();
            } catch (IOException failed) {
                if (source != null) {
                    failure.failed = new UncheckedIOException(failed);
                }
                throw failed;
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                return source().read(bytes, offset, length);
            } catch (IOException failed) {
                if (source != null) {
                    failure.failed = new UncheckedIOException(failed);
                }
                throw failed;
            }
        }

        @Override
        public long skip(long count) throws IOException {
            try {
                return source().skip(count);
            } catch (IOException failed) {
                if (source != null) {
                    failure.failed = new UncheckedIOException(failed);
                }
                throw failed;
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return source().available();
            } catch (IOException failed) {
                if (source != null) {
                    failure.failed = new UncheckedIOException(failed);
                }
                throw failed;
            }
        }

        @Override
        public void close() throws IOException {
            InputStream closing = source;
            source = null;
            if (closing != null) {
                opened.remove(this);
                try {
                    closing.close();
                } catch (IOException failed) {
                    failure.failed = new UncheckedIOException(failed);
                    throw failed;
                }
            }
        }
    }

    private static final class InputFailure {
        private @Nullable RuntimeException failed;
    }

    /** A code return stays open until the engine verifies and closes any file streams in its ending transaction. */
    public static final class Ran implements AutoCloseable {

        private final CodeStep.@Nullable Output returned;
        private final @Nullable CodeOutcome failure;
        private final List<InputStream> opened;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Ran(CodeStep.@Nullable Output returned, @Nullable CodeOutcome failure, List<InputStream> opened) {
            if (returned != null && failure != null) {
                throw new IllegalArgumentException("CodeRuns.Ran cannot both return and fail");
            }
            this.returned = returned;
            this.failure = failure;
            this.opened = List.copyOf(opened);
        }

        public CodeStep.@Nullable Output returned() {
            return returned;
        }

        public @Nullable CodeOutcome failure() {
            return failure;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                CodeRuns.close(opened, returned);
            }
        }
    }

    private static void close(List<InputStream> opened, CodeStep.@Nullable Output returned) {
        IdentityHashMap<InputStream, Boolean> seen = new IdentityHashMap<>();
        IOException failed = null;
        if (returned != null) {
            for (List<CodeStep.FileOutput> files : returned.files().values()) {
                for (CodeStep.FileOutput file : files) {
                    failed = close(file.content(), seen, failed);
                }
            }
        }
        for (InputStream stream : List.copyOf(opened)) {
            failed = close(stream, seen, failed);
        }
        if (failed != null) {
            throw new UncheckedIOException(failed);
        }
    }

    private static @Nullable IOException close(
            InputStream stream, IdentityHashMap<InputStream, Boolean> seen, @Nullable IOException failed) {
        if (seen.put(stream, true) == null) {
            try {
                stream.close();
            } catch (IOException closing) {
                if (failed == null) {
                    return closing;
                }
                failed.addSuppressed(closing);
            }
        }
        return failed;
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
