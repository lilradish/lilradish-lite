package com.lilradish.lite.app.files

import com.lilradish.lite.app.filling.ValueProblemsRefusal
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.filling.FillField
import com.lilradish.lite.domain.filling.FillOutcome
import com.lilradish.lite.domain.filling.FillPath
import com.lilradish.lite.domain.filling.FillProblems
import com.lilradish.lite.domain.filling.FillReason
import com.lilradish.lite.domain.filling.FilledFields
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.PgTrgm
import java.nio.file.Path
import java.sql.Connection
import org.flywaydb.core.Flyway
import org.libprunus.core.error.ApiErrorException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.unit.DataSize
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunFilesIntegrationSpec extends Specification {

    static final String MIGRATIONS = Objects.requireNonNull(System.getProperty("baseline.location"))
    static final GroupId GROUP = new GroupId(UUID.fromString("00000002-0000-4000-8000-000000000101"))
    static final RunId SOURCE = new RunId(UUID.fromString("0000000f-0000-4000-8000-000000000101"))

    @Shared @AutoCleanup TestPostgres server = TestPostgres.isolated()
    @Shared javax.sql.DataSource source
    @Shared JdbcClient database
    @Shared DataSourceTransactionManager manager
    @Shared TransactionTemplate transaction
    @Shared RunFiles files

    def setupSpec() {
        def migrationRoot = Path.of(MIGRATIONS)
        PgTrgm.preparedIn(server.postgresDatabase)
        Flyway.configure().dataSource(server.postgresDatabase).schemas("app")
                .locations("filesystem:${migrationRoot}", "filesystem:${migrationRoot.resolveSibling('local')}")
                .failOnMissingLocations(true).validateMigrationNaming(true).load().migrate()
        source = server.dataSource("?currentSchema=app")
        database = JdbcClient.create(source)
        manager = new DataSourceTransactionManager(source)
        transaction = new TransactionTemplate(manager)

        files = new RunFiles(database, new DatabaseFileContentStore(source),
                new FileDeployment(DataSize.ofBytes(5), 2, DataSize.ofBytes(10)))
    }

    static FillField field(Integer most = 2) {
        new FillField(new FieldName("documents"), null, null, FieldKind.FILE, null, most, false, null, [],
                [new FileExtension(".png"), new FileExtension(".pdf")], 5L)
    }

    def "a complete field consumes ordered one-pass streams, keeps their original metadata and can be opened"() {
        given:
        def first = new ByteArrayInputStream([1, 2, 3] as byte[])
        def second = new ByteArrayInputStream([4, 5] as byte[])
        def target = new RunId(UUID.randomUUID())

        when:
        def kept = transaction.execute {
            def batch = files.begin(target, GROUP, field())
            batch.upload(0, 0, "First.png", first)
            batch.upload(1, 1, "Other.pdf", second)
            def sealed = batch.finish()
            def value = assemble(target, field(), new JsonValue.JsonArray([upload(0, "First.png"), upload(1, "Other.pdf")]), sealed)
            insertRun(target, value)
            value
        }
        def read = kept.items().collect { it.members().collectEntries { member -> [(member.name()): member.value()] } }
        def opened = files.open(target, GROUP, UUID.fromString(read[0].id.value()))

        then:
        read*.name*.value() == ["First.png", "Other.pdf"]
        read*.keySet() == [["id", "name", "size"] as Set, ["id", "name", "size"] as Set]
        read*.size*.value()*.intValue() == [3, 2]
        opened.file().name().value() == "First.png"
        opened.content().bytes == ([1, 2, 3] as byte[])
        database.sql("select count(*) from stored_files where group_id = :group")
                .param("group", GROUP.value()).query(Integer).single() >= 2
        first.available() == 0 && second.available() == 0
    }

    def "one failed later file rolls back the earlier content and does not read past the limit plus one"() {
        given:
        def before = count("file_contents")
        def target = new RunId(UUID.randomUUID())
        def longStream = new CountingStream(new byte[100])

        when:
        transaction.execute {
            def batch = files.begin(target, GROUP, field())
            batch.upload(0, 0, "First.png", new ByteArrayInputStream([1] as byte[]))
            batch.upload(1, 1, "Large.pdf", longStream)
            batch.finish()
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.FILE_TOO_LARGE
        refused.message.contains("File 2")
        longStream.readCount == 6
        count("file_contents") == before
    }

    def "a trusted prior holder can be reused in another run without a second content row"() {
        given:
        def original = transaction.execute {
            def batch = files.begin(SOURCE, GROUP, field(null))
            batch.upload(0, 0, "Shared.png", new ByteArrayInputStream([7, 8] as byte[]))
            assemble(SOURCE, field(null), upload(0, "Shared.png"), batch.finish())
        }
        def id = UUID.fromString(original.members().find { it.name() == "id" }.value().value())
        def before = count("file_contents")
        def target = new RunId(UUID.randomUUID())

        when:
        def copied = transaction.execute {
            def batch = files.begin(target, GROUP, field(null))
            batch.reuse(0, 0, SOURCE, id)
            def kept = assemble(target, field(null), keep(0), batch.finish())
            insertRun(target, kept)
            kept
        }

        then:
        copied == original
        count("file_contents") == before
        files.open(target, GROUP, id).content().bytes == ([7, 8] as byte[])
    }

    def "raw file identifiers never become a filling"() {
        given:
        def fileField = field(null)
        def forged = new JsonValue.JsonObject([new JsonValue.JsonMember("documents",
                new JsonValue.JsonObject([new JsonValue.JsonMember("id", new JsonValue.JsonString(UUID.randomUUID().toString()))]))])
        def before = counts()

        when:
        def result = FileFillings.of(SOURCE, GROUP, [fileField], forged, [:])

        then:
        result instanceof FillOutcome.Unshaped
        counts() == before
    }

    def "a mismatched upload marker refuses the filling and rolls back every file table"() {
        given:
        def fileField = field(null)
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.upload(0, 2, "Demo Sample.png", new ByteArrayInputStream([1] as byte[]))
            def sealed = batch.finish()
            def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents", upload(3, "Demo Sample.png"))])
            FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): sealed])
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        counts() == before
    }

    def "an unused sealed batch refuses the filling and rolls back every file table"() {
        given:
        def fileField = field(null)
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.upload(0, 4, "Demo Sample.png", new ByteArrayInputStream([1] as byte[]))
            def sealed = batch.finish()
            def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents", new JsonValue.JsonNull())])
            FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): sealed])
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        counts() == before
    }

    def "a wider batch cannot be assembled as a narrower actual field"() {
        given:
        def wide = field(2)
        def narrow = field(1)
        def path = new FillPath([new FillPath.Named(narrow.name())])
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, wide)
            batch.upload(0, 0, "First.png", new ByteArrayInputStream([1] as byte[]))
            batch.upload(1, 1, "Second.png", new ByteArrayInputStream([2] as byte[]))
            def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents",
                    new JsonValue.JsonArray([upload(0, "First.png"), upload(1, "Second.png")]))])
            FileFillings.of(SOURCE, GROUP, [narrow], sent, [(path): batch.finish()])
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        counts() == before
    }

    def "a valid file with another required field missing keeps that field's refusal and rolls back files"() {
        given:
        def fileField = field(null)
        def title = new FillField(new FieldName("title"), null, null, FieldKind.TEXT, 40, null, true, null, [])
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def titlePath = new FillPath([new FillPath.Named(title.name())])
        def sent = new JsonValue.JsonObject([
                new JsonValue.JsonMember("documents", upload(0, "Demo Sample.png")),
                new JsonValue.JsonMember("title", new JsonValue.JsonNull())])
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.upload(0, 0, "Demo Sample.png", new ByteArrayInputStream([1] as byte[]))
            FileFillings.of(SOURCE, GROUP, [fileField, title], sent, [(path): batch.finish()])
        }

        then:
        def refused = thrown(ValueProblemsRefusal)
        refused.errorCode() == RefusalCode.VALUE_DOES_NOT_FIT
        refused.problems().collect { [it.path(), it.reason()] } == [[titlePath, FillReason.MISSING]]
        refused.found() == 1
        counts() == before
    }

    def "an ordinary missing field without a file batch keeps the original problems result"() {
        given:
        def fileField = field(null)
        def title = new FillField(new FieldName("title"), null, null, FieldKind.TEXT, 40, null, true, null, [])
        def titlePath = new FillPath([new FillPath.Named(title.name())])
        def sent = new JsonValue.JsonObject([
                new JsonValue.JsonMember("documents", new JsonValue.JsonNull()),
                new JsonValue.JsonMember("title", new JsonValue.JsonNull())])
        def before = counts()

        when:
        def result = FileFillings.of(SOURCE, GROUP, [fileField, title], sent, [:])

        then:
        result instanceof FillProblems
        result.problems().collect { [it.path(), it.reason()] } == [[titlePath, FillReason.MISSING]]
        result.found() == 1
        counts() == before
    }

    def "one upload index cannot be named twice in a field"() {
        given:
        def fileField = field()
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.upload(0, 5, "First.png", new ByteArrayInputStream([1] as byte[]))
            batch.upload(1, 5, "Other.png", new ByteArrayInputStream([2] as byte[]))
            def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents",
                    new JsonValue.JsonArray([upload(5, "First.png"), upload(5, "Other.png")]))])
            FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): batch.finish()])
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        counts() == before
    }

    def "one previous position cannot be named twice in a field"() {
        given:
        def fileField = field()
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def original = transaction.execute {
            def batch = files.begin(SOURCE, GROUP, field(null))
            batch.upload(0, 0, "Shared.png", new ByteArrayInputStream([1] as byte[]))
            assemble(SOURCE, field(null), upload(0, "Shared.png"), batch.finish())
        }
        def id = UUID.fromString(original.members().find { it.name() == "id" }.value().value())
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.reuse(0, 0, SOURCE, id)
            batch.reuse(1, 0, SOURCE, id)
            def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents",
                    new JsonValue.JsonArray([keep(0), keep(0)]))])
            FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): batch.finish()])
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        counts() == before
    }

    def "nested file batches require the exact field path and every marker position"() {
        given:
        def fileField = field(null)
        def folders = new FillField(new FieldName("folders"), null, null, FieldKind.FIELDS, null, 2,
                false, null, [fileField])
        def correctPath = new FillPath([new FillPath.Named(folders.name()), new FillPath.Place(1),
                new FillPath.Named(fileField.name())])
        def wrongPath = new FillPath([new FillPath.Named(folders.name()), new FillPath.Place(0),
                new FillPath.Named(fileField.name())])
        def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("folders", new JsonValue.JsonArray([
                new JsonValue.JsonObject([new JsonValue.JsonMember("documents", upload(6, "First.png"))]),
                new JsonValue.JsonObject([new JsonValue.JsonMember("documents", upload(7, "Nested.png"))])]))])
        def before = counts()

        when:
        transaction.execute {
            def first = files.begin(SOURCE, GROUP, fileField)
            first.upload(0, 6, "First.png", new ByteArrayInputStream([1] as byte[]))
            def second = files.begin(SOURCE, GROUP, fileField)
            second.upload(0, 7, "Nested.png", new ByteArrayInputStream([2] as byte[]))
            FileFillings.of(SOURCE, GROUP, [folders], sent,
                    [(wrongPath): second.finish(), (correctPath): first.finish()])
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        counts() == before

        when:
        def accepted = transaction.execute {
            def first = files.begin(SOURCE, GROUP, fileField)
            first.upload(0, 6, "First.png", new ByteArrayInputStream([1] as byte[]))
            def second = files.begin(SOURCE, GROUP, fileField)
            second.upload(0, 7, "Nested.png", new ByteArrayInputStream([2] as byte[]))
            FileFillings.of(SOURCE, GROUP, [folders], sent,
                    [(wrongPath): first.finish(), (correctPath): second.finish()])
        }

        then:
        accepted instanceof FilledFields
        def foldersKept = accepted.values().members().first().value().items()
        foldersKept[0].members().first().value().members()*.name() == ["id", "name", "size"]
        foldersKept[1].members().first().value().members()*.name() == ["id", "name", "size"]
        count("file_contents") == before.file_contents + 2
        count("stored_files") == before.stored_files + 2
    }

    def "many nested file fields share one transaction synchronization"() {
        given:
        def fileField = field(null)
        def original = transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.upload(0, 0, "Shared.png", new ByteArrayInputStream([1] as byte[]))
            assemble(SOURCE, fileField, upload(0, "Shared.png"), batch.finish())
        }
        def id = UUID.fromString(original.members().find { it.name() == "id" }.value().value())
        def folders = new FillField(new FieldName("folders"), null, null, FieldKind.FIELDS, null, 100,
                false, null, [fileField])
        def before = counts()

        when:
        def result = transaction.execute {
            def startingSynchronizations = TransactionSynchronizationManager.getSynchronizations().size()
            def batches = [:]
            def values = []
            100.times { index ->
                def batch = files.begin(SOURCE, GROUP, fileField)
                batch.reuse(0, 0, SOURCE, id)
                batches[new FillPath([new FillPath.Named(folders.name()), new FillPath.Place(index),
                        new FillPath.Named(fileField.name())])] = batch.finish()
                values.add(new JsonValue.JsonObject([new JsonValue.JsonMember("documents", keep(0))]))
            }
            def addedSynchronizations = TransactionSynchronizationManager.getSynchronizations().size() - startingSynchronizations
            def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("folders", new JsonValue.JsonArray(values))])
            [addedSynchronizations, FileFillings.of(SOURCE, GROUP, [folders], sent, batches)]
        }

        then:
        result[0] == 1
        result[1] instanceof FilledFields
        result[1].values().members().first().value().items().size() == 100
        counts() == before
    }

    def "a suspended transaction cannot use its batch until it resumes"() {
        given:
        def fileField = field(null)
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents", upload(14, "Paused.png"))])
        def nested = new TransactionTemplate(manager)
        nested.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        def before = counts()

        when:
        def outcome = transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.upload(0, 14, "Paused.png", new ByteArrayInputStream([7] as byte[]))
            def sealed = batch.finish()
            nested.execute {
                try {
                    FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): sealed])
                    assert false
                } catch (IllegalStateException expected) {
                    assert expected.message.contains("no longer active")
                }
                null
            }
            FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): sealed])
        }

        then:
        outcome instanceof FilledFields
        count("file_contents") == before.file_contents + 1
        count("stored_files") == before.stored_files + 1
        count("run_file_holders") == before.run_file_holders + 1
    }

    def "a sealed batch cannot assemble values after its transaction ends"() {
        given:
        def fileField = field(null)
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents", upload(9, "Done.png"))])
        RunFiles.VerifiedBatch sealed
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.upload(0, 9, "Done.png", new ByteArrayInputStream([3] as byte[]))
            sealed = batch.finish()
            FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): sealed])
        }

        when:
        FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): sealed])

        then:
        thrown(IllegalStateException)
    }

    def "a rolled back batch cannot assemble values or leave content"() {
        given:
        def fileField = field(null)
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents", upload(10, "Gone.png"))])
        def before = count("file_contents")
        RunFiles.VerifiedBatch sealed
        try {
            transaction.execute {
                def batch = files.begin(SOURCE, GROUP, fileField)
                batch.upload(0, 10, "Gone.png", new ByteArrayInputStream([4] as byte[]))
                sealed = batch.finish()
                throw new IllegalStateException("rollback")
            }
        } catch (IllegalStateException expected) {
            assert expected.message == "rollback"
        }

        when:
        FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): sealed])

        then:
        thrown(IllegalStateException)
        count("file_contents") == before
    }

    def "a caught filling refusal still prevents the transaction from committing file rows"() {
        given:
        def fileField = field(null)
        def path = new FillPath([new FillPath.Named(fileField.name())])
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, fileField)
            batch.upload(0, 11, "Demo Sample.png", new ByteArrayInputStream([5] as byte[]))
            def sent = new JsonValue.JsonObject([new JsonValue.JsonMember("documents", upload(12, "Demo Sample.png"))])
            try {
                FileFillings.of(SOURCE, GROUP, [fileField], sent, [(path): batch.finish()])
                assert false
            } catch (ApiErrorException refused) {
                assert refused.errorCode() == RefusalCode.BODY_UNUSABLE
            }
            null
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        counts() == before
    }

    def "an unassembled sealed batch cannot commit file rows"() {
        given:
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(SOURCE, GROUP, field(null))
            batch.upload(0, 13, "Demo Sample.png", new ByteArrayInputStream([6] as byte[]))
            batch.finish()
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        counts() == before
    }

    def "a bare identifier cannot open a file without the named run's holder"() {
        given:
        def holder = new RunId(UUID.randomUUID())
        def id = keepFile(holder)
        def unnamed = new RunId(UUID.randomUUID())
        def before = counts()

        when:
        files.open(unnamed, GROUP, id)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
        refused.message == "That run is not in view."
        counts() == before
        holderCount(unnamed, id) == 0
        files.open(holder, GROUP, id).content().bytes == ([7, 8] as byte[])
    }

    def "the same held file can be opened in its group and refuses another group without changing storage"() {
        given:
        def holder = new RunId(UUID.randomUUID())
        def id = keepFile(holder)
        def other = new GroupId(UUID.randomUUID())
        def before = counts()
        def opened = files.open(holder, GROUP, id)

        when:
        files.open(holder, other, id)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
        refused.message == "That run is not in view."
        counts() == before
        holderCount(holder, id) == 1
        database.sql("select count(*) from run_file_holders where file_id = :file and group_id = :group")
                .param("file", id).param("group", other.value()).query(Integer).single() == 0
        opened.content().bytes == ([7, 8] as byte[])
        files.open(holder, GROUP, id).content().bytes == ([7, 8] as byte[])
    }

    def "the same held file can be reused in its group and refuses another group without a new holder"() {
        given:
        def holder = new RunId(UUID.randomUUID())
        def id = keepFile(holder)
        def target = new RunId(UUID.randomUUID())
        def other = new GroupId(UUID.randomUUID())
        def otherEntry = UUID.randomUUID()
        def otherVersion = UUID.randomUUID()
        def otherTarget = new RunId(UUID.randomUUID())
        def beforeContent = count("file_contents")
        transaction.execute {
            def batch = files.begin(target, GROUP, field(null))
            batch.reuse(0, 0, holder, id)
            insertRun(target, assemble(target, field(null), keep(0), batch.finish()))
        }
        database.sql("""
                insert into groups (group_id, key, name, created_by)
                select :other, 'FILEOTHER', 'Demo Sample other group', created_by
                  from groups where group_id = :group
                """)
                .param("other", other.value()).param("group", GROUP.value()).update()
        database.sql("""
                insert into entries (entry_id, group_id, kind, name, created_by)
                select :entry, :other, kind, 'Demo Sample other entry', created_by
                  from entries where entry_id = (select entry_id from runs where run_id = :source)
                """)
                .param("entry", otherEntry).param("other", other.value())
                .param("source", SOURCE.value()).update()
        // DB-SPECIFIC: PostgreSQL now().
        database.sql("""
                insert into entry_versions (entry_version_id, entry_id, entry_kind, number,
                                            created_by, approved_at, approved_by, approved_by_kind)
                select :version, :entry, entry_kind, 1, created_by, now(), created_by, 'seeder'
                  from entry_versions where entry_version_id =
                       (select entry_version_id from runs where run_id = :source)
                """)
                .param("version", otherVersion).param("entry", otherEntry)
                .param("source", SOURCE.value()).update()
        database.sql("""
                insert into workflow_versions (entry_version_id, created_by)
                select :version, created_by from workflow_versions where entry_version_id =
                       (select entry_version_id from runs where run_id = :source)
                """)
                .param("version", otherVersion).param("source", SOURCE.value()).update()
        // DB-SPECIFIC: PostgreSQL :: casts to jsonb.
        database.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id,
                                  root_run_id, depth, started_with, created_by, created_by_kind)
                select :target, :other, 1, 'Demo Sample', :entry, :version,
                       :target, 0, '{}'::jsonb, created_by, created_by_kind
                  from runs where run_id = :source
                """)
                .param("target", otherTarget.value()).param("other", other.value())
                .param("entry", otherEntry).param("version", otherVersion)
                .param("source", SOURCE.value()).update()
        def before = counts()

        when:
        transaction.execute {
            def batch = files.begin(otherTarget, other, field(null))
            batch.reuse(0, 0, holder, id)
            batch.finish()
        }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
        refused.message == "That run is not in view."
        counts() == before
        count("file_contents") == beforeContent
        holderCount(holder, id) == 1
        holderCount(target, id) == 1
        holderCount(otherTarget, id) == 0
        database.sql("select group_id from runs where run_id = :run")
                .param("run", otherTarget.value()).query(UUID).single() == other.value()
        database.sql("select count(*) from run_file_holders where run_id = :run and group_id = :group")
                .param("run", otherTarget.value()).param("group", other.value()).query(Integer).single() == 0
        files.open(holder, GROUP, id).content().bytes == ([7, 8] as byte[])
        files.open(target, GROUP, id).content().bytes == ([7, 8] as byte[])
    }

    private UUID keepFile(RunId holder) {
        def kept = transaction.execute {
            def batch = files.begin(holder, GROUP, field(null))
            batch.upload(0, 0, "Demo Sample.png", new ByteArrayInputStream([7, 8] as byte[]))
            def value = assemble(holder, field(null), upload(0, "Demo Sample.png"), batch.finish())
            insertRun(holder, value)
            value
        }
        UUID.fromString(kept.members().find { it.name() == "id" }.value().value())
    }

    private int holderCount(RunId run, UUID file) {
        database.sql("select count(*) from run_file_holders where run_id = :run and file_id = :file")
                .param("run", run.value()).param("file", file).query(Integer).single()
    }


    private JsonValue assemble(RunId run, FillField field, JsonValue sent, RunFiles.VerifiedBatch sealed) {
        def input = new JsonValue.JsonObject([new JsonValue.JsonMember("documents", sent)])
        def path = new FillPath([new FillPath.Named(field.name())])
        def outcome = FileFillings.of(run, GROUP, [field], input, [(path): sealed])
        assert outcome instanceof FilledFields
        outcome.values().members().first().value()
    }

    private static JsonValue.JsonObject upload(int index, String name) {
        new JsonValue.JsonObject([
                new JsonValue.JsonMember("upload", new JsonValue.JsonNumber(BigDecimal.valueOf(index))),
                new JsonValue.JsonMember("name", new JsonValue.JsonString(name))])
    }

    private static JsonValue.JsonObject keep(int index) {
        new JsonValue.JsonObject([new JsonValue.JsonMember("keep", new JsonValue.JsonNumber(BigDecimal.valueOf(index)))])
    }

    private void insertRun(RunId target, Object kept) {
        def started = new JsonValue.JsonObject([new JsonValue.JsonMember("documents", kept)])
        // DB-SPECIFIC: PostgreSQL jsonb type.
        database.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id,
                                  root_run_id, depth, started_with, created_by, created_by_kind)
                select :target, group_id, 1000 + (select count(*) from runs), 'Demo Sample',
                       entry_id, entry_version_id, :target, 0, cast(:kept as jsonb), created_by, created_by_kind
                  from runs where run_id = :source
                """)
                .param("target", target.value()).param("source", SOURCE.value())
                .param("kept", CanonicalJson.write(started)).update()
    }

    private Map<String, Integer> counts() {
        [file_contents: count("file_contents"), stored_files: count("stored_files"),
         run_file_holders: count("run_file_holders")]
    }

    private int count(String table) {
        database.sql("select count(*) from ${table}").query(Integer).single()
    }

    static class CountingStream extends ByteArrayInputStream {
        int readCount

        CountingStream(byte[] bytes) { super(bytes) }

        @Override int read(byte[] bytes, int offset, int length) {
            int read = super.read(bytes, offset, length)
            if (read > 0) readCount += read
            read
        }

        @Override int read() {
            int read = super.read()
            if (read >= 0) readCount++
            read
        }
    }
}
