package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.entryId
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.model.ModelMode
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.run.Ceiling
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.ModelChoice
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.Producer
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.domain.workflow.StepLabel
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.CountingDataSource
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import com.lilradish.lite.testutil.library.LibraryStore
import org.libprunus.core.error.ApiErrorException
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification


class WorkflowDraftsIntegrationSpec extends Specification {

    // DB-SPECIFIC: SQL concatenations below use PostgreSQL's text || non-string overload
    // to render integer/bigint limits, boolean flags, UUID authors and native step_kind labels.

    static final String GROUP = "00000003-0000-4000-8000-000000000b01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000b02"


    static final String ANN = "00000002-0000-4000-8000-000000000b01"

    static final UserId ANN_USER = new UserId("000b01")

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000b01"

    static final String DRAFT = "00000007-0000-4000-8000-000000000b01"

    static final String QUESTION = "00000006-0000-4000-8000-000000000b02"

    static final String ASKED = "00000007-0000-4000-8000-000000000b02"

    static final String ASKED_RETIRED = "00000007-0000-4000-8000-000000000b03"

    static final String ASKED_DRAFT = "00000007-0000-4000-8000-000000000b04"

    static final String TARGET = "00000006-0000-4000-8000-000000000b05"

    static final String LED = "00000007-0000-4000-8000-000000000b05"

    static final String LED_RETIRED = "00000007-0000-4000-8000-000000000b06"

    static final String LIST = "00000006-0000-4000-8000-000000000b07"

    static final String LISTED = "00000007-0000-4000-8000-000000000b07"

    static final String OTHER_QUESTION = "00000006-0000-4000-8000-000000000b08"

    static final String OTHER_ASKED = "00000007-0000-4000-8000-000000000b08"

    static final String CONTENTLESS_WORKFLOW = "00000006-0000-4000-8000-000000000b09"


    static final String CONTENTLESS = "00000007-0000-4000-8000-000000000b09"

    static final String SEARCH_QUESTION = "00000006-0000-4000-8000-000000000b11"

    static final String SEARCH_ASKED = "00000007-0000-4000-8000-000000000b11"

    static final SearchWay WEB_SEARCH = new SearchWay("web_search", new ModelToolName("web_search"), 200, 4000, 2000, 1000)

    static final String KEPT_STEP = "0000000c-0000-4000-8000-000000000b01"

    static final String KEPT_ROUTE = "0000000c-0000-4000-8000-000000000b02"

    static final String KEPT_CASE = "0000000d-0000-4000-8000-000000000b01"

    static final Map<String, Object> COMPLAINT_INPUT =
            [target: "complaint", source: new FlowBody.SentSource.Input(Pointer.parse("grievance"))]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    WorkflowDrafts drafts

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    def setup() {
        store = LibraryStore.forCase(server, "workflow_drafts_" + (++databasesMade), specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        def roles = new GroupRoles(store.session)
        drafts = holding(LibraryStore.CODE_STEPS)
        store.person(ANN, "000b01")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "operator")
        store.member(OTHER_GROUP, ANN, "owner")
        store.entry(WORKFLOW, GROUP, "workflow", "Handle a complaint")
        store.version(DRAFT, WORKFLOW, 1, FIRST_STEWARD)
        store.content(DRAFT, "workflow")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(LISTED, LIST, 1)
        store.content(LISTED, "reference_list")
        term(LISTED, 1, "Billing")
        term(LISTED, 2, "Delivery")
        store.entry(QUESTION, GROUP, "question", "Classify")
        [ASKED_RETIRED, ASKED].eachWithIndex { version, index ->
            store.seeded(version, QUESTION, index + 1, version == ASKED_RETIRED)
            store.content(version, "question", "Say which category it is.")
            field(version, "question", "takes", 1, "complaint", "text", 4000, null, true, null)
            field(version, "question", "gives", 1, "category", "term", null, LISTED, true, "never")
            field(version, "question", "gives", 2, "summary", "text", 500, null, true, "always")
        }
        store.version(ASKED_DRAFT, QUESTION, 3, ANN)
        store.content(ASKED_DRAFT, "question")
        store.entry(TARGET, GROUP, "workflow", "Escalate")
        [LED_RETIRED, LED].eachWithIndex { version, index ->
            store.seeded(version, TARGET, index + 1, version == LED_RETIRED)
            store.content(version, "workflow")
            field(version, "workflow", "takes", 1, "complaint", "text", 4000, null, true, null)
        }
        store.entry(OTHER_QUESTION, OTHER_GROUP, "question", "Elsewhere")
        store.seeded(OTHER_ASKED, OTHER_QUESTION, 1)
        store.content(OTHER_ASKED, "question", "Say.")
        store.entry(SEARCH_QUESTION, GROUP, "question", "Find context")
        store.seeded(SEARCH_ASKED, SEARCH_QUESTION, 1)
        store.content(SEARCH_ASKED, "question", "Find background relevant to the complaint and its summary.")
        store.session.sql("update question_versions set search_way = 'web_search' where entry_version_id = ?::uuid")
                .param(SEARCH_ASKED).update()
        field(SEARCH_ASKED, "question", "takes", 1, "complaint", "text", 4000, null, true, null)
        field(SEARCH_ASKED, "question", "takes", 2, "summary", "text", 500, null, false, null)
        WEB_SEARCH.outputs().eachWithIndex { output, index ->
            field(SEARCH_ASKED, "question", "gives", index + 1, output.name().value(), "text",
                    (output.shape() as FieldShape.Text).longest(), null, output.demand().mustBe(), "never")
        }
    }

    def "declares a half of the workflow whole, each field saying whether it must be given and none how it stands"() {
        given:
        def gives = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [
                new Field(new FieldName("summary"), null, null, new FieldShape.Text(500), new HowMany.One(),
                        new Demand.Given(false)),
                new Field(new FieldName("category"), null, null, new FieldShape.Term(versionId(LISTED)), new HowMany.One(),
                        new Demand.Given(true))])

