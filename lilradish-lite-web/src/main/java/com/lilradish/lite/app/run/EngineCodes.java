package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.codestep.CodeRuns;
import com.lilradish.lite.app.files.FileFillings;
import com.lilradish.lite.app.files.RunFiles;
import com.lilradish.lite.domain.codestep.CodeCall;
import com.lilradish.lite.domain.codestep.CodeError;
import com.lilradish.lite.domain.codestep.CodeErrorReason;
import com.lilradish.lite.domain.codestep.CodeOutcome;
import com.lilradish.lite.domain.codestep.CodeStep;
import com.lilradish.lite.domain.codestep.CodeStepDeclaration;
import com.lilradish.lite.domain.codestep.CodeStepName;
import com.lilradish.lite.domain.codestep.ReleasedCodeStep;
import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.file.StoredFile;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.filling.FillPath;
import com.lilradish.lite.domain.filling.FilledFields;
import com.lilradish.lite.domain.filling.Filling;
import com.lilradish.lite.domain.run.CodeStepFit;
import com.lilradish.lite.domain.run.EngineAct;
import com.lilradish.lite.domain.run.EngineActs;
import com.lilradish.lite.domain.run.InputRecord;
import com.lilradish.lite.domain.run.ProductionId;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunStepId;
import com.lilradish.lite.domain.run.StepAct;
import com.lilradish.lite.domain.run.StepGround;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepPosition;
import com.lilradish.lite.domain.run.StepPositions;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonArray;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.wire.JsonValue.JsonNull;
import com.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.workflow.Producer;
import com.lilradish.lite.domain.workflow.StepProducer;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place a code step is run: on the engine's code threads, outside any transaction. Its try is written in a
 * short transaction of its own just before, and only while it is still the try to make, so a try on record is code
 * run and none is code never run; how it ended is written in another, so nothing planned after it takes that back.
 */
@Component
final class EngineCodes {

    private static final Logger logger = LoggerFactory.getLogger(EngineCodes.class);

    private final CodeRuns codeRuns;

    private final RunFiles files;

    private final TransactionOperations transactions;

    private final RunTree tree;

    private final RunSnapshots snapshots;

    private final EngineWrites writes;

    private final EngineExecutor executor;

    EngineCodes(
            CodeRuns codeRuns,
            TransactionOperations transactions,
            RunTree tree,
            RunSnapshots snapshots,
            EngineWrites writes,
            EngineExecutor executor,
            RunFiles files) {
        this.codeRuns = codeRuns;
        this.files = files;
        this.transactions = transactions;
        this.tree = tree;
        this.snapshots = snapshots;
        this.writes = writes;
        this.executor = executor;
    }

