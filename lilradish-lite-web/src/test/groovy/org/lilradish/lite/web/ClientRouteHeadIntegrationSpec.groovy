package org.lilradish.lite.web

import static java.nio.charset.StandardCharsets.ISO_8859_1

import org.lilradish.lite.testutil.ApplicationStore
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import spock.lang.Specification

/**
 * The document as it leaves the container this application really runs in: on HEAD the container is what
 * leaves the bytes off, and what charset a type is sent with is its to add. A mock dispatcher shows neither.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplicationStore)
class ClientRouteHeadIntegrationSpec extends Specification {

    @LocalServerPort
    private int port

    /** The whole answer as it came off the wire, status line first. */
    private String sending(String requestLine) {
        new Socket("localhost", port).withCloseable { socket ->
            socket.outputStream.write("${requestLine}\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(ISO_8859_1))
            socket.outputStream.flush()
            socket.inputStream.getText(ISO_8859_1.name())
        }
    }

    def "answers HEAD at the root with the document's headers and none of its bytes"() {
        when:
        def answer = sending("HEAD / HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "200"
        answer.readLines().any { it ==~ /(?i)etag: "[0-9a-f]{32}"/ }
        answer.readLines().any { it ==~ /(?i)content-type: text\/html.*/ }

        and: "nothing after the headers"
        answer.substring(answer.indexOf("\r\n\r\n") + 4).isEmpty()
    }

    def "sends a client route the document as UTF-8 HTML under the root's tag, with no timestamp of the archive"() {
        given:
        def rootTag = sending("HEAD / HTTP/1.1").readLines().find { it ==~ /(?i)etag: .*/ }

        when:
        def answer = sending("GET /groups HTTP/1.1")

        then:
        answer.readLines().first().split(" ")[1] == "200"
        answer.readLines().any { it.equalsIgnoreCase("content-type: text/html;charset=UTF-8") }
        answer.readLines().find { it ==~ /(?i)etag: .*/ } == rootTag

        and:
        !answer.readLines().any { it ==~ /(?i)last-modified: .*/ }
    }
}
