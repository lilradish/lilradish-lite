package com.lilradish.lite.app.files

import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.file.FileName
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.run.RunId
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.libprunus.core.error.ApiErrorException
import org.mockito.Mockito
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class FileOpenAdmissionSpec extends Specification {

    static final RunId RUN = new RunId(UUID.randomUUID())
    static final GroupId GROUP = new GroupId(UUID.randomUUID())

    private static RunFiles serving(Closure<InputStream> content) {
        def files = Mockito.mock(RunFiles)
        Mockito.when(files.open(Mockito.any(RunId), Mockito.any(GroupId), Mockito.any(UUID)))
                .thenAnswer { call ->
                    def id = call.getArgument(2, UUID)
                    new RunFiles.OpenedFile(
                            new StoredFile(id, new FileName("Demo Sample.txt"), 1), content.call())
                }
        files
    }

    private static void finishOpening(ExecutorService worker, Collection<Future<RunFiles.OpenedFile>> openings,
            Collection<RunFiles.OpenedFile> held) {
        worker.shutdownNow()
        try {
            assert worker.awaitTermination(5, TimeUnit.SECONDS)
            openings.findAll { it != null && it.done && !it.cancelled }.each { opened ->
                try {
                    opened.get().content().close()
                } catch (ExecutionException refused) {
                    assert refused.cause instanceof ApiErrorException
                }
            }
        } finally {
            held*.content()*.close()
        }
    }

    def "at most four materialized files open and a fifth waits until a close"() {
        given:
        def admission = new FileOpenAdmission()
        def files = serving { new ByteArrayInputStream([65] as byte[]) }
        def held = (0..<FileOpenAdmission.MOST_OPEN).collect {
            admission.open(files, RUN, GROUP, UUID.randomUUID())
        }
        def worker = Executors.newSingleThreadExecutor()
        Future<RunFiles.OpenedFile> waiting = null

        when:
        waiting = worker.submit({ admission.open(files, RUN, GROUP, UUID.randomUUID()) } as Callable)
        new PollingConditions(timeout: 5).eventually {
            assert admission.@turns.hasQueuedThreads()
        }

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
        finishOpening(worker, [waiting], held)
    }

    def "sixteen files may wait but a seventeenth is refused without reaching storage"() {
        given:
        def admission = new FileOpenAdmission()
        def files = serving { new ByteArrayInputStream([65] as byte[]) }
        def held = (0..<FileOpenAdmission.MOST_OPEN).collect {
            admission.open(files, RUN, GROUP, UUID.randomUUID())
        }
        def workers = Executors.newFixedThreadPool(16)
        def waiting = (0..<16).collect {
            workers.submit({ admission.open(files, RUN, GROUP, UUID.randomUUID()) } as Callable<RunFiles.OpenedFile>)
        }
        new PollingConditions(timeout: 5).eventually {
            assert admission.@turns.queueLength == 16
            assert admission.@waiting.get() == 16
        }
        def began = System.nanoTime()

        when:
        admission.open(files, RUN, GROUP, UUID.randomUUID())

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.SERVICE_BUSY
        System.nanoTime() - began < TimeUnit.SECONDS.toNanos(5)
        waiting.every { !it.done }
        admission.@waiting.get() == 16
        admission.@turns.queueLength == 16
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == 4

        when:
        workers.shutdownNow()

        then:
        workers.awaitTermination(5, TimeUnit.SECONDS)
        admission.@waiting.get() == 0
        !admission.@turns.hasQueuedThreads()
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == 4

        cleanup:
        finishOpening(workers, waiting, held)
    }

    def "interrupting a waiting file preserves the signal and returns its waiting place without opening storage"() {
        given:
        def admission = new FileOpenAdmission()
        def files = serving { new ByteArrayInputStream([65] as byte[]) }
        def held = (0..<FileOpenAdmission.MOST_OPEN).collect {
            admission.open(files, RUN, GROUP, UUID.randomUUID())
        }
        def outcome = new FutureTask<Map>({
            try {
                def opened = admission.open(files, RUN, GROUP, UUID.randomUUID())
                try {
                    [opened: true, interrupted: Thread.currentThread().isInterrupted()]
                } finally {
                    opened.content().close()
                }
            } catch (ApiErrorException refused) {
                [code: refused.errorCode(), interrupted: Thread.currentThread().isInterrupted()]
            }
        } as Callable<Map>)
        def worker = Thread.ofPlatform().daemon(true).start(outcome)
        new PollingConditions(timeout: 5).eventually {
            assert admission.@turns.queueLength == 1
            assert admission.@waiting.get() == 1
        }

        when:
        worker.interrupt()
        def answered = outcome.get(5, TimeUnit.SECONDS)
        worker.join(5000)

        then:
        !worker.alive
        answered.code == RefusalCode.SERVICE_BUSY
        answered.interrupted
        admission.@waiting.get() == 0
        !admission.@turns.hasQueuedThreads()
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == 4

        when:
        held[0].content().close()
        def next = admission.open(files, RUN, GROUP, UUID.randomUUID())

        then:
        next.file().size() == 1
        Mockito.mockingDetails(files).invocations.count { it.method.name == "open" } == 5

        cleanup:
        worker.interrupt()
        worker.join(5000)
        assert !worker.alive
        held*.content()*.close()
        if (next != null) next.content().close()
    }

    def "closing a file twice closes its source and returns its permit only once even when the source refuses"() {
        given:
        def admission = new FileOpenAdmission()
        def closes = new AtomicInteger()
        boolean refusesClose = fails
        def files = serving {
            new InputStream() {
                @Override
                int read() { -1 }

                @Override
                void close() throws IOException {
                    closes.incrementAndGet()
                    if (refusesClose) throw new IOException("close failed")
                }
            }
        }
        def opened = admission.open(files, RUN, GROUP, UUID.randomUUID())
        IOException failed = null

        when:
        try { opened.content().close() } catch (IOException refused) { failed = refused }

        then:
        (failed != null) == fails
        closes.get() == 1
        admission.@turns.availablePermits() == FileOpenAdmission.MOST_OPEN

        when:
        opened.content().close()

        then:
        noExceptionThrown()
        closes.get() == 1
        admission.@turns.availablePermits() == FileOpenAdmission.MOST_OPEN

        cleanup:
        try { opened.content().close() } catch (IOException ignored) { }

        where:
        fails << [false, true]
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

    def "a storage error returns its permit before four files open and a fifth waits"() {
        given:
        def admission = new FileOpenAdmission()
        def failure = new AssertionError("Storage failed.")
        def calls = new AtomicInteger()
        def files = serving {
            if (calls.incrementAndGet() == 1) throw failure
            new ByteArrayInputStream([65] as byte[])
        }
        def held = []
        def worker = Executors.newSingleThreadExecutor()
        Future<RunFiles.OpenedFile> waiting = null

        when:
        admission.open(files, RUN, GROUP, UUID.randomUUID())

        then:
        def refused = thrown(AssertionError)
        refused.is(failure)
        calls.get() == 1
        admission.@turns.availablePermits() == 4
        admission.@waiting.get() == 0
        !admission.@turns.hasQueuedThreads()

        when:
        held.addAll((0..<4).collect { admission.open(files, RUN, GROUP, UUID.randomUUID()) })
        waiting = worker.submit({ admission.open(files, RUN, GROUP, UUID.randomUUID()) } as Callable)
        new PollingConditions(timeout: 5).eventually {
            assert admission.@turns.queueLength == 1
            assert admission.@waiting.get() == 1
        }

        then:
        calls.get() == 5
        admission.@turns.availablePermits() == 0
        !waiting.done

        when:
        held[0].content().close()
        def fifth = waiting.get(5, TimeUnit.SECONDS)

        then:
        fifth.file().size() == 1
        calls.get() == 6
        admission.@waiting.get() == 0
        !admission.@turns.hasQueuedThreads()

        cleanup:
        finishOpening(worker, [waiting], held)
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