    /**
     * Makes {@code pending} where it is still the try to make and writes how it ended, returning once that is
     * committed or found ended already; whether its try was written, one ended at once as no longer fitting what the
     * release declares included, its code then never run.
     *
     * @param maximumDepth how many runs deep a tree may go, as the run's next act is worked out with it
     */
    boolean runAndEnd(PendingCode pending, int maximumDepth) {
        requireNonNull(pending, "EngineCodes pending must not be null");
        if (!(Thread.currentThread() instanceof CodeThread)) {
            throw new IllegalStateException(
                    "Step " + pending.step().value() + " was to run code off the run engine's code threads");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Step " + pending.step().value() + " was to run code inside a transaction it would hold open");
        }
        Begun begun = transactions.execute(status -> begun(pending, maximumDepth));
        if (begun == null) {
            return false;
        }
        CodeCall call = begun.call();
        if (call == null) {
            return true;
        }
        CodeRuns.Ran ran = null;
        try {
            CodeOutcome ordinaryFailure = null;
            try {
                ran = codeRuns.run(call);
            } catch (Error fatal) {
                logger.error(
                        "Try {} (number {}) of step {} of run {} under run {} of group {} stopped its thread with {}",
                        begun.aTry().value(),
                        pending.number(),
                        pending.step().value(),
                        pending.run().value(),
                        pending.root().value(),
                        pending.group().value(),
                        fatal.getClass().getName());
                throw fatal;
            } catch (RuntimeException failed) {
                logger.warn(
                        "Try {} of run {} failed on this side with {}",
                        begun.aTry().value(),
                        pending.run().value(),
                        failed.getClass().getName());
                ordinaryFailure = new CodeOutcome.Errored(
                        new CodeError.Fault(CodeErrorReason.FAILED_ON_THIS_SIDE, List.of(), null, null), null);
            }
            if (ordinaryFailure != null) {
                endPlain(pending, begun.aTry(), ordinaryFailure);
            } else {
                CodeRuns.Ran result = requireNonNull(ran);
                CodeStep.Output returned = result.returned();
                if (result.failure() != null
                        || returned == null
                        || returned.files().isEmpty()) {
                    CodeOutcome outcome = result.failure() != null
                            ? result.failure()
                            : CodeOutcome.returned(call, returned == null ? null : returned.values());
                    try {
                        result.close();
                    } catch (RuntimeException failed) {
                        logger.warn(
                                "Try {} of run {} failed on this side while closing a code stream with {}",
                                begun.aTry().value(),
                                pending.run().value(),
                                failed.getClass().getName());
                        outcome = failedOnThisSide();
                    }
                    endPlain(pending, begun.aTry(), outcome);
                } else {
                    try {
                        endWithFiles(pending, begun.aTry(), call, returned, result);
                    } catch (FileRefusal refused) {
                        try {
                            result.close();
                        } catch (RuntimeException closing) {
                            logger.warn(
                                    "Try {} of run {} refused a code file and its stream close failed with {}",
                                    begun.aTry().value(),
                                    pending.run().value(),
                                    closing.getClass().getName());
                        }
                        endPlain(pending, begun.aTry(), CodeOutcome.refusedFile(refused.fault, returned.values()));
                    } catch (RuntimeException failed) {
                        logger.warn(
                                "Try {} of run {} failed on this side while keeping code files with {}",
                                begun.aTry().value(),
                                pending.run().value(),
                                failed.getClass().getName());
                        endPlain(pending, begun.aTry(), failedOnThisSide());
                    }
                }
            }
            return true;
        } finally {
            if (ran != null) {
                ran.close();
            }
        }
    }

    private static CodeOutcome failedOnThisSide() {
        return new CodeOutcome.Errored(
                new CodeError.Fault(CodeErrorReason.FAILED_ON_THIS_SIDE, List.of(), null, null), null);
    }

    private void endPlain(PendingCode pending, ProductionId aTry, CodeOutcome outcome) {
        transactions.executeWithoutResult(status -> {
            LockedTree held = locked(pending);
            if (!writes.codeEnded(held, aTry, outcome)) {
                logger.warn(
                        "Try {} of run {} was ended before its code came back; what it came to is dropped",
                        aTry.value(),
                        pending.run().value());
            } else {
                holdOutputs(held, pending, aTry, outcome);
            }
        });
    }

    private void holdOutputs(LockedTree held, PendingCode pending, ProductionId aTry, CodeOutcome outcome) {
        if (!(outcome instanceof CodeOutcome.Gave gave)) {
            return;
        }
        List<String> affected = writes.fileOutputsAffected(
                held,
                pending.run(),
                aTry,
                gave.values().stream().map(value -> value.name().value()).toList());
        if (!affected.isEmpty()) {
            RunEngine.holdBeneathOutputs(snapshots.inTreeLocked(held, pending.run()), affected, files);
        }
    }

