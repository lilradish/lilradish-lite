package com.lilradish.lite.web;

import com.lilradish.lite.app.files.FileDeployment;
import com.lilradish.lite.app.files.TransferPace;
import com.lilradish.lite.domain.failure.RefusalCode;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;

public final class FileTransfers implements AutoCloseable {

    private static final String CONNECTION = FileTransfers.class.getName() + ".connection";
    private static final Logger logger = LoggerFactory.getLogger(FileTransfers.class);

    private final FileDeployment deployment;
    private final LongSupplier nanoTime;
    private final ThreadPoolTaskExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Set<UploadLife> uploading = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public FileTransfers(FileDeployment deployment, LongSupplier nanoTime, ThreadPoolTaskExecutor workers) {
        this.deployment = deployment;
        this.nanoTime = nanoTime;
        this.workers = workers;
        deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "file-transfer-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        deadlines.setRemoveOnCancelPolicy(true);
    }

    @FunctionalInterface
    public interface Upload<T> {
        T receive(MultipartFiles files) throws IOException;
    }

    /** Start the body reader after Spring has started asynchronous handling, with its timeout disabled. */
    public <T> DeferredResult<T> upload(
            HttpServletRequest request, HttpServletResponse response, int documentMaximum, Upload<T> act) {
        LargeBodyAdmission.ReadDeadline turn = LargeBodyAdmission.readDeadline(request);
        long started = LargeBodyAdmission.bodyStarted(request, nanoTime);
        RequestTrace.Carried carried = RequestTrace.captured(request);
        MultipartFiles.Sending sending = MultipartFiles.sending(request);
        long allowed = TransferPace.allowedNanos(
                MultipartFiles.largestSending(deployment.maximumTotalSize().toBytes()));
        DeferredResult<T> result = new DeferredResult<>(0L);
        UploadLife life = new UploadLife(started, allowed, result, response);
        FutureTask<Void> worker = new FutureTask<>(() -> {
            if (!life.start(turn)) {
                return null;
            }
            try {
                RequestTrace.carry(carried);
                ReadCutoff cutoff = new ReadCutoff(started, allowed);
                try (RequestBodyInput body = life.input(request, cutoff)) {
                    try (MultipartFiles multipart = MultipartFiles.open(sending, body, documentMaximum, cutoff)) {
                        result.setResult(act.receive(multipart));
                    } finally {
                        cutoff.close();
                    }
                }
            } catch (Throwable failure) {
                // An unread remainder is never drained on this worker before the refusal can be answered.
                life.ifActive(() -> response.setHeader("Connection", "close"));
                result.setErrorResult(failure);
            } finally {
                try {
                    LargeBodyAdmission.workerFinished(turn);
                } finally {
                    try {
                        life.workerFinished();
                    } finally {
                        RequestTrace.clear();
                    }
                }
            }
            return null;
        });
        life.worker = worker;
        uploading.add(life);
        if (closed.get()) {
            life.stopped();
        }
        result.onCompletion(life::completed);
        WebAsyncUtils.getAsyncManager(request)
                .registerDeferredResultInterceptor(life, new DeferredResultProcessingInterceptor() {
                    @Override
                    public <V> void preProcess(NativeWebRequest asked, DeferredResult<V> deferred) {
                        life.submit();
                    }
                });
        return result;
    }

    private final class UploadLife {
        private final long started;
        private final long allowed;
        private final DeferredResult<?> result;
        private final HttpServletResponse response;
        private boolean requestDone;
        private boolean workerStarted;
        private boolean workerEnded;
        private boolean refusedBeforeStart;
        private @Nullable RequestBodyInput body;
        private @Nullable FutureTask<Void> worker;
        private @Nullable ScheduledFuture<?> waiting;

        private UploadLife(long started, long allowed, DeferredResult<?> result, HttpServletResponse response) {
            this.started = started;
            this.allowed = allowed;
            this.result = result;
            this.response = response;
        }

        private synchronized void queue() {
            waiting = deadlines.schedule(
                    this::expireQueued, Math.max(0, allowed - (nanoTime.getAsLong() - started)), TimeUnit.NANOSECONDS);
        }

        private synchronized void submit() {
            if (requestDone || refusedBeforeStart || closed.get()) {
                stopped();
                return;
            }
            queue();
            try {
                workers.execute(java.util.Objects.requireNonNull(worker));
            } catch (RejectedExecutionException rejected) {
                rejected(rejected);
            }
        }

