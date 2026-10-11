package com.lilradish.lite.web;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.files.FileDeployment;
import com.lilradish.lite.app.files.FileFillings;
import com.lilradish.lite.app.files.RunFiles;
import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.FieldKind;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.file.StoredFile;
import com.lilradish.lite.domain.filling.FillField;
import com.lilradish.lite.domain.filling.FillOutcome;
import com.lilradish.lite.domain.filling.FillPath;
import com.lilradish.lite.domain.filling.Filling;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.run.Reasons;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonArray;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.wire.JsonValue.JsonNull;
import com.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.wire.JsonValue.JsonString;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.libprunus.core.log.annotation.DoNotLog;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** The two file-filling requests read their parts from the raw request, inside the run transaction. */
public final class MultipartFiles implements AutoCloseable {

    private static final int LARGEST_BOUNDARY = 70;
    private static final int LARGEST_LINE = 8192;
    private static final int LARGEST_HEADERS = 16384;
    private static final long HEADER_ALLOWANCE = LARGEST_HEADERS + 2L * LARGEST_BOUNDARY + 16;

    private final Sending request;
    private final Receiving allowance;
    private PushbackInputStream input;
    private final byte[] delimiter;
    private final int documentMaximum;
    private @Nullable PartInput active;
    private boolean more = true;
    private boolean ended;
    private boolean readingFiles;
    private long counted;
    private long maximum = Long.MAX_VALUE;
    private long uploadedBytes;
    private long maximumUploadedBytes = Long.MAX_VALUE;