    private void endWithFiles(
            PendingCode pending, ProductionId aTry, CodeCall call, CodeStep.Output output, CodeRuns.Ran ran) {
        transactions.executeWithoutResult(status -> {
            LockedTree held = locked(pending);
            List<FillField> declaredFields =
                    FillField.of(call.declared().gives().fields(), call.lists());
            List<FillField> fileFields = fileEnvelopeFields(declaredFields);
            List<FileOffering> offerings = new ArrayList<>();
            Set<FillPath> used = new HashSet<>();
            int[] nextUpload = {0};
            JsonObject marked = fileMarkers(
                    fileFields,
                    declaredFields,
                    output.values(),
                    List.of(),
                    output.files(),
                    used,
                    offerings,
                    nextUpload);
            if (!used.equals(output.files().keySet())) {
                FillPath unknown = output.files().keySet().stream()
                        .filter(path -> !used.contains(path))
                        .findFirst()
                        .orElseThrow();
                throw new FileRefusal(fault(CodeErrorReason.NOT_ITS_KIND, unknown));
            }
            FileFillings.beforeFileStreams(fileFields, marked);
            TotalFileBytes total = new TotalFileBytes(files.maximumTotalSize());
            Map<FillPath, RunFiles.VerifiedBatch> batches = new HashMap<>();
            for (FileOffering offering : offerings) {
                RunFiles.Batch batch = files.begin(pending.run(), pending.group(), offering.field());
                for (int index = 0; index < offering.files().size(); index++) {
                    CodeStep.FileOutput file = offering.files().get(index);
                    try {
                        batch.upload(index, offering.markers().get(index), file.name(), total.stream(file.content()));
                    } catch (RuntimeException refused) {
                        if (total.exceeded()) {
                            throw new FileRefusal(
                                    new CodeError.Fault(CodeErrorReason.FILE_TOTAL_TOO_LARGE, List.of(), null, null));
                        }
                        if (refused instanceof ApiErrorException input) {
                            throw fileRefusal(input, offering.path());
                        }
                        throw refused;
                    }
                }
                try {
                    batches.put(offering.path(), batch.finish());
                } catch (ApiErrorException refused) {
                    throw fileRefusal(refused, offering.path());
                }
            }
            if (!(FileFillings.of(pending.run(), pending.group(), fileFields, marked, batches)
                    instanceof FilledFields filled)) {
                throw new IllegalStateException("Verified code files were not accepted as a filling");
            }
            JsonObject returned = mergeFiles(fileFields, output.values(), filled.values());
            ran.close();
            CodeOutcome outcome = CodeOutcome.returnedAfterFileFillings(call, returned);
            if (!writes.codeEnded(held, aTry, outcome)) {
                status.setRollbackOnly();
                logger.warn(
                        "Try {} of run {} was ended before its code came back; what it came to is dropped",
                        aTry.value(),
                        pending.run().value());
                return;
            }
            holdOutputs(held, pending, aTry, outcome);
            if (outcome instanceof CodeOutcome.Errored) {
                List<EngineWrites.KeptCodeFile> kept = new ArrayList<>();
                keptFiles(fileFields, filled.values(), List.of(), kept);
                writes.codeFilesKept(held, pending.run(), aTry, kept);
            }
        });
    }

