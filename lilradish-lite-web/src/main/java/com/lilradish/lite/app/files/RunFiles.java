package com.lilradish.lite.app.files;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.file.FileName;
import com.lilradish.lite.domain.file.StoredFile;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.run.RunId;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The sole entry and exit for files held by runs. The caller owns the enclosing run or try transaction. */
@Component
public final class RunFiles {

    private static final String HELD = """
            select file.file_id, file.original_name, file.byte_size
              from run_file_holders holder
              join stored_files file on file.file_id = holder.file_id and file.group_id = holder.group_id
             where holder.run_id = :run and holder.file_id = :file and holder.group_id = :group
            """;

    private static final String METADATA = """
            insert into stored_files (file_id, group_id, original_name, byte_size)
            values (:file, :group, :name, :size)
            """;

    // DB-SPECIFIC: PostgreSQL ON CONFLICT makes the holder insert idempotent.
    private static final String HOLDER = """
            insert into run_file_holders (run_id, file_id, group_id)
            values (:run, :file, :group)
            on conflict (run_id, file_id) do nothing
            """;

    private final JdbcClient database;
    private final FileContentStore content;
    private final FileDeployment deployment;

    public RunFiles(JdbcClient database, FileContentStore content, FileDeployment deployment) {
        this.database = database;
        this.content = content;
        this.deployment = deployment;
    }

    /** Combined byte limit for all new files returned by one code-step attempt, across its fields. */
    public long maximumTotalSize() {
        return deployment.maximumTotalSize().toBytes();
    }

    /** Begin a complete, ordered file value inside the caller's transaction. */
    public Batch begin(RunId run, GroupId group, FillField field) {
        requireNonNull(run, "RunFiles run must not be null");
        requireNonNull(group, "RunFiles group must not be null");
        requireNonNull(field, "RunFiles field must not be null");
        if (field.kind() != FieldKind.FILE) {
            throw new IllegalArgumentException("RunFiles batch requires a file field");
        }
        return new Batch(run, group, field, FileTransaction.current());
    }

    /** The caller must have proved the run readable before this call. */
    public OpenedFile open(RunId run, GroupId group, UUID file) {
        requireNonNull(run, "RunFiles run must not be null");
        requireNonNull(group, "RunFiles group must not be null");
        requireNonNull(file, "RunFiles file must not be null");
        StoredFile metadata = held(run, group, file).orElseThrow(RunFiles::notInView);
        return new OpenedFile(metadata, content.open(file));
    }

    private Optional<StoredFile> held(RunId run, GroupId group, UUID file) {
        return database.sql(HELD)
                .param("run", run.value())
                .param("file", file)
                .param("group", group.value())
                .query((result, number) -> stored(result))
                .optional();
    }

