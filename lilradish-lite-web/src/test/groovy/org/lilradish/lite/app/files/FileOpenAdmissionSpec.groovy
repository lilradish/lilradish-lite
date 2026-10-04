package org.lilradish.lite.app.files

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.file.FileName
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.file.StoredFile
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.run.RunId
import org.mockito.Mockito
import spock.lang.Specification

class FileOpenAdmissionSpec extends Specification {

    static final RunId RUN = new RunId(UUID.randomUUID())
    static final GroupId GROUP = new GroupId(UUID.randomUUID())

    private static RunFiles serving(Closure<InputStream> content) {
        def files = Mockito.mock(RunFiles)
        Mockito.when(files.open(Mockito.any(RunId), Mockito.any(GroupId), Mockito.any(UUID)))
                .thenAnswer { call ->
                    def id = call.getArgument(2, UUID)
                    new RunFiles.OpenedFile(
                            new StoredFile(id, new FileName("Demo Sample.txt"),
                                    new MediaTypeName("text/plain"), 1), content.call())
                }
        files
    }

    def "at most four materialized files open and a fifth waits until a close"() {
        given:
        def admission = new FileOpenAdmission()
        def files = serving { new ByteArrayInputStream([65] as byte[]) }
        def held = (0..<FileOpenAdmission.MOST_OPEN).collect {
            admission.open(files, RUN, GROUP, UUID.randomUUID())
        }
        def worker = Executors.newSingleThreadExecutor()

        when:
        def waiting = worker.submit({ admission.open(files, RUN, GROUP, UUID.randomUUID()) } as Callable)
        Thread.sleep(100)

        then:
        !waiting.done
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == 4

        when:
        held[0].content().close()
        def fifth = waiting.get(2, TimeUnit.SECONDS)

        then:
        fifth.file().size() == 1
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == 5

        cleanup:
        held*.content()*.close()
        if (fifth != null) fifth.content().close()
        worker.shutdownNow()
    }

    def "a fifth open times out as service busy without materializing another file"() {
        given:
        def admission = new FileOpenAdmission()
        def files = serving { new ByteArrayInputStream([65] as byte[]) }
        def held = (0..<FileOpenAdmission.MOST_OPEN).collect {
            admission.open(files, RUN, GROUP, UUID.randomUUID())
        }
        def started = System.nanoTime()

        when:
        admission.open(files, RUN, GROUP, UUID.randomUUID())

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.SERVICE_BUSY
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) >= 30
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == 4

        cleanup:
        held*.content()*.close()
    }

    def "opening failure and closing failure each return their permit"() {
        given:
        def admission = new FileOpenAdmission()
        def opened = new AtomicInteger()
        def files = serving {
            if (opened.incrementAndGet() == 1) throw new IllegalStateException("storage unavailable")
            new InputStream() {
                @Override
                int read() { -1 }

                @Override
                void close() throws IOException { throw new IOException("close failed") }
            }
        }

        when:
        admission.open(files, RUN, GROUP, UUID.randomUUID())

        then:
        thrown(IllegalStateException)

        when:
        def held = (0..<FileOpenAdmission.MOST_OPEN).collect {
            admission.open(files, RUN, GROUP, UUID.randomUUID())
        }
        held[0].content().close()

        then:
        thrown(IOException)

        when:
        def fifth = admission.open(files, RUN, GROUP, UUID.randomUUID())

        then:
        fifth.file().size() == 1

        cleanup:
        held.each { file ->
            try { file.content().close() } catch (IOException ignored) { }
        }
        if (fifth != null) {
            try { fifth.content().close() } catch (IOException ignored) { }
        }
    }
}
