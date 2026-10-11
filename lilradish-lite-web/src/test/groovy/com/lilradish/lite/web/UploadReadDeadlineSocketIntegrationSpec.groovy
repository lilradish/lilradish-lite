package com.lilradish.lite.web

import static java.nio.charset.StandardCharsets.ISO_8859_1

import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.testutil.ApplicationStore
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.catalina.connector.Connector
import org.apache.catalina.startup.Tomcat
import org.apache.coyote.http11.AbstractHttp11Protocol
import org.apache.coyote.http11.Http11NioProtocol
import org.apache.tomcat.util.net.NioEndpoint
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.tomcat.TomcatWebServer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.util.FileSystemUtils
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import spock.lang.Specification
import spock.lang.Timeout

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplicationStore)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class UploadReadDeadlineSocketIntegrationSpec extends Specification {

    @Autowired
    private WebServerApplicationContext application

    def "the running application's Tomcat connector applies the framing limits"() {
        given:
        def server = application.webServer as TomcatWebServer
        def protocol = server.tomcat.connector.protocolHandler as AbstractHttp11Protocol

        expect:
        application.getBeansOfType(TomcatBodyFraming).size() == 1
        server.port > 0
        protocol.maxExtensionSize == 0
        protocol.maxTrailerSize == 2
    }

    @Timeout(25)
    def "an incomplete chunk extension in a #bodyKind upload ends the read and releases the turn"() {
        given:
        def probe = new Probe(bodyKind)

        when:
        probe.start()
        probe.sendExtension()
        def readExited = probe.readExited.await(5, TimeUnit.SECONDS)
        def turnReleased = probe.filterExited.await(5, TimeUnit.SECONDS)
        probe.askSecond()
        def secondEntered = probe.secondEntered.await(5, TimeUnit.SECONDS)
        def firstStatus = probe.status(probe.first)
        def secondStatus = probe.status(probe.second)
        def firstEnded = probe.ended(probe.first)
        def secondEnded = probe.ended(probe.second)

        then:
        probe.firstDeadlineObserved
        probe.secondDeadlineObserved
        readExited && turnReleased && secondEntered
        probe.readFailure != null
        firstStatus == 400 && firstEnded
        secondStatus == 200 && secondEnded

        cleanup:
        probe.close()

        where:
        bodyKind << ["json", "multipart"]
    }

    @Timeout(25)
    def "an incomplete trailer in a #bodyKind upload releases the turn after framing is bounded"() {
        given:
        def probe = new Probe(bodyKind)

        when:
        probe.start()
        probe.sendTrailer()
        def readExited = probe.readExited.await(5, TimeUnit.SECONDS)
        def turnReleased = probe.filterExited.await(5, TimeUnit.SECONDS)
        probe.askSecond()
        def secondEntered = probe.secondEntered.await(5, TimeUnit.SECONDS)
        def firstStatus = probe.status(probe.first)
        def secondStatus = probe.status(probe.second)
        def firstEnded = probe.ended(probe.first)
        def secondEnded = probe.ended(probe.second)

        then:
        probe.firstDeadlineObserved
        probe.secondDeadlineObserved
        readExited && turnReleased && secondEntered
        (probe.readFailure != null) == readFailed
        firstStatus == expectedStatus && firstEnded
        secondStatus == 200 && secondEnded

        cleanup:
        probe.close()

        where:
        bodyKind    || readFailed || expectedStatus
        "json"      || true       || 400
        "multipart" || false      || 200
    }

    @Timeout(25)
    def "ordinary chunked #bodyKind with an empty trailer remains readable"() {
        given:
        def probe = new Probe(bodyKind)

        when:
        probe.start()
        probe.sendComplete()
        def readExited = probe.readExited.await(5, TimeUnit.SECONDS)
        def turnReleased = probe.filterExited.await(5, TimeUnit.SECONDS)
        probe.askSecond()
        def secondEntered = probe.secondEntered.await(5, TimeUnit.SECONDS)
        def firstStatus = probe.status(probe.first)
        def secondStatus = probe.status(probe.second)
        def firstEnded = probe.ended(probe.first)
        def secondEnded = probe.ended(probe.second)

        then:
        probe.firstDeadlineObserved
        probe.secondDeadlineObserved
        readExited && turnReleased && secondEntered
        probe.readFailure == null
        firstStatus == 200 && firstEnded
        secondStatus == 200 && secondEnded

        cleanup:
        probe.close()

        where:
        bodyKind << ["json", "multipart"]
    }

    private static final class Probe implements AutoCloseable {

        final String bodyKind
        final CountDownLatch readExited = new CountDownLatch(1)
        final CountDownLatch filterExited = new CountDownLatch(1)
        final CountDownLatch secondEntered = new CountDownLatch(1)
        final writer = Executors.newSingleThreadScheduledExecutor()
        final Tomcat tomcat = new Tomcat()
        final HandlerExceptionResolver refusals = { request, response, handler, failure ->
            response.status = 503
            new ModelAndView()
        } as HandlerExceptionResolver
        final CallerAdmission door = new CallerAdmission(
                { request -> Optional.of(new UserId("000001")) } as UserIdentification, refusals)
        final LargeBodyAdmission admission = new LargeBodyAdmission(refusals, System.&nanoTime)
        final String prefix = '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n'
        volatile boolean firstDeadlineObserved
        volatile boolean secondDeadlineObserved
        volatile Throwable readFailure
        Socket first
        Socket second
        Path base
        Path documents
        int port

        Probe(String bodyKind) {
            this.bodyKind = bodyKind
        }

        void start() {
            def protocol = new Http11NioProtocol(new NioEndpoint())
            protocol.connectionTimeout = 5000
            protocol.disableUploadTimeout = true
            protocol.maxThreads = 4
            def connector = new Connector(protocol)
            def factory = new TomcatServletWebServerFactory()
            new TomcatBodyFraming().customize(factory)
            factory.connectorCustomizers.each { it.customize(connector) }
            assert protocol.maxExtensionSize == 0
            assert protocol.maxTrailerSize == 2
            connector.port = 0
            connector.setProperty("address", "127.0.0.1")
            base = Files.createTempDirectory("upload-socket-tomcat-")
            documents = Files.createTempDirectory("upload-socket-documents-")
            tomcat.baseDir = base.toString()
            tomcat.connector = connector
            def context = tomcat.addContext("", documents.toString())
            def servlet = new HttpServlet() {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response) {
                    serve(request, response)
                }
            }
            def wrapper = Tomcat.addServlet(context, "probe", servlet)
            wrapper.asyncSupported = true
            context.addServletMappingDecoded("/", "probe")
            tomcat.start()
            port = connector.localPort
            assert port > 0
        }

        void serve(HttpServletRequest request, HttpServletResponse response) {
            boolean isSecond = request.requestURI.endsWith("/second")
            try {
                door.doFilter(request, response, { admitted, answer ->
                    admission.doFilter(admitted, answer, { held, output ->
                        if (isSecond) {
                            secondDeadlineObserved = LargeBodyAdmission.readDeadline(held) != null
                            secondEntered.countDown()
                            output.status = 200
                            return
                        }
                        firstDeadlineObserved = LargeBodyAdmission.readDeadline(held) != null
                        if (bodyKind == "multipart") {
                            def async = held.startAsync(held, output)
                            async.timeout = 0
                            def turn = LargeBodyAdmission.readDeadline(held)
                            assert LargeBodyAdmission.workerStarted(turn)
                            Thread.startVirtualThread {
                                try (def body = new RequestBodyInput(held.inputStream, { turn.passed() }, {})
                                     def multipart = MultipartFiles.open(MultipartFiles.sending(held), body, 1024,
                                             new MultipartFiles.Receiving() {
                                                 @Override void limit(long bytes) {}
                                                 @Override void finished() {}
                                             })) {
                                    multipart.document()
                                } catch (Exception failure) {
                                    readFailure = failure
                                    output.status = 400
                                } finally {
                                    LargeBodyAdmission.workerFinished(turn)
                                    readExited.countDown()
                                    async.complete()
                                }
                            }
                            return
                        }
                        try {
                            JsonBody.read(held, 1024 * 1024 + 1, "Demo Sample")
                        } catch (Exception failure) {
                            readFailure = failure
                            output.status = 400
                        } finally {
                            readExited.countDown()
                        }
                    } as FilterChain)
                } as FilterChain)
            } finally {
                if (!isSecond) {
                    filterExited.countDown()
                }
            }
        }

        void sendExtension() {
            first = connect()
            String initial = headers("first")
            if (bodyKind == "multipart") {
                initial += Integer.toHexString(prefix.length()) + "\r\n" + prefix + "\r\n"
            }
            first.outputStream.write((initial + "2;x=").getBytes(ISO_8859_1))
            first.outputStream.flush()
            drip()
        }

        void sendTrailer() {
            first = connect()
            String body = bodyKind == "json" ? "{}" : prefix + "{}\r\n--Aa--\r\n"
            String initial = headers("first") + Integer.toHexString(body.length()) + "\r\n" +
                    body + "\r\n0\r\nX-Demo: a"
            first.outputStream.write(initial.getBytes(ISO_8859_1))
            first.outputStream.flush()
            drip()
        }

        void sendComplete() {
            first = connect()
            String body = bodyKind == "json" ? "{}" : prefix + "{}\r\n--Aa--\r\n"
            String complete = headers("first") + Integer.toHexString(body.length()) + "\r\n" +
                    body + "\r\n0\r\n\r\n"
            first.outputStream.write(complete.getBytes(ISO_8859_1))
            first.outputStream.flush()
        }

        void drip() {
            writer.scheduleAtFixedRate({
                try {
                    first.outputStream.write((int) 'a'.charAt(0))
                    first.outputStream.flush()
                } catch (IOException ignored) {
                    writer.shutdown()
                }
            }, 0, 50, TimeUnit.MILLISECONDS)
        }

        void askSecond() {
            second = connect()
            second.outputStream.write((headers("second") + "2\r\n{}\r\n0\r\n\r\n").getBytes(ISO_8859_1))
            second.outputStream.flush()
        }

        String headers(String which) {
            String contentType = bodyKind == "json" ? "application/json" : "multipart/form-data; boundary=Aa"
            "POST /api/probe/${which} HTTP/1.1\r\nHost: localhost\r\nSec-Fetch-Site: same-origin\r\n" +
                    "Content-Type: ${contentType}\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
        }

        Socket connect() {
            def socket = new Socket()
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000)
            socket.soTimeout = 5000
            socket
        }

        int status(Socket socket) {
            def line = new ByteArrayOutputStream()
            int value
            while ((value = socket.inputStream.read()) >= 0 && value != (int) '\n'.charAt(0)) {
                line.write(value)
            }
            assert value >= 0
            Integer.parseInt(line.toString(ISO_8859_1).split(" ")[1])
        }

        boolean ended(Socket socket) {
            while (socket.inputStream.read() >= 0) {}
            true
        }

        @Override
        void close() {
            Throwable firstFailure = null
            boolean interrupted = false
            for (def cleanup : [
                    { first?.close() },
                    { second?.close() },
                    { writer.shutdownNow() },
                    { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Socket writer did not stop") },
                    { tomcat.stop() },
                    { tomcat.destroy() },
                    { if (base != null) FileSystemUtils.deleteRecursively(base) },
                    { if (documents != null) FileSystemUtils.deleteRecursively(documents) }
            ]) {
                try {
                    cleanup.call()
                } catch (Throwable failure) {
                    interrupted |= failure instanceof InterruptedException
                    if (firstFailure == null) {
                        firstFailure = failure
                    } else if (!firstFailure.is(failure)) {
                        firstFailure.addSuppressed(failure)
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            if (firstFailure != null) throw firstFailure
        }
    }
}