        private synchronized void expireQueued() {
            if (requestDone || workerStarted || refusedBeforeStart) {
                return;
            }
            if (nanoTime.getAsLong() - started <= allowed) {
                queue();
                return;
            }
            refusedBeforeStart = true;
            cancelQueued();
            response.setHeader("Connection", "close");
            result.setErrorResult(new ApiErrorException(
                    RefusalCode.BODY_SENT_TOO_SLOWLY, "What was sent arrived too slowly to be read; send it again."));
        }

        private synchronized void rejected(RejectedExecutionException failure) {
            refusedBeforeStart = true;
            cancelWaiting();
            cancelQueued();
            if (!requestDone) {
                response.setHeader("Connection", "close");
                if (failure instanceof TaskRejectedException
                        && !workers.getThreadPoolExecutor().isShutdown()) {
                    result.setErrorResult(new ApiErrorException(
                            RefusalCode.SERVICE_BUSY, "Too many files are being read; send this request again."));
                } else {
                    result.setErrorResult(failure);
                }
            }
        }

        private synchronized boolean start(LargeBodyAdmission.@Nullable ReadDeadline turn) {
            if (requestDone || refusedBeforeStart || closed.get()) {
                return false;
            }
            if (nanoTime.getAsLong() - started > allowed) {
                expireQueued();
                return false;
            }
            if (!LargeBodyAdmission.workerStarted(turn)) {
                return false;
            }
            workerStarted = true;
            cancelWaiting();
            return true;
        }

        private synchronized void completed() {
            requestDone = true;
            cancelWaiting();
            if (body != null) {
                body.close();
            }
            if (!workerStarted) {
                cancelQueued();
            }
            retire();
        }

        private void cancelQueued() {
            if (worker != null) {
                worker.cancel(false);
                workers.getThreadPoolExecutor().remove(worker);
            }
        }

        private synchronized void workerFinished() {
            workerEnded = true;
            retire();
        }

        private void retire() {
            if (requestDone && (!workerStarted || workerEnded)) {
                uploading.remove(this);
            }
        }

        private synchronized void stopped() {
            cancelWaiting();
            if (!workerStarted) {
                refusedBeforeStart = true;
                cancelQueued();
                if (!requestDone) {
                    result.setErrorResult(new RejectedExecutionException("The upload reader is closed."));
                }
            } else if (body != null) {
                body.close();
            }
        }

        private void cancelWaiting() {
            if (waiting != null) {
                waiting.cancel(false);
            }
        }

        private synchronized RequestBodyInput input(HttpServletRequest request, ReadCutoff cutoff) throws IOException {
            if (requestDone || closed.get()) {
                throw new IOException("The request ended before its body reader started.");
            }
            body = new RequestBodyInput(request.getInputStream(), cutoff::passed, cutoff::finished);
            cutoff.start(body);
            return body;
        }

        private synchronized void ifActive(Runnable action) {
            if (!requestDone) {
                action.run();
            }
        }
    }

    /** The live container connection; its implementation forgets the pooled request when this act ends. */
    interface ConnectionEnd extends AutoCloseable {
        void abort(IOException failure);

        @Override
        void close();
    }

    static void connection(HttpServletRequest request, ConnectionEnd connection) {
        request.setAttribute(CONNECTION, connection);
    }

