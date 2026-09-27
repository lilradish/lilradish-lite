package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

/**
 * Writing a reference list draft's note and terms one change at a time, on a real server running the real
 * baseline: each change counted as a revision, and a term found by its key within the draft alone.
 */
class ReferenceListDraftsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000b01"

    static final String ANN = "00000002-0000-4000-8000-000000000b01"

    static final String BEN = "00000002-0000-4000-8000-000000000b02"

    static final UserId ANN_USER = new UserId("000b01")

    static final String LIST = "00000006-0000-4000-8000-000000000b01"

    static final String IN_SERVICE = "00000007-0000-4000-8000-000000000b01"

    static final String DRAFT = "00000007-0000-4000-8000-000000000b02"

    static final String NOBODYS = "0000000a-0000-4000-8000-000000000b09"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ReferenceLists lists

    ReferenceListDrafts drafts

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "list_drafts_" + (++databasesMade))
        lists = new ReferenceLists(store.session, new GroupRoles(store.session), store.transactionManager())
        drafts = new ReferenceListDrafts(store.session,
                new Drafts(store.session, store.transactions(), new GroupRoles(store.session)), lists)
        store.person(ANN, "000b01")
        store.person(BEN, "000b02")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.member(GROUP, ANN, "operator")
        store.member(GROUP, BEN, "operator")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(IN_SERVICE, LIST, 1)
        store.content(IN_SERVICE, "reference_list")
        store.version(DRAFT, LIST, 2, BEN)
        store.content(DRAFT, "reference_list")
    }

    def "writes the note as the caller's change, and the version in service as it was"() {
        when:
        drafts.note(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, 1, new ListNote("Pick the nearest.\n\tAsk."))

        then:
        store.texts("select note || ' ' || updated_by from reference_list_versions where entry_version_id = ?::uuid",
                DRAFT) == ["Pick the nearest.\n\tAsk. ${ANN}" as String]
        store.texts("select created_by::text from entry_version_writers") == [ANN]
        revision() == 2
        store.count("select count(*) from reference_list_versions where updated_at is not null") == 1
    }

    def "clearing the note holds none rather than an empty one, and the version in service as it was"() {
        given:
        store.session.sql("update reference_list_versions set note = 'Pick one.' where entry_version_id = ?::uuid")
                .param(DRAFT).update()

        when:
        drafts.note(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, 1, null)

        then:
        store.count("select count(*) from reference_list_versions where entry_version_id = ?::uuid and note is null",
                DRAFT) == 1
        revision() == 2
        store.count("select count(*) from reference_list_versions where updated_by is not null") == 1
    }

    /** A write that waited on the draft began before the one it waited on was stamped, and never goes back past it. */
    def "a note written after a change stamped later than this one began keeps its time from going back"() {
        given:
        store.session.sql("""
                update reference_list_versions set updated_at = now() + interval '1 day', updated_by = ?::uuid
                 where entry_version_id = ?::uuid
                """).params(FIRST_STEWARD, DRAFT).update()
        def stamped = store.texts("select updated_at::text from reference_list_versions where updated_at is not null")

        when:
        drafts.note(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, 1, new ListNote("Pick one."))

        then:
        store.texts("select updated_at::text from reference_list_versions where updated_at is not null") == stamped
        store.texts("select updated_by::text from reference_list_versions where updated_at is not null") == [ANN]
    }

    /** Alike terms are not refused as they are written: only submitting a list holding them is. */
    def "adds each term after every term the draft holds, as the caller's, alike ones and all"() {
        when:
        add(1, "Billing", "A charge is disputed.")
        add(2, "Delivery", "It came late.")
        add(3, "billing", "A charge again.")

        then:
        terms() == ["1 Billing A charge is disputed.", "2 Delivery It came late.", "3 billing A charge again."]
        store.texts("select distinct created_by::text from reference_list_terms") == [ANN]
        store.count("select count(*) from reference_list_terms where updated_at is not null") == 0
        revision() == 4

        and: "nothing added to the version in service"
        store.count("select count(*) from reference_list_terms where entry_version_id = ?::uuid", IN_SERVICE) == 0
    }

    def "a draft holding one term fewer than a list holds takes one more, as the last"() {
        given:
        holdingTerms(Term.MOST_IN_A_LIST - 1)

        when:
        add(1, "Returns", "It was sent back.")

        then:
        store.count("select count(*) from reference_list_terms where entry_version_id = ?::uuid", DRAFT) == 256
        store.texts("select position || ' ' || term from reference_list_terms where created_by = ?::uuid", ANN) ==
                ["256 Returns"]
        revision() == 2
    }

    /** Counted where a term is added rather than at submitting, so no draft ever holds more than it could submit. */
    def "a draft holding as many terms as a list holds is refused another, and nothing is written"() {
        given:
        holdingTerms(Term.MOST_IN_A_LIST)
        def before = store.contents()

        when:
        add(1, "Returns", "It was sent back.")

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.LIST_TOO_LARGE
        refused.message == "A reference list holds at most 256 terms."
        store.contents() == before
    }

    /** Once the draft is let go one lands and the other finds the revision moved on, never reaching the count. */
    def "two adds racing on one revision, one term short of the most a list holds, land once and refuse the other as written since"() {
        given:
        holdingTerms(Term.MOST_IN_A_LIST - 1)
        def holder = store.holding("select 1 from entry_versions where entry_version_id = '${DRAFT}' for no key update")

        when:
        def adding = [attempting(racing) { add(1, "Returns", "It was sent back.") },
                      attempting(racing) { add(1, "Refunds", "Money went back.") }]
        store.untilWaiting(2)
        holder.rollback()
        holder.close()
        def outcomes = adding*.get(10, TimeUnit.SECONDS)

        then:
        outcomes.count { it == null } == 1
        outcomes.findAll { it != null }*.errorCode() == [RefusalCode.DRAFT_WRITTEN_SINCE_READ]
        store.count("select count(*) from reference_list_terms where entry_version_id = ?::uuid", DRAFT) == 256
        revision() == 2
    }

    def "edits a term's word and what it means together, in its place, and no other term"() {
        given:
        def billing = termed(DRAFT, 1, "Billing")
        termed(DRAFT, 2, "Delivery")

        when:
        drafts.edit(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, 1, billing, new Term("Invoicing"),
                new TermMeaning("An invoice is wrong."))

        then:
        terms() == ["1 Invoicing An invoice is wrong.", "2 Delivery About Delivery."]
        store.texts("select reference_list_term_id::text from reference_list_terms where updated_by = ?::uuid", ANN) ==
                [billing.toString()]
        revision() == 2
    }

    /** A key of another version's term, or of none, reads alike: the draft holds no such term. */
    def "a term's key that is not of this draft is refused alike whatever it is, whichever change names it"() {
        given:
        termed(DRAFT, 1, "Billing")
        def elsewhere = termed(IN_SERVICE, 1, "Billing")
        def before = store.contents()

        when:
        changing(change, 1, key == "elsewhere" ? elsewhere : UUID.fromString(NOBODYS))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.TERM_NOT_IN_VIEW
        refused.message == "That term is not in this draft."
        store.contents() == before

        where:
        [change, key] << [["edit", "remove", "up", "down"], ["elsewhere", "nobody's"]].combinations()
    }

    /** No place is left between: every term after the one removed moves up one. */
    def "removes a term, every term after it moving up a place"() {
        given:
        termed(DRAFT, 1, "Billing")
        def delivery = termed(DRAFT, 2, "Delivery")
        termed(DRAFT, 3, "Returns")
        termed(DRAFT, 4, "Other")

        when:
        drafts.remove(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, 1, delivery)

        then:
        terms() == ["1 Billing About Billing.", "2 Returns About Returns.", "3 Other About Other."]
        store.count("select count(*) from reference_list_terms where reference_list_term_id = ?::uuid", delivery) == 0
        store.texts("select term from reference_list_terms where updated_by = ?::uuid order by position", ANN) ==
                ["Returns", "Other"]
        revision() == 2
    }

    def "removing the only term leaves the draft holding none"() {
        given:
        def billing = termed(DRAFT, 1, "Billing")

        when:
        drafts.remove(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, 1, billing)

        then:
        terms() == []
        revision() == 2
    }

    def "moves a term one place, changing places with the term beside it that way, and no other"() {
        given:
        def keys = ["Billing", "Delivery", "Returns", "Other"].withIndex().collect { term, index ->
            termed(DRAFT, index + 1, term)
        }

        when:
        drafts.move(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, 1, keys[moved], way)

        then:
        terms().collect { it.split(" ")[1] } == order
        store.texts("select term from reference_list_terms where updated_by = ?::uuid order by position", ANN) ==
                changed
        revision() == 2

        where:
        moved | way                          || order                                         | changed
        1     | ReferenceListDrafts.Way.UP   || ["Delivery", "Billing", "Returns", "Other"]   | ["Delivery", "Billing"]
        1     | ReferenceListDrafts.Way.DOWN || ["Billing", "Returns", "Delivery", "Other"]   | ["Returns", "Delivery"]
        3     | ReferenceListDrafts.Way.UP   || ["Billing", "Delivery", "Other", "Returns"]   | ["Other", "Returns"]
        0     | ReferenceListDrafts.Way.DOWN || ["Delivery", "Billing", "Returns", "Other"]   | ["Delivery", "Billing"]
    }

    def "the first term moves no higher and the last no lower, and nothing changes for asking"() {
        given:
        def first = termed(DRAFT, 1, "Billing")
        def last = termed(DRAFT, 2, "Delivery")
        def before = store.contents()

        when:
        drafts.move(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, 1, which == "first" ? first : last, way)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.TERM_AT_END
        store.contents() == before

        where:
        which   | way
        "first" | ReferenceListDrafts.Way.UP
        "last"  | ReferenceListDrafts.Way.DOWN
    }

    /** The revision is counted before the change is made, so a view read before the change would carry it too. */
    def "every change answers with the draft as that change left it, its revision counted past the one it named"() {
        given:
        def billing = termed(DRAFT, 1, "Billing")
        def delivery = termed(DRAFT, 2, "Delivery")
        def before = lists.read(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER)

        when:
        def answered = changing(change, 1, change == "up" ? delivery : billing)

        then:
        answered.revision() == 2
        answered == lists.read(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER)
        [answered.note(), answered.terms()] != [before.note(), before.terms()]

        where:
        change << ["note", "add", "edit", "remove", "up", "down"]
    }

    /** Each change is one the draft's revision counts, so one made from a reading since passed lands nothing. */
    def "every change naming a revision the draft has passed is refused, and nothing lands"() {
        given:
        def billing = termed(DRAFT, 1, "Billing")
        termed(DRAFT, 2, "Delivery")
        store.session.sql("update entry_versions set revision = 2 where entry_version_id = ?::uuid").param(DRAFT).update()
        def before = store.contents()

        when:
        changing(change, 1, billing)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.DRAFT_WRITTEN_SINCE_READ
        store.contents() == before

        where:
        change << ["note", "add", "edit", "remove", "up", "down"]
    }

    def "a list version that is no longer a draft is changed by nobody, and holds what it held"() {
        given:
        def billing = termed(IN_SERVICE, 1, "Billing")
        termed(IN_SERVICE, 2, "Delivery")
        def before = store.contents()

        when:
        changingInService(change, billing)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_STANDING_REFUSES
        store.contents() == before

        where:
        change << ["note", "add", "edit", "remove", "up", "down"]
    }

    private ReferenceLists.ListView changing(String change, int seen, UUID term) {
        changed(DRAFT, change, seen, term)
    }

    private void changingInService(String change, UUID term) {
        changed(IN_SERVICE, change, 1, term)
    }

    private ReferenceLists.ListView changed(String version, String change, int seen, UUID term) {
        def at = [groupId(GROUP), entryId(LIST), versionId(version), ANN_USER, seen]
        switch (change) {
            case "note": return drafts.note(*at, new ListNote("Pick one."))
            case "add": return drafts.add(*at, new Term("Returns"), new TermMeaning("It was sent back."))
            case "edit": return drafts.edit(*at, term, new Term("Invoicing"), new TermMeaning("An invoice is wrong."))
            case "remove": return drafts.remove(*at, term)
            case "up": return drafts.move(*at, term, ReferenceListDrafts.Way.UP)
            case "down": return drafts.move(*at, term, ReferenceListDrafts.Way.DOWN)
            default: throw new IllegalArgumentException("No change is named " + change)
        }
    }

    private void add(int seen, String term, String meaning) {
        drafts.add(groupId(GROUP), entryId(LIST), versionId(DRAFT), ANN_USER, seen, new Term(term), new TermMeaning(meaning))
    }

    private void holdingTerms(int many) {
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                select ?::uuid, place, 'Term ' || place, 'What it means.', ?::uuid from generate_series(1, ?) place
                """).params(DRAFT, SEEDER, many).update()
    }

    private UUID termed(String version, int position, String term) {
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, ?, ?::uuid)
                returning reference_list_term_id
                """).params(version, position, term, "About ${term}." as String, SEEDER).query(UUID).single()
    }

    /** The draft's terms in its order, by place and words rather than any key. */
    private List<String> terms() {
        store.texts("""
                select concat_ws(' ', position, term, meaning) from reference_list_terms
                 where entry_version_id = ?::uuid order by position
                """, DRAFT)
    }

    private int revision() {
        store.count("select revision from entry_versions where entry_version_id = ?::uuid", DRAFT)
    }
}
