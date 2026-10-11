package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static com.lilradish.lite.testutil.library.LibraryStore.entryId
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldHelp
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldLabel
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
import org.libprunus.core.error.ApiErrorException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Writing a question draft's instruction and either half of what it declares, on a real server running the
 * real baseline, and reading each back as the store then holds it.
 */
class QuestionDraftsIntegrationSpec extends Specification {

    // DB-SPECIFIC: SQL concatenations below use PostgreSQL's text || non-string overload
    // to render UUID authors, integer positions/thresholds and native field_standing labels.

    static final String GROUP = "00000003-0000-4000-8000-000000000901"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000902"

    /** An operator: may write an entry. */
    static final String ANN = "00000002-0000-4000-8000-000000000901"

    static final UserId ANN_USER = new UserId("000901")

    static final String QUESTION = "00000006-0000-4000-8000-000000000901"

    static final String DRAFT = "00000007-0000-4000-8000-000000000901"

    static final String LIST = "00000006-0000-4000-8000-000000000902"

    static final String LIST_IN_SERVICE = "00000007-0000-4000-8000-000000000902"

    static final String LIST_RETIRED = "00000007-0000-4000-8000-000000000903"

    static final String LIST_DRAFT = "00000007-0000-4000-8000-000000000904"

    static final String OTHER_LIST = "00000006-0000-4000-8000-000000000905"

    static final String OTHER_LIST_IN_SERVICE = "00000007-0000-4000-8000-000000000905"

    static final String SIBLING = "00000006-0000-4000-8000-000000000906"

    static final String SIBLING_IN_SERVICE = "00000007-0000-4000-8000-000000000906"

    static final String OTHER_QUESTION = "00000006-0000-4000-8000-000000000907"

    static final String OTHER_DRAFT = "00000007-0000-4000-8000-000000000907"

    static final String KEPT = "0000000b-0000-4000-8000-000000000901"

    static final String PINNING_ANOTHER = "0000000b-0000-4000-8000-000000000902"

    static final String TAKEN_SIDE = "0000000b-0000-4000-8000-000000000903"

    static final String ELSEWHERE = "0000000b-0000-4000-8000-000000000904"

