package org.lilradish.lite.app.files;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.run.RunId;
import org.springframework.stereotype.Component;

/** Bounds the heap held by concurrent downloads. */
@Component
public final class FileOpenAdmission {

    public static final int MOST_OPEN = 4;

    private static final int MOST_WAITING = 16;

    private static final Duration LONGEST_WAIT = Duration.ofSeconds(30);

    private final Semaphore turns = new Semaphore(MOST_OPEN, true);
    private final AtomicInteger waiting = new AtomicInteger();

    /** Take a turn before storage materializes the file; release it with the stream. */
    public RunFiles.OpenedFile open(RunFiles files, RunId run, GroupId group, UUID file) {
        if (!take()) {
            throw new ApiErrorException(
                    RefusalCode.SERVICE_BUSY, "Too many files are being read; send this request again.");
        }
        try {
            RunFiles.OpenedFile opened = files.open(run, group, file);
            return new RunFiles.OpenedFile(opened.file(), new ReleasingInput(opened.content()));
        } catch (RuntimeException | Error failed) {
            turns.release();
            throw failed;
        }
    }

    private boolean take() {
        if (waiting.incrementAndGet() > MOST_WAITING) {
            waiting.decrementAndGet();
            return false;
        }
        try {
            return turns.tryAcquire(LONGEST_WAIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            waiting.decrementAndGet();
        }
    }

    private final class ReleasingInput extends FilterInputStream {

        private final AtomicBoolean closed = new AtomicBoolean();

        private ReleasingInput(InputStream source) {
            super(source);
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                InputStream closing = in;
                in = InputStream.nullInputStream();
                try {
                    closing.close();
                } finally {
                    turns.release();
                }
            }
        }
    }
}
