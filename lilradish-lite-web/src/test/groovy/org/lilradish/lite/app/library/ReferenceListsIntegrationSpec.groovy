package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Reading a reference list version as one moment, only within its group, on a real server running the real baseline. */
class ReferenceListsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000c01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000c02"

    static final String ANN = "00000002-0000-4000-8000-000000000c01"

    static final UserId ANN_USER = new UserId("000c01")

    static final UserId DAN_USER = new UserId("000c04")

    static final String LIST = "00000006-0000-4000-8000-000000000c01"

    static final String VERSION = "00000007-0000-4000-8000-000000000c01"

    static final String DRAFT = "00000007-0000-4000-8000-000000000c02"

    static final String OTHER_LIST = "00000006-0000-4000-8000-000000000c03"

    static final String OTHER_VERSION = "00000007-0000-4000-8000-000000000c03"

    static final String QUESTION = "00000006-0000-4000-8000-000000000c04"

    static final String QUESTION_VERSION = "00000007-0000-4000-8000-000000000c04"

    static final String COMPOSED = "R" + Character.toString(0xE9) + "sum" + Character.toString(0xE9)

    static final String DECOMPOSED = "re" + Character.toString(0x301) + "sume" + Character.toString(0x301)

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ReferenceLists lists

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "lists_" + (++databasesMade))
        lists = new ReferenceLists(store.session, new GroupRoles(store.session), store.transactionManager())
        store.person(ANN, "000c01")
        store.person("00000002-0000-4000-8000-000000000c04", "000c04")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "operator")
        store.member(OTHER_GROUP, ANN, "owner")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(VERSION, LIST, 1)
        store.content(VERSION, "reference_list", "Pick the nearest.\n\tAsk where none is.")
        store.version(DRAFT, LIST, 2, ANN)
        store.content(DRAFT, "reference_list")
    }

    /** Given out of their order, so the order read is the version's and not the order they were written in. */
    def "reads a version's revision, its note, and every term in the order the version gives them, each by its key"() {
        given:
        def delivery = termed(VERSION, 2, "Delivery", "It came late or not at all.")
        def billing = termed(VERSION, 1, "Billing", "A charge is what is disputed.")
        def returns = termed(VERSION, 3, "Returns", "It was sent back.")
        store.session.sql("update entry_versions set revision = 6 where entry_version_id = ?::uuid").param(VERSION)
                .update()

        when:
        def view = lists.read(groupId(GROUP), entryId(LIST), versionId(VERSION), ANN_USER)

        then:
        view.revision() == 6
        view.note() == new ListNote("Pick the nearest.\n\tAsk where none is.")
        view.terms() == [
                new ReferenceLists.HeldTerm(
                        billing, new Term("Billing"), new TermMeaning("A charge is what is disputed."), false),
                new ReferenceLists.HeldTerm(
                        delivery, new Term("Delivery"), new TermMeaning("It came late or not at all."), false),
                new ReferenceLists.HeldTerm(returns, new Term("Returns"), new TermMeaning("It was sent back."), false)]
    }

    /** Judged by the one expression submitting names a repeated term by, so the page and the refusal never part. */
    def "each term alike to one before it is read as so, whatever the case either is written in"() {
        given:
        terms.withIndex().each { term, index -> termed(VERSION, positions[index], term, "What it means.") }

        when:
        def view = lists.read(groupId(GROUP), entryId(LIST), versionId(VERSION), ANN_USER)

        then:
        view.terms().collect { [it.term().value(), it.alikeEarlier()] } == read

        where:
        terms                                         | positions    || read
        ["Billing", "Delivery"]                       | [1, 2]       || [["Billing", false], ["Delivery", false]]
        ["BILLING", "Delivery", "billing", "Billing"] | [1, 2, 3, 4] || [["BILLING", false], ["Delivery", false], ["billing", true], ["Billing", true]]
        ["billing", "Billing"]                        | [2, 1]       || [["Billing", false], ["billing", true]]
        [COMPOSED, DECOMPOSED]                        | [1, 2]       || [[COMPOSED, false], [DECOMPOSED, true]]
        ["Resume", COMPOSED]                          | [1, 2]       || [["Resume", false], [COMPOSED, false]]
    }

    def "a draft holding no note and no term yet is read as holding neither, and nothing of another version's"() {
        given:
        termed(VERSION, 1, "Billing", "A charge is what is disputed.")

        when:
        def view = lists.read(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER)

        then:
        view.revision() == 1
        view.note() == null
        view.terms() == []
    }

    /**
     * Another group's list, another entry's version, a question's, and one nobody holds are one refusal;
     * somebody in no role here is refused as the group is.
     */
    def "a version not in view is refused alike however it is not, and a reader in no role here as no group"() {
        given:
        store.entry(OTHER_LIST, OTHER_GROUP, "reference_list", "Invoice kinds")
        store.seeded(OTHER_VERSION, OTHER_LIST, 1)
        store.content(OTHER_VERSION, "reference_list")
        store.entry(QUESTION, GROUP, "question", "Classify")
        store.seeded(QUESTION_VERSION, QUESTION, 1)
        store.content(QUESTION_VERSION, "question")

        when:
        lists.read(groupId(GROUP), entryId(entry), versionId(version), caller)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == code

        where:
        entry      | version                                | caller   || code
        OTHER_LIST | OTHER_VERSION                          | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        LIST       | OTHER_VERSION                          | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        QUESTION   | QUESTION_VERSION                       | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        LIST       | "00000009-0000-4000-8000-000000000009" | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        LIST       | VERSION                                | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
    }

    /** Only something written past this system's own rules, straight into the store, holds such words. */
    def "a version holding words this system will not show fails the read rather than showing them"() {
        given:
        termed(VERSION, 1, term, "What it means.")

        when:
        lists.read(groupId(GROUP), entryId(LIST), versionId(VERSION), ANN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Reference list version ${VERSION} holds words this system will not show" as String
        failed.cause instanceof IllegalArgumentException

        where:
        term << ["Billing ", "Bill" + Character.toString(0x202E) + "ing", "Bill" + Character.toString(0x200B) + "ing"]
    }

    def "a version of a list holding no content of its kind is a store gone wrong"() {
        given:
        store.entry(OTHER_LIST, GROUP, "reference_list", "Channels")
        store.seeded("00000007-0000-4000-8000-000000000c09", OTHER_LIST, 1)

        when:
        lists.read(groupId(GROUP), entryId(OTHER_LIST), versionId("00000007-0000-4000-8000-000000000c09"), ANN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message ==
                "Reference list version 00000007-0000-4000-8000-000000000c09 holds no reference list content"
    }

    private UUID termed(String version, int position, String term, String meaning) {
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, ?, ?::uuid)
                returning reference_list_term_id
                """).params(version, position, term, meaning, SEEDER).query(UUID).single()
    }
}
