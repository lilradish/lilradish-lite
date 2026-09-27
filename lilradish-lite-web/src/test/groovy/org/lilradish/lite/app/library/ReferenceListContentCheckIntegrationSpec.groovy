package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** What a reference list's stored content is held to at submitting, on a real server running the real baseline. */
class ReferenceListContentCheckIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000d01"

    static final String LIST = "00000006-0000-4000-8000-000000000d01"

    static final String VERSION = "00000007-0000-4000-8000-000000000d01"

    static final String OTHER_VERSION = "00000007-0000-4000-8000-000000000d02"

    static final String ACUTE = Character.toString(0x301)

    static final String E_ACUTE = Character.toString(0xE9)

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    ReferenceListContentCheck check

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "list_checks_" + (++databasesMade))
        check = new ReferenceListContentCheck(store.session)
        store.group(GROUP, "SUPPORT", "Customer support")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(VERSION, LIST, 1)
        store.content(VERSION, "reference_list", "Pick the nearest.")
        store.seeded(OTHER_VERSION, LIST, 2)
        store.content(OTHER_VERSION, "reference_list")
    }

    /** A note is no term: whatever else the list says, nothing could answer with one of none. */
    def "a list holding no term is refused as a whole, whatever its note says, and whatever another version holds"() {
        given:
        termed(OTHER_VERSION, 1, "Billing")

        expect:
        check.problemsIn(groupId(GROUP), versionId(VERSION)) ==
                [new ContentProblem(ContentProblemCode.NO_TERMS, new ContentPlace.Whole(ContentPart.TERMS), null)]
    }

    /**
     * Alike is equal once folded: case aside, and one spelling of a letter as good as another, but an accent
     * still telling two apart. Each later one of alike terms is named, never the first.
     */
    def "each term alike to one before it is named by its key, in the order the list gives them"() {
        given:
        def keys = terms.withIndex().collect { term, index -> termed(VERSION, positions[index], term) }

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        problems == repeated.collect {
            new ContentProblem(ContentProblemCode.TERM_REPEATED, new ContentPlace.AtTerm(keys[it]), null)
        }

        where:
        terms                                                   | positions || repeated
        ["Billing"]                                             | [1]       || []
        ["Billing", "Delivery", "Returns"]                      | [1, 2, 3] || []
        ["Billing", "billing"]                                  | [1, 2]    || [1]
        ["BILLING", "Delivery", "billing", "Billing"]           | [1, 2, 3, 4] || [2, 3]
        ["billing", "Billing"]                                  | [2, 1]    || [0]
        ["R" + E_ACUTE + "sum" + E_ACUTE, "re" + ACUTE + "sume" + ACUTE] | [1, 2] || [1]
        ["Resume", "R" + E_ACUTE + "sum" + E_ACUTE]             | [1, 2]    || []
        ["Billing", "Billing dispute"]                          | [1, 2]    || []
    }

    def "terms alike in another version of the list are nothing to this one"() {
        given:
        termed(VERSION, 1, "Billing")
        termed(OTHER_VERSION, 1, "Billing")
        termed(OTHER_VERSION, 2, "billing")

        expect:
        check.problemsIn(groupId(GROUP), versionId(VERSION)) == []
    }

    private UUID termed(String version, int position, String term) {
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, 'What it means.', ?::uuid)
                returning reference_list_term_id
                """).params(version, position, term, SEEDER).query(UUID).single()
    }
}
