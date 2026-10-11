package com.lilradish.lite.web

import com.lilradish.lite.domain.failure.RefusalCode
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import org.libprunus.core.error.ApiErrorException
import spock.lang.Specification

class RequestBodyInputSpec extends Specification {

    def 'a worker reads a bounded chunk once ready and keeps every byte before the end'() {
        given:
        byte[] sent = new byte[100_000]
        Arrays.fill(sent, (byte) 65)
        def source = new ReadyBody(sent)
        def input = new RequestBodyInput(source, { false } as BooleanSupplier, {})

        when:
        byte[] received = input.readAllBytes()

        then:
        received == sent
        source.reads.get() == 2
        source.largestBuffer == 64 * 1024
        source.closed == false

        when:
        input.close()

        then:
        source.closed == false
    }

    def 'a cutoff wakes a worker waiting for readiness and does not read or drain the socket'() {
        given:
        def source = new ReadyBody([65] as byte[])
        source.ready = false
        def input = new RequestBodyInput(source, { false } as BooleanSupplier, {})
        def worker = CompletableFuture.supplyAsync {
            try {
                input.read()
                return null
            } catch (ApiErrorException caught) {
                return caught
            }
        }
        assert source.asked.await(2, TimeUnit.SECONDS)

        when:
        input.expired()
        def failure = worker.get(2, TimeUnit.SECONDS)

        then:
        failure.errorCode() == RefusalCode.BODY_SENT_TOO_SLOWLY
        failure.message == 'What was sent arrived too slowly to be read; send it again.'
        source.reads.get() == 0
        source.closed == false

        cleanup:
        input?.close()
    }

    def 'a readiness notification wakes its waiting worker without reading in the callback'() {
        given:
        def source = new ReadyBody([65] as byte[])
        source.ready = false
        def input = new RequestBodyInput(source, { false } as BooleanSupplier, {})
        def worker = CompletableFuture.supplyAsync { input.read() }
        assert source.asked.await(2, TimeUnit.SECONDS)

        when:
        source.ready = true
        source.listener.onDataAvailable()

        then:
        worker.get(2, TimeUnit.SECONDS) == 65
        input.read() == -1
        source.reads.get() == 1
        source.closed == false

        cleanup:
        input?.close()
    }

    def 'a transport failure wakes its reader and preserves the cause without closing the source'() {
        given:
        def source = new ReadyBody([65] as byte[])
        source.ready = false
        def input = new RequestBodyInput(source, { false } as BooleanSupplier, {})
        def worker = CompletableFuture.supplyAsync {
            try {
                input.read()
                return null
            } catch (IOException caught) {
                return caught
            }
        }
        assert source.asked.await(2, TimeUnit.SECONDS)
        def gone = new IOException('The sender disconnected.')

        when:
        source.listener.onError(gone)
        def failure = worker.get(2, TimeUnit.SECONDS)

        then:
        failure.cause.is(gone)
        source.reads.get() == 0
        source.closed == false

        cleanup:
        input?.close()
    }

    def 'bytes already arrived within the deadline stay readable after it and finish the receipt once'() {
        given:
        def source = new ReadyBody([65, 66, 67] as byte[])
        boolean past = false
        def receipts = new AtomicInteger()
        def input = new RequestBodyInput(source, { past } as BooleanSupplier, { receipts.incrementAndGet() })
        assert input.read() == 65
        assert receipts.get() == 1

        when:
        past = true
        input.expired()
        source.listener.onAllDataRead()

        then:
        input.readAllBytes() == [66, 67] as byte[]
        receipts.get() == 1
        source.reads.get() == 1
        source.closed == false

        cleanup:
        input?.close()
    }

    def 'the last bytes arriving after the deadline do not turn a failed receipt into success'() {
        given:
        def source = new ReadyBody([65] as byte[])
        def receipts = new AtomicInteger()
        def input = new RequestBodyInput(source, { true } as BooleanSupplier, { receipts.incrementAndGet() })

        when:
        source.listener.onAllDataRead()
        input.read()

        then:
        def failure = thrown(ApiErrorException)
        failure.errorCode() == RefusalCode.BODY_SENT_TOO_SLOWLY
        receipts.get() == 0
        source.reads.get() == 0
        source.closed == false

        cleanup:
        input?.close()
    }

    private static final class ReadyBody extends ServletInputStream {
        final byte[] bytes
        final AtomicInteger reads = new AtomicInteger()
        final CountDownLatch asked = new CountDownLatch(1)
        volatile boolean ready = true
        ReadListener listener
        int position
        int largestBuffer
        boolean closed

        ReadyBody(byte[] bytes) {
            this.bytes = bytes
        }

        @Override boolean isFinished() { position == bytes.length }

        @Override boolean isReady() { asked.countDown(); ready }

        @Override void setReadListener(ReadListener listener) { this.listener = listener }

        @Override int read() { throw new UnsupportedOperationException() }

        @Override int read(byte[] into, int offset, int length) {
            assert ready
            reads.incrementAndGet()
            largestBuffer = Math.max(largestBuffer, into.length)
            int count = Math.min(length, bytes.length - position)
            System.arraycopy(bytes, position, into, offset, count)
            position += count
            count
        }

        @Override void close() { closed = true }
    }
}