    private static List<FillField> fileEnvelopeFields(List<FillField> fields) {
        List<FillField> kept = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            if (field.kind() == FieldKind.FILE) {
                kept.add(new FillField(
                        field.name(),
                        field.label(),
                        field.help(),
                        FieldKind.FILE,
                        null,
                        field.most(),
                        false,
                        null,
                        List.of(),
                        field.fileTypes(),
                        field.maximumBytes()));
            } else if (field.kind() == FieldKind.FIELDS) {
                List<FillField> children = new ArrayList<>(fileEnvelopeFields(field.fields()));
                String marker = "file_envelope_marker";
                for (int number = 2; fieldsNamed(children, marker); number++) {
                    marker = "file_envelope_marker_" + number;
                }
                // Keep an otherwise empty nested object through Filling; it is never merged into the code return.
                children.add(new FillField(
                        new FieldName(marker), null, null, FieldKind.TEXT, 1, null, false, null, List.of()));
                kept.add(new FillField(
                        field.name(),
                        field.label(),
                        field.help(),
                        FieldKind.FIELDS,
                        null,
                        field.most() == null ? null : Integer.MAX_VALUE,
                        false,
                        null,
                        children));
            } else {
                kept.add(new FillField(
                        field.name(), field.label(), field.help(), FieldKind.TEXT, 1, null, false, null, List.of()));
            }
        }
        return List.copyOf(kept);
    }

    private static boolean fieldsNamed(List<FillField> fields, String name) {
        return fields.stream().anyMatch(field -> field.name().value().equals(name));
    }

    private static JsonObject fileMarkers(
            List<FillField> fields,
            List<FillField> declared,
            JsonObject raw,
            List<FillPath.Step> parent,
            Map<FillPath, List<CodeStep.FileOutput>> files,
            Set<FillPath> used,
            List<FileOffering> offerings,
            int[] nextUpload) {
        List<JsonMember> members = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            FillField original = declared.stream()
                    .filter(candidate -> candidate.name().equals(field.name()))
                    .findFirst()
                    .orElse(null);
            List<FillPath.Step> at = new ArrayList<>(parent);
            at.add(new FillPath.Named(field.name()));
            FillPath path = new FillPath(at);
            JsonValue present = member(raw, field.name().value());
            if (field.kind() == FieldKind.FILE) {
                if (present != null) {
                    throw new FileRefusal(fault(CodeErrorReason.NOT_ITS_KIND, path));
                }
                List<CodeStep.FileOutput> given = files.getOrDefault(path, List.of());
                if (files.containsKey(path)) {
                    used.add(path);
                }
                if (field.most() == null && given.size() > 1) {
                    throw new FileRefusal(fault(CodeErrorReason.NOT_ITS_KIND, path));
                }
                if (field.most() != null && given.size() > field.most()) {
                    throw new FileRefusal(fault(CodeErrorReason.TOO_MANY, path));
                }
                if (given.isEmpty() && requireNonNull(original).mustBeGiven()) {
                    throw new FileRefusal(fault(CodeErrorReason.NOTHING_GIVEN, path));
                }
                List<Integer> markers = new ArrayList<>(given.size());
                List<JsonValue> shown = new ArrayList<>(given.size());
                for (CodeStep.FileOutput file : given) {
                    int marker = nextUpload[0]++;
                    markers.add(marker);
                    shown.add(new JsonObject(List.of(
                            new JsonMember("upload", new JsonNumber(BigDecimal.valueOf(marker))),
                            new JsonMember("name", new JsonValue.JsonString(file.name())))));
                }
                if (!given.isEmpty()) {
                    offerings.add(new FileOffering(path, field, given, markers));
                }
                JsonValue marked = field.most() == null
                        ? shown.isEmpty() ? new JsonNull() : shown.getFirst()
                        : new JsonArray(shown);
                members.add(new JsonMember(field.name().value(), marked));
            } else if (field.kind() == FieldKind.TEXT) {
                members.add(new JsonMember(field.name().value(), new JsonValue.JsonString("x")));
            } else {
                if (present == null || present instanceof JsonNull) {
                    members.add(new JsonMember(
                            field.name().value(), field.most() == null ? new JsonNull() : new JsonArray(List.of())));
                } else if (field.most() == null && present instanceof JsonObject nested) {
                    members.add(new JsonMember(
                            field.name().value(),
                            fileMarkers(
                                    field.fields(),
                                    requireNonNull(original).fields(),
                                    nested,
                                    at,
                                    files,
                                    used,
                                    offerings,
                                    nextUpload)));
                } else if (field.most() != null && present instanceof JsonArray many) {
                    List<JsonValue> nested = new ArrayList<>(many.items().size());
                    for (int index = 0; index < many.items().size(); index++) {
                        if (many.items().get(index) instanceof JsonObject object) {
                            List<FillPath.Step> item = new ArrayList<>(at);
                            item.add(new FillPath.Place(index));
                            nested.add(fileMarkers(
                                    field.fields(),
                                    requireNonNull(original).fields(),
                                    object,
                                    item,
                                    files,
                                    used,
                                    offerings,
                                    nextUpload));
                        } else {
                            nested.add(emptyFileMarkers(field.fields()));
                        }
                    }
                    members.add(new JsonMember(field.name().value(), new JsonArray(nested)));
                } else {
                    members.add(new JsonMember(
                            field.name().value(), field.most() == null ? new JsonNull() : new JsonArray(List.of())));
                }
            }
        }
        return new JsonObject(members);
    }

    private static JsonObject emptyFileMarkers(List<FillField> fields) {
        List<JsonMember> members = new ArrayList<>(fields.size());
        for (FillField field : fields) {
            JsonValue value =
                    switch (field.kind()) {
                        case FILE -> field.most() == null ? new JsonNull() : new JsonArray(List.of());
                        case FIELDS -> field.most() == null ? new JsonNull() : new JsonArray(List.of());
                        default -> new JsonValue.JsonString("x");
                    };
            members.add(new JsonMember(field.name().value(), value));
        }
        return new JsonObject(members);
    }

    private static JsonObject mergeFiles(List<FillField> fields, JsonObject raw, JsonObject verified) {
        List<JsonMember> combined = new ArrayList<>(raw.members().size() + fields.size());
        for (JsonMember member : raw.members()) {
            FillField field = fields.stream()
                    .filter(candidate -> candidate.name().value().equals(member.name()))
                    .findFirst()
                    .orElse(null);
            if (field == null || field.kind() != FieldKind.FIELDS || member.value() instanceof JsonNull) {
                combined.add(member);
                continue;
            }
            JsonValue files = member(verified, field.name().value());
            if (member.value() instanceof JsonObject object && files instanceof JsonObject fileObject) {
                combined.add(new JsonMember(member.name(), mergeFiles(field.fields(), object, fileObject)));
            } else if (member.value() instanceof JsonArray many && files instanceof JsonArray fileMany) {
                List<JsonValue> items = new ArrayList<>(many.items().size());
                for (int index = 0; index < many.items().size(); index++) {
                    JsonValue item = many.items().get(index);
                    JsonValue fileItem = fileMany.items().get(index);
                    items.add(
                            item instanceof JsonObject object && fileItem instanceof JsonObject fileObject
                                    ? mergeFiles(field.fields(), object, fileObject)
                                    : item);
                }
                combined.add(new JsonMember(member.name(), new JsonArray(items)));
            } else {
                combined.add(member);
            }
        }
        for (FillField field : fields) {
            if (field.kind() == FieldKind.FILE) {
                combined.add(new JsonMember(
                        field.name().value(),
                        requireNonNull(member(verified, field.name().value()))));
            }
        }
        return new JsonObject(combined);
    }

    private static void keptFiles(
            List<FillField> fields,
            JsonObject values,
            List<FillPath.Step> parent,
            List<EngineWrites.KeptCodeFile> kept) {
        for (FillField field : fields) {
            List<FillPath.Step> at = new ArrayList<>(parent);
            at.add(new FillPath.Named(field.name()));
            JsonValue value = requireNonNull(member(values, field.name().value()));
            if (field.kind() == FieldKind.FILE) {
                if (value instanceof JsonNull) {
                    continue;
                }
                if (value instanceof JsonArray many) {
                    for (int index = 0; index < many.items().size(); index++) {
                        List<FillPath.Step> item = new ArrayList<>(at);
                        item.add(new FillPath.Place(index));
                        kept.add(new EngineWrites.KeptCodeFile(
                                new FillPath(item),
                                StoredFile.fromKept(many.items().get(index)).id()));
                    }
                } else {
                    kept.add(new EngineWrites.KeptCodeFile(
                            new FillPath(at), StoredFile.fromKept(value).id()));
                }
            } else if (value instanceof JsonObject nested) {
                keptFiles(field.fields(), nested, at, kept);
            } else if (value instanceof JsonArray many) {
                for (int index = 0; index < many.items().size(); index++) {
                    List<FillPath.Step> item = new ArrayList<>(at);
                    item.add(new FillPath.Place(index));
                    keptFiles(field.fields(), (JsonObject) many.items().get(index), item, kept);
                }
            }
        }
    }

    private static @Nullable JsonValue member(JsonObject object, String name) {
        for (JsonMember member : object.members()) {
            if (member.name().equals(name)) {
                return member.value();
            }
        }
        return null;
    }

    private static FileRefusal fileRefusal(ApiErrorException refused, FillPath path) {
        CodeErrorReason reason;
        if (refused.errorCode() == RefusalCode.FILE_TYPE_UNUSABLE) {
            reason = CodeErrorReason.FILE_TYPE_UNUSABLE;
        } else if (refused.errorCode() == RefusalCode.FILE_NAME_UNUSABLE) {
            reason = CodeErrorReason.FILE_NAME_UNUSABLE;
        } else if (refused.errorCode() == RefusalCode.FILE_TOO_LARGE) {
            reason = CodeErrorReason.FILE_TOO_LARGE;
        } else if (refused.errorCode() == RefusalCode.FILES_TOO_MANY) {
            reason = CodeErrorReason.TOO_MANY;
        } else {
            throw refused;
        }
        return new FileRefusal(fault(reason, path));
    }

    private static CodeError.Fault fault(CodeErrorReason reason, FillPath path) {
        List<FieldName> names = new ArrayList<>();
        for (FillPath.Step step : path.steps()) {
            if (step instanceof FillPath.Place) {
                break;
            }
            names.add(((FillPath.Named) step).name());
        }
        return new CodeError.Fault(reason, names, null, null);
    }

    private static CodeError.@Nullable Fault fileMisfit(
            List<FillField> fields, JsonObject values, List<FillPath.Step> parent) {
        for (FillField field : fields) {
            if (!hasFile(field)) {
                continue;
            }
            JsonValue value = requireNonNull(member(values, field.name().value()));
            if (value instanceof JsonNull) {
                continue;
            }
            List<FillPath.Step> at = new ArrayList<>(parent);
            at.add(new FillPath.Named(field.name()));
            if (field.kind() == FieldKind.FILE) {
                try {
                    Filling.wire(field, value);
                } catch (IllegalStateException refused) {
                    return fault(CodeErrorReason.TAKES_OTHERWISE, new FillPath(at));
                }
            } else if (value instanceof JsonObject nested) {
                CodeError.Fault refused = fileMisfit(field.fields(), nested, at);
                if (refused != null) {
                    return refused;
                }
            } else if (value instanceof JsonArray many) {
                for (int index = 0; index < many.items().size(); index++) {
                    if (!(many.items().get(index) instanceof JsonObject nested)) {
                        continue;
                    }
                    List<FillPath.Step> item = new ArrayList<>(at);
                    item.add(new FillPath.Place(index));
                    CodeError.Fault refused = fileMisfit(field.fields(), nested, item);
                    if (refused != null) {
                        return refused;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasFile(FillField field) {
        return field.kind() == FieldKind.FILE || field.fields().stream().anyMatch(EngineCodes::hasFile);
    }

    private record FileOffering(
            FillPath path, FillField field, List<CodeStep.FileOutput> files, List<Integer> markers) {}

    private static final class TotalFileBytes {

        private final long maximum;
        private long counted;
        private boolean exceeded;

        private TotalFileBytes(long maximum) {
            this.maximum = maximum;
        }

        private boolean exceeded() {
            return exceeded;
        }

        private InputStream stream(InputStream content) {
            return new FilterInputStream(content) {
                @Override
                public int read() throws IOException {
                    if (exceeded) {
                        throw new IOException("File total exceeded");
                    }
                    int value = super.read();
                    if (value >= 0 && ++counted > maximum) {
                        exceeded = true;
                        throw new IOException("File total exceeded");
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (exceeded) {
                        throw new IOException("File total exceeded");
                    }
                    int bounded = (int) Math.min(length, maximum - counted + 1);
                    int read = super.read(bytes, offset, bounded);
                    if (read > 0 && (counted += read) > maximum) {
                        exceeded = true;
                        throw new IOException("File total exceeded");
                    }
                    return read;
                }
            };
        }
    }

    private static final class FileRefusal extends RuntimeException {
        private final CodeError.Fault fault;

        private FileRefusal(CodeError.Fault fault) {
            this.fault = fault;
        }
    }

    private @Nullable Begun begun(PendingCode pending, int maximumDepth) {
        LockedTree held = locked(pending);
        RunInTree at = snapshots.inTreeLocked(held, pending.run());
        RunSnapshot run = at.run();
        StepSnapshot step = run.step(pending.step())
                .orElseThrow(
                        () -> new IllegalStateException("Run " + pending.run().value() + " holds no step "
                                + pending.step().value()));
        // Judged once the lock is held: stopping may have begun while this waited for it.
        if (executor.stopping() || !due(at, step, pending, maximumDepth)) {
            return null;
        }
        StepRuns.Code code = (StepRuns.Code) step.planned().runs();
        ReleasedCodeStep released = requireNonNull(code.released(), "a code step started is one the release holds");
        if (pending.number() > 1 && !released.mayRunAgain()) {
            throw new IllegalStateException(
                    "Code step " + code.codeStep() + " was to be run again, which the release does not let it be");
        }
        RunStepId runStep = writes.runStep(held, run, step);
        List<StepInputs.BindingRecord> bound = StepInputs.bound(at, step)
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + step.planned().name().value() + " was reached before what it takes stood"));
        Optional<CodeError.Fault> misfit = CodeStepFit.misfit(run, step);
        CodeStepDeclaration declared = released.declared();
        @Nullable JsonObject filled = null;
        if (misfit.isEmpty()) {
            filled = StepInputs.filled(declared.takes(), bound);
            if (declared.takes().hasFile()) {
                misfit = Optional.ofNullable(
                        fileMisfit(FillField.of(declared.takes().fields(), released.lists()), filled, List.of()));
            }
        }
        if (misfit.isPresent()) {
            ProductionId aTry = writes.tried(
                    held,
                    run,
                    runStep,
                    pending.number(),
                    StepProducer.CODE,
                    pending.asker(),
                    released.mayRunAgain(),
                    untraced(bound));
            CodeOutcome errored = new CodeOutcome.Errored(misfit.get(), null);
            if (!writes.codeEnded(held, aTry, errored)) {
                throw new IllegalStateException("Try " + aTry.value() + " was ended as it was written");
            }
            return new Begun(aTry, null);
        }
        List<InputRecord> inputs = StepInputs.traced(bound, declared.takes());
        ProductionId aTry = writes.tried(
                held,
                run,
                runStep,
                pending.number(),
                StepProducer.CODE,
                pending.asker(),
                released.mayRunAgain(),
                inputs);
        Map<FillPath, CodeStep.FileInput> fileInputs = new HashMap<>();
        for (Map.Entry<FillPath, StepInputs.FileSource> source :
                StepInputs.fileSources(at, bound, declared.takes()).entrySet()) {
            fileInputs.put(source.getKey(), () -> files.open(
                            source.getValue().run(),
                            pending.group(),
                            source.getValue().file())
                    .content());
        }
        return new Begun(
                aTry,
                new CodeCall(
                        new CodeStepName(code.codeStep()),
                        declared,
                        released.lists(),
                        requireNonNull(filled),
                        fileInputs));
    }

    /*
     * Worked out again under the lock, since anything may have happened since it was handed over: the run's own
     * next act is still this try, or the one a person asked for is still theirs to ask. Either refuses a run stopped.
     */
    private static boolean due(RunInTree at, StepSnapshot step, PendingCode pending, int maximumDepth) {
        RunSnapshot run = at.run();
        if (!(step.planned().runs() instanceof StepRuns.Code)
                || !(step.planned().producer() instanceof Producer.Code)) {
            throw new IllegalStateException("Step " + step.planned().name().value() + " runs no code of its own");
        }
        if (pending.asker() == null) {
            return EngineActs.next(at, maximumDepth)
                    .equals(new EngineAct.StartTry(run.run(), step.planned(), pending.number()));
        }
        StepPosition position = StepPositions.of(run, step);
        StepPosition.Owed owed = position.nextTry();
        return owed != null
                && owed.number() == pending.number()
                && StepAct.ASK_AGAIN.refusal(StepGround.of(run, step, position, null)) == null;
    }

    /* Each binding beside what it was read from, kept though what it fills is no longer declared. */
    private static List<InputRecord> untraced(List<StepInputs.BindingRecord> bound) {
        return bound.stream()
                .map(each -> new InputRecord(each.binding().id(), each.source(), each.parts()))
                .toList();
    }

    private LockedTree locked(PendingCode pending) {
        return tree.lock(pending.group(), pending.root())
                .orElseThrow(() -> new IllegalStateException("Group "
                        + pending.group().value() + " holds no run "
                        + pending.root().value() + " for step " + pending.step().value()));
    }

    /**
     * A try written, and what its code is run with; none where it no longer fit and was ended at once.
     */
    private record Begun(ProductionId aTry, @Nullable CodeCall call) {}
}
