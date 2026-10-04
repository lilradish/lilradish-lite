package org.lilradish.lite.app.inference;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.inference.KeptAnswer;

/** Passes a response through in small reads while decoding only a bounded evidence prefix. */
final class BoundedResponseCapture extends InputStream {

    private static final int READ_SIZE = 8192;

    private final InputStream source;

    private final CharsetDecoder decoder = UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

    @DoNotLog
    private final StringBuilder prefix = new StringBuilder();

    private final byte[] carry = new byte[4];

    private final byte[] one = new byte[1];

    private int carryLength;

    private int codePoints;

    private char pendingHigh;

    private boolean malformedUtf8;

    private boolean cutAtBound;

    private boolean ended;

    BoundedResponseCapture(InputStream source) {
        this.source = source;
    }

    @Override
    public int read() throws IOException {
        return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
    }

    @Override
    public int read(byte[] into, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, into.length);
        if (length == 0) {
            return 0;
        }
        int read = source.read(into, offset, Math.min(length, READ_SIZE));
        if (read > 0) {
            decode(into, offset, read, false);
        } else if (read < 0 && !ended) {
            ended = true;
            decode(carry, 0, 0, true);
        }
        return read;
    }

    /** After a parser fails early, read only until EOF or evidence is known to be cut. */
    void finishBounded() throws IOException {
        byte[] buffer = new byte[READ_SIZE];
        while (!cutAtBound && read(buffer, 0, buffer.length) >= 0) {
            // Only the decoded code-point bound or EOF permits a failed parse to stop draining.
        }
    }

    KeptAnswer evidence() {
        return KeptAnswer.captured(prefix.toString(), malformedUtf8, cutAtBound);
    }

    boolean malformedUtf8() {
        return malformedUtf8;
    }

    private void decode(byte[] bytes, int offset, int length, boolean last) {
        byte[] joined = new byte[carryLength + length];
        System.arraycopy(carry, 0, joined, 0, carryLength);
        System.arraycopy(bytes, offset, joined, carryLength, length);
        ByteBuffer input = ByteBuffer.wrap(joined);
        CharBuffer output = CharBuffer.allocate(READ_SIZE);
        while (true) {
            CoderResult result = decoder.decode(input, output, last);
            output.flip();
            if (!cutAtBound) {
                append(output);
            }
            output.clear();
            if (result.isMalformed() || result.isUnmappable()) {
                malformedUtf8 = true;
                input.position(input.position() + result.length());
                if (!cutAtBound) {
                    appendCodePoint('\uFFFD', (char) 0);
                }
            } else if (result.isUnderflow()) {
                break;
            }
        }
        carryLength = input.remaining();
        if (carryLength > 0) {
            input.get(carry, 0, carryLength);
        }
        if (last) {
            CoderResult result;
            do {
                result = decoder.flush(output);
                output.flip();
                if (!cutAtBound) {
                    append(output);
                }
                output.clear();
            } while (result.isOverflow());
            if (pendingHigh != 0) {
                malformedUtf8 = true;
                if (!cutAtBound) {
                    appendCodePoint('\uFFFD', (char) 0);
                }
                pendingHigh = 0;
            }
        }
    }

    private void append(CharBuffer decoded) {
        while (decoded.hasRemaining() && !cutAtBound) {
            char next = decoded.get();
            if (pendingHigh != 0) {
                if (Character.isLowSurrogate(next)) {
                    appendCodePoint(pendingHigh, next);
                    pendingHigh = 0;
                    continue;
                }
                malformedUtf8 = true;
                appendCodePoint('\uFFFD', (char) 0);
                pendingHigh = 0;
            }
            if (Character.isHighSurrogate(next)) {
                pendingHigh = next;
            } else if (Character.isLowSurrogate(next)) {
                malformedUtf8 = true;
                appendCodePoint('\uFFFD', (char) 0);
            } else {
                appendCodePoint(next, (char) 0);
            }
        }
    }

    private void appendCodePoint(char first, char second) {
        if (codePoints == KeptAnswer.MOST_KEPT) {
            cutAtBound = true;
            return;
        }
        prefix.append(first);
        if (second != 0) {
            prefix.append(second);
        }
        codePoints++;
    }

    // The JSON parser closes its input after parsing; the surrounding HTTP response owns the source.
    @Override
    public void close() {}

    @Override
    public String toString() {
        return "BoundedResponseCapture";
    }
}
