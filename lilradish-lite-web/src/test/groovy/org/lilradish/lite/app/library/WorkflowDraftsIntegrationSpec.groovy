package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Writing a workflow draft's halves, its steps with what fills them, its ceiling and whether its runs may be
 * helped, on a real server running the real baseline, and reading each back as the store then holds it.
 */
class WorkflowDraftsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000b01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000b02"

    /** An operator: may write an entry. */
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

    /** A draft's version row with no workflow content beside it. */
    static final String CONTENTLESS = "00000007-0000-4000-8000-000000000b09"

    static final String KEPT_STEP = "0000000c-0000-4000-8000-000000000b01"

    static final String KEPT_ROUTE = "0000000c-0000-4000-8000-000000000b02"

    static final String KEPT_CASE = "0000000d-0000-4000-8000-000000000b01"

    static final Map<String, Object> COMPLAINT_INPUT =
            [target: "complaint", source: new FlowBody.SentSource.Input(Pointer.parse("grievance"))]

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    WorkflowDrafts drafts

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "workflow_drafts_" + (++databasesMade))
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
        store.texts("select name || ' ' || must_be_given || ' ' || coalesce(standing::text, '-') from declaration_fields where entry_version_id = ?::uuid order by position", DRAFT) ==
                ["summary false -", "category true -"]
        store.texts("select created_by::text from entry_version_writers where entry_version_id = ?::uuid", DRAFT) == [ANN]
    }

    /** A key read under that names more fields than were sent is a caller gone wrong, and nothing is written. */
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

    /**
     * Written back as it was sent, so reading it is what was sent: every step in its place, what each runs and who
     * produces and reviews it, what fills each input. The model running as it is is stored under the store's word.
     */
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
        store.texts("""
                select position || ' ' || name || ' ' || coalesce(kind::text, '-') || ' ' ||
                       coalesce(producer::text, '-') || ' ' || coalesce(producer_mode, '-') || ' ' || tells_what_happened
                  from workflow_steps where entry_version_id = ?::uuid order by position""", DRAFT) ==
                ["1 classify entry model ordinary true", "2 route route - - false", "3 escalate entry - - false",
                 "4 later - - - false"]
        store.count("select count(*) from bindings where entry_version_id = ?::uuid", DRAFT) == 6
        store.texts("select distinct created_by::text from workflow_steps") == [ANN]
    }

    /** The list a route chooses by offers two terms, so it holds two cases on them and a fallback at most. */
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
        store.count("select count(*) from route_cases where entry_version_id = ?::uuid", DRAFT) == 3
    }

    /** Where the list a route chooses by is not known yet, any list's most bounds its cases. */
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
        store.count("select count(*) from route_cases where entry_version_id = ?::uuid", DRAFT) == 257
    }

    /** Each binding fills a field of its own, so what it fills declares at least as many fields as it has bindings. */
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
        ]
    }

    /** Every key is drawn anew, so what the page read before names nothing now; the halves are left as they were. */
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

    /** A pin is kept only by the row that holds it already; everything else pins anew, and only what is in service here. */
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
        store.count("select count(*) from workflow_steps where workflow_step_id = ?::uuid", KEPT_STEP) == 0
    }

    /**
     * A version retired and pinned by nothing here, a draft, another group's, one of the wrong kind and one
     * nobody holds are one refusal, and the draft is left as it was.
     */
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

    /** A code step no migration published to this group is refused where it is written, not only at submitting. */
    def "a code step is written only where it was published to the group, by key or to every group"() {
        given:
        store.session.sql("alter type code_step add value 'send_reply'").update()
        store.session.sql("alter type code_step add value 'archive'").update()
        publish(key)

        when:
        drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER, flow([coded("send_reply")], []))

        then:
        store.texts("select code_step::text || ' ' || producer::text || ' ' || tries from workflow_steps") ==
                ["send_reply code 1"]

        where:
        key << ["SUPPORT", null]
    }

    def "a code step's values are produced by code, by a person saying it was done, or by nobody chosen yet"() {
        given:
        store.session.sql("alter type code_step add value 'send_reply'").update()
        publish("SUPPORT")

        when:
        def answered = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([coded("send_reply", producer)], []))

        then:
        answered.stored().steps()*.producer() == [producer]
        store.texts("select coalesce(producer::text, '-') from workflow_steps") == [stored]

        where:
        producer             || stored
        new Producer.Code()   || "code"
        new Producer.Person() || "person"
        null                  || "-"
    }

    def "a code step published to no group of this one's is refused, and nothing is written"() {
        given:
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
    def "a code step taking a term from another group's list is refused as though not published, and nothing is written"() {
        given:
        def theirs = "00000006-0000-4000-8000-000000000b0a"
        def theirsListed = "00000007-0000-4000-8000-000000000b0a"
        store.entry(theirs, OTHER_GROUP, "reference_list", "Their categories")
        store.seeded(theirsListed, theirs, 1)
        store.content(theirsListed, "reference_list")
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
    def "a code step taking a term from the group's own list, or from a list not here, is written, in service or not"() {
        given:
        store.session.sql("alter type code_step add value 'sort_out'").update()
        publish("SUPPORT", "sort_out")

        when:
        holding(sortingBy(list)).flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([coded("sort_out")], []))

        then:
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

    /** A version whose draft row stands without the workflow content it should hold is a store gone wrong. */
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

    /** The answer is what the change itself read, holding the draft, and it names the revision it read. */
    def "each write answers with the version as the change writing it then reads it"() {
        when:
        def answered = drafts.flow(groupId(GROUP), entryId(WORKFLOW), versionId(DRAFT), ANN_USER,
                flow([asking(null)], []))

        then:
        answered.stored().revision() == store.count("select revision from entry_versions where entry_version_id = ?::uuid", DRAFT)
        answered.stored().steps()*.id() == store.texts("select workflow_step_id::text from workflow_steps").collect { UUID.fromString(it) }
        answered.problems()*.code()*.published() == ["source_unknown"]
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

    /** Written over the revision a draft no other write has touched yet stands at, unless another is named. */
    private static FlowBody.Sent flow(List<FlowBody.SentStep> steps, List<FlowBody.SentBinding> outputs, int revision = 1) {
        new FlowBody.Sent(revision, steps, outputs)
    }

    private static FlowBody.SentStep asking(String readAs, String pinned = ASKED) {
        new FlowBody.SentStep(readAs == null ? null : UUID.fromString(readAs), new StepId("classify"),
                new FlowBody.SentRuns.Pinned(EntryKind.QUESTION, versionId(pinned)),
                new Producer.Model(new ModelChoice(new ModelName("general"), null), true), 3,
                new ModelChoice(new ModelName("general"), new ModelMode("research")),
                [bound(COMPLAINT_INPUT.target as String, COMPLAINT_INPUT.source as FlowBody.SentSource)])
    }

    /** The step {@link #asking} writes, filling one field more than the question it runs takes. */
    private static FlowBody.SentStep askingAlso(FlowBody.SentBinding extra) {
        def asked = asking(null)
        new FlowBody.SentStep(null, asked.name(), asked.runs(), asked.producer(), asked.tries(), asked.reviewer(),
                asked.bindings() + [extra])
    }

    /** A route giving back nothing, choosing by {@code chosenBy} between {@code cases}. */
    private static FlowBody.SentStep routedOver(
            List<FlowBody.SentCase> cases,
            FlowBody.SentSource chosenBy = new FlowBody.SentSource.Step(0, Pointer.parse("category")),
            List<FlowBody.SentBinding> bindings = []) {
        def gives = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [])
        new FlowBody.SentStep(null, new StepId("route"),
                new FlowBody.SentRuns.Route(chosenBy, new DeclarationBody.Sent(gives, []), cases),
                null, null, null, bindings)
    }

    private static FlowBody.SentStep routing(String readAs, String caseReadAs, String target = LED) {
        def gives = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), [
                new Field(new FieldName("note"), null, null, new FieldShape.Text(100), new HowMany.One(),
                        new Demand.Given(true))])
        new FlowBody.SentStep(readAs == null ? null : UUID.fromString(readAs), new StepId("route"),
                new FlowBody.SentRuns.Route(new FlowBody.SentSource.Step(0, Pointer.parse("category")),
                        new DeclarationBody.Sent(gives, [null]),
                        [new FlowBody.SentCase(caseReadAs == null ? null : UUID.fromString(caseReadAs), "Billing",
                                versionId(target), [bound("complaint", new FlowBody.SentSource.Input(Pointer.parse("grievance")))]),
                         new FlowBody.SentCase(null, null, versionId(LED),
                                 [bound("complaint", new FlowBody.SentSource.Written('"Unknown"'))])]),
                null, null, null, [])
    }

    private static FlowBody.SentStep beneath() {
        new FlowBody.SentStep(null, new StepId("escalate"), new FlowBody.SentRuns.Pinned(EntryKind.WORKFLOW, versionId(LED)),
                null, null, null, [bound("complaint", new FlowBody.SentSource.Written('"Kind regards"'))])
    }

    private static FlowBody.SentStep unchosen() {
        new FlowBody.SentStep(null, new StepId("later"), new FlowBody.SentRuns.Unchosen(), null, null, null, [])
    }

    private static FlowBody.SentStep coded(String name, Producer producer = new Producer.Code()) {
        new FlowBody.SentStep(null, new StepId("send"), new FlowBody.SentRuns.Code(name), producer, 1, null, [])
    }

    private static FlowBody.SentBinding bound(String target, FlowBody.SentSource source) {
        new FlowBody.SentBinding(Pointer.parse(target), source)
    }

    private void field(String version, String kind, String side, int position, String name, String fieldKind,
                       Integer longest, String list, Boolean mustBeGiven, String standing) {
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, text_limit,
                                                term_list_version_id, must_be_given, standing, created_by)
                values (?::uuid, ?::entry_kind, ?::declaration_side, ?, ?, ?::field_kind, ?, ?::uuid, ?,
                        ?::field_standing, ?::uuid)
                """).params(version, kind, side, position, name, fieldKind, longest, list, mustBeGiven, standing, SEEDER)
                .update()
    }

    private void term(String list, int position, String term) {
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, 'When it is.', ?::uuid)
                """).params(list, position, term, SEEDER).update()
    }

    private void pinnedStep(String step, String pinned) {
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
        store.session.sql("""
                insert into code_step_publications (code_step, group_key, every_group, created_by)
                values (?::code_step, ?, ?, ?::uuid)
                """).params(codeStep, key, key == null, SEEDER).update()
    }

    /** The drafts of a release holding {@code codeSteps}. */
    private WorkflowDrafts holding(CodeSteps codeSteps) {
        def roles = new GroupRoles(store.session)
        def released = new ReleasedCodeSteps(codeSteps)
        new WorkflowDrafts(store.session, new Drafts(store.session, store.transactions(), roles),
                new Workflows(store.session, roles, LibraryStore.MODELS, released, store.transactionManager()), released)
    }

    /** A code step taking a reply, and giving back a category from {@code list}. */
    private static CodeSteps sortingBy(String list) {
        new CodeSteps([new SpecCodeStep("sort_out", [SpecCodeStep.given("reply", new FieldShape.Text(2000))],
                [SpecCodeStep.standing("category", new FieldShape.Term(versionId(list)))], false)])
    }
}
