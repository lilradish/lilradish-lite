package com.lilradish.lite.web

import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.app.files.TransferPace
import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponseWrapper
import java.util.concurrent.atomic.AtomicLong
import org.apache.catalina.Valve
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.coyote.ActionCode
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.util.unit.DataSize
import spock.lang.Specification

class FileTransfersSpec extends Specification {

    private final AtomicLong now = new AtomicLong()
    private final FileTransfers transfers = new FileTransfers(
            new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofBytes(200)), { now.get() }, new ThreadPoolTaskExecutor())

    def cleanup() {
        transfers.close()
    }

    def 'an initial source failure retains its cause and invalidates the connection even if closing also fails'() {
        given:
        def reading = new IOException('The stored bytes could not be read.')
        def closing = new IOException('The source could not be closed.')
        int closes = 0
        def source = new InputStream() {
            @Override int read() { throw reading }
            @Override void close() { closes++; throw closing }
        }
        def request = new MockHttpServletRequest('GET', '/api/groups/a/runs/b/files/c')
        request.asyncSupported = true
        def connection = new Ending(request)
        FileTransfers.connection(request, connection)

        when:
        transfers.download(request, new MockHttpServletResponse(), source)

        then:
        def failed = thrown(IOException)
        failed.is(reading)
        failed.suppressed.toList() == [closing]
        closes == 1
        connection.closes == 1
        connection.aborts == 0
        !request.asyncStarted
    }

    def 'source EOF leaves the file admitted until the last queued write and flush finish'() {
        given:
        def request = new MockHttpServletRequest('GET', '/api/groups/a/runs/b/files/c')
        request.asyncSupported = true
        def output = new QueuedOutput()
        def response = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override ServletOutputStream getOutputStream() { output }
        }
        def source = new ClosingSource()
        def connection = new Ending(request)
        FileTransfers.connection(request, connection)
        transfers.download(request, response, source)

        when:
        output.listener.onWritePossible()

        then:
        source.eof
        source.closes == 0
        request.asyncStarted
        connection.closes == 0
        output.sent.toByteArray() == [65, 66, 67] as byte[]

        when:
        output.ready = true
        output.flushStalls = true
        output.listener.onWritePossible()

        then:
        output.flushes == 1
        request.asyncStarted
        source.closes == 0
        connection.closes == 0

        when:
        output.ready = true
        output.flushStalls = false
        output.listener.onWritePossible()

        then:
        !request.asyncStarted
        source.closes == 1
        connection.closes == 1
        connection.aborts == 0

        when:
        now.set(TransferPace.allowedNanos(3) + 1)
        output.listener.onTimeout(new AsyncEvent(request.asyncContext))
        output.listener.onWritePossible()

        then:
        source.closes == 1
        connection.closes == 1
        connection.aborts == 0
        output.sent.size() == 3
    }

    def 'a deadline after source EOF cuts the queued tail off and closes its source once'() {
        given:
        def request = new MockHttpServletRequest('GET', '/api/groups/a/runs/b/files/c')
        request.asyncSupported = true
        def output = new QueuedOutput()
        def response = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override ServletOutputStream getOutputStream() { output }
        }
        def source = new ClosingSource()
        def connection = new Ending(request)
        FileTransfers.connection(request, connection)
        transfers.download(request, response, source)
        def context = request.asyncContext
        output.listener.onWritePossible()
        assert source.eof && request.asyncStarted && source.closes == 0

        when:
        now.set(TransferPace.allowedNanos(3) + 1)
        output.listener.onTimeout(new AsyncEvent(context))

        then:
        connection.aborts == 1
        connection.closes == 1
        source.closes == 1
        !request.asyncStarted

        when:
        output.ready = true
        output.listener.onWritePossible()
        output.listener.onComplete(new AsyncEvent(context))

        then:
        source.closes == 1
        connection.closes == 1
        output.sent.size() == 3
        output.flushes == 0
    }

    def 'an old expiry waiting behind progress recomputes the new allowance before cutting off'() {
        given:
        def request = new MockHttpServletRequest('GET', '/api/groups/a/runs/b/files/c')
        request.asyncSupported = true
        def output = new QueuedOutput()
        def response = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override ServletOutputStream getOutputStream() { output }
        }
        def source = new ClosingSource()
        def connection = new Ending(request)
        FileTransfers.connection(request, connection)
        transfers.download(request, response, source)
        def context = request.asyncContext
        output.listener.onWritePossible()

        when:
        now.set(TransferPace.allowedNanos(0) + 1)
        output.listener.onTimeout(new AsyncEvent(context))

        then:
        connection.aborts == 0
        source.closes == 0
        request.asyncStarted

        when:
        now.set(TransferPace.allowedNanos(3) + 1)
        output.listener.onTimeout(new AsyncEvent(context))

        then:
        connection.aborts == 1
        source.closes == 1
        !request.asyncStarted
    }

    def 'an ended connection capability cannot close the same pooled request reused by another transfer'() {
        given:
        def factory = Mock(TomcatServletWebServerFactory)
        List<Valve> installed = []
        def request = Mock(Request)
        def response = Mock(Response)
        def coyote = Mock(org.apache.coyote.Request)
        def context = Mock(AsyncContext)
        def onward = Mock(Valve)
        List<FileTransfers.ConnectionEnd> captured = []
        List<AsyncListener> listeners = []
        request.method >> 'GET'
        request.decodedRequestURI >> '/api/groups/a/runs/b/files/c'
        request.coyoteRequest >> coyote
        request.asyncStarted >> true
        request.asyncContext >> context

        when:
        new FileTransfersConfiguration().fileTransferConnections().customize(factory)

        then:
        1 * factory.addContextValves(_ as Valve[]) >> { Valve[] valves -> installed.addAll(valves) }

        when:
        installed.first().next = onward
        installed.first().invoke(request, response)
        listeners.first().onComplete(new AsyncEvent(context, request, response))
        installed.first().invoke(request, response)

        then:
        2 * onward.invoke(request, response)
        2 * request.setAttribute(_, _ as FileTransfers.ConnectionEnd) >> { String name, FileTransfers.ConnectionEnd end ->
            captured.add(end)
        }
        2 * context.addListener(_ as AsyncListener) >> { AsyncListener listener -> listeners.add(listener) }

        when:
        captured.first().abort(new IOException('An earlier deadline expired.'))

        then:
        0 * coyote.action(_, _)

        when:
        captured.last().abort(new IOException('This transfer expired.'))

        then:
        1 * coyote.action(ActionCode.CLOSE_NOW, _ as IOException)

        cleanup:
        listeners*.onComplete(new AsyncEvent(context, request, response))
    }

    def 'an ordinary asynchronous error closes storage while leaving its error answer to the container'() {
        given:
        def request = new MockHttpServletRequest('GET', '/api/groups/a/runs/b/files/c')
        request.asyncSupported = true
        def output = new QueuedOutput()
        def response = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override ServletOutputStream getOutputStream() { output }
        }
        def source = new ClosingSource()
        def connection = new Ending(request)
        FileTransfers.connection(request, connection)
        transfers.download(request, response, source)
        def context = request.asyncContext

        when:
        (output.listener as AsyncListener).onError(new AsyncEvent(context, request, response,
                new IOException('The connection failed.')))

        then:
        source.closes == 1
        connection.closes == 1
        connection.aborts == 0
        request.asyncStarted

        when:
        context.complete()
        output.listener.onWritePossible()

        then:
        source.closes == 1
        connection.closes == 1
        output.sent.size() == 0
        !request.asyncStarted
    }

    private static final class Ending implements FileTransfers.ConnectionEnd {
        final MockHttpServletRequest request
        int aborts
        int closes

        Ending(MockHttpServletRequest request) { this.request = request }

        @Override void abort(IOException failure) {
            aborts++
            request.asyncContext.complete()
        }

        @Override void close() { closes++ }
    }

    private static final class ClosingSource extends ByteArrayInputStream {
        boolean eof
        int closes

        ClosingSource() { super([65, 66, 67] as byte[]) }

        @Override int read(byte[] bytes, int offset, int length) {
            int count = super.read(bytes, offset, length)
            if (count == -1) eof = true
            count
        }

        @Override void close() { closes++ }
    }

    private static final class QueuedOutput extends ServletOutputStream {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream()
        WriteListener listener
        boolean ready = true
        boolean flushStalls
        int flushes

        @Override boolean isReady() { ready }
        @Override void setWriteListener(WriteListener listener) { this.listener = listener }
        @Override void write(int value) { throw new UnsupportedOperationException() }
        @Override void write(byte[] bytes, int offset, int length) {
            assert ready
            sent.write(bytes, offset, length)
            ready = false
        }

        @Override void flush() {
            flushes++
            if (flushStalls) ready = false
        }
    }
}
