package com.lilradish.lite.web;

import com.lilradish.lite.domain.failure.RefusalCode;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;

/** A bounded body buffer read by its worker only while the container says reading will not block. */
final class RequestBodyInput extends InputStream {

    private static final int CHUNK_BYTES = 64 * 1024;

    private final ServletInputStream source;
    private final BooleanSupplier deadlinePassed;
    private final Runnable received;
    private final byte[] buffer = new byte[CHUNK_BYTES];
    private int position;
    private int limit;
    private boolean closed;
    private boolean tooSlow;
    private boolean arrived;
    private @Nullable IOException failed;

    RequestBodyInput(ServletInputStream source, BooleanSupplier deadlinePassed, Runnable received) {
        this.source = Objects.requireNonNull(source);
        this.deadlinePassed = Objects.requireNonNull(deadlinePassed);
        this.received = Objects.requireNonNull(received);
        source.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() {
                readinessChanged();
            }

            @Override
            public void onAllDataRead() {
                allArrived();
            }

            @Override
            public void onError(Throwable failure) {
                fail(failure);
            }
        });
    }

    private synchronized void readinessChanged() {
        notifyAll();
    }

    private synchronized void allArrived() {
        if (!arrived) {
            if (!tooSlow && !deadlinePassed.getAsBoolean()) {
                arrived = true;
                received.run();
            } else {
                tooSlow = true;
            }
        }
        notifyAll();
    }

    synchronized void fail(Throwable failure) {
        if (failed == null) {
            failed = new IOException("The request body could not be read.", failure);
        }
        notifyAll();
    }

    synchronized void expired() {
        if (!arrived) {
            tooSlow = true;
        }
        notifyAll();
    }

    @Override
    public synchronized int read() throws IOException {
        return ready() ? Byte.toUnsignedInt(buffer[position++]) : -1;
    }

    @Override
    public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        if (!ready()) {
            return -1;
        }
        int count = Math.min(length, limit - position);
        System.arraycopy(buffer, position, bytes, offset, count);
        position += count;
        return count;
    }

    private boolean ready() throws IOException {
        for (; ; ) {
            if (tooSlow || !arrived && deadlinePassed.getAsBoolean()) {
                throw new ApiErrorException(
                        RefusalCode.BODY_SENT_TOO_SLOWLY,
                        "What was sent arrived too slowly to be read; send it again.");
            }
            if (failed != null) {
                throw failed;
            }
            if (closed) {
                throw new IOException("The request body is closed.");
            }
            if (position < limit) {
                return true;
            }
            if (arrived) {
                return false;
            }
            if (source.isFinished()) {
                allArrived();
                continue;
            }
            if (source.isReady()) {
                int count = source.read(buffer);
                if (count < 0) {
                    allArrived();
                    continue;
                }
                position = 0;
                limit = count;
                if (source.isFinished()) {
                    allArrived();
                }
            } else {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("The request body read was interrupted.", interrupted);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        notifyAll();
    }
}