    private static StoredFile stored(ResultSet result) throws SQLException {
        return new StoredFile(
                result.getObject("file_id", UUID.class),
                new FileName(result.getString("original_name")),
                result.getLong("byte_size"));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("RunFiles writes require an enclosing transaction");
        }
    }

    private static ApiErrorException notInView() {
        return new ApiErrorException(RefusalCode.RUN_NOT_IN_VIEW, "That run is not in view.");
    }

    private static ApiErrorException refuse(RefusalCode code, String sentence) {
        return new ApiErrorException(code, sentence);
    }

    private static final class FileTransaction implements TransactionSynchronization {

        private static final Object KEY = new Object();

        private final Thread thread = Thread.currentThread();
        private final Set<VerifiedBatch> pending = new HashSet<>();
        private boolean active = true;
        private boolean failed;

        static FileTransaction current() {
            requireTransaction();
            Object bound = TransactionSynchronizationManager.getResource(KEY);
            if (bound instanceof FileTransaction transaction) {
                transaction.check();
                return transaction;
            }
            FileTransaction transaction = new FileTransaction();
            TransactionSynchronizationManager.bindResource(KEY, transaction);
            try {
                TransactionSynchronizationManager.registerSynchronization(transaction);
            } catch (RuntimeException failure) {
                TransactionSynchronizationManager.unbindResource(KEY);
                throw failure;
            }
            return transaction;
        }

        void check() {
            requireTransaction();
            if (!active
                    || failed
                    || Thread.currentThread() != thread
                    || TransactionSynchronizationManager.getResource(KEY) != this) {
                throw new IllegalStateException("Verified file batch is no longer active");
            }
        }

        void add(VerifiedBatch batch) {
            check();
            pending.add(batch);
        }

        void accept(VerifiedBatch batch) {
            check();
            if (!pending.remove(batch)) {
                failed = true;
                throw new IllegalStateException("Verified file batch was already used");
            }
        }

        void fail() {
            failed = true;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (failed || !pending.isEmpty()) {
                throw refuse(RefusalCode.BODY_UNUSABLE, "A file value was not accepted.");
            }
        }

        @Override
        public void suspend() {
            TransactionSynchronizationManager.unbindResource(KEY);
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(KEY, this);
        }

        @Override
        public void afterCompletion(int status) {
            active = false;
            pending.clear();
            if (TransactionSynchronizationManager.getResource(KEY) == this) {
                TransactionSynchronizationManager.unbindResource(KEY);
            }
        }
    }

    public record OpenedFile(StoredFile file, InputStream content) {

        public OpenedFile {
            requireNonNull(file, "OpenedFile file must not be null");
            requireNonNull(content, "OpenedFile content must not be null");
        }
    }

    /** One complete value, built in order and never reused after failure or finish. */
    public final class Batch {

        private final RunId run;
        private final GroupId group;
        private final FillField field;
        private final FileTransaction transaction;
        private final int maximumCount;
        private final long maximumSize;
        private final Map<Integer, VerifiedItem> files = new TreeMap<>();
        private final List<StoredFile> newFiles = new ArrayList<>();
        private boolean ended;

        private Batch(RunId run, GroupId group, FillField field, FileTransaction transaction) {
            this.run = run;
            this.group = group;
            this.field = field;
            this.transaction = transaction;
            this.maximumCount = Math.min(field.most() == null ? 1 : field.most(), deployment.maximumPerField());
            this.maximumSize = Math.min(
                    requireNonNull(field.maximumBytes()),
                    deployment.maximumSize().toBytes());
        }

        /** Consume one new stream once, without closing the caller's stream. */
        public void upload(int position, int uploadIndex, String originalName, InputStream stream) {
            check();
            try {
                countNext(position, uploadIndex);
                FileName name = name(originalName, position);
                if (requireNonNull(field.fileTypes()).stream().noneMatch(extension -> extension.accepts(name))) {
                    throw refuse(
                            RefusalCode.FILE_TYPE_UNUSABLE, "That file doesn't have an extension this field accepts.");
                }
                requireNonNull(stream, "RunFiles upload stream must not be null");
                UUID id = UUID.randomUUID();
                BoundedInput bounded = new BoundedInput(stream, maximumSize);
                try {
                    content.put(id, bounded);
                } catch (RuntimeException failure) {
                    if (bounded.exceeded()) {
                        throw refuse(
                                RefusalCode.FILE_TOO_LARGE,
                                "File " + (position + 1) + " is larger than this field takes.");
                    }
                    throw failure;
                }
                StoredFile stored = new StoredFile(id, name, bounded.count());
                files.put(position, new VerifiedItem(stored, true, uploadIndex));
                newFiles.add(stored);
            } catch (RuntimeException failure) {
                ended = true;
                transaction.fail();
                throw failure;
            }
        }

        /** Add a reference the caller obtained from a trusted server-side source. */
        public void reuse(int position, int keepIndex, RunId sourceRun, UUID file) {
            check();
            try {
                countNext(position, keepIndex);
                StoredFile stored = held(sourceRun, group, file).orElseThrow(RunFiles::notInView);
                if (requireNonNull(field.fileTypes()).stream()
                        .noneMatch(extension -> extension.accepts(stored.name()))) {
                    throw refuse(
                            RefusalCode.FILE_TYPE_UNUSABLE, "That file doesn't have an extension this field accepts.");
                }
                if (stored.size() > maximumSize) {
                    throw refuse(
                            RefusalCode.FILE_TOO_LARGE, "File " + (position + 1) + " is larger than this field takes.");
                }
                files.put(position, new VerifiedItem(stored, false, keepIndex));
            } catch (RuntimeException failure) {
                ended = true;
                transaction.fail();
                throw failure;
            }
        }

        public VerifiedBatch finish() {
            check();
            try {
                if (files.isEmpty() && field.mustBeGiven()) {
                    throw refuse(RefusalCode.BODY_UNUSABLE, "This field needs a file.");
                }
                for (int position = 0; position < files.size(); position++) {
                    if (!files.containsKey(position)) {
                        throw refuse(RefusalCode.BODY_UNUSABLE, "A file is missing from this field.");
                    }
                }
                for (StoredFile file : newFiles) {
                    database.sql(METADATA)
                            .param("file", file.id())
                            .param("group", group.value())
                            .param("name", file.name().value())
                            .param("size", file.size())
                            .update();
                }
                for (VerifiedItem item : files.values()) {
                    database.sql(HOLDER)
                            .param("run", run.value())
                            .param("file", item.file().id())
                            .param("group", group.value())
                            .update();
                }
                ended = true;
                VerifiedBatch verified = new VerifiedBatch(run, group, field, List.copyOf(files.values()), transaction);
                transaction.add(verified);
                return verified;
            } catch (RuntimeException failure) {
                ended = true;
                transaction.fail();
                throw failure;
            }
        }

        private void countNext(int position, int markerIndex) {
            if (position < 0 || markerIndex < 0 || files.containsKey(position)) {
                throw refuse(RefusalCode.BODY_UNUSABLE, "A file has an invalid position.");
            }
            if (files.size() >= maximumCount) {
                throw refuse(
                        RefusalCode.FILES_TOO_MANY, "This field takes fewer than " + (files.size() + 1) + " files.");
            }
        }

        private void check() {
            if (ended) {
                throw new IllegalStateException("RunFiles batch is no longer active");
            }
            transaction.check();
        }

        private FileName name(@Nullable String name, int position) {
            try {
                return new FileName(requireNonNull(name));
            } catch (IllegalArgumentException | NullPointerException refused) {
                throw refuse(RefusalCode.FILE_NAME_UNUSABLE, "File " + (position + 1) + " has an unusable name.");
            }
        }
    }

    record VerifiedItem(StoredFile file, boolean upload, int markerIndex) {}

    public static final class VerifiedBatch {

        private final RunId run;
        private final GroupId group;
        private final FillField field;
        private final List<VerifiedItem> items;
        private final FileTransaction transaction;

        private VerifiedBatch(
                RunId run, GroupId group, FillField field, List<VerifiedItem> items, FileTransaction transaction) {
            this.run = run;
            this.group = group;
            this.field = field;
            this.items = items;
            this.transaction = transaction;
        }

        void check() {
            transaction.check();
        }

        RunId run() {
            return run;
        }

        GroupId group() {
            return group;
        }

        FillField field() {
            return field;
        }

        List<VerifiedItem> items() {
            return items;
        }

        void accept() {
            transaction.accept(this);
        }

        void fail() {
            transaction.fail();
        }
    }

    private static final class BoundedInput extends FilterInputStream {

        private final long maximum;
        private long count;
        private boolean exceeded;

        private BoundedInput(InputStream stream, long maximum) {
            super(stream);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            if (exceeded) {
                throw new IOException("File exceeds the accepted size");
            }
            int value = super.read();
            if (value >= 0 && ++count > maximum) {
                exceeded = true;
                throw new IOException("File exceeds the accepted size");
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (exceeded) {
                throw new IOException("File exceeds the accepted size");
            }
            int bounded = (int) Math.min(length, maximum - count + 1);
            int read = super.read(bytes, offset, bounded);
            if (read > 0 && (count += read) > maximum) {
                exceeded = true;
                throw new IOException("File exceeds the accepted size");
            }
            return read;
        }

        long count() {
            return count;
        }

        boolean exceeded() {
            return exceeded;
        }

        @Override
        public void close() {
            // The caller owns the input stream.
        }
    }
}
