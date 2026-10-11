package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TreeWorkflows.ASK_GIVES
import static com.lilradish.lite.app.run.fixture.TreeWorkflows.CAT
import static com.lilradish.lite.app.run.fixture.TreeWorkflows.GROUP
import static com.lilradish.lite.app.run.fixture.TreeWorkflows.ROOT
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.files.FileFillings
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.library.ConstantJson
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TreeWorkflows
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.filling.FillField
import com.lilradish.lite.domain.filling.FillPath
import com.lilradish.lite.domain.filling.FilledFields
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunInTree
import com.lilradish.lite.domain.run.RunStepFailureReason
import com.lilradish.lite.domain.run.StepAct
import com.lilradish.lite.domain.run.StepFailure
import com.lilradish.lite.domain.run.StepGround
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.codestep.ScriptedCodeStep
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import com.lilradish.lite.web.ReadyMultipartFiles
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class SubWorkflowRunIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    List<EngineWiring> wirings = []

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "sub_workflow_run_" + (++databasesMade), specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        TreeWorkflows.seed(store)
    }

    def cleanup() {
        wirings.each { it.executor.destroy() }
    }

    private EngineWiring wired(int maximumDepth = 32) {
        def wiring = TreeWorkflows.wired(store, maximumDepth)
        wirings << wiring
        wiring
    }

    private void started(EngineWiring wiring, String entry, String version, String startedWith = '{}') {
        store.transactions().executeWithoutResult {
            TreeWorkflows.run(store, entry, version, startedWith)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId(ROOT)).orElseThrow())
        }
    }

    private RunInTree tree(EngineWiring wiring) {
        store.transactions().execute { wiring.snapshots.inTreeAsRead(groupId(GROUP), runId(ROOT)) }
    }

    private static StepPosition position(RunInTree at, String step) {
        StepPositions.of(at.run(), at.run().step(new WorkflowStepId(UUID.fromString(step))).orElseThrow())
    }

    private static StepPosition deepestOf(StepPosition position) {
        position instanceof StepPosition.Beneath ? deepestOf(position.where()) : position
    }

    private static RunId runId(String run) {
        new RunId(UUID.fromString(run))
    }

    private List<String> runsBeneath() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts; || converts integer, boolean, enum and UUID operands to text.
        store.texts("""
                select number || ' ' || depth || ' ' || coalesce(name, '-') || ' ' || (started_with is null) || ' '
                       || created_by_kind || ' ' || parent_workflow_step_id
                  from runs where root_run_id = ?::uuid and parent_run_id is not null order by number
                """, ROOT)
    }

    private List<String> triesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts; || converts integer and enum operands to text.
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by_kind || ' '
                       || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> failures() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts; || converts integer, enum and boolean operands to text.
        store.texts("""
                select run.depth || ' ' || failure.reason || ' ' || coalesce(failure.detail, '-') || ' '
                       || (failure.production_id is null)
                  from run_step_failures failure join runs run on run.run_id = failure.run_id
                 where run.root_run_id = ?::uuid order by run.depth
                """, ROOT)
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a run whose first step runs a workflow starts it beneath in the same transaction, numbered next and started by the run above"() {
        given:
        def wiring = wired()

        when:
        started(wiring, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTED_TICKET)

        then:
        runsBeneath() == ["2 1 - true system ${TreeWorkflows.NOTED}" as String]
        triesOf(TreeWorkflows.WRITE_NOTE) == ["1 person system open"]
        def read = tree(wiring)
        def noted = position(read, TreeWorkflows.NOTED)
        noted instanceof StepPosition.Beneath
        noted.at().planned().id() == new WorkflowStepId(UUID.fromString(TreeWorkflows.WRITE_NOTE))
        noted.where() instanceof StepPosition.Owed

        and:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where root_run_id = ?::uuid", ROOT) == 2
        triesOf(TreeWorkflows.NOTED) == []
        triesOf(TreeWorkflows.CHECK) == []
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("""
                select count(*) from production_inputs taken join run_steps held on held.run_step_id = taken.run_step_id
                 where held.workflow_step_id = ?::uuid
                """, TreeWorkflows.NOTED) == 0
        failures() == []
    }

    private void fileBindings() {
        [TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTE_VERSION].each { version ->
            def field = UUID.randomUUID().toString()
            StepRows.field(store, [id: field, owner: version, ownerKind: "workflow", side: "takes",
                                   position: 2, name: "attachment", kind: "file", mustBe: true])
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("update declaration_fields set file_types = array['.txt'], " +
                    "file_maximum_bytes = 100 where declaration_field_id = ?::uuid").params(field).update()
        }
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.ESCALATE_VERSION,
                TreeWorkflows.NOTED, "attachment", null, "attachment")
    }

    private JsonValue.JsonObject sourceFile(String run) {
        def files = EngineWiring.files(store)
        def field = new FillField(new FieldName("attachment"), null, null, FieldKind.FILE, null, null, true,
                null, [], [new FileExtension(".txt")], 100L)
        def path = new FillPath([new FillPath.Named(new FieldName("attachment"))])
        def marked = new JsonValue.JsonObject([new JsonValue.JsonMember("attachment",
                new JsonValue.JsonObject([new JsonValue.JsonMember("upload", new JsonValue.JsonNumber(0G)),
                        new JsonValue.JsonMember("name", new JsonValue.JsonString("Demo Sample.txt"))]))])
        def batch = files.begin(runId(run), groupId(GROUP), field)
        batch.upload(0, 0, "Demo Sample.txt", new ByteArrayInputStream("file bytes".bytes))
        def verified = batch.finish()
        def filled = FileFillings.of(runId(run), groupId(GROUP), [field], marked, [(path): verified])
        assert filled instanceof FilledFields
        filled.values()
    }

    private void fileField(String version, String ownerKind, String side, int position, String name,
                           String parent = null, Integer most = null) {
        def field = UUID.randomUUID().toString()
        StepRows.field(store, [id: field, owner: version, ownerKind: ownerKind, side: side,
                               position: position, name: name, kind: 'file', mustBe: true,
                               standing: ownerKind == 'question' && side == 'gives' ? 'always' : null,
                               parent: parent, most: most])
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set file_types = array['.txt'], " +
                "file_maximum_bytes = 100 where declaration_field_id = ?::uuid").params(field).update()
    }

    private void answerAttachments(EngineWiring wiring, String child, int count = 1, boolean many = false) {
        def markers = (0..<count).collect {
            new JsonValue.JsonObject([
                    new JsonValue.JsonMember('upload', new JsonValue.JsonNumber(BigDecimal.valueOf(it))),
                    new JsonValue.JsonMember('name', new JsonValue.JsonString("Demo Sample ${it}.txt" as String))])
        }
        def values = new JsonValue.JsonObject([
                new JsonValue.JsonMember('note', new JsonValue.JsonString('Demo Sample note')),
                new JsonValue.JsonMember('attachment', many ? new JsonValue.JsonArray(markers) : markers[0])])
        def document = new JsonValue.JsonObject([
                new JsonValue.JsonMember('values', values),
                new JsonValue.JsonMember('why', new JsonValue.JsonString('Done.'))])
        String wire = '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n' +
                CanonicalJson.write(document) + '\r\n'
        (0..<count).each {
            wire += '--Aa\r\nContent-Disposition: form-data; name="upload' + it +
                    '"; filename="ignored.txt"\r\nContent-Type: text/plain\r\n\r\nfile bytes\r\n'
        }
        wire += '--Aa--\r\n'
        def request = new MockHttpServletRequest('PUT', '/')
        request.setContentType('multipart/form-data; boundary=Aa')
        request.setContent(wire.getBytes(StandardCharsets.ISO_8859_1))
        ReadyMultipartFiles.open(request, 8192).withCloseable { multipart ->
            def json = multipart.document()
            wiring.acts.answerFiles(groupId(GROUP), runId(child),
                    new WorkflowStepId(UUID.fromString(TreeWorkflows.WRITE_NOTE)), 1, new UserId('003701'),
                    ConstantJson.sent(json.path('values').toString()), json.path('why').asString(), multipart)
        }
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a last ordinary text output promotes an earlier file inside a composite in its own transaction"() {
        given:
        fileField(TreeWorkflows.NOTE_QUESTION_VERSION, 'question', 'gives', 2, 'attachment')
        [TreeWorkflows.NOTE_VERSION, TreeWorkflows.ESCALATE_VERSION].each { version ->
            def composite = UUID.randomUUID().toString()
            StepRows.field(store, [id: composite, owner: version, ownerKind: 'workflow', side: 'gives',
                                   position: 2, name: 'bundle', kind: 'fields', mustBe: true])
            fileField(version, 'workflow', 'gives', 1, 'attachment', composite)
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: 'workflow',
                                   side: 'gives', parent: composite, position: 2, name: 'text', mustBe: true])
        }
        def question = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        def late = UUID.randomUUID().toString()
        StepRows.question(store, question, questionVersion, GROUP, 'Demo Sample finish')
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: 'question',
                               side: 'gives', position: 1, name: 'late', mustBe: true, standing: 'always'])
        def release = CodeStepsHeld.NONE
        if (producer == 'code') {
            // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
            store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
            StepRows.codeStep(store, late, TreeWorkflows.NOTE_VERSION, 2, 'finish', 'send_reply', 'code', 1)
            release = CodeStepsHeld.of(new ScriptedCodeStep('send_reply', [],
                    [SpecCodeStep.standing('late', new FieldShape.Text(1000))], false,
                    { ignored -> ConstantJson.sent('{"late":"Demo Sample text"}') }))
        } else {
            StepRows.step(store, late, TreeWorkflows.NOTE_VERSION, 2, 'finish', questionVersion, producer, 1)
        }
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.NOTE_VERSION,
                null, 'bundle.attachment', TreeWorkflows.WRITE_NOTE, 'attachment')
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.NOTE_VERSION,
                null, 'bundle.text', late, 'late')
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.ESCALATE_VERSION,
                null, 'bundle', TreeWorkflows.NOTED, 'bundle')
        def model = new ScriptedModelCalls()
        if (producer == 'model') {
            model.answering(new CallOutcome.CameBack('{"values":{"late":"Demo Sample text"},"confidences":{}}',
                    12, 4, true, false, null, null))
        }
        def wiring = new EngineWiring(store, model, release)
        wirings << wiring
        started(wiring, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTED_TICKET)
        wiring.drained()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def child = store.texts('select run_id::text from runs where parent_run_id = ?::uuid', ROOT)[0]
        def gate = wiring.occupied()

        when:
        answerAttachments(wiring, child)

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count('select count(*) from run_file_holders where run_id = ?::uuid', child) == 1
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count('select count(*) from run_file_holders where run_id = ?::uuid', ROOT) == 0

        when:
        gate.countDown()
        if (producer == 'person') {
            wiring.acts.answer(groupId(GROUP), runId(child), new WorkflowStepId(UUID.fromString(late)), 1,
                    new UserId('003701'), ConstantJson.sent('{"late":"Demo Sample text"}'), 'Done.')
        }
        wiring.drained()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def fileId = store.texts('select file_id::text from stored_files')[0]
        // DB-SPECIFIC: PostgreSQL ::text casts, xmin system column.
        def finalTx = store.texts("select value.xmin::text from production_values value " +
                "left join declaration_fields field on field.declaration_field_id = value.declaration_field_id " +
                "where coalesce(value.field_name, field.name) = 'late'")[0]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, xmin system column.
        def rootTx = store.texts('select xmin::text from run_file_holders where run_id = ?::uuid', ROOT)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, xmin system column.
        def childTx = store.texts('select xmin::text from run_file_holders where run_id = ?::uuid', child)
        def reading = new RunSteps(store.session, new GroupRoles(store.session), wiring.snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new com.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)
        def result = reading.step(groupId(GROUP), runId(ROOT),
                new WorkflowStepId(UUID.fromString(TreeWorkflows.NOTED)), new UserId('003701'))
        def output = result.cameOut().find { it.field() == 'bundle' }
        def opened = EngineWiring.files(store).open(runId(ROOT), groupId(GROUP), UUID.fromString(fileId))

        then:
        rootTx == [finalTx]
        childTx.size() == 1
        childTx[0] != finalTx
        output.standing().value().get('attachment').get('id').asString() == fileId
        output.standing().value().get('text').asString() == 'Demo Sample text'
        opened.content().readAllBytes() == 'file bytes'.bytes

        cleanup:
        gate?.countDown()
        opened?.content()?.close()

        where:
        producer << ['person', 'model', 'code']
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "an unrelated answer does not revisit completed sibling files as their number grows"() {
        given:
        def statements = Collections.synchronizedList([])
        store = new LibraryStore(new CountingDataSource(store.database, statements))
        fileField(TreeWorkflows.NOTE_QUESTION_VERSION, 'question', 'gives', 2, 'attachment', null, 5)
        fileField(TreeWorkflows.NOTE_VERSION, 'workflow', 'gives', 2, 'attachment', null, 5)
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.NOTE_VERSION,
                null, 'attachment', TreeWorkflows.WRITE_NOTE, 'attachment')
        def entry = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        StepRows.workflow(store, entry, version, GROUP, 'Demo Sample siblings')
        (1..siblings).each { index ->
            def step = UUID.randomUUID().toString()
            store.step(step, version, index, TreeWorkflows.NOTE_VERSION, 'workflow')
            StepRows.binding(store, UUID.randomUUID().toString(), version, step, 'ticket', null, null,
                    '"Demo Sample ticket"')
        }
        def question = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        def last = UUID.randomUUID().toString()
        StepRows.question(store, question, questionVersion, GROUP, 'Demo Sample last')
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: 'question',
                               side: 'gives', position: 1, name: 'text', mustBe: true, standing: 'always'])
        StepRows.step(store, last, version, siblings + 1, 'last', questionVersion, 'person', 1)
        def wiring = wired()
        started(wiring, entry, version)
        wiring.drained()
        (1..siblings).each {
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, LIMIT.
            def child = store.texts("select run_id::text from runs where parent_run_id = ?::uuid " +
                    "order by number desc limit 1", ROOT)[0]
            answerAttachments(wiring, child, 5, true)
            wiring.drained()
        }
        // DB-SPECIFIC: PostgreSQL ::text casts, xmin system column.
        def before = store.texts('select run_id::text || file_id::text || xmin::text from run_file_holders order by run_id, file_id')
        assert before.size() == siblings * 10
        statements.clear()

        when:
        wiring.acts.answer(groupId(GROUP), runId(ROOT), new WorkflowStepId(UUID.fromString(last)), 1,
                new UserId('003701'), ConstantJson.sent('{"text":"Demo Sample done"}'), 'Done.')
        wiring.drained()
        def actionSql = new ArrayList<>(statements)

        then:
        actionSql.count { it.contains('select run.run_id, run.root_run_id, run.parent_run_id') } == 3
        !actionSql.any { it.contains('with recursive file_fields') }
        !actionSql.any { it.contains('run_file_holders') }
        // DB-SPECIFIC: PostgreSQL ::text casts, xmin system column.
        store.texts('select run_id::text || file_id::text || xmin::text from run_file_holders order by run_id, file_id') == before

        where:
        siblings << [1, 8]
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a fully refused child file review adds no promotion snapshot or holder work"() {
        given:
        def statements = Collections.synchronizedList([])
        store = new LibraryStore(new CountingDataSource(store.database, statements))
        fileField(TreeWorkflows.NOTE_QUESTION_VERSION, 'question', 'gives', 2, 'attachment')
        fileField(TreeWorkflows.NOTE_VERSION, 'workflow', 'gives', 2, 'attachment')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set standing = 'never' where entry_version_id = ?::uuid " +
                "and name = 'attachment'").params(TreeWorkflows.NOTE_QUESTION_VERSION).update()
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.NOTE_VERSION,
                null, 'attachment', TreeWorkflows.WRITE_NOTE, 'attachment')
        def reviewer = UUID.randomUUID().toString()
        store.person(reviewer, '008777', 'Demo Sample reviewer')
        store.member(GROUP, reviewer, 'overseer')
        def wiring = wired()
        started(wiring, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTED_TICKET)
        wiring.drained()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def child = store.texts('select run_id::text from runs where parent_run_id = ?::uuid', ROOT)[0]
        answerAttachments(wiring, child)
        wiring.drained()
        // DB-SPECIFIC: PostgreSQL ::text casts, xmin system column.
        def before = store.texts('select run_id::text || file_id::text || xmin::text from run_file_holders order by run_id, file_id')
        assert before.size() == 1
        statements.clear()

        when:
        wiring.acts.review(groupId(GROUP), runId(child),
                new WorkflowStepId(UUID.fromString(TreeWorkflows.WRITE_NOTE)), 1, new UserId('008777'),
                [attachment: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, 'Please replace it.')])
        wiring.drained()
        def actionSql = new ArrayList<>(statements)

        then:
        actionSql.count { it.contains('select run.run_id, run.root_run_id, run.parent_run_id') } == 3
        !actionSql.any { it.contains('with recursive file_fields') }
        !actionSql.any { it.contains('run_file_holders') }
        // DB-SPECIFIC: PostgreSQL ::text casts, xmin system column.
        store.texts('select run_id::text || file_id::text || xmin::text from run_file_holders order by run_id, file_id') == before
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select outcome::text from review_decisions where not for_length") == ['refused']
    }

    def "a file bound from the parent is held once more by the child and downloads with the same bytes"() {
        given:
        fileBindings()
        fileField(TreeWorkflows.NOTE_VERSION, 'workflow', 'gives', 2, 'attachment')
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.NOTE_VERSION,
                null, 'attachment', null, 'attachment')
        def wiring = wired()
        String fileId = null

        when:
        store.transactions().executeWithoutResult {
            TreeWorkflows.run(store, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, '{}')
            def file = sourceFile(ROOT)
            fileId = StoredFile.fromKept(file.members()[0].value()).id().toString()
            def startedWith = new JsonValue.JsonObject([
                    new JsonValue.JsonMember("ticket", new JsonValue.JsonString("Demo Sample ticket")),
                    file.members()[0]])
            // DB-SPECIFIC: PostgreSQL ::uuid casts, jsonb type.
            store.session.sql("update runs set started_with = cast(? as jsonb) where run_id = ?::uuid")
                    .params(CanonicalJson.write(startedWith), ROOT).update()
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId(ROOT)).orElseThrow())
        }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def child = store.texts("select run_id::text from runs where parent_run_id = ?::uuid", ROOT)[0]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def holderRuns = store.texts("select run_id::text from run_file_holders where file_id = ?::uuid order by run_id",
                fileId)
        def opened = EngineWiring.files(store).open(runId(child), groupId(GROUP), UUID.fromString(fileId))

        then:
        holderRuns == [ROOT, child].sort()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from stored_files where file_id = ?::uuid", fileId) == 1
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from file_contents where file_id = ?::uuid", fileId) == 1
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, xmin system column.
        store.texts('select xmin::text from run_file_holders where file_id = ?::uuid', fileId).unique().size() == 1
        opened.content().readAllBytes() == "file bytes".bytes
        triesOf(TreeWorkflows.WRITE_NOTE) == ["1 person system open"]

        cleanup:
        opened?.content()?.close()
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a grandchild file output is held through each parent for the workflow result and next code input"() {
        given:
        def answerFile = UUID.randomUUID().toString()
        def outputFile = UUID.randomUUID().toString()
        [[answerFile, TreeWorkflows.NOTE_QUESTION_VERSION, "question", "gives", 2],
         [outputFile, TreeWorkflows.NOTE_VERSION, "workflow", "gives", 2]].each {
            id, version, ownerKind, side, position ->
            StepRows.field(store, [id: id, owner: version, ownerKind: ownerKind, side: side,
                                   position: position, name: "attachment", kind: "file", mustBe: true,
                                   standing: side == "gives" && ownerKind == "question" ? "always" : null])
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("update declaration_fields set file_types = array['.txt'], " +
                    "file_maximum_bytes = 100 where declaration_field_id = ?::uuid").params(id).update()
        }
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.NOTE_VERSION,
                null, "attachment", TreeWorkflows.WRITE_NOTE, "attachment")
        def bridge = UUID.randomUUID().toString()
        def bridgeVersion = UUID.randomUUID().toString()
        def bridgeStep = UUID.randomUUID().toString()
        StepRows.workflow(store, bridge, bridgeVersion, GROUP, "Demo Sample carrier")
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: bridgeVersion,
                               ownerKind: "workflow", side: "takes", position: 1,
                               name: "ticket", limit: 4000, mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: bridgeVersion,
                               ownerKind: "workflow", side: "gives", position: 1,
                               name: "note", limit: 1000, mustBe: true])
        def bridgeFile = UUID.randomUUID().toString()
        StepRows.field(store, [id: bridgeFile, owner: bridgeVersion, ownerKind: "workflow",
                               side: "gives", position: 2, name: "attachment", kind: "file", mustBe: true])
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set file_types = array['.txt'], " +
                "file_maximum_bytes = 100 where declaration_field_id = ?::uuid").params(bridgeFile).update()
        store.step(bridgeStep, bridgeVersion, 1, TreeWorkflows.NOTE_VERSION, "workflow")
        StepRows.binding(store, UUID.randomUUID().toString(), bridgeVersion,
                bridgeStep, "ticket", null, "ticket")
        StepRows.binding(store, UUID.randomUUID().toString(), bridgeVersion,
                null, "note", bridgeStep, "note")
        StepRows.binding(store, UUID.randomUUID().toString(), bridgeVersion,
                null, "attachment", bridgeStep, "attachment")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set pinned_version_id = ?::uuid " +
                "where workflow_step_id = ?::uuid").params(bridgeVersion, TreeWorkflows.NOTED).update()
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum type code_step.
        store.session.sql("""
                update workflow_steps set kind = 'code_step', code_step = cast(? as code_step),
                       pinned_version_id = null, pinned_kind = null, producer = 'code'
                 where workflow_step_id = ?::uuid
                """).params("send_reply", TreeWorkflows.CHECK).update()
        StepRows.binding(store, UUID.randomUUID().toString(), TreeWorkflows.ESCALATE_VERSION,
                TreeWorkflows.CHECK, "attachment", TreeWorkflows.NOTED, "attachment")
        def code = new ScriptedCodeStep("send_reply",
                [SpecCodeStep.given("text", new FieldShape.Text(1000)),
                 SpecCodeStep.given("attachment", new FieldShape.File([new FileExtension(".txt")], 100L))],
                [SpecCodeStep.standing("receipt", new FieldShape.Text(32))], false,
                { value -> new JsonValue.JsonObject([new JsonValue.JsonMember("receipt",
                        new JsonValue.JsonString("Read."))]) })
        def wiring = new EngineWiring(store, new ScriptedModelCalls(), CodeStepsHeld.of(code))
        wirings << wiring
        started(wiring, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTED_TICKET)
        wiring.drained()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def child = store.texts("select run_id::text from runs where parent_run_id = ?::uuid", ROOT)[0]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def grandchild = store.texts("select run_id::text from runs where parent_run_id = ?::uuid", child)[0]
        String wire = '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n' +
                '{"values":{"note":"A note.","attachment":{"upload":0,"name":"Demo Sample.txt"}},' +
                '"why":"Written."}' +
                '\r\n--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: text/plain\r\n\r\nfile bytes\r\n--Aa--\r\n'
        def request = new MockHttpServletRequest('PUT', '/')
        request.setContentType('multipart/form-data; boundary=Aa')
        request.setContent(wire.getBytes(StandardCharsets.ISO_8859_1))

        when:
        ReadyMultipartFiles.open(request, 2048).withCloseable { multipart ->
            def json = multipart.document()
            wiring.acts.answerFiles(groupId(GROUP), runId(grandchild),
                    new WorkflowStepId(UUID.fromString(TreeWorkflows.WRITE_NOTE)), 1, new UserId("003701"),
                    ConstantJson.sent(json.path('values').toString()), json.path('why').asString(), multipart)
        }
        wiring.drained()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def fileId = store.texts("select file_id::text from stored_files where original_name = 'Demo Sample.txt'")[0]
        def reading = new RunSteps(store.session, new GroupRoles(store.session), wiring.snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new com.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)
        def workflow = reading.step(groupId(GROUP), runId(ROOT),
                new WorkflowStepId(UUID.fromString(TreeWorkflows.NOTED)), new UserId("003701"))
        def middle = reading.step(groupId(GROUP), runId(child),
                new WorkflowStepId(UUID.fromString(bridgeStep)), new UserId("003701"))
        def next = reading.step(groupId(GROUP), runId(ROOT),
                new WorkflowStepId(UUID.fromString(TreeWorkflows.CHECK)), new UserId("003701"))
        def output = workflow.cameOut().find { it.field() == "attachment" }
        def middleOutput = middle.cameOut().find { it.field() == "attachment" }
        def input = next.wentIn().find { it.input() == "attachment" }
        def opened = EngineWiring.files(store).open(runId(ROOT), groupId(GROUP), UUID.fromString(fileId))
        def openedMiddle = EngineWiring.files(store).open(runId(child), groupId(GROUP), UUID.fromString(fileId))

        then:
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select run_id::text from run_file_holders where file_id = ?::uuid order by run_id", fileId) ==
                [ROOT, child, grandchild].sort()
        // DB-SPECIFIC: PostgreSQL ::text casts, xmin system column, JSON/JSONB ->>.
        def answerTx = store.texts("select value.xmin::text from production_values value " +
                "where value.value ->> 'id' = ?", fileId)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, xmin system column.
        def holderTx = store.texts("select holder.xmin::text from run_file_holders holder " +
                "where holder.file_id = ?::uuid order by holder.run_id", fileId)
        answerTx.size() == 1
        holderTx == [answerTx[0], answerTx[0], answerTx[0]]
        middleOutput.standing().value().get("id").asString() == fileId
        openedMiddle.content().readAllBytes() == "file bytes".bytes
        output.standing().value().get("id").asString() == fileId
        output.standing().value().get("name").asString() == "Demo Sample.txt"
        output.standing().value().get("type") == null
        output.standing().value().get("size").asLong() == 10L
        code.runs.get() == 1
        input.value().get("id").asString() == fileId
        input.value().get("name").asString() == "Demo Sample.txt"
        input.value().get("type") == null
        input.value().get("size").asLong() == 10L
        input.earlierShape() == null
        opened.content().readAllBytes() == "file bytes".bytes

        cleanup:
        opened?.content()?.close()
        openedMiddle?.content()?.close()
    }

    def "a file id held by another run cannot be laundered through the parent binding into a child"() {
        given:
        fileBindings()
        def source = "00000008-0000-4000-8000-0000000037f1"
        def fileId = null
        JsonValue.JsonObject file = null
        store.transactions().executeWithoutResult {
            StepRows.run(store, source, GROUP, 7, TreeWorkflows.ESCALATE,
                    TreeWorkflows.ESCALATE_VERSION, CAT, '{}')
            file = sourceFile(source)
            fileId = StoredFile.fromKept(file.members()[0].value()).id().toString()
        }
        def wiring = wired()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def before = store.texts("select run_id::text || ' ' || file_id::text from run_file_holders")
        def startedWith = CanonicalJson.write(new JsonValue.JsonObject([
                new JsonValue.JsonMember("ticket", new JsonValue.JsonString("Demo Sample ticket")),
                file.members()[0]]))

        when:
        store.transactions().executeWithoutResult {
            TreeWorkflows.run(store, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, startedWith)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId(ROOT)).orElseThrow())
        }

        then:
        thrown(RuntimeException)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select run_id::text || ' ' || file_id::text from run_file_holders") == before
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where run_id = ?::uuid", ROOT) == 0
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where parent_run_id = ?::uuid", ROOT) == 0
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a run beneath done makes its step done, and the step after takes its value from the run that made it"() {
        given:
        def wiring = wired()
        started(wiring, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION, TreeWorkflows.NOTED_TICKET)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def child = store.texts("select run_id::text from runs where parent_run_id = ?::uuid", ROOT)[0]

        when:
        StepRows.answered(store, TreeWorkflows.WRITE_NOTE, CAT, "Noted.", [(TreeWorkflows.NOTE_GIVES): '"A note."'])
        wiring.engine.goOn(groupId(GROUP), runId(ROOT))

        then:
        position(tree(wiring), TreeWorkflows.NOTED) == new StepPosition.Done()
        triesOf(TreeWorkflows.CHECK) == ["1 person system open"]
        // DB-SPECIFIC: PostgreSQL ::uuid casts; || converts enum and UUID operands to text.
        store.texts("""
                select taken.source_run_step_kind || ' ' || made.run_id
                  from production_inputs taken
                  join run_steps held on held.run_step_id = taken.run_step_id
                  join production_values value on value.production_value_id = taken.source_production_value_id
                  join productions made on made.production_id = value.production_id
                 where held.workflow_step_id = ?::uuid
                """, TreeWorkflows.CHECK) == ["workflow ${child}" as String]

        and:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("""
                select count(*) from reviews review join productions try on try.production_id = review.production_id
                 where try.run_id = ?::uuid
                """, ROOT) == 0
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where root_run_id = ?::uuid", ROOT) == 2
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a run beneath giving nothing back lets the run above go on to its next step"() {
        given:
        def wiring = wired()
        started(wiring, TreeWorkflows.QUIET, TreeWorkflows.QUIET_VERSION)

        when:
        StepRows.answered(store, TreeWorkflows.SILENT_ASK, CAT, "Asked.", [(ASK_GIVES): '"Yes."'])
        wiring.engine.goOn(groupId(GROUP), runId(ROOT))

        then:
        position(tree(wiring), TreeWorkflows.QUIETED) == new StepPosition.Done()
        triesOf(TreeWorkflows.AFTER_QUIET) == ["1 person system open"]

        and:
        triesOf(TreeWorkflows.QUIETED) == []
        failures() == []
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a step that would start a run deeper than the deployment allows fails instead, starting none and offering nothing"() {
        given:
        def wiring = wired(most)

        when:
        started(wiring, TreeWorkflows.LOOP, TreeWorkflows.LOOP_VERSION)
        wiring.drained()

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts; || converts integer operands to text.
        store.texts("select number || ' ' || depth from runs where root_run_id = ?::uuid order by number", ROOT) ==
                numbered
        failures() == ["${most - 1} too_deep - true" as String]

        and:
        def read = tree(wiring)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def deepest = read.find(runId(store.texts(
                "select run_id::text from runs where root_run_id = ?::uuid and depth = ?", ROOT, most - 1)[0]))
                .orElseThrow()
        def failedStep = deepest.run().steps()[0]
        def failed = StepPositions.of(deepest.run(), failedStep)
        failed == new StepPosition.Failed(new StepFailure.Recorded(RunStepFailureReason.TOO_DEEP, null),
                failed.since())
        StepAct.values().findAll { it.refusal(StepGround.of(deepest.run(), failedStep, failed, null)) == null } == []

        and:
        def top = position(read, TreeWorkflows.LOOP_STEP)
        deepestOf(top) == failed
        (top instanceof StepPosition.Beneath) == (most > 1)
        top.nextTry() == null
        StepAct.values().findAll {
            it.refusal(StepGround.of(read.run(), read.run().steps()[0], top, null)) == null
        } == []
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from productions where root_run_id = ?::uuid", ROOT) == 0

        where:
        most || numbered
        1    || ["1 0"]
        2    || ["1 0", "2 1"]
        3    || ["1 0", "2 1", "3 2"]
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a step too deep fails rather than being held, though what it would run is stopped"() {
        given:
        store.stopped(TreeWorkflows.BACK, CAT)
        def wiring = wired(1)

        when:
        started(wiring, TreeWorkflows.LOOP, TreeWorkflows.LOOP_VERSION)

        then:
        failures() == ["0 too_deep - true"]

        and:
        store.count("select count(*) from run_step_holds") == 0
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where root_run_id = ?::uuid", ROOT) == 1
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a step whose run beneath would not go too deep is held while what it would run is stopped"() {
        given:
        store.stopped(TreeWorkflows.BACK, CAT)
        def wiring = wired(2)

        when:
        started(wiring, TreeWorkflows.LOOP, TreeWorkflows.LOOP_VERSION)

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select reason::text from run_step_holds where released_at is null") == ["entry_stopped"]

        and:
        failures() == []
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where root_run_id = ?::uuid", ROOT) == 1
    }

    // Limit recursive workflow descent so it cannot retain the tree and group numbering locks indefinitely.
    @spock.lang.Tag("postgres-schema-changing")
    def "one planning of a tree starts one run beneath, and the next is started in a transaction of its own after it commits"() {
        given:
        def wiring = wired(4)
        def gate = wiring.occupied()

        when:
        started(wiring, TreeWorkflows.LOOP, TreeWorkflows.LOOP_VERSION)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        def afterStart = store.count("select count(*) from runs where root_run_id = ?::uuid", ROOT)
        gate.countDown()
        wiring.drained()

        then:
        afterStart == 2
        // DB-SPECIFIC: PostgreSQL ::uuid casts; || converts integer operands to text.
        store.texts("select number || ' ' || depth from runs where root_run_id = ?::uuid order by number", ROOT) ==
                ["1 0", "2 1", "3 2", "4 3"]
        failures() == ["3 too_deep - true"]
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a stop landing between one run beneath and the next leaves the next unstarted"() {
        given:
        def wiring = wired(4)
        def gate = wiring.occupied()
        started(wiring, TreeWorkflows.LOOP, TreeWorkflows.LOOP_VERSION)

        when:
        RunRows.stopped(store, ROOT, CAT)
        gate.countDown()
        wiring.drained()

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts; || converts integer operands to text.
        store.texts("select number || ' ' || depth from runs where root_run_id = ?::uuid order by number", ROOT) ==
                ["1 0", "2 1"]

        and:
        failures() == []
        store.count("select count(*) from run_step_holds") == 0
    }
}