        when:
        def answered = drafts.declare(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, 1, gives, [null, null])

        then:
        answered.stored().gives().declaration() == gives
        answered.stored().takes().declaration().fields().isEmpty()
        StoredWorkflow.read(store.session, versionId(DRAFT)).get().gives().declaration() == gives
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select name || ' ' || must_be_given || ' ' || coalesce(standing::text, '-') from declaration_fields where entry_version_id = ?::uuid order by position", DRAFT) ==
                ["summary false -", "category true -"]
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select created_by::text from entry_version_writers where entry_version_id = ?::uuid", DRAFT) == [ANN]
    }

    def "file declarations with different type counts survive the same stored half"() {
        given:
        def takes = new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), [
                new Field(new FieldName("pictures"), null, null,
                        new FieldShape.File([new FileExtension(".PNG")], 1_048_576L),
                        new HowMany.Many(3), new Demand.Given(true)),
                new Field(new FieldName("documents"), null, null,
                        new FieldShape.File([new FileExtension(".pdf"), new FileExtension(".txt")],
                                2_097_152L), new HowMany.One(), new Demand.Given(false))])

        when:
        def answered = drafts.declare(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, 1,
                takes, [null, null])
        def read = StoredWorkflow.read(store.session, versionId(DRAFT)).get().takes().declaration()

        then:
        answered.stored().takes().declaration() == takes
        read == takes
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; array_to_string().
        store.texts("""
                select name || ' ' || array_to_string(file_types, ',') || ' ' || file_maximum_bytes
                  from declaration_fields where entry_version_id = ?::uuid order by position
                """, DRAFT) == ["pictures .png 1048576", "documents .pdf,.txt 2097152"]
    }


    def "a half sent with more keys read than fields is refused as a caller's fault, and nothing is written"() {
        given:
        def before = store.contents()

        when:
        drafts.declare(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, 1,
                new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), []), [null])

        then:
        def failed = thrown(IllegalArgumentException)
        failed.message == "FieldWriting was handed more keys read than fields"
        store.contents() == before
    }


    def "writes every step in the order sent, and reads each back as it was written"() {
        given:
        field(DRAFT, "workflow", "gives", 1, "summary", "text", 500, null, true, null)

        when:
        def answered = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow(
                [asking(null), routing(null, null), beneath(), unchosen()],
                [bound("summary", new FlowBody.SentSource.Step(0, Pointer.parse("summary")))]))
        def read = StoredWorkflow.read(store.session, versionId(DRAFT)).get()

        then:
        answered.stored() == read
        read.steps()*.name() == ["classify", "route", "escalate", "later"].collect { new StepId(it) }
        read.steps()[0].runs() == new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, versionId(ASKED))
        read.steps()[0].producer() == new Producer.Model(new ModelChoice(new ModelName("general"), null), true)
        read.steps()[0].tries() == 3
        read.steps()[0].reviewer() == new ModelChoice(new ModelName("general"), new ModelMode("research"))
        read.steps()[0].bindings()*.target() == [Pointer.parse("complaint")]
        read.steps()[0].bindings()*.source() == [new BindingSource.WorkflowInput(Pointer.parse("grievance"))]
        read.steps()[2].runs() == new StoredWorkflow.Runs.Pinned(EntryKind.WORKFLOW, versionId(LED))
        read.steps()[2].producer() == null
        read.steps()[2].bindings()*.source() == [new BindingSource.Written(new JsonValue.JsonString("Kind regards"))]
        read.steps()[3].runs() == new StoredWorkflow.Runs.Unchosen()
        read.outputs()*.source() == [new BindingSource.StepOutput(read.steps()[0].id(), Pointer.parse("summary"))]
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "a display name is written verbatim and cleared while machine bindings refer to each newly written step"() {
        given:
        field(DRAFT, "workflow", "gives", 1, "summary", "text", 500, null, true, null)
        def outputs = [bound("summary", new FlowBody.SentSource.Step(0, Pointer.parse("summary")))]
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([asking(null)], outputs))
        def original = StoredWorkflow.read(store.session, versionId(DRAFT)).get()
        def step = original.steps()[0]
        def asked = asking(step.id().toString())
        def sent = new FlowBody.SentStep(step.id(), step.name(), label == null ? null : new StepLabel(label),
                asked.runs(), asked.producer(), asked.tries(), asked.reviewer(), asked.bindings())

        when:
        def answered = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([sent], outputs, 2))
        def read = StoredWorkflow.read(store.session, versionId(DRAFT)).get()

        then:
        answered.stored() == read
        read.steps()[0].id() != step.id()
        read.steps()[0].name() == new StepId("classify")
        read.steps()[0].label()?.value() == label
        read.steps()[0].bindings()*.target() == [Pointer.parse("complaint")]
        read.steps()[0].bindings()*.source() == [new BindingSource.WorkflowInput(Pointer.parse("grievance"))]
        read.outputs()*.target() == [Pointer.parse("summary")]
        read.outputs()*.source() == [new BindingSource.StepOutput(read.steps()[0].id(), Pointer.parse("summary"))]
        store.texts("select coalesce(label, '<none>') from workflow_steps where entry_version_id = ?::uuid", DRAFT) == [label ?: "<none>"]

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([new FlowBody.SentStep(read.steps()[0].id(), step.name(), null, asked.runs(), asked.producer(), asked.tries(), asked.reviewer(), asked.bindings())], outputs, 3))
        def cleared = StoredWorkflow.read(store.session, versionId(DRAFT)).get()

        then:
        cleared.steps()[0].id() != read.steps()[0].id()
        cleared.steps()[0].name() == new StepId("classify")
        cleared.steps()[0].label() == null
        cleared.steps()[0].bindings()*.target() == [Pointer.parse("complaint")]
        cleared.steps()[0].bindings()*.source() == [new BindingSource.WorkflowInput(Pointer.parse("grievance"))]
        cleared.outputs()*.target() == [Pointer.parse("summary")]
        cleared.outputs()*.source() == [new BindingSource.StepOutput(cleared.steps()[0].id(), Pointer.parse("summary"))]
        store.texts("select coalesce(label, '<none>') from workflow_steps where entry_version_id = ?::uuid", DRAFT) == ["<none>"]

        where:
        label << [null, "\u78ba\u8a8d\u3059\u308b", "إرسال الرد", "Join\u200der\u202e", "😀" * 128]
    }

    def "the store refuses a new or existing display collision naming the other displayed step and writes nothing"() {
        given:
        def first = new FlowBody.SentStep(null, new StepId("first_step"), null, new FlowBody.SentRuns.Unchosen(), null, null, null, [])
        def other = new FlowBody.SentStep(null, new StepId("other_step"), null, new FlowBody.SentRuns.Unchosen(), null, null, null, [])
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([first, other], []))
        def original = StoredWorkflow.read(store.session, versionId(DRAFT)).get()
        def before = store.contents()
        def firstId = existing ? original.steps()[0].id() : null
        def secondId = existing ? original.steps()[1].id() : null

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([
                new FlowBody.SentStep(firstId, first.name(), new StepLabel(label), first.runs(), null, null, null, []),
                new FlowBody.SentStep(secondId, other.name(), otherLabel == null ? null : new StepLabel(otherLabel), other.runs(), null, null, null, [])], [], 2))

        then:
        def refused = thrown(ContentProblemsRefusal)
        refused.errorCode() == LibraryRefusal.VERSION_CONTENT_DOES_NOT_HOLD.code()
        refused.problemTotal() == expected.size()
        refused.problems() == expected.collect { at -> new ContentProblem(ContentProblemCode.STEP_LABEL_REPEATED,
                new ContentPlace.AtStepNamed(at == 0 ? firstId : secondId, at == 0 ? otherShown : label), null, null) }
        refused.pins() == []
        StoredWorkflow.read(store.session, versionId(DRAFT)).get() == original
        store.contents() == before

        where:
        existing | label        | otherLabel  | otherShown   || expected
        false    | "OTHER STEP" | null        | "other step" || [0]
        true     | "OTHER STEP" | null        | "other step" || [0]
        false    | "OTHER STEP" | "\u5225\u306e\u8868\u793a"    | "\u5225\u306e\u8868\u793a"      || [0]
        true     | "OTHER STEP" | "\u5225\u306e\u8868\u793a"    | "\u5225\u306e\u8868\u793a"      || [0]
        false    | "Straße"     | "STRASSE"   | "STRASSE"    || [0, 1]
        true     | "Straße"     | "STRASSE"   | "STRASSE"    || [0, 1]
        false    | "ς"          | "σ"         | "σ"          || [0, 1]
        true     | "Équipe"     | "E\u0301QUIPE" | "E\u0301QUIPE" || [0, 1]
    }

    def "writes and reads Unicode labels that the database keeps distinct without changing their machine names"() {
        given:
        def label = new String(Character.toChars(left))
        def otherLabel = new String(Character.toChars(right))
        def first = new FlowBody.SentStep(null, new StepId("first_step"), new StepLabel(label),
                new FlowBody.SentRuns.Unchosen(), null, null, null, [])
        def other = new FlowBody.SentStep(null, new StepId("other_step"), new StepLabel(otherLabel),
                new FlowBody.SentRuns.Unchosen(), null, null, null, [])

        when:
        def answered = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([first, other], []))
        def read = StoredWorkflow.read(store.session, versionId(DRAFT)).get()

        then:
        answered.stored() == read
        read.steps() == [
                new StoredWorkflow.Step(read.steps()[0].id(), new StepId("first_step"), new StepLabel(label),
                        new StoredWorkflow.Runs.Unchosen(), null, null, null, []),
                new StoredWorkflow.Step(read.steps()[1].id(), new StepId("other_step"), new StepLabel(otherLabel),
                        new StoredWorkflow.Runs.Unchosen(), null, null, null, [])]
        read.steps()[0].id() != read.steps()[1].id()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("select name || ':' || label from workflow_steps where entry_version_id = ?::uuid order by position")
                .param(DRAFT).query(String).list() == ["first_step:" + label, "other_step:" + otherLabel]
        !store.session.sql("select search_fold(:left) = search_fold(:right)")
                .param("left", label).param("right", otherLabel).query(Boolean).single()

        where:
        left | right
        0xa7ce | 0xa7cf
        0xa7d2 | 0xa7d3
        0xa7d4 | 0xa7d5
        0x16ea0 | 0x16ebb
        0x16ea1 | 0x16ebc
        0x16ea2 | 0x16ebd
        0x16ea3 | 0x16ebe
        0x16ea4 | 0x16ebf
        0x16ea5 | 0x16ec0
        0x16ea6 | 0x16ec1
        0x16ea7 | 0x16ec2
        0x16ea8 | 0x16ec3
        0x16ea9 | 0x16ec4
        0x16eaa | 0x16ec5
        0x16eab | 0x16ec6
        0x16eac | 0x16ec7
        0x16ead | 0x16ec8
        0x16eae | 0x16ec9
        0x16eaf | 0x16eca
        0x16eb0 | 0x16ecb
        0x16eb1 | 0x16ecc
        0x16eb2 | 0x16ecd
        0x16eb3 | 0x16ece
        0x16eb4 | 0x16ecf
        0x16eb5 | 0x16ed0
        0x16eb6 | 0x16ed1
        0x16eb7 | 0x16ed2
        0x16eb8 | 0x16ed3
    }

    def "database folding preserves its declared equality and distinction across scripts"() {
        when:
        def equal = store.session.sql("select search_fold(:left) = search_fold(:right)")
                .param("left", left).param("right", right).query(Boolean).single()

        then:
        equal == databaseEqual

        where:
        left       | right       || databaseEqual
        "Review"   | "REVIEW"    || true
        "\u78ba\u8a8d"       | "\u78ba\u8a8d"        || true
        "إرسال"     | "إرسال"      || true
        "Équipe"   | "E\u0301QUIPE" || true
        "Straße"   | "STRASSE"   || true
        "ς"        | "σ"         || true
        "İ"        | "i"         || false
        "résumé"   | "resume"    || false
        "Α"        | "α"         || true
    }

    def "a route is written choosing by an earlier step, between its cases, giving back what it declares"() {
        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow(
                [asking(null), routing(null, null)], []))

        then:
        def read = StoredWorkflow.read(store.session, versionId(DRAFT)).get()
        def route = read.steps()[1].runs() as StoredWorkflow.Runs.Route
        route.discriminator().target() == null
        route.discriminator().source() == new BindingSource.StepOutput(read.steps()[0].id(), Pointer.parse("category"))
        route.cases()*.term() == ["Billing", null]
        route.cases()*.target() == [versionId(LED), versionId(LED)]
        route.cases().collect { it.bindings()*.target() } == [[Pointer.parse("complaint")], [Pointer.parse("complaint")]]
        route.cases().collect { it.bindings()*.source() } == [[new BindingSource.WorkflowInput(Pointer.parse("grievance"))],
                                                [new BindingSource.Written(new JsonValue.JsonString("Unknown"))]]
        route.gives().declaration().fields() == [new Field(new FieldName("note"), null, null, new FieldShape.Text(100),
                new HowMany.One(), new Demand.Given(true))]
    }

    /** Read back through a double, these digits and the trailing zero would be lost. */
    def "a constant of as many digits as one may hold is stored and read back to the digit"() {
        given:
        def digits = "-1234567890123456789.0123456789012345670"
        field(DRAFT, "workflow", "gives", 1, "summary", "text", 500, null, true, null)

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([],
                [bound("summary", new FlowBody.SentSource.Written(digits))]))

        then:
        def read = StoredWorkflow.read(store.session, versionId(DRAFT)).get().outputs()*.source()
        read == [new BindingSource.Written(new JsonValue.JsonNumber(new BigDecimal(digits)))]
        read.collect { ConstantJson.written((it as BindingSource.Written).constant()) } == [digits]
    }

    def "every step is written as the store holds it, by the caller, and every binding beside it"() {
        given:
        field(DRAFT, "workflow", "gives", 1, "summary", "text", 500, null, true, null)

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow(
                [asking(null), routing(null, null), beneath(), unchosen()],
                [bound("summary", new FlowBody.SentSource.Step(0, Pointer.parse("summary")))]))

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("""
                select position || ' ' || name || ' ' || coalesce(kind::text, '-') || ' ' ||
                       coalesce(producer::text, '-') || ' ' || coalesce(producer_mode, '-') || ' ' || tells_what_happened
                  from workflow_steps where entry_version_id = ?::uuid order by position""", DRAFT) ==
                ["1 classify entry model ordinary true", "2 route route - - false", "3 escalate entry - - false",
                 "4 later - - - false"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from bindings where entry_version_id = ?::uuid", DRAFT) == 6
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select distinct created_by::text from workflow_steps") == [ANN]
    }

    def "a search is written with its way, question pin, standings and input bindings, and read back as written"() {
        when:
        def answered = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([asking(null), searching()], []))
        def read = StoredWorkflow.read(store.session, versionId(DRAFT)).get()

        then:
        answered.stored() == read
        def search = read.steps()[1].runs() as StoredWorkflow.Runs.Search
        search.way() == "web_search"
        search.standings() == [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.NEVER]
        search.question() == versionId(SEARCH_ASKED)
        read.steps()[1].producer() == new Producer.Model(new ModelChoice(new ModelName("general"), null), false)
        read.steps()[1].tries() == 2
        read.steps()[1].bindings()*.source() as Set == [
                new BindingSource.WorkflowInput(Pointer.parse("grievance")),
                new BindingSource.StepOutput(read.steps()[0].id(), Pointer.parse("summary"))] as Set
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("""
                select target_path || ' ' || source_path || ' ' || created_by
                  from bindings where workflow_step_id = ?::uuid order by target_path""", read.steps()[1].id()) ==
                ["complaint grievance ${ANN}", "summary summary ${ANN}"]*.toString()
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("""
                select coalesce(search_references_standing::text, '-') || ' ' ||
                       coalesce(search_other_standing::text, '-') || ' ' || kind
                  from workflow_steps where entry_version_id = ?::uuid and position = 2""", DRAFT) == ["- - search"]
    }

    def "question content reads stay batched across #count search pins of #versions distinct versions"() {
        given:
        def second = "00000007-0000-4000-8000-000000000b12"
        if (versions == 2) {
            store.seeded(second, SEARCH_QUESTION, 2)
            store.content(second, "question", "Find invented context for another reader.")
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("update question_versions set search_way = 'web_search' where entry_version_id = ?::uuid")
                    .param(second).update()
            WEB_SEARCH.outputs().eachWithIndex { output, index ->
                field(second, "question", "gives", index + 1, output.name().value(), "text",
                        (output.shape() as FieldShape.Text).longest(), null, output.demand().mustBe(), "never")
            }
        }
        def statements = []
        def countedSource = new CountingDataSource(store.database, statements)
        def counted = JdbcClient.create(countedSource)
        def transactions = store.transactionManager(countedSource)
        def roles = new GroupRoles(counted)
        def released = new ReleasedCodeSteps(LibraryStore.CODE_STEPS)
        def ways = new SearchWays([WEB_SEARCH])
        def reader = new Workflows(counted, roles, LibraryStore.MODELS, released, LibraryStore.FILES, ways, transactions)
        def writer = new WorkflowDrafts(counted,
                new Drafts(counted, store.transactions(countedSource), roles),
                reader, released, ways)
        def steps = (0..<count).collect { index ->
            new FlowBody.SentStep(null, new StepId("find_" + index), null,
                    new FlowBody.SentRuns.Search("web_search", versionId(index % versions == 0 ? SEARCH_ASKED : second), [:]),
                    new Producer.Person(), 1, null, [])
        }

        when:
        def answered = writer.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow(steps, []))

        then:
        answered.stored().steps().size() == count
        answered.stored().steps()*.runs()*.question() as Set ==
                (versions == 1 ? [versionId(SEARCH_ASKED)] : [versionId(SEARCH_ASKED), versionId(second)]) as Set
        def questionReads = statements.findAll { it.contains("from question_versions question") }
        questionReads.size() == 3
        questionReads.every { it.contains("any(cast(? as uuid[]))") }
        statements.every { !it.contains("question.drawing") }
        StoredWorkflow.read(store.session, versionId(DRAFT)).get().steps().size() == count

        where:
        count | versions
        1     | 1
        8     | 1
        256   | 1
        8     | 2
        256   | 2
    }

    def "an unavailable or ineligible search pin is refused before any draft content changes: #caseName"() {
        given:
        def way = new SearchWay("other_search", new ModelToolName("other_search"), 200, 4000, 2000, 1000)
        if (caseName == "absent release") {
            drafts = holding(LibraryStore.CODE_STEPS, [])
        } else if (caseName == "wrong marker") {
            drafts = holding(LibraryStore.CODE_STEPS, [way])
        }
        def selected = new FlowBody.SentStep(null, new StepId("find_context"), null,
                new FlowBody.SentRuns.Search(caseName == "wrong marker" ? way.name() : "web_search",
                        versionId(question), [:]), new Producer.Person(), 1, null, [])
        def before = store.contents()

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([selected], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_PINNABLE
        !(refused instanceof SearchPinRefusal)
        store.contents() == before
        StoredWorkflow.read(store.session, versionId(DRAFT)).get().revision() == 1
        StoredWorkflow.read(store.session, versionId(DRAFT)).get().steps().isEmpty()

        where:
        caseName           | question
        "absent release"   | SEARCH_ASKED
        "wrong marker"     | SEARCH_ASKED
        "unmarked"         | ASKED
        "foreign question" | OTHER_ASKED
        "missing version"  | "00000007-0000-4000-8000-000000000fff"
        "draft question"   | ASKED_DRAFT
        "workflow version" | LED
    }

    def "an admitted search pin with a changed answer shape names its readable version and writes nothing"() {
        given:
        def changed = new SearchWay("web_search", new ModelToolName("web_search"), 201, 4000, 2000, 1000)
        drafts = holding(LibraryStore.CODE_STEPS, [changed])
        def before = store.contents()

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([searching([])], []))

        then:
        def refused = thrown(SearchPinRefusal)
        refused.errorCode() == RefusalCode.SEARCH_QUESTION_DOES_NOT_MATCH
        refused.way() == "web_search"
        refused.question().value() == "Find context"
        refused.number() == 1
        refused.message.contains("Find context · Version 1 can't be used to search with web_search")
        !refused.message.contains(SEARCH_ASKED)
        store.contents() == before
        StoredWorkflow.read(store.session, versionId(DRAFT)).get().revision() == 1
    }

    def "a draft holding a search is written again, every binding drawn anew, and none left once that step is gone"() {
        given:
        def first = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([asking(null), searching()], []))
        def before = first.stored().steps()[1].bindings()*.id()

        when:
        def again = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([asking(null), searching()], [], first.stored().revision()))
        def after = again.stored().steps()[1].bindings()*.id()
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([beneath()], [], again.stored().revision()))

        then:
        before.size() == 2
        after.size() == 2
        after.disjoint(before)
        // DB-SPECIFIC: PostgreSQL ::uuid casts bind the rebuilt identifiers.
        store.count("select count(*) from bindings where binding_id in (?::uuid, ?::uuid)",
                after[0], after[1]) == 0
        StoredWorkflow.read(store.session, versionId(DRAFT)).get().steps()*.name() == [new StepId("escalate")]
    }

    def "a retired search question is kept only by its actual existing step"() {
        given:
        def first = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([searching([])], []))
        def kept = first.stored().steps()[0]
        store.retired(SEARCH_ASKED, ANN)
        def sent = new FlowBody.SentStep(kept.id(), kept.name(), kept.label(),
                new FlowBody.SentRuns.Search("web_search", versionId(SEARCH_ASKED), kept.runs().standings()),
                kept.producer(), kept.tries(), kept.reviewer(), [])

        when:
        def again = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([sent], [], first.stored().revision()))

        then:
        again.stored().steps()[0].id() != kept.id()
        again.stored().steps()[0].name() == kept.name()
        again.stored().steps()[0].producer() == kept.producer()
        again.stored().steps()[0].tries() == kept.tries()
        again.stored().steps()[0].runs().standings() == kept.runs().standings()
        again.stored().steps()[0].runs().question() == versionId(SEARCH_ASKED)
        again.stored().revision() == first.stored().revision() + 1

        when:
        def before = store.contents()
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([searching([])], [], again.stored().revision()))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_PINNABLE
        store.contents() == before
        StoredWorkflow.read(store.session, versionId(DRAFT)).get() == again.stored()
    }

    def "the same question version can be pinned by an ordinary step and a searching step"() {
        given:
        def ordinary = new FlowBody.SentStep(null, new StepId("ask_directly"), null,
                new FlowBody.SentRuns.Pinned(EntryKind.QUESTION, versionId(SEARCH_ASKED)), new Producer.Person(), 1,
                null, [bound("complaint", new FlowBody.SentSource.Written('"A fictional delivery query"'))])

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([ordinary, searching([bound("complaint", new FlowBody.SentSource.Written('"A fictional delivery query"'))])], []))

        then:
        def steps = StoredWorkflow.read(store.session, versionId(DRAFT)).get().steps()
        steps[0].runs() == new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, versionId(SEARCH_ASKED))
        steps[1].runs().question() == versionId(SEARCH_ASKED)
        steps[1].runs().way() == "web_search"
        steps*.bindings()*.size() == [1, 1]
    }


    def "a route holding a case for every term its list offers and a fallback is written, and one more refused"() {
        given:
        def before = store.contents()
        def cases = { List<String> terms ->
            terms.collect { new FlowBody.SentCase(null, it, versionId(LED), []) }
        }

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([asking(null), routedOver(cases(["Billing", "Delivery", "Refund", null]))], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CASES_PAST_TERMS
        store.contents() == before

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([asking(null), routedOver(cases(["Billing", "Delivery", null]))], []))

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from route_cases where entry_version_id = ?::uuid", DRAFT) == 3
    }


    def "a route choosing by nothing yet holds as many cases as any list offers terms, and a fallback"() {
        given:
        def cases = { int count ->
            (1..count).collect { new FlowBody.SentCase(null, "term_" + it, null, []) } +
                    [new FlowBody.SentCase(null, null, null, [])]
        }

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([routedOver(cases(257), null)], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CASES_PAST_TERMS

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([routedOver(cases(256), null)], []))

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from route_cases where entry_version_id = ?::uuid", DRAFT) == 257
    }


    def "more bindings than what they fill declares fields are refused, and nothing is written"() {
        given:
        def before = store.contents()

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, sent)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BINDINGS_PAST_INPUTS
        store.contents() == before

        where:
        sent << [
                flow([askingAlso(bound("channel", new FlowBody.SentSource.Written('"email"')))], []),
                flow([asking(null), routedOver([new FlowBody.SentCase(null, "Billing", versionId(LED), [
                        bound("complaint", new FlowBody.SentSource.Input(Pointer.parse("complaint"))),
                        bound("channel", new FlowBody.SentSource.Written('"email"'))])])], []),
                flow([asking(null), routedOver([], new FlowBody.SentSource.Step(0, Pointer.parse("category")),
                        [bound("note", new FlowBody.SentSource.Written('"x"'))])], []),
                flow([asking(null)], [bound("summary", new FlowBody.SentSource.Step(0, Pointer.parse("summary")))]),
                flow([asking(null), searching([
                        bound("complaint", new FlowBody.SentSource.Written('"x"')),
                        bound("summary", new FlowBody.SentSource.Written('"x"')),
                        bound("channel", new FlowBody.SentSource.Written('"email"'))])], []),
        ]
    }


    def "writing the steps again replaces every step, case, field and binding the draft held, and nothing else"() {
        given:
        field(DRAFT, "workflow", "takes", 1, "complaint", "text", 4000, null, true, null)
        def first = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([asking(null), routing(null, null)], []))
        def before = StoredWorkflow.read(store.session, versionId(DRAFT)).get()

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([beneath()], [], first.stored().revision()))

        then:
        def after = StoredWorkflow.read(store.session, versionId(DRAFT)).get()
        after.steps()*.name() == [new StepId("escalate")]
        after.steps()[0].id() != before.steps()[0].id()
        after.takes() == before.takes()
        store.count("select count(*) from route_cases") == 0
        store.count("select count(*) from declaration_fields where workflow_step_id is not null") == 0
        store.count("select count(*) from bindings") == 1
    }


    def "a step and a case keep the version they pin already, retired since, sent back under the key they were read under"() {
        given:
        pinnedStep(KEPT_STEP, ASKED_RETIRED)
        routedTo(KEPT_ROUTE, KEPT_CASE, LED_RETIRED)

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow(
                [asking(KEPT_STEP, ASKED_RETIRED), routing(KEPT_ROUTE, KEPT_CASE, LED_RETIRED)], []))

        then:
        def read = StoredWorkflow.read(store.session, versionId(DRAFT)).get()
        read.steps()[0].runs() == new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, versionId(ASKED_RETIRED))
        (read.steps()[1].runs() as StoredWorkflow.Runs.Route).cases()*.target() == [versionId(LED_RETIRED), versionId(LED)]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from workflow_steps where workflow_step_id = ?::uuid", KEPT_STEP) == 0
    }


    def "a step or a case pinning what is not this group's in service of its kind is refused, and nothing is written"() {
        given:
        pinnedStep(KEPT_STEP, ASKED_RETIRED)
        def before = store.contents()

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow(steps, []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_PINNABLE
        store.contents() == before

        where:
        steps << [
                [asking(null, ASKED_RETIRED)],
                [asking(KEPT_ROUTE, ASKED_RETIRED)],
                [asking(null, ASKED_DRAFT)],
                [asking(null, OTHER_ASKED)],
                [asking(null, LED)],
                [asking(null, "00000007-0000-4000-8000-000000000bff")],
                [asking(null), routing(null, null, LED_RETIRED)],
                [asking(null), routing(null, null, ASKED)],
        ]
    }


    @spock.lang.Tag("postgres-schema-changing")
    def "a code step is written only where it was published to the group, by key or to every group"() {
        given:
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'send_reply'").update()
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'archive'").update()
        publish(key)

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([coded("send_reply")], []))

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select code_step::text || ' ' || producer::text || ' ' || tries from workflow_steps") ==
                ["send_reply code 1"]

        where:
        key << ["SUPPORT", null]
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a code step's values are produced by code, by a person saying it was done, or by nobody chosen yet"() {
        given:
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'send_reply'").update()
        publish("SUPPORT")

        when:
        def answered = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([coded("send_reply", producer)], []))

        then:
        answered.stored().steps()*.producer() == [producer]
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select coalesce(producer::text, '-') from workflow_steps") == [stored]

        where:
        producer             || stored
        new Producer.Code()   || "code"
        new Producer.Person() || "person"
        null                  || "-"
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a code step published to no group of this one's is refused, and nothing is written"() {
        given:
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'send_reply'").update()
        publish("BILLING")
        def before = store.contents()

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([coded("send_reply")], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CODE_STEP_NOT_PUBLISHED
        store.contents() == before
    }

    /** Nothing of another group's reaches here, so a code step taking a term from its list is as though unpublished. */
    @spock.lang.Tag("postgres-schema-changing")
    def "a code step taking a term from another group's list is refused as though not published, and nothing is written"() {
        given:
        def theirs = "00000006-0000-4000-8000-000000000b0a"
        def theirsListed = "00000007-0000-4000-8000-000000000b0a"
        store.entry(theirs, OTHER_GROUP, "reference_list", "Their categories")
        store.seeded(theirsListed, theirs, 1)
        store.content(theirsListed, "reference_list")
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'sort_out'").update()
        publish("SUPPORT", "sort_out")
        def sorting = holding(sortingBy(theirsListed))
        def before = store.contents()

        when:
        sorting.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([coded("sort_out")], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CODE_STEP_NOT_PUBLISHED
        store.contents() == before
    }

    /** A draft may hold what submitting would refuse: a list not in service is named there, not where it is written. */
    @spock.lang.Tag("postgres-schema-changing")
    def "a code step taking a term from the group's own list, or from a list not here, is written, in service or not"() {
        given:
        // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
        store.session.sql("alter type code_step add value 'sort_out'").update()
        publish("SUPPORT", "sort_out")

        when:
        holding(sortingBy(list)).flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([coded("sort_out")], []))

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select code_step::text from workflow_steps") == ["sort_out"]

        where:
        list << [LISTED, "00000007-0000-4000-8000-000000000bfe"]
    }

    def "sets how much a run may spend, and whether a run keeps its own and a raise waits on approving"() {
        when:
        def answered = drafts.ceiling(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, 1, ceiling,
                true, false)

        then:
        answered.stored().ceiling() == ceiling
        answered.stored().keepsOwnCeiling()
        !answered.stored().raiseNeedsApproval()
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid.
        store.texts("select coalesce(ceiling::text, '-') || ' ' || updated_by from workflow_versions where entry_version_id = ?::uuid", DRAFT) ==
                ["${ceiling == null ? '-' : ceiling.value()} ${ANN}" as String]

        where:
        ceiling << [new Ceiling(Ceiling.LARGEST), null]
    }

    def "sets whether runs may be helped and by what, a model run as it is stored under the store's word for that"() {
        when:
        def answered = drafts.help(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, 1, may, helper)

        then:
        answered.stored().mayBeHelped() == may
        answered.stored().helper() == helper
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select may_be_helped || ' ' || coalesce(helper_model, '-') || ' ' || coalesce(helper_mode, '-') from workflow_versions where entry_version_id = ?::uuid", DRAFT) ==
                [stored]

        where:
        may   | helper                                                               || stored
        true  | new ModelChoice(new ModelName("general"), null)                      || "true general ordinary"
        true  | new ModelChoice(new ModelName("general"), new ModelMode("research")) || "true general research"
        true  | null                                                                 || "true - -"
        false | null                                                                 || "false - -"
    }

    def "a helper named for runs that may not be helped is refused as a caller's fault, and nothing is written"() {
        given:
        def before = store.contents()

        when:
        drafts.help(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, 1, false,
                new ModelChoice(new ModelName("general"), null))

        then:
        def failed = thrown(IllegalArgumentException)
        failed.message == "WorkflowDrafts names a helper for runs that may not be helped"
        store.contents() == before
    }


    def "a ceiling written to a draft holding no workflow content fails, and nothing is written"() {
        given:
        store.entry(CONTENTLESS_WORKFLOW, GROUP, "workflow", "Archive a complaint")
        store.version(CONTENTLESS, CONTENTLESS_WORKFLOW, 1, FIRST_STEWARD)
        def before = store.contents()

        when:
        drafts.ceiling(groupId(GROUP), entryId(CONTENTLESS_WORKFLOW), versionId(CONTENTLESS), ANN_USER, 1, null, false,
                false)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Workflow version ${CONTENTLESS} holds no workflow content" as String
        store.contents() == before
    }

    def "whether runs may be helped, written to a draft holding no workflow content, fails and nothing is written"() {
        given:
        store.entry(CONTENTLESS_WORKFLOW, GROUP, "workflow", "Archive a complaint")
        store.version(CONTENTLESS, CONTENTLESS_WORKFLOW, 1, FIRST_STEWARD)
        def before = store.contents()

        when:
        drafts.help(groupId(GROUP), entryId(CONTENTLESS_WORKFLOW), versionId(CONTENTLESS), ANN_USER, 1, false, null)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Workflow version ${CONTENTLESS} holds no workflow content" as String
        store.contents() == before
    }


    def "each write answers with the version as the change writing it then reads it"() {
        when:
        def answered = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([asking(null)], []))

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        answered.stored().revision() == store.count("select revision from entry_versions where entry_version_id = ?::uuid", DRAFT)
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        answered.stored().steps()*.id() == store.texts("select workflow_step_id::text from workflow_steps").collect { UUID.fromString(it) }
        answered.problems().problems()*.code()*.published() == ["source_unknown"]
    }

    def "a workflow version that is no longer a draft is written by nobody, and holds what it held"() {
        given:
        store.submitted(DRAFT, ANN)
        def before = store.contents()

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([asking(null)], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_STANDING_REFUSES
        store.contents() == before
    }


    private static FlowBody.Sent flow(List<FlowBody.SentStep> steps, List<FlowBody.SentBinding> outputs, int revision = 1) {
        new FlowBody.Sent(revision, steps, outputs)
    }

    private static FlowBody.SentStep asking(String readAs, String pinned = ASKED) {
        new FlowBody.SentStep(readAs == null ? null : UUID.fromString(readAs), new StepId("classify"), null,
                new FlowBody.SentRuns.Pinned(EntryKind.QUESTION, versionId(pinned)),
                new Producer.Model(new ModelChoice(new ModelName("general"), null), true), 3,
                new ModelChoice(new ModelName("general"), new ModelMode("research")),
                [bound(COMPLAINT_INPUT.target as String, COMPLAINT_INPUT.source as FlowBody.SentSource)])
    }


    private static FlowBody.SentStep askingAlso(FlowBody.SentBinding extra) {
        def asked = asking(null)
        new FlowBody.SentStep(null, asked.name(), asked.label(), asked.runs(), asked.producer(), asked.tries(), asked.reviewer(),
                asked.bindings() + [extra])
    }


    private static FlowBody.SentStep routedOver(
            List<FlowBody.SentCase> cases,
            FlowBody.SentSource chosenBy = new FlowBody.SentSource.Step(0, Pointer.parse("category")),
            List<FlowBody.SentBinding> bindings = []) {
        def gives = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [])
        new FlowBody.SentStep(null, new StepId("route"), null,
                new FlowBody.SentRuns.Route(chosenBy, new DeclarationBody.Sent(gives, []), cases),
                null, null, null, bindings)
    }

    private static FlowBody.SentStep routing(String readAs, String caseReadAs, String target = LED) {
        def gives = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [
                new Field(new FieldName("note"), null, null, new FieldShape.Text(100), new HowMany.One(),
                        new Demand.Given(true))])
        new FlowBody.SentStep(readAs == null ? null : UUID.fromString(readAs), new StepId("route"), null,
                new FlowBody.SentRuns.Route(new FlowBody.SentSource.Step(0, Pointer.parse("category")),
                        new DeclarationBody.Sent(gives, [null]),
                        [new FlowBody.SentCase(caseReadAs == null ? null : UUID.fromString(caseReadAs), "Billing",
                                versionId(target), [bound("complaint", new FlowBody.SentSource.Input(Pointer.parse("grievance")))]),
                         new FlowBody.SentCase(null, null, versionId(LED),
                                 [bound("complaint", new FlowBody.SentSource.Written('"Unknown"'))])]),
                null, null, null, [])
    }

    private static FlowBody.SentStep beneath() {
        new FlowBody.SentStep(null, new StepId("escalate"), null, new FlowBody.SentRuns.Pinned(EntryKind.WORKFLOW, versionId(LED)),
                null, null, null, [bound("complaint", new FlowBody.SentSource.Written('"Kind regards"'))])
    }


    private static FlowBody.SentStep searching(
            List<FlowBody.SentBinding> bindings = [
                    bound("complaint", new FlowBody.SentSource.Input(Pointer.parse("grievance"))),
                    bound("summary", new FlowBody.SentSource.Step(0, Pointer.parse("summary")))]) {
        new FlowBody.SentStep(null, new StepId("find_context"), null,
                new FlowBody.SentRuns.Search("web_search", versionId(SEARCH_ASKED),
                        [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.NEVER]),
                new Producer.Model(new ModelChoice(new ModelName("general"), null), false), 2, null, bindings)
    }

    private static FlowBody.SentStep unchosen() {
        new FlowBody.SentStep(null, new StepId("later"), null, new FlowBody.SentRuns.Unchosen(), null, null, null, [])
    }

    private static FlowBody.SentStep coded(String name, Producer producer = new Producer.Code()) {
        new FlowBody.SentStep(null, new StepId("send"), null, new FlowBody.SentRuns.Code(name), producer, 1, null, [])
    }

    private static FlowBody.SentBinding bound(String target, FlowBody.SentSource source) {
        new FlowBody.SentBinding(Pointer.parse(target), source)
    }

    private void field(String version, String kind, String side, int position, String name, String fieldKind,
                       Integer longest, String list, Boolean mustBeGiven, String standing) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, entry_kind, declaration_side, field_kind, field_standing.
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, text_limit,
                                                term_list_version_id, must_be_given, standing, created_by)
                values (?::uuid, ?::entry_kind, ?::declaration_side, ?, ?, ?::field_kind, ?, ?::uuid, ?,
                        ?::field_standing, ?::uuid)
                """).params(version, kind, side, position, name, fieldKind, longest, list, mustBeGiven, standing, SEEDER)
                .update()
    }

    private void term(String list, int position, String term) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, 'When it is.', ?::uuid)
                """).params(list, position, term, SEEDER).update()
    }

    private void pinnedStep(String step, String pinned) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, pinned_version_id,
                                            pinned_kind, created_by)
                values (?::uuid, ?::uuid, 1, 'classify', 'entry', ?::uuid, 'question', ?::uuid)
                """).params(step, DRAFT, pinned, SEEDER).update()
    }

    private void routedTo(String step, String routeCase, String target) {
        store.routed(step, routeCase, DRAFT, 2, target)
    }

    private void publish(String key, String codeStep = "send_reply") {
        // DB-SPECIFIC: PostgreSQL :: casts to code_step, uuid.
        store.session.sql("""
                insert into code_step_publications (code_step, group_key, every_group, created_by)
                values (?::code_step, ?, ?, ?::uuid)
                """).params(codeStep, key, key == null, SEEDER).update()
    }


    private WorkflowDrafts holding(CodeSteps codeSteps, List<SearchWay> searches = [WEB_SEARCH]) {
        def roles = new GroupRoles(store.session)
        def released = new ReleasedCodeSteps(codeSteps)
        def ways = new SearchWays(searches)
        new WorkflowDrafts(store.session, new Drafts(store.session, store.transactions(), roles),
                new Workflows(store.session, roles, LibraryStore.MODELS, released, LibraryStore.FILES, ways,
                        store.transactionManager()), released, ways)
    }


    private static CodeSteps sortingBy(String list) {
        new CodeSteps([new SpecCodeStep("sort_out", [SpecCodeStep.given("reply", new FieldShape.Text(2000))],
                [SpecCodeStep.standing("category", new FieldShape.Term(versionId(list)))], false)])
    }
}