    static final Declaration TAKEN = half(DeclarationSide.TAKES, [
            new Field(new FieldName("complaint"), new FieldLabel("The complaint"), new FieldHelp("As written."),
                    new FieldShape.Text(4000), new HowMany.One(), new Demand.Given(true)),
            new Field(new FieldName("sender"), null, null, new FieldShape.Nested([
                    new Field(new FieldName("name"), null, null, new FieldShape.Text(null), new HowMany.One(),
                            new Demand.Given(false)),
                    new Field(new FieldName("addresses"), null, null, new FieldShape.Text(200),
                            new HowMany.Many(3), new Demand.Given(true))]),
                    new HowMany.One(), new Demand.Given(true))])

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    QuestionDrafts drafts

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "question_drafts_" + (++databasesMade))
        drafts = new QuestionDrafts(store.session,
                new Drafts(store.session, store.transactions(), new GroupRoles(store.session)),
                new Questions(store.session, new GroupRoles(store.session), store.transactionManager(), new SearchWays([])), new SearchWays([]))
        store.person(ANN, "000901")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "operator")
        store.member(OTHER_GROUP, ANN, "owner")
        store.entry(QUESTION, GROUP, "question", "Summarise a complaint")
        store.version(DRAFT, QUESTION, 1, FIRST_STEWARD)
        store.content(DRAFT, "question")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(LIST_RETIRED, LIST, 1, true)
        store.seeded(LIST_IN_SERVICE, LIST, 2)
        store.version(LIST_DRAFT, LIST, 3, ANN)
        store.entry(OTHER_LIST, OTHER_GROUP, "reference_list", "Invoice kinds")
        store.seeded(OTHER_LIST_IN_SERVICE, OTHER_LIST, 1)
        [LIST_RETIRED, LIST_IN_SERVICE, LIST_DRAFT, OTHER_LIST_IN_SERVICE].each { store.content(it, "reference_list") }
        store.entry(SIBLING, GROUP, "question", "Classify")
        store.seeded(SIBLING_IN_SERVICE, SIBLING, 1)
    }

    def "writes the instruction as the caller's change, the caller one who wrote the draft from then on"() {
        when:
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, new Instruction("Say why.\n\tBriefly."))

        then:
        store.texts("select instruction || ' ' || updated_by from question_versions") == ["Say why.\n\tBriefly. ${ANN}" as String]
        store.count("select count(*) from question_versions where updated_at >= created_at") == 1
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select created_by::text from entry_version_writers") == [ANN]

        when: "saying nothing"
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, null)

        then: "holds no instruction, rather than an empty one"
        store.count("select count(*) from question_versions where instruction is null") == 1
    }

    def "writes and reads back an optional drawing as the caller's version change"() {
        when:
        def written = drafts.draw(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1,
                "## Case\n{{complaint}}")

        then:
        written.revision() == 2
        written.stored().drawing() == "## Case\n{{complaint}}"
        store.texts("select drawing from question_versions") == ["## Case\n{{complaint}}"]
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select updated_by::text from question_versions") == [ANN]

        when:
        def removed = drafts.draw(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, null)

        then:
        removed.revision() == 3
        removed.stored().drawing() == null
        store.count("select count(*) from question_versions where drawing is null") == 1
    }

    /** A write that waited on the draft began before the one it waited on was stamped, and never goes back past it. */
    def "an instruction written after a change stamped later than this one began keeps its time from going back"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now() and the verbose interval '1 day' literal.
        store.session.sql("""
                update question_versions set updated_at = now() + interval '1 day', updated_by = ?::uuid
                 where entry_version_id = ?::uuid
                """).params(FIRST_STEWARD, DRAFT).update()
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        def stamped = store.texts("select updated_at::text from question_versions")

        when:
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, new Instruction("Say."))

        then:
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select updated_at::text from question_versions") == stamped
        store.texts("select instruction || ' ' || updated_by from question_versions") == ["Say. ${ANN}" as String]
    }

    /** Written back as it was declared, so reading it is the same declaration, order and depth and all. */
    def "declares a half whole, each field under what holds it in declared order, as the caller's"() {
        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))

        then:
        StoredQuestion.read(store.session, versionId(DRAFT)).get().takes().declaration() == TAKEN
        store.texts("""
                select field.position || ' ' || field.name || ' ' || coalesce(parent.name, '-')
                  from declaration_fields field
                  left join declaration_fields parent on parent.declaration_field_id = field.parent_field_id
                 order by field.name
                """) == ["2 addresses sender", "1 complaint -", "1 name sender", "2 sender -"]
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select distinct created_by::text from declaration_fields") == [ANN]

        and: "the other half left holding nothing"
        store.count("select count(*) from declaration_fields where side = 'gives'") == 0
    }

    /**
     * The answer is read by the change that wrote, holding the draft: a second writer landing after it but before a
     * separate read would otherwise be answered as though it were the caller's.
     */
    def "each write answers with the version as the change writing it then reads it"() {
        when:
        def instructed = drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1,
                new Instruction("Say why."))

        then:
        instructed.revision() == 2
        instructed.stored().instruction() == new Instruction("Say why.")
        instructed.stored().takes().declaration().fields().isEmpty()

        when:
        def declared = drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, TAKEN,
                unread(TAKEN))

        then:
        declared.revision() == 3
        declared.stored().takes().declaration() == TAKEN
        declared.stored().takes().keys()*.id() ==
                // DB-SPECIFIC: PostgreSQL :: casts to text.
                store.texts("select declaration_field_id::text from declaration_fields where parent_field_id is null order by position")
                        .collect { UUID.fromString(it) }
        declared.stored().instruction() == new Instruction("Say why.")
        declared.added() == null
    }

    /** What the half held goes whole, fields held inside it with it; the other half is not touched. */
    def "declaring a half again replaces what it held, and leaves the other half as it was"() {
        given:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))
        def gives = half(DeclarationSide.GIVES, [new Field(new FieldName("summary"), null, null,
                new FieldShape.Text(1000), new HowMany.One(), new Demand.Stands(true, FieldStanding.ALWAYS, null))])
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, gives, unread(gives))
        def replaced = half(DeclarationSide.TAKES, [new Field(new FieldName("channel"), null, null,
                new FieldShape.Plain(FieldKind.YES_NO), new HowMany.One(), new Demand.Given(false))])

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, replaced, unread(replaced))

        then:
        def stored = StoredQuestion.read(store.session, versionId(DRAFT)).get()
        stored.takes().declaration() == replaced
        stored.gives().declaration() == gives
        store.count("select count(*) from declaration_fields") == 2
    }

    def "a field of terms pins this group's list in service, and reads back pinning it"() {
        given:
        def gives = half(DeclarationSide.GIVES, [
                terms("category", LIST_IN_SERVICE, new Demand.Stands(true,FieldStanding.ABOVE_CONFIDENCE, 80)),
                terms("secondary", LIST_IN_SERVICE, new Demand.Stands(true,FieldStanding.NEVER, null)),
                terms("unchosen", null, new Demand.Stands(true,null, null))])

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, gives, unread(gives))

        then:
        StoredQuestion.read(store.session, versionId(DRAFT)).get().gives().declaration() == gives
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select coalesce(term_list_version_id::text, '-') from declaration_fields order by position") ==
                [LIST_IN_SERVICE, LIST_IN_SERVICE, "-"]
    }

    /**
     * A list retired, a draft of one, another group's in service, a question in service and a version nobody
     * holds are one refusal, and what the half held is left as it was.
     */
    def "a field of terms naming anything but this group's list in service is refused, the half left as it was"() {
        given:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))
        def before = store.contents()

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, half(DeclarationSide.TAKES,
                [terms("category", target, new Demand.Given(true))]), [null])

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_PINNABLE
        store.contents() == before

        where:
        target << [LIST_RETIRED, LIST_DRAFT, OTHER_LIST_IN_SERVICE, SIBLING_IN_SERVICE, "00000009-0000-4000-8000-000000000009"]
    }

    /**
     * A list pinned while it was in service and retired since stays pinned by the field that pinned it, sent
     * back under the key it was read under; only submitting refuses it. Another field beside it pins anew.
     */
    def "a field sent back under its key keeps the list it pins, though retired since"() {
        given:
        storedPin(KEPT, DRAFT, "gives", 1, LIST_RETIRED)
        def gives = half(DeclarationSide.GIVES, [
                terms("category", LIST_RETIRED, new Demand.Stands(true,FieldStanding.NEVER, null)),
                terms("fresh", LIST_IN_SERVICE, new Demand.Stands(true,FieldStanding.ALWAYS, null))])

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, gives,
                [UUID.fromString(KEPT), null])

        then:
        StoredQuestion.read(store.session, versionId(DRAFT)).get().gives().declaration() == gives
        // DB-SPECIFIC: PostgreSQL :: casts to text.
        store.texts("select term_list_version_id::text from declaration_fields order by position") ==
                [LIST_RETIRED, LIST_IN_SERVICE]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from declaration_fields where declaration_field_id = ?::uuid", KEPT) == 0
    }

    /**
     * Kept only by the stored row that pins it: a field added beside it, a key of a row pinning another list,
     * a key of the other half's row, of another version's row, or of no row each pin anew, and are refused.
     */
    def "a retired list is refused to every field but the one whose stored row pins it"() {
        given:
        storedPin(KEPT, DRAFT, "gives", 1, LIST_RETIRED)
        storedPin(PINNING_ANOTHER, DRAFT, "gives", 2, LIST_IN_SERVICE)
        storedPin(TAKEN_SIDE, DRAFT, "takes", 1, LIST_RETIRED)
        store.entry(OTHER_QUESTION, GROUP, "question", "Elsewhere")
        store.version(OTHER_DRAFT, OTHER_QUESTION, 1, ANN)
        store.content(OTHER_DRAFT, "question")
        storedPin(ELSEWHERE, OTHER_DRAFT, "gives", 1, LIST_RETIRED)
        def before = store.contents()

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, half(DeclarationSide.GIVES, [
                terms("category", LIST_RETIRED, new Demand.Stands(true, FieldStanding.NEVER, null)),
                terms("secondary", LIST_RETIRED, new Demand.Stands(true, FieldStanding.NEVER, null))]),
                [UUID.fromString(KEPT), key == null ? null : UUID.fromString(key)])

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_PINNABLE
        store.contents() == before

        where:
        key << [null, PINNING_ANOTHER, TAKEN_SIDE, ELSEWHERE, "0000000b-0000-4000-8000-00000000090f"]
    }

    def "a half handed keys that do not match its fields one for one is refused as a caller's fault"() {
        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, keys)

        then:
        def failed = thrown(IllegalArgumentException)
        failed.message == message
        store.count("select count(*) from declaration_fields") == 0

        where:
        keys                  || message
        [null, null, null]    || "FieldWriting was handed fewer keys read than fields"
        [null] * 5            || "FieldWriting was handed more keys read than fields"
    }

    /** One statement writes every field: nested four deep under one another, each still under its own parent. */
    def "a half nested several deep is written in one, every field under the copy of the field holding it"() {
        given:
        def innermost = new Field(new FieldName("d"), null, null, new FieldShape.Text(5), new HowMany.One(),
                new Demand.Given(true))
        def nested = ["c", "b", "a"].inject(innermost) { held, name ->
            new Field(new FieldName(name), null, null, new FieldShape.Nested([held]), new HowMany.One(),
                    new Demand.Given(false))
        }

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1,
                half(DeclarationSide.TAKES, [nested]), [null] * 4)

        then:
        StoredQuestion.read(store.session, versionId(DRAFT)).get().takes().declaration().fields() == [nested]
        store.texts("""
                select field.name || ' ' || coalesce(parent.name, '-')
                  from declaration_fields field
                  left join declaration_fields parent on parent.declaration_field_id = field.parent_field_id
                 order by field.name
                """) == ["a -", "b a", "c b", "d c"]
    }

    def "a question version that is no longer a draft is written by nobody, and holds what it held"() {
        given:
        store.submitted(DRAFT, ANN)
        def before = store.contents()

        when:
        change == "instruct"
                ? drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, new Instruction("Say."))
                : drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_STANDING_REFUSES
        store.contents() == before

        where:
        change << ["instruct", "declare"]
    }

    def "an unknown complete instruction reference refuses the whole draft change, keeping its raw name"() {
        given:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))
        def before = store.contents()

        when:
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2,
                new Instruction("Read {{${name}}}."))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.INSTRUCTION_NAMES_UNKNOWN
        refused.message == "{{${name}}} isn't an input of this question."
        store.contents() == before

        where:
        name << ["unknown", "sender.name", "complaint[0]", "", " ", "<b>"]
    }

    def "known references, escaped opening braces and unfinished references are saved exactly as written"() {
        given:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))

        when:
        def written = drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2,
                new Instruction(said))

        then:
        written.stored().instruction().value() == said
        store.texts("select instruction from question_versions") == [said]
        written.revision() == 3

        where:
        said << ["Read {{complaint}}, not \\{{unknown}}.\n", "An unfinished {{unknown", "No input named."]
    }

    def "renaming by original top-level keys swaps instruction and drawing references in one draft change"() {
        given:
        def fields = ["left", "right"].collect { new Field(new FieldName(it), null, null,
                new FieldShape.Plain(FieldKind.YES_NO), new HowMany.One(), new Demand.Given(true)) }
        def taken = half(DeclarationSide.TAKES, fields)
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, taken, unread(taken))
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2,
                new Instruction("{{left}} / {{right}} / {{left}} / \\{{left}} / {{right"))
        drafts.draw(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, "## {{right}}\n{{left}}")
        def before = StoredQuestion.read(store.session, versionId(DRAFT)).get()
        def after = half(DeclarationSide.TAKES, [renamed(fields[0], "right"), renamed(fields[1], "left")])

        when:
        def written = drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 4,
                after, before.takes().keys()*.id())

        then:
        written.revision() == 5
        written.stored().takes().declaration() == after
        written.stored().instruction().value() == "{{right}} / {{left}} / {{right}} / \\{{left}} / {{right"
        written.stored().drawing() == "## {{left}}\n{{right}}"
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from entry_version_writers where entry_version_id = ?::uuid", DRAFT) == 1
    }

    def "renamed references accept exactly the prose bound, counting Unicode and every occurrence"() {
        given:
        def field = new Field(new FieldName("a"), null, null,
                new FieldShape.Plain(FieldKind.YES_NO), new HowMany.One(), new Demand.Given(true))
        def taken = half(DeclarationSide.TAKES, [field])
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, taken, unread(taken))
        def raw = new String(Character.toChars(0x1f642)).repeat(8172) + "{{a}}{{a}}"
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, new Instruction(raw))
        drafts.draw(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, raw)
        def previous = StoredQuestion.read(store.session, versionId(DRAFT)).get()
        def after = half(DeclarationSide.TAKES, [renamed(field, "longer")])

        when:
        def written = drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 4,
                after, previous.takes().keys()*.id())

        then:
        written.revision() == 5
        written.stored().takes().declaration() == after
        written.stored().takes().keys()[0].id() != previous.takes().keys()[0].id()
        written.stored().instruction().value() == raw.replace("{{a}}", "{{longer}}")
        written.stored().drawing() == written.stored().instruction().value()
        written.stored().drawing().codePointCount(0, written.stored().drawing().length()) == 8192
        // DB-SPECIFIC: PostgreSQL ::uuid cast.
        store.count("select count(*) from entry_version_writers where entry_version_id = ?::uuid", DRAFT) == 1
    }

    def "renamed references beyond the bound refuse before changing either prose or the declaration"() {
        given:
        def field = new Field(new FieldName("a"), null, null,
                new FieldShape.Plain(FieldKind.YES_NO), new HowMany.One(), new Demand.Given(true))
        def taken = half(DeclarationSide.TAKES, [field])
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, taken, unread(taken))
        def raw = new String(Character.toChars(0x1f642)).repeat(8173) + "{{a}}{{a}}"
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2,
                new Instruction(instructionTooLong ? raw : "Read {{a}}."))
        drafts.draw(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3,
                instructionTooLong ? "## {{a}}" : raw)
        def previous = StoredQuestion.read(store.session, versionId(DRAFT)).get()
        def before = store.contents()
        def after = half(DeclarationSide.TAKES, [renamed(field, "longer")])

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 4,
                after, previous.takes().keys()*.id())

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == (instructionTooLong ? RefusalCode.INSTRUCTION_UNUSABLE : RefusalCode.DRAWING_UNUSABLE)
        store.contents() == before

        when:
        def questions = new Questions(store.session, new GroupRoles(store.session), store.transactionManager(), new SearchWays([]))
        def mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new QuestionController(questions, drafts))
                .setControllerAdvice(new com.lilradish.lite.web.RefusalOutlet()).build()
        def body = '{"revision":4,"fields":[{"fieldId":"' + previous.takes().keys()[0].id() +
                '","name":"longer","label":null,"help":null,"kind":"yes_no","many":false,"most":null,"mustBeGiven":true}]}'
        def response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/groups/${GROUP}/questions/${QUESTION}/versions/${DRAFT}/takes")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)
                .requestAttr(com.lilradish.lite.web.CallerAdmission.class.name + '.caller', ANN_USER)
                .requestAttr(com.lilradish.lite.web.ActAdmission.class.name + '.group', groupId(GROUP)))
                .andReturn().response

        then:
        response.status == 400
        tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.contentAsString)
                .get("code").asString() == refused.errorCode().name()
        store.contents() == before

        where:
        instructionTooLong << [true, false]
    }

    def "inputs still named in instruction or drawing cannot be removed or replaced by new same-name fields"() {
        given:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, new Instruction(instruction))
        drafts.draw(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, drawing)
        def after = replacement ? TAKEN : half(DeclarationSide.TAKES, [])
        def before = store.contents()

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 4, after, unread(after))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.INPUT_STILL_MENTIONED
        refused.message == message
        store.contents() == before

        where:
        instruction                  | drawing            | replacement || message
        "Read {{complaint}}."         | null               | false       || "{{complaint}} is still mentioned in the instruction or how it is drawn; remove those mentions first."
        "Read."                       | "{{complaint}}"    | false       || "{{complaint}} is still mentioned in the instruction or how it is drawn; remove those mentions first."
        "{{sender}} {{complaint}}"     | "{{complaint}}"    | false       || "{{complaint}} and {{sender}} are still mentioned in the instruction or how it is drawn; remove those mentions first."
        "Read {{complaint}}."         | null               | true        || "{{complaint}} is still mentioned in the instruction or how it is drawn; remove those mentions first."
    }

    def "renaming a nested field leaves whole-input references alone, and removing their saved mentions permits removal"() {
        given:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, new Instruction("Read {{sender}}."))
        drafts.draw(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, "## {{sender}}")
        def before = StoredQuestion.read(store.session, versionId(DRAFT)).get()
        def sender = TAKEN.fields()[1]
        def children = (sender.shape() as FieldShape.Nested).fields()
        def changed = new Field(sender.name(), sender.label(), sender.help(),
                new FieldShape.Nested([renamed(children[0], "display_name"), children[1]]), sender.howMany(), sender.demand())
        def after = half(DeclarationSide.TAKES, [TAKEN.fields()[0], changed])
        def keys = [before.takes().keys()[0].id(), before.takes().keys()[1].id()] + before.takes().keys()[1].fields()*.id()

        when:
        def written = drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 4, after, keys)

        then:
        written.revision() == 5
        written.stored().takes().declaration() == after
        written.stored().instruction().value() == "Read {{sender}}."
        written.stored().drawing() == "## {{sender}}"

        when:
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 5, new Instruction("Read what is given."))
        drafts.draw(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 6, "## Case")
        def removed = drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 7,
                half(DeclarationSide.TAKES, []), [])

        then:
        removed.revision() == 8
        removed.stored().takes().declaration().fields() == []
        removed.stored().instruction().value() == "Read what is given."
        removed.stored().drawing() == "## Case"
    }

    def "fake and duplicate field keys cannot rename another input's references"() {
        given:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, TAKEN, unread(TAKEN))
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, new Instruction("{{complaint}}"))
        def stored = StoredQuestion.read(store.session, versionId(DRAFT)).get()
        def before = store.contents()
        def after = half(DeclarationSide.TAKES, [renamed(TAKEN.fields()[0], "ticket"), TAKEN.fields()[1]])
        def keys = duplicate
                ? [stored.takes().keys()[0].id()] * 4
                : [UUID.fromString(ELSEWHERE), null, null, null]

        when:
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, after, keys)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == (duplicate ? RefusalCode.BODY_UNUSABLE : RefusalCode.INPUT_STILL_MENTIONED)
        store.contents() == before

        where:
        duplicate << [false, true]
    }

    private static Field renamed(Field field, String name) {
        new Field(new FieldName(name), field.label(), field.help(), field.shape(), field.howMany(), field.demand())
    }

    static Declaration half(DeclarationSide side, List<Field> fields) {
        new Declaration(side, Demands.ofQuestion(side), fields)
    }

    /** No key read for any field of the half, every one being added. */
    static List<UUID> unread(Declaration half) {
        [null] * counted(half.fields())
    }

    private static int counted(List<Field> level) {
        level.sum(0) { Field field ->
            1 + (field.shape() instanceof FieldShape.Nested ? counted(((FieldShape.Nested) field.shape()).fields()) : 0)
        } as int
    }

    private void storedPin(String field, String version, String side, int position, String list) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, declaration_side.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position,
                                                name, kind, term_list_version_id, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', ?::declaration_side, ?, ?, 'term', ?::uuid, true, ?::uuid)
                """).params(field, version, side, position, "pinned_" + position, list, ANN).update()
    }

    private static Field terms(String name, String list, Demand demand) {
        new Field(new FieldName(name), null, null, new FieldShape.Term(list == null ? null : versionId(list)),
                new HowMany.One(), demand)
    }

    private Questions questionsWith(com.lilradish.lite.domain.search.SearchWay way) {
        new Questions(store.session, new GroupRoles(store.session), store.transactionManager(), new SearchWays([way]))
    }

    private QuestionDrafts searchingWith(com.lilradish.lite.domain.search.SearchWay way) {
        new QuestionDrafts(store.session,
                new Drafts(store.session, store.transactions(), new GroupRoles(store.session)),
                questionsWith(way), new SearchWays([way]))
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "a search marker PUT presets the four answers while GET and an unchanged PUT write nothing"() {
        given:
        def way = com.lilradish.lite.testutil.library.DeployedModels.WEB
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1,
                new Instruction("Find fictional delivery evidence."))
        drafts.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, TAKEN, unread(TAKEN))
        def questions = questionsWith(way)
        def writing = searchingWith(way)
        def mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new QuestionController(questions, writing)).build()
        def address = "/api/groups/${GROUP}/questions/${QUESTION}/versions/${DRAFT}"
        def json = tools.jackson.databind.json.JsonMapper.builder().build()

        when:
        def put = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(address + "/searching")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content('{"revision":3,"searchWay":"web"}')
                .requestAttr(com.lilradish.lite.web.CallerAdmission.class.name + '.caller', ANN_USER)
                .requestAttr(com.lilradish.lite.web.ActAdmission.class.name + '.group', groupId(GROUP)))
                .andReturn().response
        def written = json.readTree(put.contentAsString)

        then:
        put.status == 200
        written.path('revision').asInt() == 4
        written.path('searchWay').asText() == 'web'
        !written.path('searchShapeNeedsRefresh').asBoolean()
        written.path('gives').collect { it.path('name').asText() } == ['title', 'body', 'references', 'other']
        written.path('gives').collect { it.path('longest').asInt() } == [200, 4000, 2000, 1000]
        written.path('gives').collect { it.path('mustBeGiven').asBoolean() } == [true, true, false, false]
        written.path('gives').collect { it.path('stands').asText() } == ['never'] * 4
        written.path('takes').collect { it.path('name').asText() } == ['complaint', 'sender']
        written.path('instruction').asText() == 'Find fictional delivery evidence.'
        written.path('searchWays').get(0).path('name').asText() == 'web'

        when:
        def before = store.contents()
        def get = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(address)
                .requestAttr(com.lilradish.lite.web.CallerAdmission.class.name + '.caller', ANN_USER)
                .requestAttr(com.lilradish.lite.web.ActAdmission.class.name + '.group', groupId(GROUP)))
                .andReturn().response
        def unchanged = writing.searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 4, 'web')

        then:
        get.status == 200
        json.readTree(get.contentAsString) == written
        unchanged.revision() == 4
        store.contents() == before
        // DB-SPECIFIC: PostgreSQL ::uuid/::text casts read the writer's identifier.
        store.texts('select created_by::text from entry_version_writers where entry_version_id = ?::uuid', DRAFT) == [ANN]
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts and native enum literals for
    // entry_kind, declaration_side, field_kind and field_standing.
    def "a release refresh keeps same-name answer identities standings and floors and adds a missing answer as never"() {
        given:
        def way = com.lilradish.lite.testutil.library.DeployedModels.WEB
        def writing = searchingWith(way)
        writing.searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, 'web')
        store.session.sql("""
                update declaration_fields set standing = 'above_confidence', standing_threshold = 80
                 where entry_version_id = ?::uuid and side = 'gives' and name = 'title'
                """).param(DRAFT).update()
        store.session.sql("""
                update declaration_fields set standing = 'always'
                 where entry_version_id = ?::uuid and side = 'gives' and name = 'body'
                """).param(DRAFT).update()
        def kept = store.session.sql("""
                select name, declaration_field_id::text as id, standing::text as standing, standing_threshold as floor
                  from declaration_fields where entry_version_id = ?::uuid and side = 'gives'
                   and name <> 'other' order by position
                """).param(DRAFT).query().listOfRows()
        store.session.sql("delete from declaration_fields where entry_version_id = ?::uuid and name = 'other'")
                .param(DRAFT).update()
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind,
                    text_limit, text_lines, must_be_given, standing, created_by)
                values (?::uuid, 'question', 'gives', 8, 'obsolete', 'text', 100, 'several', false, 'always', ?::uuid)
                """).params(DRAFT, ANN).update()
        def changed = new com.lilradish.lite.domain.search.SearchWay('web', way.requiredTool(), 201, 4001, 2001, 1001)
        def current = questionsWith(changed)
        def before = store.contents()

        when:
        def read = current.read(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER)

        then:
        read.revision() == 2
        read.searchShapeNeedsRefresh()
        store.contents() == before

        when:
        def refreshed = searchingWith(changed).searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, 'web')
        def fields = store.session.sql("""
                select name, declaration_field_id::text as id, standing::text as standing,
                       standing_threshold as floor, text_limit as limit, position
                  from declaration_fields where entry_version_id = ?::uuid and side = 'gives' order by position
                """).param(DRAFT).query().listOfRows()

        then:
        refreshed.revision() == 3
        !refreshed.searchShapeNeedsRefresh()
        fields*.name == ['title', 'body', 'references', 'other']
        fields*.position == [0, 1, 2, 3]
        fields*.limit == [201, 4001, 2001, 1001]
        fields.take(3).collect { [name: it.name, id: it.id, standing: it.standing, floor: it.floor] } == kept
        fields.last().standing == 'never'
        fields.last().floor == null
        !kept*.id.contains(fields.last().id)

        when:
        def stable = store.contents()
        def unchanged = searchingWith(changed).searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, 'web')

        then:
        unchanged.revision() == 3
        store.contents() == stable
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts; || converts enum and integer
    // operands to text.
    def "a marked question locks answer shape while standing and confidence floor remain writable"() {
        given:
        def way = com.lilradish.lite.testutil.library.DeployedModels.WEB
        def writing = searchingWith(way)
        writing.searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, 'web')
        def fields = way.outputs().collect { field ->
            new Field(field.name(), field.label(), field.help(), field.shape(), field.howMany(),
                    new Demand.Stands(field.demand().mustBe(), FieldStanding.ABOVE_CONFIDENCE, 75))
        }
        def declared = half(DeclarationSide.GIVES, fields)

        when:
        def written = writing.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, declared, unread(declared))

        then:
        written.revision() == 3
        written.stored().gives().declaration() == declared
        store.texts("""
                select standing || ' ' || standing_threshold from declaration_fields
                 where entry_version_id = ?::uuid and side = 'gives' order by position
                """, DRAFT) == ['above_confidence 75'] * 4

        when:
        def before = store.contents()
        def first = fields.first()
        def changed = new Field(shape == 'name' ? new FieldName('different') : first.name(), null, null,
                shape == 'kind' ? new FieldShape.Plain(FieldKind.NUMBER)
                        : shape == 'limit' ? new FieldShape.Text(201) : first.shape(),
                shape == 'many' ? new HowMany.Many(2) : first.howMany(),
                new Demand.Stands(shape == 'demand' ? false : true, FieldStanding.ALWAYS, null))
        def invalid = half(DeclarationSide.GIVES, [changed] + fields.drop(1))
        writing.declare(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, invalid, unread(invalid))

        then:
        def failure = thrown(ApiErrorException)
        failure.errorCode() == RefusalCode.SEARCH_QUESTION_SHAPE_LOCKED
        failure.message == "The answer is set by web while For searching is on; only how sure each field must be can change. Turn off For searching to change the rest."
        store.contents() == before

        where:
        shape << ['name', 'kind', 'limit', 'many', 'demand']
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "an unchanged search marker still checks membership revision and draft standing"() {
        given:
        def way = com.lilradish.lite.testutil.library.DeployedModels.WEB
        def writing = searchingWith(way)
        writing.searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, 'web')
        if (gate == 'member') {
            store.session.sql('delete from group_members where group_id = ?::uuid and subject_id = ?::uuid')
                    .params(GROUP, ANN).update()
        } else if (gate == 'submitted') {
            store.submitted(DRAFT, ANN)
        }
        def before = store.contents()

        when:
        writing.searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, gate == 'seen' ? 1 : 2, 'web')

        then:
        def failure = thrown(ApiErrorException)
        failure.errorCode() == expected
        store.contents() == before

        where:
        gate        || expected
        'member'    || RefusalCode.GROUP_NOT_IN_VIEW
        'seen'      || RefusalCode.DRAFT_WRITTEN_SINCE_READ
        'submitted' || RefusalCode.VERSION_STANDING_REFUSES
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "removing a search marker preserves ordinary answers and repeats without becoming another change"() {
        given:
        def writing = searchingWith(com.lilradish.lite.testutil.library.DeployedModels.WEB)
        writing.searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, 'web')
        def fields = store.texts('select declaration_field_id::text from declaration_fields where entry_version_id = ?::uuid order by position', DRAFT)

        when:
        def ordinary = writing.searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, null)

        then:
        ordinary.revision() == 3
        ordinary.stored().searchWay() == null
        store.texts('select declaration_field_id::text from declaration_fields where entry_version_id = ?::uuid order by position', DRAFT) == fields

        when:
        def before = store.contents()
        def unchanged = writing.searching(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 3, null)

        then:
        unchanged.revision() == 3
        store.contents() == before
    }

}
