package com.lilradish.lite.web

import com.lilradish.lite.app.files.DatabaseFileContentStore
import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.app.files.RunFiles
import com.lilradish.lite.app.files.TransferPace
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.library.ConstantJson
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.filling.FillField
import com.lilradish.lite.domain.filling.FilledFields
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.ReadyServletStreams
import com.lilradish.lite.testutil.library.LibraryStore
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import org.libprunus.core.error.ApiErrorException
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.util.unit.DataSize
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.servlet.HandlerExceptionResolver
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/** Raw multipart boundaries and a real RunFiles transaction, without servlet multipart parsing. */
class MultipartFilesSpec extends Specification {

    def 'the largest deployment file budget keeps a positive deadline instead of overflowing'() {
        when:
        long bound = MultipartFiles.largestSending(FieldShape.File.LARGEST_BYTES)

        then:
        bound > FieldShape.File.LARGEST_BYTES
        TransferPace.allowedNanos(bound) == Long.MAX_VALUE
        !TransferPace.tooSlow(0, bound, Long.MAX_VALUE)
    }

    @Shared @AutoCleanup TestPostgres server = TestPostgres.worker()
    @Shared int databasesMade = 0
    LibraryStore store

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        assert com.lilradish.lite.domain.observability.Correlation.CURRENT.get() == null
        store = LibraryStore.forCase(server, "multipart_files_" + (++databasesMade))
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
    }

    def cleanup() {
        try {
            assert com.lilradish.lite.domain.observability.Correlation.CURRENT.get() == null
            assert org.slf4j.MDC.get('correlationId') == null
        } finally {
            com.lilradish.lite.domain.observability.Correlation.clear()
            org.slf4j.MDC.clear()
        }
    }

    private static FillField photo(long maximum = 100L) {
        new FillField(new FieldName("photo"), null, null, FieldKind.FILE, null, null, true,
                null, [], [new FileExtension(".png")], maximum)
    }

    private static FillField copy(long maximum = 8L) {
        new FillField(new FieldName("copy"), null, null, FieldKind.FILE, null, null, true,
                null, [], [new FileExtension(".png")], maximum)
    }

    private static FillField third(long maximum = 8L) {
        new FillField(new FieldName("third"), null, null, FieldKind.FILE, null, null, true,
                null, [], [new FileExtension(".png")], maximum)
    }

    private static FillField photos() {
        new FillField(new FieldName('photo'), null, null, FieldKind.FILE, null, 4, true,
                null, [], [new FileExtension('.png')], 8L)
    }

    private FilledFields uploadedPhotos(RunId run, GroupId group, RunFiles files, FileDeployment deployment) {
        String wire = document('{"values":{"photo":[' +
                '{"upload":0,"name":"first.png"},{"upload":1,"name":"second.png"},' +
                '{"upload":2,"name":"third.png"}]}}') +
                uploadPart(0, 'a') + uploadPart(1, 'bb') + uploadPart(2, 'ccc') + '--Aa--\r\n'
        def result
        ReadyMultipartFiles.open(request(wire), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            result = store.transactions().execute {
                multipart.fill(run, group, [photos()], values, null, files, deployment)
            }
        }
        result
    }

    private static String uploadPart(int index, String content) {
        '--Aa\r\nContent-Disposition: form-data; name="upload' + index + '"; filename="ignored"\r\n' +
                'Content-Type: image/png\r\n\r\n' + content + '\r\n'
    }

    private static String twoFiles(String first, String second) {
        document('{"values":{"photo":{"upload":0,"name":"first.png"},' +
                '"copy":{"upload":1,"name":"second.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored"\r\n' +
                'Content-Type: image/png\r\n\r\n' + first + '\r\n' +
                '--Aa\r\nContent-Disposition: form-data; name="upload1"; filename="ignored"\r\n' +
                'Content-Type: image/png\r\n\r\n' + second + '\r\n--Aa--\r\n'
    }

    private static MockHttpServletRequest request(String wire, String boundary = "Aa") {
        def sent = new MockHttpServletRequest("POST", "/")
        sent.setContentType("multipart/form-data; boundary=${boundary}")
        sent.setContent(wire.getBytes(StandardCharsets.ISO_8859_1))
        sent
    }

    private static MockHttpServletRequest lengthAs(String wire, long length) {
        def sent = new MockHttpServletRequest("POST", "/") {
            @Override
            long getContentLengthLong() { length }

            @Override
            int getContentLength() { length < 0 ? -1 : Math.toIntExact(length) }
        }
        sent.setContentType('multipart/form-data; boundary=Aa')
        sent.setContent(wire.getBytes(StandardCharsets.ISO_8859_1))
        sent
    }

    private static MockHttpServletRequest closingRequest(String wire, AtomicBoolean closed) {
        def bytes = new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1))
        def sent = new MockHttpServletRequest('POST', '/') {
            @Override
            ServletInputStream getInputStream() {
                new ServletInputStream() {
                    @Override
                    int read() { bytes.read() }

                    @Override
                    boolean isFinished() { bytes.available() == 0 }

                    @Override
                    boolean isReady() { true }

                    @Override
                    void setReadListener(ReadListener listener) { throw new UnsupportedOperationException() }

                    @Override
                    void close() {
                        closed.set(true)
                        bytes.close()
                    }
                }
            }
        }
        sent.setContentType('multipart/form-data; boundary=Aa')
        sent
    }

    private static MockHttpServletRequest countedRequest(String wire, AtomicLong fetched, long declared = -1L) {
        byte[] bytes = wire.getBytes(StandardCharsets.ISO_8859_1)
        def sent = new MockHttpServletRequest('POST', '/') {
            @Override
            long getContentLengthLong() { declared }

            @Override
            int getContentLength() { declared < 0 ? -1 : Math.toIntExact(declared) }

            @Override
            ServletInputStream getInputStream() {
                new ServletInputStream() {
                    int offset

                    @Override
                    int read() {
                        if (offset == bytes.length) return -1
                        fetched.incrementAndGet()
                        bytes[offset++] & 0xff
                    }

                    @Override
                    boolean isFinished() { offset == bytes.length }

                    @Override
                    boolean isReady() { true }

                    @Override
                    void setReadListener(ReadListener listener) { throw new UnsupportedOperationException() }
                }
            }
        }
        sent.setContentType('multipart/form-data; boundary=Aa')
        sent
    }

    private static MockHttpServletRequest slowFile(String wire, AtomicLong now) {
        byte[] bytes = wire.getBytes(StandardCharsets.ISO_8859_1)
        int fileAt = wire.indexOf('\r\n\r\nx') + 4
        def sent = new MockHttpServletRequest('POST', CallerAdmission.THIS_APPLICATION_ANSWERS + '/standing') {
            @Override
            long getContentLengthLong() { -1L }

            @Override
            int getContentLength() { -1 }

            @Override
            ServletInputStream getInputStream() {
                new ServletInputStream() {
                    int offset

                    @Override
                    int read() {
                        if (offset == bytes.length) return -1
                        if (offset == fileAt) now.set(TimeUnit.SECONDS.toNanos(31))
                        bytes[offset++] & 0xff
                    }

                    @Override
                    int read(byte[] target, int start, int length) {
                        if (length == 0) return 0
                        int value = read()
                        if (value < 0) return -1
                        target[start] = (byte) value
                        1
                    }

                    @Override
                    boolean isFinished() { offset == bytes.length }

                    @Override
                    boolean isReady() { true }

                    @Override
                    void setReadListener(ReadListener listener) { throw new UnsupportedOperationException() }
                }
            }
        }
        sent.setContentType('multipart/form-data; boundary=Aa')
        sent.addHeader('Sec-Fetch-Site', 'same-origin')
        sent
    }

    private static String document(String value) {
        "--Aa\r\nContent-Disposition: form-data; name=\"document\"\r\n\r\n${value}\r\n"
    }

    def "an uploaded part streams through a false boundary and takes its name from JSON"() {
        given:
        String content = "one\r\n--Aax\r\ntwo"
        String wire = document('{"values":{"photo":{"upload":0,"name":"actual.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\n' + content + '\r\n--Aa--\r\n'
        def deployment = new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofGigabytes(1))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))

        when:
        def outcome
        ReadyMultipartFiles.open(request(wire), 1024).withCloseable { multipart ->
            def json = multipart.document()
            outcome = store.transactions().execute {
                multipart.fill(run, group, [photo()], ConstantJson.sent(json.path('values').toString()),
                        null, files, deployment)
            }
        }

        then:
        outcome instanceof FilledFields
        def file = StoredFile.fromKept(outcome.values().members().first().value())
        file.name().value() == 'actual.png'
        store.session.sql('select original_name from stored_files').query(String).single() == 'actual.png'
        files.open(run, group, file.id()).content().withCloseable { it.readAllBytes() } ==
                content.getBytes(StandardCharsets.ISO_8859_1)
    }

    def "a false boundary split across the file read buffer is kept as content"() {
        given:
        String content = ('a' * 65535) + '\r\n--Aax' + ('\r' * 1024) + 'end'
        String wire = document('{"values":{"photo":{"upload":0,"name":"actual.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\n' + content + '\r\n--Aa--\r\n'
        def deployment = new FileDeployment(DataSize.ofBytes(70_000), 2, DataSize.ofGigabytes(1))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))

        when:
        def outcome
        ReadyMultipartFiles.open(request(wire), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            outcome = store.transactions().execute {
                multipart.fill(run, group, [photo(70_000L)], values, null, files, deployment)
            }
        }

        then:
        outcome instanceof FilledFields
        def file = StoredFile.fromKept(outcome.values().members().first().value())
        files.open(run, group, file.id()).content().withCloseable { it.readAllBytes() } ==
                content.getBytes(StandardCharsets.ISO_8859_1)
    }

    def "a keep marker reuses only the prior value at this run and field path"() {
        given:
        String upload = document('{"values":{"photo":{"upload":0,"name":"actual.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\nabc\r\n--Aa--\r\n'
        String keep = document('{"values":{"photo":{"keep":0}}}') + '--Aa--\r\n'
        def deployment = new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofGigabytes(1))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))

        when:
        def uploaded
        ReadyMultipartFiles.open(request(upload), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            uploaded = store.transactions().execute {
                multipart.fill(run, group, [photo()], values, null, files, deployment)
            }
        }
        def kept
        ReadyMultipartFiles.open(request(keep), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            kept = store.transactions().execute {
                multipart.fill(run, group, [photo()], values, uploaded.values(), files, deployment)
            }
        }

        then:
        kept instanceof FilledFields
        StoredFile.fromKept(kept.values().members().first().value()).id() ==
                StoredFile.fromKept(uploaded.values().members().first().value()).id()
        store.count('select count(*) from stored_files') == 1
    }

    def "many keeps select original file indices for #caseName"() {
        given:
        def deployment = new FileDeployment(DataSize.ofBytes(8), 4, DataSize.ofBytes(8))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))
        def previous = uploadedPhotos(run, group, files, deployment)
        def old = previous.values().members().first().value().items().collect { StoredFile.fromKept(it) }
        def before = store.contents()
        String wire = document('{"values":{"photo":' + markers + '}}') +
                (hasUpload ? uploadPart(0, 'newbytes') : '') + '--Aa--\r\n'

        when:
        def outcome
        ReadyMultipartFiles.open(request(wire), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            outcome = store.transactions().execute {
                multipart.fill(run, group, [photos()], values, previous.values(), files, deployment)
            }
        }

        then:
        outcome instanceof FilledFields
        outcome.values().members().collect { it.name() } == ['photo']
        def selected = outcome.values().members().first().value().items().collect { StoredFile.fromKept(it) }
        selected.size() == originalIndices.size()
        selected.withIndex().every { file, position ->
            int original = originalIndices[position]
            original < 0 ? !old.collect { it.id() }.contains(file.id()) &&
                    file.name().value() == 'new.png' && file.kept().members()*.name() == ['id', 'name', 'size'] && file.size() == 8L :
                    file == old[original]
        }
        selected.withIndex().every { file, position ->
            def opened = files.open(run, group, file.id())
            opened.content().withCloseable { content ->
                opened.file() == file && content.readAllBytes() == expectedContents[position].bytes
            }
        }
        store.count('select count(*) from stored_files') == (hasUpload ? 4 : 3)
        store.count('select count(*) from file_contents') == (hasUpload ? 4 : 3)
        store.count('select count(*) from run_file_holders') == (hasUpload ? 4 : 3)
        old.withIndex().every { file, position ->
            def opened = files.open(run, group, file.id())
            opened.content().withCloseable { content ->
                opened.file() == file && content.readAllBytes() == ['a', 'bb', 'ccc'][position].bytes
            }
        }
        hasUpload || store.contents() == before

        where:
        caseName     | markers                                                                | hasUpload || originalIndices | expectedContents
        'same order' | '[{"keep":0},{"keep":1},{"keep":2}]'                                     | false     || [0, 1, 2]       | ['a', 'bb', 'ccc']
        'reordered'  | '[{"keep":2},{"keep":0},{"keep":1}]'                                     | false     || [2, 0, 1]       | ['ccc', 'a', 'bb']
        'deleted'    | '[{"keep":2}]'                                                           | false     || [2]             | ['ccc']
        'mixed new'  | '[{"keep":2},{"upload":0,"name":"new.png"},{"keep":0}]'                    | true      || [2, -1, 0]      | ['ccc', 'newbytes', 'a']
    }

    def "many keeps reject #caseName without changing any saved content"() {
        given:
        def deployment = new FileDeployment(DataSize.ofBytes(8), 4, DataSize.ofBytes(8))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))
        def previous = uploadedPhotos(run, group, files, deployment)
        def old = previous.values().members().first().value().items().collect { StoredFile.fromKept(it) }
        def before = store.contents()
        String wire = document('{"values":{"photo":' + markers + '}}') +
                uploadPart(0, 'newbytes') + '--Aa--\r\n'

        when:
        ReadyMultipartFiles.open(request(wire), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            store.transactions().execute {
                multipart.fill(run, group, [photos()], values, previous.values(), files, deployment)
            }
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == expectedCode
        store.contents() == before
        old.withIndex().every { file, position ->
            def opened = files.open(run, group, file.id())
            opened.content().withCloseable { content ->
                opened.file() == file && content.readAllBytes() == ['a', 'bb', 'ccc'][position].bytes
            }
        }

        where:
        caseName        | markers                                                                || expectedCode
        'duplicate old' | '[{"keep":0},{"upload":0,"name":"new.png"},{"keep":0}]'                    || RefusalCode.BODY_UNUSABLE
        'out of bounds' | '[{"keep":3},{"upload":0,"name":"new.png"}]'                              || RefusalCode.BODY_UNUSABLE
    }

    def "an undeclared length is accepted and bounded while it arrives"() {
        given:
        String wire = document('{"values":{}}') + '--Aa--\r\n'
        def deployment = new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofGigabytes(1))

        when:
        def outcome
        ReadyMultipartFiles.open(lengthAs(wire, -1), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            outcome = multipart.fill(new RunId(UUID.randomUUID()), new GroupId(UUID.randomUUID()), [], values,
                    null, Mockito.mock(RunFiles), deployment)
        }

        then:
        outcome instanceof FilledFields
        outcome.values().members().isEmpty()
    }

    def "a declared total over the field budget is refused before reading an upload"() {
        given:
        String wire = document('{"values":{"photo":{"upload":0,"name":"actual.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\nabc\r\n--Aa--\r\n'
        def deployment = new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofGigabytes(1))

        when:
        ReadyMultipartFiles.open(lengthAs(wire, Integer.MAX_VALUE), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            multipart.fill(new RunId(UUID.randomUUID()), new GroupId(UUID.randomUUID()), [photo()], values,
                    null, Mockito.mock(RunFiles), deployment)
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
    }

    def "two uploaded fields totaling the deployment limit keep exactly their content"() {
        given:
        String wire = twoFiles('four', 'more')
        def deployment = new FileDeployment(DataSize.ofBytes(8), 2, DataSize.ofBytes(8))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))

        when:
        def outcome
        ReadyMultipartFiles.open(request(wire), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            outcome = store.transactions().execute {
                multipart.fill(run, group, [photo(8L), copy()], values, null, files, deployment)
            }
        }

        then:
        outcome instanceof FilledFields
        store.count('select count(*) from stored_files') == 2
        store.count('select count(*) from file_contents') == 2
        store.count('select count(*) from run_file_holders') == 2
        def kept = outcome.values().members().collectEntries { [(it.name()): StoredFile.fromKept(it.value())] }
        kept.keySet() == ['photo', 'copy'] as Set
        files.open(run, group, kept.photo.id()).content().withCloseable { it.readAllBytes() } == 'four'.bytes
        files.open(run, group, kept.copy.id()).content().withCloseable { it.readAllBytes() } == 'more'.bytes
    }

    def "an unknown-length second field one byte above the total rolls back both files"() {
        given:
        String wire = twoFiles('four', 'fives')
        int secondAt = wire.indexOf('\r\n\r\n', wire.indexOf('name="upload1"')) + 4
        def fetched = new AtomicLong()
        def deployment = new FileDeployment(DataSize.ofBytes(8), 2, DataSize.ofBytes(8))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)

        when:
        ReadyMultipartFiles.open(countedRequest(wire, fetched), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            store.transactions().execute {
                multipart.fill(new RunId(UUID.fromString(TicketWorkflow.RUN)),
                        new GroupId(UUID.fromString(TicketWorkflow.GROUP)), [photo(8L), copy()], values,
                        null, files, deployment)
            }
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
        fetched.get() == secondAt + 5
        store.count('select count(*) from stored_files') == 0
        store.count('select count(*) from file_contents') == 0
        store.count('select count(*) from run_file_holders') == 0
    }

    def "a declared body beyond the bounded total is refused before the first file part"() {
        given:
        String wire = twoFiles('four', 'more')
        int firstAt = wire.indexOf('\r\n\r\n', wire.indexOf('name="upload0"')) + 4
        def fetched = new AtomicLong()
        def deployment = new FileDeployment(DataSize.ofBytes(8), 2, DataSize.ofBytes(8))

        when:
        ReadyMultipartFiles.open(countedRequest(wire, fetched, 100_000L), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            multipart.fill(new RunId(UUID.randomUUID()), new GroupId(UUID.randomUUID()),
                    [photo(8L), copy()], values, null, Mockito.mock(RunFiles), deployment)
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
        fetched.get() < firstAt
        store.count('select count(*) from stored_files') == 0
        store.count('select count(*) from file_contents') == 0
        store.count('select count(*) from run_file_holders') == 0
    }

    def "a prior file kept alone or beside two new files consumes no new-byte allowance"() {
        given:
        def deployment = new FileDeployment(DataSize.ofBytes(8), 2, DataSize.ofBytes(8))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))
        String first = document('{"values":{"photo":{"upload":0,"name":"old.png"}}}') +
                uploadPart(0, 'oldbytes') + '--Aa--\r\n'
        def previous
        ReadyMultipartFiles.open(request(first), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            previous = store.transactions().execute {
                multipart.fill(run, group, [photo(8L)], values, null, files, deployment)
            }
        }
        def old = StoredFile.fromKept(previous.values().members().first().value())
        String onlyKeep = document('{"values":{"photo":{"keep":0}}}') + '--Aa--\r\n'
        String mixed = document('{"values":{"photo":{"keep":0},' +
                '"copy":{"upload":0,"name":"copy.png"},' +
                '"third":{"upload":1,"name":"third.png"}}}') +
                uploadPart(0, 'four') + uploadPart(1, 'more') + '--Aa--\r\n'

        when:
        def kept
        ReadyMultipartFiles.open(request(onlyKeep), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            kept = store.transactions().execute {
                multipart.fill(run, group, [photo(8L)], values, previous.values(), files, deployment)
            }
        }
        def combined
        ReadyMultipartFiles.open(request(mixed), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            combined = store.transactions().execute {
                multipart.fill(run, group, [photo(8L), copy(), third()], values, previous.values(), files, deployment)
            }
        }

        then:
        kept instanceof FilledFields
        StoredFile.fromKept(kept.values().members().first().value()).id() == old.id()
        combined instanceof FilledFields
        StoredFile.fromKept(combined.values().members().first().value()).id() == old.id()
        store.count('select count(*) from stored_files') == 3
        store.count('select count(*) from file_contents') == 3
        store.count('select count(*) from run_file_holders') == 3
        files.open(run, group, old.id()).content().withCloseable { it.readAllBytes() } == 'oldbytes'.bytes
    }

    def "a kept file does not mask two new files exceeding the total by one byte"() {
        given:
        def deployment = new FileDeployment(DataSize.ofBytes(8), 2, DataSize.ofBytes(8))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))
        String first = document('{"values":{"photo":{"upload":0,"name":"old.png"}}}') +
                uploadPart(0, 'oldbytes') + '--Aa--\r\n'
        def previous
        ReadyMultipartFiles.open(request(first), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            previous = store.transactions().execute {
                multipart.fill(run, group, [photo(8L)], values, null, files, deployment)
            }
        }
        def priorId = StoredFile.fromKept(previous.values().members().first().value()).id().toString()
        String mixed = document('{"values":{"photo":{"keep":0},' +
                '"copy":{"upload":0,"name":"copy.png"},' +
                '"third":{"upload":1,"name":"third.png"}}}') +
                uploadPart(0, 'four') + uploadPart(1, 'fives') + '--Aa--\r\n'

        when:
        ReadyMultipartFiles.open(countedRequest(mixed, new AtomicLong()), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            store.transactions().execute {
                multipart.fill(run, group, [photo(8L), copy(), third()], values,
                        previous.values(), files, deployment)
            }
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts('select file_id::text from stored_files') == [priorId]
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts('select file_id::text from file_contents') == [priorId]
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts('select file_id::text from run_file_holders') == [priorId]
        files.open(run, group, UUID.fromString(priorId)).content().withCloseable { it.readAllBytes() } ==
                'oldbytes'.bytes
    }

    def "nested file fields share the one total and an overage rolls their new rows back"() {
        given:
        def folder = new FillField(new FieldName('folders'), null, null, FieldKind.FIELDS, null, 2,
                true, null, [photo(8L)])
        def deployment = new FileDeployment(DataSize.ofBytes(8), 2, DataSize.ofBytes(8))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)
        def run = new RunId(UUID.fromString(TicketWorkflow.RUN))
        def group = new GroupId(UUID.fromString(TicketWorkflow.GROUP))
        String marked = '{"values":{"folders":[' +
                '{"photo":{"upload":0,"name":"first.png"}},' +
                '{"photo":{"upload":1,"name":"second.png"}}]}}'
        String exact = document(marked) + uploadPart(0, 'four') + uploadPart(1, 'more') + '--Aa--\r\n'
        String excess = document(marked) + uploadPart(0, 'four') + uploadPart(1, 'fives') + '--Aa--\r\n'
        def exactResult
        ReadyMultipartFiles.open(request(exact), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            exactResult = store.transactions().execute {
                multipart.fill(run, group, [folder], values, null, files, deployment)
            }
        }
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        def before = store.texts('select file_id::text from stored_files order by file_id')

        when:
        ReadyMultipartFiles.open(countedRequest(excess, new AtomicLong()), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            store.transactions().execute {
                multipart.fill(run, group, [folder], values, null, files, deployment)
            }
        }

        then:
        exactResult instanceof FilledFields
        before.size() == 2
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts('select file_id::text from stored_files order by file_id') == before
        store.count('select count(*) from file_contents') == 2
        store.count('select count(*) from run_file_holders') == 2
    }

    def "a too-large file stops at its second physical payload byte and leaves no rows"() {
        given:
        String wire = document('{"values":{"photo":{"upload":0,"name":"actual.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\n' + ('x' * 80_000) + '\r\n--Aa--\r\n'
        def fetched = new AtomicLong()
        int fileAt = wire.indexOf('\r\n\r\n', wire.indexOf('name="upload0"')) + 4
        def deployment = new FileDeployment(DataSize.ofBytes(1), 2, DataSize.ofGigabytes(1))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)

        when:
        ReadyMultipartFiles.open(countedRequest(wire, fetched), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            store.transactions().execute {
                multipart.fill(new RunId(UUID.fromString(TicketWorkflow.RUN)),
                        new GroupId(UUID.fromString(TicketWorkflow.GROUP)), [photo(1L)], values,
                        null, files, deployment)
            }
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.FILE_TOO_LARGE
        fetched.get() == fileAt + 2
        store.count('select count(*) from stored_files') == 0
        store.count('select count(*) from file_contents') == 0
    }

    def "a false delimiter at the file limit reads only its bounded prefix before refusal"() {
        given:
        String payload = '\r\n--AaX' + ('x' * 80_000)
        String wire = document('{"values":{"photo":{"upload":0,"name":"actual.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\n' + payload + '\r\n--Aa--\r\n'
        def fetched = new AtomicLong()
        int fileAt = wire.indexOf('\r\n\r\n', wire.indexOf('name="upload0"')) + 4
        def deployment = new FileDeployment(DataSize.ofBytes(1), 2, DataSize.ofGigabytes(1))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)

        when:
        ReadyMultipartFiles.open(countedRequest(wire, fetched), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            store.transactions().execute {
                multipart.fill(new RunId(UUID.fromString(TicketWorkflow.RUN)),
                        new GroupId(UUID.fromString(TicketWorkflow.GROUP)), [photo(1L)], values,
                        null, files, deployment)
            }
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.FILE_TOO_LARGE
        fetched.get() > fileAt + 2
        fetched.get() <= fileAt + 8
        store.count('select count(*) from stored_files') == 0
        store.count('select count(*) from file_contents') == 0
    }

    def "a file part with a second disposition name is refused"() {
        given:
        String wire = document('{"values":{"photo":{"upload":0,"name":"actual.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"; name="evil"\r\n' +
                'Content-Type: image/png\r\n\r\nx\r\n--Aa--\r\n'
        def deployment = new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofGigabytes(1))
        def fetched = new AtomicLong()
        int fileAt = wire.indexOf('\r\n\r\n', wire.indexOf('name="upload0"')) + 4

        when:
        ReadyMultipartFiles.open(countedRequest(wire, fetched), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            multipart.fill(new RunId(UUID.randomUUID()), new GroupId(UUID.randomUUID()), [photo()], values,
                    null, Mockito.mock(RunFiles), deployment)
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
        fetched.get() == fileAt
        store.count('select count(*) from stored_files') == 0
    }

    def "an invalid ordinary value is refused before the first file byte: #caseName"() {
        given:
        String wire = document('{"values":{"photo":{"upload":0,"name":"actual.png"},"title":' + titleValue + '}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\nx\r\n--Aa--\r\n'
        def fetched = new AtomicLong()
        int fileAt = wire.indexOf('\r\n\r\n', wire.indexOf('name="upload0"')) + 4
        def title = new FillField(new FieldName('title'), null, null, FieldKind.TEXT, 2, null, true, null, [])
        def deployment = new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofGigabytes(1))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)

        when:
        ReadyMultipartFiles.open(countedRequest(wire, fetched), 1024).withCloseable { multipart ->
            def values = ConstantJson.sent(multipart.document().path('values').toString())
            store.transactions().execute {
                multipart.fill(new RunId(UUID.fromString(TicketWorkflow.RUN)),
                        new GroupId(UUID.fromString(TicketWorkflow.GROUP)), [photo(), title], values,
                        null, files, deployment)
            }
        }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.VALUE_DOES_NOT_FIT
        fetched.get() < fileAt
        store.count('select count(*) from stored_files') == 0
        store.count('select count(*) from file_contents') == 0
        store.count('select count(*) from run_file_holders') == 0

        where:
        caseName       | titleValue
        'required null' | 'null'
        'too long'      | '"long"'
    }

    def "opening closes the raw stream when the first boundary is wrong or too long: #caseName"() {
        given:
        def closed = new AtomicBoolean()

        when:
        ReadyMultipartFiles.open(closingRequest(wire, closed), 1024)

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
        closed.get()

        where:
        caseName        | wire
        'wrong boundary' | '--wrong\r\n'
        'long first line' | ('X' * 8193) + '\r\n'
    }

    def "a one-byte late file read is refused and releases the large-body turn"() {
        given:
        String wire = document('{"values":{"photo":{"upload":0,"name":"actual.png"}}}') +
                '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\nx\r\n--Aa--\r\n'
        def now = new AtomicLong()
        def request = slowFile(wire, now)
        def admission = new LargeBodyAdmission(Stub(HandlerExceptionResolver), { now.get() } as LongSupplier)
        def door = new CallerAdmission(
                { asked -> Optional.of(new UserId('000001')) } as UserIdentification,
                Stub(HandlerExceptionResolver))
        def deployment = new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofGigabytes(1))

        def files = new RunFiles(store.session, new DatabaseFileContentStore(store.database), deployment)

        when:
        request.asyncSupported = true
        def response = new MockHttpServletResponse()
        def workers = new ThreadPoolTaskExecutor()
        workers.setVirtualThreads(true)
        workers.initialize()
        def transfers = new FileTransfers(deployment, { now.get() }, workers)
        def manager
        new RequestTrace().doFilter(ReadyServletStreams.request(request), response, { traced, tracedResponse ->
        door.doFilter(traced, tracedResponse, { asked, answer ->
            admission.doFilter(asked, answer, { inside, output ->
                assert LargeBodyAdmission.readDeadline(inside) != null
                def received = transfers.upload(inside, output, 1024, { multipart ->
                    def values = ConstantJson.sent(multipart.document().path('values').toString())
                    store.transactions().execute {
                        multipart.fill(new RunId(UUID.fromString(TicketWorkflow.RUN)),
                                new GroupId(UUID.fromString(TicketWorkflow.GROUP)), [photo()], values,
                                null, files, deployment)
                    }
                })
                manager = WebAsyncUtils.getAsyncManager(inside)
                manager.setAsyncWebRequest(WebAsyncUtils.createAsyncWebRequest(inside, output))
                manager.startDeferredResultProcessing(received)
            } as FilterChain)
        } as FilterChain)
        } as FilterChain)
        new PollingConditions(timeout: 5).eventually {
            assert manager.hasConcurrentResult()
            assert workers.activeCount == 0
        }
        request.asyncContext.complete()
        assert now.get() == TimeUnit.SECONDS.toNanos(31)
        if (manager.concurrentResult instanceof Throwable) throw manager.concurrentResult

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_SENT_TOO_SLOWLY
        admission.turn.turn.availablePermits() == 1
        store.count('select count(*) from stored_files') == 0

        cleanup:
        transfers?.close()
        workers?.destroy()
    }

    def "a missing first JSON part is refused before any file part"() {
        given:
        String wire = '--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="x"\r\n' +
                'Content-Type: image/png\r\n\r\nx\r\n--Aa--\r\n'

        when:
        ReadyMultipartFiles.open(request(wire), 1024).withCloseable { it.document() }

        then:
        def error = thrown(ApiErrorException)
        error.errorCode() == RefusalCode.BODY_UNUSABLE
    }
}