    /** Send an admitted file without a container thread waiting for a client to read it. */
    public void download(HttpServletRequest request, HttpServletResponse response, InputStream content)
            throws IOException {
        if (!(request.getAttribute(CONNECTION) instanceof ConnectionEnd connection)) {
            content.close();
            throw new IOException("The container did not supply a file transfer connection.");
        }
        try {
            byte[] buffer = new byte[64 * 1024];
            int first = content.read(buffer);
            AsyncContext context = request.startAsync();
            context.setTimeout(0);
            Download download = new Download(context, response.getOutputStream(), content, connection, buffer, first);
            context.addListener(download);
            download.start();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                content.close();
            } catch (IOException closing) {
                if (closing != failure) {
                    failure.addSuppressed(closing);
                }
            } finally {
                connection.close();
            }
            throw failure;
        }
    }

    private final class Download implements WriteListener, AsyncListener {

        private final AsyncContext context;
        private final ServletOutputStream output;
        private final InputStream content;
        private final ConnectionEnd connection;
        private final byte[] buffer;
        private final long started = nanoTime.getAsLong();
        private long accepted;
        private int pending;
        private boolean eof;
        private boolean completing;
        private boolean expired;
        private boolean deadlineExceeded;
        private boolean ended;
        private @Nullable ScheduledFuture<?> task;

        private Download(
                AsyncContext context,
                ServletOutputStream output,
                InputStream content,
                ConnectionEnd connection,
                byte[] buffer,
                int pending) {
            this.context = context;
            this.output = output;
            this.content = content;
            this.connection = connection;
            this.buffer = buffer;
            this.pending = pending;
        }

        private synchronized void start() {
            schedule();
            output.setWriteListener(this);
        }

        @Override
        public synchronized void onWritePossible() throws IOException {
            if (ended || expired || completing) {
                return;
            }
            try {
                while (output.isReady()) {
                    if (TransferPace.tooSlow(started, accepted, nanoTime.getAsLong())) {
                        expire();
                        return;
                    }
                    if (!eof) {
                        int count = pending;
                        if (count >= 0) {
                            output.write(buffer, 0, count);
                            accepted += count;
                            pending = content.read(buffer);
                            if (task != null) {
                                task.cancel(false);
                            }
                            schedule();
                            continue;
                        }
                        eof = true;
                    }
                    output.flush();
                    if (output.isReady()) {
                        completing = true;
                        context.complete();
                    }
                    return;
                }
            } catch (IOException | RuntimeException failure) {
                abort(failure instanceof IOException io ? io : new IOException("The file could not be sent.", failure));
                throw failure;
            }
        }

        private void schedule() {
            long elapsed = nanoTime.getAsLong() - started;
            long allowance = TransferPace.allowedNanos(accepted);
            task = deadlines.schedule(this::expire, Math.max(0, allowance - elapsed), TimeUnit.NANOSECONDS);
        }

        private synchronized void expire() {
            if (!ended) {
                if (TransferPace.tooSlow(started, accepted, nanoTime.getAsLong())) {
                    deadlineExceeded = true;
                    abort(new IOException("The file was read too slowly."));
                } else {
                    schedule();
                }
            }
        }

        private void abort(IOException failure) {
            expired = true;
            connection.abort(failure);
        }

        @Override
        public synchronized void onError(Throwable failure) {
            abort(failure instanceof IOException io ? io : new IOException("The file could not be sent.", failure));
            finish();
        }

        private synchronized void finish() {
            if (ended) {
                return;
            }
            ended = true;
            connection.close();
            if (task != null) {
                task.cancel(false);
            }
            try {
                content.close();
            } catch (IOException closing) {
                // The admission wrapper gives the turn back even where its source could not be closed.
                logger.warn("A file transfer's source could not be closed", closing);
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            finish();
        }

        @Override
        public synchronized void onError(AsyncEvent event) {
            finish();
            if (deadlineExceeded) {
                context.complete();
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            expire();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (UploadLife life : uploading) {
                life.stopped();
            }
            deadlines.shutdownNow();
        }
    }

    private final class ReadCutoff implements AutoCloseable, MultipartFiles.Receiving {

        private final long started;
        private volatile long allowed;
        private @Nullable RequestBodyInput input;
        private @Nullable ScheduledFuture<?> task;
        private boolean closed;

        private ReadCutoff(long started, long allowed) {
            this.started = started;
            this.allowed = allowed;
        }

        private boolean passed() {
            return nanoTime.getAsLong() - started > allowed;
        }

        private synchronized void start(RequestBodyInput input) {
            this.input = input;
            if (!closed) {
                schedule();
            }
        }

        @Override
        public synchronized void limit(long bytes) {
            long narrowed = TransferPace.allowedNanos(bytes);
            if (narrowed < allowed && !closed) {
                allowed = narrowed;
                if (task != null) {
                    task.cancel(false);
                }
                schedule();
            }
        }

        @Override
        public void finished() {
            close();
        }

        private void schedule() {
            long elapsed = nanoTime.getAsLong() - started;
            task = deadlines.schedule(this::expire, Math.max(0, allowed - elapsed), TimeUnit.NANOSECONDS);
        }

        private void expire() {
            RequestBodyInput expiredInput;
            synchronized (this) {
                if (closed || input == null) {
                    return;
                }
                if (!passed()) {
                    schedule();
                    return;
                }
                expiredInput = input;
            }
            expiredInput.expired();
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (task != null) {
                task.cancel(false);
            }
        }
    }
}
