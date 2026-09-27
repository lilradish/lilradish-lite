package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldHelp
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldLabel
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Writing a question draft's instruction and either half of what it declares, on a real server running the
 * real baseline, and reading each back as the store then holds it.
 */
class QuestionDraftsIntegrationSpec extends Specification {

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
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    QuestionDrafts drafts

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "question_drafts_" + (++databasesMade))
        drafts = new QuestionDrafts(store.session,
                new Drafts(store.session, store.transactions(), new GroupRoles(store.session)),
                new Questions(store.session, new GroupRoles(store.session), store.transactionManager()))
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
        store.texts("select created_by::text from entry_version_writers") == [ANN]

        when: "saying nothing"
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 2, null)

        then: "holds no instruction, rather than an empty one"
        store.count("select count(*) from question_versions where instruction is null") == 1
    }

    /** A write that waited on the draft began before the one it waited on was stamped, and never goes back past it. */
    def "an instruction written after a change stamped later than this one began keeps its time from going back"() {
        given:
        store.session.sql("""
                update question_versions set updated_at = now() + interval '1 day', updated_by = ?::uuid
                 where entry_version_id = ?::uuid
                """).params(FIRST_STEWARD, DRAFT).update()
        def stamped = store.texts("select updated_at::text from question_versions")

        when:
        drafts.instruct(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER, 1, new Instruction("Say."))

        then:
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
        store.texts("select term_list_version_id::text from declaration_fields order by position") ==
                [LIST_RETIRED, LIST_IN_SERVICE]
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
}
