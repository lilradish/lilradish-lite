package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.VersionStanding
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** One version of a question read as one moment of a real server running the real baseline. */
class QuestionsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000a01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000a02"

    static final String ANN = "00000002-0000-4000-8000-000000000a01"

    static final UserId ANN_USER = new UserId("000a01")

    /** In no role in the group. */
    static final UserId DAN_USER = new UserId("000a04")

    static final String QUESTION = "00000006-0000-4000-8000-000000000a01"

    static final String VERSION = "00000007-0000-4000-8000-000000000a01"

    static final String DRAFT = "00000007-0000-4000-8000-000000000a02"

    static final String LIST = "00000006-0000-4000-8000-000000000a03"

    static final String LIST_PINNED = "00000007-0000-4000-8000-000000000a03"

    static final String LIST_NEWER = "00000007-0000-4000-8000-000000000a04"

    static final String LIST_DRAFT = "00000007-0000-4000-8000-000000000a05"

    static final String CHANNELS = "00000006-0000-4000-8000-000000000a06"

    static final String CHANNELS_IN_SERVICE = "00000007-0000-4000-8000-000000000a06"

    static final String OTHER_LIST = "00000006-0000-4000-8000-000000000a07"

    static final String OTHER_LIST_IN_SERVICE = "00000007-0000-4000-8000-000000000a07"

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000a08"

    static final String WORKFLOW_VERSION = "00000007-0000-4000-8000-000000000a08"

    static final String OTHER_QUESTION = "00000006-0000-4000-8000-000000000a09"

    static final String OTHER_VERSION = "00000007-0000-4000-8000-000000000a09"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    Questions questions

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "questions_" + (++databasesMade))
        questions = new Questions(store.session, new GroupRoles(store.session), store.transactionManager())
        store.person(ANN, "000a01")
        store.person("00000002-0000-4000-8000-000000000a04", "000a04")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "operator")
        store.member(OTHER_GROUP, ANN, "owner")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(LIST_PINNED, LIST, 1)
        store.content(LIST_PINNED, "reference_list", "Choose what is to be put right.")
        store.seeded(LIST_NEWER, LIST, 2)
        store.content(LIST_NEWER, "reference_list")
        store.version(LIST_DRAFT, LIST, 3, ANN)
        store.entry(CHANNELS, GROUP, "reference_list", "Channels")
        store.seeded(CHANNELS_IN_SERVICE, CHANNELS, 1)
        store.entry(OTHER_LIST, OTHER_GROUP, "reference_list", "Invoice kinds")
        store.seeded(OTHER_LIST_IN_SERVICE, OTHER_LIST, 1)
        ["Billing": "A charge is what is disputed.", "Delivery": "It came late or not at all."]
                .eachWithIndex { term, meaning, position ->
                    store.session.sql("""
                            insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                            values (?::uuid, ?, ?, ?, ?::uuid)
                            """).params(LIST_PINNED, position + 1, term, meaning, SEEDER).update()
                }
        store.entry(QUESTION, GROUP, "question", "Summarise a complaint")
        store.seeded(VERSION, QUESTION, 1)
        store.content(VERSION, "question", "Say which category it falls under.")
        fields(VERSION, true)
    }

    /** The version's fields: a text taken, a term pinning the older list, and a field holding one of text. */
    private void fields(String version, boolean whole) {
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                parent_field_id, position, name, kind, text_limit,
                                                term_list_version_id, must_be_given, standing, standing_threshold,
                                                created_by)
                values (?::uuid, ?::uuid, 'question', 'takes', null, 1, 'complaint', 'text', 4000, null, true, null,
                        null, ?::uuid),
                       (?::uuid, ?::uuid, 'question', 'gives', null, 1, 'category', 'term', null, ?::uuid, true,
                        'above_confidence', 80, ?::uuid),
                       (?::uuid, ?::uuid, 'question', 'gives', null, 2, 'details', 'fields', null, null, false, 'never',
                        null, ?::uuid),
                       (?::uuid, ?::uuid, 'question', 'gives', ?::uuid, 1, 'product', 'text', ?, null, true, null, null,
                        ?::uuid)
                """).params(key(version, 1), version, SEEDER, key(version, 2), version, LIST_PINNED, SEEDER,
                key(version, 3), version, SEEDER, key(version, 4), version, key(version, 3), whole ? 128 : null,
                SEEDER).update()
    }

    /** The key of a version's field, which says which version and which field it is. */
    private static String key(String version, int field) {
        "0000000b-0000-4000-8000-000000" + version[-3..-1] + String.format(Locale.ROOT, "%03d", field)
    }

    def "reads what a version holds, each list it pins as a reader names it, and what a field of terms could pin"() {
        given:
        store.session.sql("update entry_versions set revision = 4 where entry_version_id = ?::uuid").param(VERSION)
                .update()

        when:
        def view = questions.read(groupId(GROUP), entryId(QUESTION), versionId(VERSION), ANN_USER)

        then:
        view.revision() == 4
        view.stored().instruction() == new Instruction("Say which category it falls under.")
        view.stored().takes().declaration().fields()*.name() == [new FieldName("complaint")]
        view.stored().gives().declaration().fields()*.name() == [new FieldName("category"), new FieldName("details")]
        view.stored().gives().keys()*.id() == [UUID.fromString(key(VERSION, 2)), UUID.fromString(key(VERSION, 3))]
        view.pins() == [(versionId(LIST_PINNED)): new PinnedVersions.PinnedVersion(new EntryName("Categories"),
                versionId(LIST_PINNED), 1, VersionStanding.IN_SERVICE,
                new RetiredPinsRefusal.NumberedVersion(versionId(LIST_NEWER), 2))]

        and: "every list of the group's in service, and no draft of one, nor another group's"
        view.pinnable() == [
                new PinnedVersions.OfferedVersion(new EntryName("Categories"), versionId(LIST_NEWER), 2),
                new PinnedVersions.OfferedVersion(new EntryName("Categories"), versionId(LIST_PINNED), 1),
                new PinnedVersions.OfferedVersion(new EntryName("Channels"), versionId(CHANNELS_IN_SERVICE), 1)]
    }

    /** A list pinned and retired since still reads as pinned, saying it is retired and which is newer. */
    def "a list pinned and retired since is named as retired, with the newest of it in service"() {
        given:
        store.retired(LIST_PINNED, LibraryStore.FIRST_STEWARD)

        when:
        def view = questions.read(groupId(GROUP), entryId(QUESTION), versionId(VERSION), ANN_USER)

        then:
        view.pins()[versionId(LIST_PINNED)].standing() == VersionStanding.RETIRED
        view.pins()[versionId(LIST_PINNED)].newer() == new RetiredPinsRefusal.NumberedVersion(versionId(LIST_NEWER), 2)
        !view.pinnable()*.version().contains(versionId(LIST_PINNED))
    }

    /** What is added is the half given back as a model is told it: never a label, a help or the floor. */
    def "what a model is told of what a whole version gives back is read beside it, each list's terms and note"() {
        when:
        def view = questions.read(groupId(GROUP), entryId(QUESTION), versionId(VERSION), ANN_USER)

        then:
        view.added() == [
                new AskedField(new FieldName("category"), FieldKind.TERM, null, null, new OfferedTerms([
                        new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge is what is disputed.")),
                        new OfferedTerms.Offered(new Term("Delivery"), new TermMeaning("It came late or not at all."))],
                        new ListNote("Choose what is to be put right.")), [], true, true),
                new AskedField(new FieldName("details"), FieldKind.FIELDS, null, null, null, [
                        new AskedField(new FieldName("product"), FieldKind.TEXT, 128, null, null, [], true, false)],
                        false, false)]
    }

    /** A question giving nothing back is refused at submitting, so nothing could be told of that half. */
    def "what a model is told is left out where a version gives nothing back"() {
        given:
        store.version(DRAFT, QUESTION, 2, ANN)
        store.content(DRAFT, "question", "Say it.")

        when:
        def view = questions.read(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER)

        then:
        view.added() == null
        view.stored().gives().declaration().holds()
    }

    def "what a model is told is left out where what a version gives back is not whole yet"() {
        given:
        store.version(DRAFT, QUESTION, 2, ANN)
        store.content(DRAFT, "question")
        fields(DRAFT, false)

        when:
        def view = questions.read(groupId(GROUP), entryId(QUESTION), versionId(DRAFT), ANN_USER)

        then:
        view.added() == null
        view.stored().instruction() == null
        view.stored().gives().declaration().problems([:])*.code()*.published() == ["longest_missing"]
    }

    /**
     * Another group's question, another entry's version, a workflow's, and one nobody holds are one refusal;
     * somebody in no role here is refused as the group is.
     */
    def "a version not in view is refused alike however it is not, and a reader in no role here as no group"() {
        given:
        store.entry(OTHER_QUESTION, OTHER_GROUP, "question", "Elsewhere")
        store.seeded(OTHER_VERSION, OTHER_QUESTION, 1)
        store.content(OTHER_VERSION, "question")
        store.entry(WORKFLOW, GROUP, "workflow", "Handle")
        store.seeded(WORKFLOW_VERSION, WORKFLOW, 1)
        store.content(WORKFLOW_VERSION, "workflow")

        when:
        questions.read(groupId(GROUP), entryId(entry), versionId(version), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code

        where:
        entry          | version                                | caller   || code
        OTHER_QUESTION | OTHER_VERSION                          | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        QUESTION       | OTHER_VERSION                          | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        WORKFLOW       | WORKFLOW_VERSION                       | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        QUESTION       | "00000009-0000-4000-8000-000000000009" | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        QUESTION       | VERSION                                | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
    }

    /** Nothing but a page ever pins a list, and it pins only its own group's: another's is a store gone wrong. */
    def "a version pinning a list of another group's fails the read rather than naming it"() {
        given:
        store.session.sql("update declaration_fields set term_list_version_id = ?::uuid where name = 'category'")
                .param(OTHER_LIST_IN_SERVICE).update()

        when:
        questions.read(groupId(GROUP), entryId(QUESTION), versionId(VERSION), ANN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A version pins a version group ${GROUP} does not hold" as String
    }
}
