package org.lilradish.lite.web;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Enumeration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.ErrorResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A request body read off the request rather than bound, and the one way a handler here reads a body it
 * takes: bound, a body of any length is read whole and one of the wrong kind is refused quoting it.
 *
 * <p>What the body is declared as is judged before a byte of it is read, and anything but one plain
 * JSON declaration is refused as a type this does not take; nothing undeclared is examined for a type.
 *
 * <p>A charset other than UTF-8 or its ASCII subset is refused, though JSON defines none and a compliant
 * recipient ignores one: bytes sent in another encoding can be valid UTF-8 and read as something else.
 *
 * <p>A byte order mark and a member named twice are refused, each being a body two readers can read
 * differently. Static, so that nothing sent is ever an argument a woven method logs.
 */
public final class JsonBody {

    /* A number with a fraction is read as the decimal written, never rounded through a double. */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final int BYTE_ORDER_MARK = 0xFEFF;

    private static final int CHUNK_BYTES = 64 * 1024;

    private static final List<MediaType> TAKEN = List.of(MediaType.APPLICATION_JSON);

    private static final String UNENCODED = "identity";

    private static final String NOT_JSON = "This takes a body declared as application/json, in UTF-8.";

    private static final String ENCODED = "This takes a body sent without a content coding.";

    private static final String TOO_SLOW = "What was sent arrived too slowly to be read; send it again.";

    private JsonBody() {}

    /**
     * The document a body holds, the body read no further than {@code largest} bytes and unread if declared longer.
     * One that may run past a mebibyte is read only while {@link LargeBodyAdmission} holds the turn for it, and only
     * until the turn's read deadline.
     */
    public static JsonNode read(HttpServletRequest request, int largest, String refusedAs) throws IOException {
        long declaredLength = request.getContentLengthLong();
        LargeBodyAdmission.ReadDeadline deadline = LargeBodyAdmission.readDeadline(request);
        if (deadline == null
                && largest > LargeBodyAdmission.ONE_MEBIBYTE
                && (declaredLength < 0 || declaredLength > LargeBodyAdmission.ONE_MEBIBYTE)) {
            throw new IllegalStateException("A body that may run past a mebibyte is read without the turn held for it");
        }
        if (!declaredOnce(request) || !declaredJson(request.getContentType())) {
            throw notJson();
        }
        if (!unencoded(request)) {
            throw encoded();
        }
        if (declaredLength > largest) {
            throw unusable(refusedAs, null);
        }
        byte[] sent;
        try (InputStream body = request.getInputStream()) {
            sent = deadline == null
                    ? body.readNBytes(largest + 1)
                    : readBefore(body, largest + 1, declaredLength, deadline);
        }
        if (sent.length > largest) {
            throw unusable(refusedAs, null);
        }
        String text;
        try {
            text = UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sent))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw unusable(refusedAs, malformed);
        }
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            throw unusable(refusedAs, null);
        }
        JsonNode document;
        try {
            document = STRICT.readTree(text);
        } catch (JacksonException unreadable) {
            throw unusable(refusedAs, unreadable);
        }
        if (document.isMissingNode()) {
            throw unusable(refusedAs, null);
        }
        return document;
    }

    /** A document this system wrote itself, read as a body's document is; one it cannot read is thrown as unread. */
    public static JsonNode written(String text) {
        return STRICT.readTree(text);
    }

    private static byte[] readBefore(
            InputStream body, int most, long declaredLength, LargeBodyAdmission.ReadDeadline deadline)
            throws IOException {
        ByteArrayOutputStream read =
                new ByteArrayOutputStream(declaredLength < 0 ? CHUNK_BYTES : (int) Math.min(declaredLength, most));
        byte[] chunk = new byte[CHUNK_BYTES];
        // A single blocking read is still bounded only by the container's connectionTimeout, so the worst
        // case is the deadline plus one idle timeout.
        int count;
        while (read.size() < most && (count = body.read(chunk, 0, Math.min(CHUNK_BYTES, most - read.size()))) >= 0) {
            if (deadline.passed()) {
                throw new ApiErrorException(RefusalCode.BODY_SENT_TOO_SLOWLY, TOO_SLOW);
            }
            read.write(chunk, 0, count);
        }
        return read.toByteArray();
    }

    /* Declared twice, recipients disagree on which declaration counts, so neither is taken. */
    private static boolean declaredOnce(HttpServletRequest request) {
        Enumeration<String> declared = request.getHeaders(HttpHeaders.CONTENT_TYPE);
        if (declared.hasMoreElements()) {
            declared.nextElement();
        }
        return !declared.hasMoreElements();
    }

    private static boolean declaredJson(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        MediaType declared;
        try {
            declared = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException unreadable) {
            return false;
        }
        Charset charset = declared.getCharset();
        return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(declared)
                && (charset == null || UTF_8.equals(charset) || US_ASCII.equals(charset));
    }

    private static boolean unencoded(HttpServletRequest request) {
        Enumeration<String> codings = request.getHeaders(HttpHeaders.CONTENT_ENCODING);
        while (codings.hasMoreElements()) {
            if (!UNENCODED.equalsIgnoreCase(codings.nextElement().strip())) {
                return false;
            }
        }
        return true;
    }

    private static ErrorResponseException notJson() {
        ErrorResponseException refused = new ErrorResponseException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        refused.setDetail(NOT_JSON);
        refused.getHeaders().setAccept(TAKEN);
        return refused;
    }

    private static ErrorResponseException encoded() {
        ErrorResponseException refused = new ErrorResponseException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        refused.setDetail(ENCODED);
        refused.getHeaders().set(HttpHeaders.ACCEPT_ENCODING, UNENCODED);
        return refused;
    }

    private static ApiErrorException unusable(String refusedAs, @Nullable Throwable cause) {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, refusedAs, cause);
    }
}