    private MultipartFiles(Sending request, InputStream body, String boundary, int documentMaximum, Receiving allowance)
            throws IOException {
        this.request = request;
        this.allowance = requireNonNull(allowance);
        this.documentMaximum = documentMaximum;
        this.delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
        this.input = new PushbackInputStream(new CountedInput(body), delimiter.length + 2);
        try {
            if (!line().equals("--" + boundary)) {
                throw unusable();
            }
        } catch (IOException | RuntimeException failure) {
            try {
                input.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    public static boolean sent(HttpServletRequest request) {
        String declared = request.getContentType();
        return declared != null && declared.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    }

    /** The request metadata kept while its worker may outlive the container request. */
    record Sending(List<String> contentTypes, List<String> contentEncodings, long contentLength) {}

    /** The transfer deadline follows bytes still arriving, ending once the complete body has been read. */
    interface Receiving {
        void limit(long bytes);

        void finished();
    }

    static Sending sending(HttpServletRequest request) {
        return new Sending(
                Collections.list(request.getHeaders("Content-Type")),
                Collections.list(request.getHeaders("Content-Encoding")),
                request.getContentLengthLong());
    }

    static MultipartFiles open(Sending request, InputStream body, int documentMaximum, Receiving allowance)
            throws IOException {
        if (documentMaximum < 1 || documentMaximum == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Document bound must be positive and leave room for one byte");
        }
        Enumeration<String> types = Collections.enumeration(request.contentTypes());
        if (!types.hasMoreElements()) {
            throw unusable();
        }
        String declaration = types.nextElement();
        if (types.hasMoreElements()) {
            throw unusable();
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(declaration);
        } catch (InvalidMediaTypeException invalid) {
            throw unusable();
        }
        Charset charset;
        try {
            charset = type.getCharset();
        } catch (IllegalArgumentException invalid) {
            throw unusable();
        }
        if (!"multipart".equalsIgnoreCase(type.getType())
                || !"form-data".equalsIgnoreCase(type.getSubtype())
                || type.getParameters().keySet().stream()
                        .anyMatch(name -> !"boundary".equalsIgnoreCase(name) && !"charset".equalsIgnoreCase(name))
                || charset != null && !UTF_8.equals(charset) && !StandardCharsets.US_ASCII.equals(charset)) {
            throw unusable();
        }
        String boundary = type.getParameter("boundary");
        if (boundary == null) {
            throw unusable();
        }
        if (boundary.length() < 1
                || boundary.length() > LARGEST_BOUNDARY
                || boundary.charAt(0) == ' '
                || boundary.charAt(boundary.length() - 1) == ' '
                || !boundary.chars()
                        .allMatch(character -> character == ' '
                                || character >= '0' && character <= '9'
                                || character >= 'A' && character <= 'Z'
                                || character >= 'a' && character <= 'z'
                                || "'()+_,-./:=?".indexOf(character) >= 0)) {
            throw unusable();
        }
        Enumeration<String> encodings = Collections.enumeration(request.contentEncodings());
        while (encodings.hasMoreElements()) {
            if (!"identity".equalsIgnoreCase(encodings.nextElement().strip())) {
                throw unusable();
            }
        }
        return new MultipartFiles(request, body, boundary, documentMaximum, allowance);
    }

    public JsonNode document() throws IOException {
        Part part = next();
        if (part == null || !"document".equals(part.name()) || part.filename() || part.contentType() != null) {
            throw unusable();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = part.content().read(chunk, 0, Math.min(chunk.length, documentMaximum + 1 - bytes.size())))
                >= 0) {
            bytes.write(chunk, 0, count);
            if (bytes.size() > documentMaximum) {
                throw unusable();
            }
        }
        String text;
        try {
            text = UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw unusable();
        }
        if (text.startsWith("\ufeff")) {
            throw unusable();
        }
        try {
            JsonNode node = JsonBody.written(text);
            if (node.isMissingNode()) {
                throw unusable();
            }
            return node;
        } catch (RuntimeException invalid) {
            throw unusable();
        }
    }

    /** Fill all file fields once, including trusted prior files only when answering a try. */
    public FillOutcome fill(
            RunId run,
            GroupId group,
            List<FillField> fields,
            JsonValue sent,
            JsonValue.@Nullable JsonObject previous,
            RunFiles files,
            FileDeployment deployment)
            throws IOException {
        List<Item> items = new ArrayList<>();
        scan(fields, sent, previous, List.of(), items);
        List<Item> uploads = items.stream()
                .filter(item -> item.uploadIndex() != null)
                .sorted(Comparator.comparingInt(item -> item.uploadIndex()))
                .toList();
        for (int index = 0; index < uploads.size(); index++) {
            if (requireNonNull(uploads.get(index).uploadIndex()) != index) {
                throw unusable();
            }
        }
        FileFillings.beforeFileStreams(fields, sent);
        long total = deployment.maximumTotalSize().toBytes();
        long fileAllowance = 0;
        for (Item item : uploads) {
            long single = Math.min(
                    requireNonNull(item.field().maximumBytes()),
                    deployment.maximumSize().toBytes());
            fileAllowance += Math.min(single, total - fileAllowance);
        }
        long allowed;
        try {
            allowed = requestBound(documentMaximum, fileAllowance, uploads.size());
        } catch (ArithmeticException impossibleBound) {
            throw unusable();
        }
        maximum = allowed;
        allowance.limit(allowed);
        maximumUploadedBytes = total;
        if (request.contentLength() > maximum || counted > maximum) {
            throw unusable();
        }
        readingFiles = true;
        Map<FillPath, RunFiles.Batch> open = new HashMap<>();
        for (Item item : items) {
            if (item.keepIndex() != null) {
                batch(open, item, run, group, files)
                        .reuse(
                                item.position(),
                                requireNonNull(item.keepIndex()),
                                run,
                                requireNonNull(item.previous()).id());
            }
        }
        for (int index = 0; index < uploads.size(); index++) {
            Item item = uploads.get(index);
            Part part = next();
            if (part == null || !part.name().equals("upload" + index) || !part.filename()) {
                throw unusable();
            }
            batch(open, item, run, group, files)
                    .upload(item.position(), index, requireNonNull(item.name()), part.content());
            if (active != null) {
                throw unusable();
            }
        }
        if (next() != null) {
            throw unusable();
        }
        Map<FillPath, RunFiles.VerifiedBatch> verified = new HashMap<>();
        for (Map.Entry<FillPath, RunFiles.Batch> entry : open.entrySet()) {
            verified.put(entry.getKey(), entry.getValue().finish());
        }
        return FileFillings.of(run, group, fields, sent, verified);
    }

    private static RunFiles.Batch batch(
            Map<FillPath, RunFiles.Batch> batches, Item item, RunId run, GroupId group, RunFiles files) {
        return batches.computeIfAbsent(item.path(), ignored -> files.begin(run, group, item.field()));
    }

    /* Each upload occupies a separate object with these two keys. Escapes, indices and names only add bytes;
     * nesting and lists add bytes too, so even the smallest spelling gives a safe count for every declaration. */
    static long largestSending(long totalFiles) {
        // Both start and answer envelopes fit this bound, with every code point escaped at its worst.
        int documentMaximum = Math.max(
                Filling.LARGEST_REQUEST_BYTES, Math.toIntExact(12 * (Declaration.MOST_SENT + Reasons.LONGEST) + 4096));
        int smallestMarker = "{\"upload\":0,\"name\":\"\"}".getBytes(UTF_8).length;
        return requestBound(documentMaximum, totalFiles, documentMaximum / smallestMarker);
    }

    private static long requestBound(int documentMaximum, long fileBytes, long uploads) {
        return Math.addExact(
                Math.addExact(documentMaximum, HEADER_ALLOWANCE),
                Math.addExact(fileBytes, Math.multiplyExact(uploads, HEADER_ALLOWANCE)));
    }

    private static void scan(
            List<FillField> fields,
            JsonValue sent,
            JsonValue.@Nullable JsonObject previous,
            List<FillPath.Step> parent,
            List<Item> items) {
        if (!(sent instanceof JsonObject object) || object.members().size() != fields.size()) {
            throw unusable();
        }
        Map<String, JsonValue> given = members(object);
        Map<String, JsonValue> old = previous == null ? Map.of() : members(previous);
        if (given.size() != fields.size()) {
            throw unusable();
        }
        for (FillField field : fields) {
            JsonValue value = given.get(field.name().value());
            if (value == null) {
                throw unusable();
            }
            List<FillPath.Step> path = new ArrayList<>(parent);
            path.add(new FillPath.Named(field.name()));
            JsonValue earlier = old.get(field.name().value());
            if (field.kind() == FieldKind.FILE) {
                file(field, value, earlier, new FillPath(path), items);
            } else if (field.kind() == FieldKind.FIELDS && !(value instanceof JsonNull)) {
                if (field.most() == null) {
                    scan(
                            field.fields(),
                            value,
                            earlier instanceof JsonObject oldObject ? oldObject : null,
                            path,
                            items);
                } else if (value instanceof JsonArray many && many.items().size() <= field.most()) {
                    JsonArray older = earlier instanceof JsonArray oldArray ? oldArray : new JsonArray(List.of());
                    for (int index = 0; index < many.items().size(); index++) {
                        List<FillPath.Step> element = new ArrayList<>(path);
                        element.add(new FillPath.Place(index));
                        JsonValue before =
                                index < older.items().size() ? older.items().get(index) : null;
                        scan(
                                field.fields(),
                                many.items().get(index),
                                before instanceof JsonObject oldObject ? oldObject : null,
                                element,
                                items);
                    }
                } else {
                    throw unusable();
                }
            }
        }
    }

    private static void file(
            FillField field, JsonValue value, @Nullable JsonValue earlier, FillPath path, List<Item> items) {
        if (field.most() == null) {
            if (!(value instanceof JsonNull)) {
                items.add(item(field, value, earlier, path, 0));
            }
            return;
        }
        if (!(value instanceof JsonArray many) || many.items().size() > field.most()) {
            throw unusable();
        }
        JsonArray old = earlier instanceof JsonArray array ? array : new JsonArray(List.of());
        for (int index = 0; index < many.items().size(); index++) {
            items.add(item(field, many.items().get(index), old, path, index));
        }
    }

    private static Item item(
            FillField field, JsonValue value, @Nullable JsonValue earlier, FillPath path, int position) {
        if (!(value instanceof JsonObject object)) {
            throw unusable();
        }
        Map<String, JsonValue> marker = members(object);
        if (marker.size() == 2
                && marker.get("upload") instanceof JsonNumber number
                && marker.get("name") instanceof JsonString name) {
            return new Item(path, field, position, index(number), null, name.value(), null);
        }
        if (marker.size() == 1 && marker.get("keep") instanceof JsonNumber number) {
            int index = index(number);
            JsonValue source = field.most() == null
                    ? index == 0 ? earlier : null
                    : earlier instanceof JsonArray array
                                    && index < array.items().size()
                            ? array.items().get(index)
                            : null;
            if (source == null) {
                throw unusable();
            }
            try {
                return new Item(path, field, position, null, index, null, StoredFile.fromKept(source));
            } catch (IllegalArgumentException invalidPriorFile) {
                throw unusable();
            }
        }
        throw unusable();
    }

    private static int index(JsonNumber number) {
        try {
            int index = number.value().intValueExact();
            if (index >= 0 && BigDecimal.valueOf(index).equals(number.value())) {
                return index;
            }
        } catch (ArithmeticException ignored) {
            // An unrepresentable position is not a file position.
        }
        throw unusable();
    }

    private static Map<String, JsonValue> members(JsonObject object) {
        Map<String, JsonValue> named = new HashMap<>();
        for (JsonMember member : object.members()) {
            if (named.putIfAbsent(member.name(), member.value()) != null) {
                throw unusable();
            }
        }
        return named;
    }

    private @Nullable Part next() throws IOException {
        if (active != null) {
            throw new IllegalStateException("Previous multipart part was not consumed");
        }
        if (!more) {
            if (!ended) {
                int tail = readRaw();
                if (tail == '\r') {
                    if (readRaw() != '\n' || readRaw() != -1) {
                        throw unusable();
                    }
                } else if (tail != -1) {
                    throw unusable();
                }
                ended = true;
                allowance.finished();
            }
            return null;
        }
        int headerBytes = 0;
        String disposition = null;
        String contentType = null;
        while (true) {
            String line = line();
            headerBytes += line.length() + 2;
            if (headerBytes > LARGEST_HEADERS) {
                throw unusable();
            }
            if (line.isEmpty()) {
                break;
            }
            if (line.regionMatches(true, 0, "Content-Disposition: ", 0, 21)) {
                if (disposition != null) {
                    throw unusable();
                }
                disposition = line.substring(21);
            } else if (line.regionMatches(true, 0, "Content-Type: ", 0, 14)) {
                if (contentType != null) {
                    throw unusable();
                }
                contentType = line.substring(14);
            } else {
                throw unusable();
            }
        }
        if (disposition == null || !disposition.startsWith("form-data; name=\"")) {
            throw unusable();
        }
        int end = disposition.indexOf('"', "form-data; name=\"".length());
        if (end < 0) {
            throw unusable();
        }
        String name = disposition.substring("form-data; name=\"".length(), end);
        String rest = disposition.substring(end + 1);
        boolean filename = rest.startsWith("; filename=\"");
        if (!rest.isEmpty() && (!filename || rest.indexOf('"', "; filename=\"".length()) != rest.length() - 1)) {
            throw unusable();
        }
        active = new PartInput();
        return new Part(name, contentType, filename, active);
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() <= LARGEST_LINE) {
            int character = readRaw();
            if (character < 0) {
                throw unusable();
            }
            if (previous == '\r' && character == '\n') {
                byte[] line = bytes.toByteArray();
                return new String(line, 0, line.length - 1, ISO_8859_1);
            }
            if (character == '\n' || previous == '\r') {
                throw unusable();
            }
            bytes.write(character);
            previous = character;
        }
        throw unusable();
    }

    private int readRaw() throws IOException {
        return input.read();
    }

    private static ApiErrorException unusable() {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, "A file filling was not sent in a usable form.");
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private record Part(
            String name,
            @Nullable String contentType,
            boolean filename,
            @DoNotLog InputStream content) {}

    private record Item(
            FillPath path,
            FillField field,
            int position,
            @Nullable Integer uploadIndex,
            @Nullable Integer keepIndex,
            @Nullable String name,
            @Nullable StoredFile previous) {}

    private final class CountedInput extends InputStream {

        private final InputStream source;

        private CountedInput(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            int read = source.read();
            if (read >= 0 && ++counted > maximum) {
                throw unusable();
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    private final class PartInput extends InputStream {

        private final byte[] look = new byte[delimiter.length + 1];
        private boolean finished;

        @Override
        public int read() throws IOException {
            if (finished) {
                return -1;
            }
            int first = readRaw();
            if (first < 0) {
                throw unusable();
            }
            if (first != '\r') {
                return payload(first);
            }
            int read = 0;
            boolean prefix = true;
            while (read < delimiter.length - 1) {
                int next = readRaw();
                if (next < 0) {
                    prefix = false;
                    break;
                }
                look[read++] = (byte) next;
                if (next != delimiter[read]) {
                    prefix = false;
                    break;
                }
            }
            if (prefix) {
                while (read < look.length) {
                    int next = readRaw();
                    if (next < 0) {
                        break;
                    }
                    look[read++] = (byte) next;
                }
            }
            boolean matched = prefix
                    && read == look.length
                    && (look[delimiter.length - 1] == '\r' && look[delimiter.length] == '\n'
                            || look[delimiter.length - 1] == '-' && look[delimiter.length] == '-');
            if (matched) {
                for (int index = 1; index < delimiter.length; index++) {
                    matched &= look[index - 1] == delimiter[index];
                }
                matched &= look[delimiter.length - 1] == '\r' && look[delimiter.length] == '\n'
                        || look[delimiter.length - 1] == '-' && look[delimiter.length] == '-';
            }
            if (matched) {
                more = look[delimiter.length - 1] == '\r';
                finished = true;
                active = null;
                return -1;
            }
            input.unread(look, 0, read);
            return payload(first);
        }

        private int payload(int value) {
            if (readingFiles && ++uploadedBytes > maximumUploadedBytes) {
                throw new ApiErrorException(
                        RefusalCode.BODY_UNUSABLE, "The files together are larger than this deployment takes at once.");
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int read = 0;
            while (read < length) {
                int next = read();
                if (next < 0) {
                    break;
                }
                bytes[offset + read++] = (byte) next;
            }
            return read == 0 ? -1 : read;
        }

        @Override
        public void close() {
            // The multipart reader owns the request stream.
        }
    }
}
