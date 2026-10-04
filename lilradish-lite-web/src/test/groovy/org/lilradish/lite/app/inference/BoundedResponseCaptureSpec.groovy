package org.lilradish.lite.app.inference

import org.lilradish.lite.domain.inference.KeptAnswer
import spock.lang.Specification
import spock.lang.Timeout

class BoundedResponseCaptureSpec extends Specification {

    @Timeout(120)
    def "a four-byte character ends the evidence at exactly the code-point bound"() {
        given:
        def source = new RepeatedCodePoint(KeptAnswer.MOST_KEPT + extra)
        def capture = new BoundedResponseCapture(source)

        when:
        capture.finishBounded()
        def kept = capture.evidence()
        def text = kept.text()

        then:
        text.codePointCount(0, text.length()) == KeptAnswer.MOST_KEPT
        text.codePointAt(text.length() - 2) == 0x1F600
        !text.endsWith('\uFFFD')
        kept.truncated() == (extra == 1)
        kept.altered() == (extra == 1)
        source.served <= (long) (KeptAnswer.MOST_KEPT + extra) * 4

        where:
        extra << [0, 1]
    }

    def "a truncated UTF-8 sequence at EOF is replacement evidence"() {
        given:
        def source = new ByteArrayInputStream([(byte) 0xF0, (byte) 0x9F, (byte) 0x98] as byte[])
        def capture = new BoundedResponseCapture(source)

        when:
        capture.finishBounded()
        def kept = capture.evidence()

        then:
        kept.text() == '\uFFFD'
        kept.altered()
        !kept.truncated()
    }

    def "the capture never renders its private response text"() {
        given:
        def capture = new BoundedResponseCapture(new ByteArrayInputStream('Demo Sample private'.bytes))

        when:
        capture.finishBounded()

        then:
        capture.evidence().text() == 'Demo Sample private'
        capture.toString() == 'BoundedResponseCapture'
    }

    private static final class RepeatedCodePoint extends InputStream {
        private static final byte[] UNIT = [(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80] as byte[]
        private static final byte[] BLOCK = new byte[8192]

        static {
            2048.times { at -> System.arraycopy(UNIT, 0, BLOCK, at * 4, 4) }
        }

        private final long length
        long served

        RepeatedCodePoint(long copies) {
            length = Math.multiplyExact(copies, 4L)
        }

        @Override
        int read() {
            if (served == length) {
                return -1
            }
            UNIT[(int) (served++ % 4)] & 0xff
        }

        @Override
        int read(byte[] into, int offset, int asked) {
            Objects.checkFromIndexSize(offset, asked, into.length)
            if (asked == 0) {
                return 0
            }
            if (served == length) {
                return -1
            }
            int count = (int) Math.min((long) asked, length - served)
            int left = count
            while (left > 0) {
                int start = (int) (served % BLOCK.length)
                int part = Math.min(left, BLOCK.length - start)
                System.arraycopy(BLOCK, start, into, offset + count - left, part)
                served += part
                left -= part
            }
            count
        }
    }
}
