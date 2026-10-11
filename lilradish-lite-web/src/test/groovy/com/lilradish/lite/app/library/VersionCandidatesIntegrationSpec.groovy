package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
import org.libprunus.core.error.ApiErrorException
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class VersionCandidatesIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-00000000ca01"
    static final String OTHER = "00000003-0000-4000-8000-00000000ca02"
    static final String PERSON = "00000002-0000-4000-8000-00000000ca01"
    static final UserId READER = new UserId("00ca01")

    @Shared @AutoCleanup
    TestPostgres server = TestPostgres.worker()
    @Shared int copies = 0
    LibraryStore store
    VersionCandidates versions

    def setupSpec() { LibraryStore.prepareBaseline(server) }

    def setup() {
        store = LibraryStore.forCase(server, "version_candidates_${++copies}")
        versions = new VersionCandidates(store.session, new GroupRoles(store.session), new SearchWays([]),
                store.transactionManager())
        store.person(PERSON, READER.value())
        store.group(GROUP, "CATALOGUE", "Fictional catalogue")
        store.group(OTHER, "ELSEWHERE", "Fictional other group")
        store.member(GROUP, PERSON, "operator")
    }

    def "empty search holds membership but supplies no candidates"() {
        expect:
        versions.candidates(groupId(GROUP), EntryKind.QUESTION, "", null, READER) ==
                new VersionCandidates.Page([], false)

        when:
        versions.candidates(groupId(OTHER), EntryKind.QUESTION, "", null, READER)
        then:
        thrown(ApiErrorException)
    }

    def "matching occurs before the twenty item boundary and order is entry then descending version number"() {
        given:
        (1..25).each { add(it, GROUP, "workflow", "Case ${String.format(Locale.ROOT, '%02d', it)}", 1) }
        add(26, GROUP, "workflow", "Case 01", 2, 1)
        add(27, OTHER, "workflow", "Case 00", 1)
        add(28, GROUP, "question", "Case 00", 1)
        add(29, GROUP, "workflow", "Case 00", 1)
        store.retired(id(7, 29), LibraryStore.FIRST_STEWARD)

        when:
        def broad = versions.candidates(groupId(GROUP), EntryKind.WORKFLOW, "Case", null, READER)
        def narrow = versions.candidates(groupId(GROUP), EntryKind.WORKFLOW, "Case 25", null, READER)
        then:
        broad.more()
        broad.items().size() == 20
        broad.items()*.versionId() == ([26] + (1..19)).collect { UUID.fromString(id(7, it)) }
        narrow.items()*.versionId() == [UUID.fromString(id(7, 25))]
        !narrow.more()
    }

    def "name folding and escaped literal patterns agree with entry matching for #typed"() {
        given:
        add(1, GROUP, "workflow", name, 3)
        when:
        def page = versions.candidates(groupId(GROUP), EntryKind.WORKFLOW, typed, null, READER)
        then:
        page.items().size() == matched
        where:
        name             | typed             | matched
        "Straße"         | "STRASSE"         | 1
        "Café"           | "Cafe\u0301"      | 1
        "René"           | "Rene"            | 0
        "Ｗｉｄｅ"         | "Wide"            | 0
        "İpek"           | "ipek"            | 0
        "Literal !%_"    | "!%_"             | 1
        "Ordinary"       | "%"               | 0
    }

    def "search way filtering precedes the page boundary while declaration qualification remains visible"() {
        given:
        def way = new SearchWay("web_search", new ModelToolName("web_search"), 100, 100, 100, 100)
        versions = new VersionCandidates(store.session, new GroupRoles(store.session), new SearchWays([way]),
                store.transactionManager())
        (1..28).each { serial ->
            add(serial, GROUP, "question", "Case ${String.format(Locale.ROOT, '%02d', serial)}", 1)
            // DB-SPECIFIC: PostgreSQL ::uuid cast; filtering uses the persisted search-way marker.
            store.session.sql("update question_versions set search_way = ? where entry_version_id = ?::uuid")
                    .params(serial < 26 ? "other_search" : "web_search", id(7, serial)).update()
            if (serial > 26) {
                ["title", "body", "references", "other"].eachWithIndex { field, position ->
                    StepRows.field(store, [id: id(11, serial * 10 + position), owner: id(7, serial),
                                          ownerKind: "question", side: "gives", position: position + 1,
                                          name: field, limit: 100, mustBe: position < 2])
                }
            }
        }

        when:
        def page = versions.candidates(groupId(GROUP), EntryKind.QUESTION, "Case", "web_search", READER)
        def chosen = versions.detail(groupId(GROUP), versionId(id(7, 27)), READER)
        then:
        page.items()*.versionId() == (26..28).collect { UUID.fromString(id(7, it)) }
        page.items()*.searchMatches() == [false, true, true]
        page.items()*.searchWay() == ["web_search", "web_search", "web_search"]
        !page.more()
        chosen.searchMatches()
        chosen.searchWay() == "web_search"
        chosen.gives()*.name() == ["title", "body", "references", "other"]
    }

    def "numbers match only entire ASCII integers and names remain searchable independently"() {
        given:
        add(1, GROUP, "workflow", "A plain entry", 3)
        // DB-SPECIFIC: PostgreSQL ::uuid cast; version_name is mutable metadata independent of content.
        store.session.sql("update entry_versions set version_name = ? where entry_version_id = ?::uuid")
                .params("Quarter three", id(7, 1)).update()
        expect:
        versions.candidates(groupId(GROUP), EntryKind.WORKFLOW, typed, null, READER).items().size() == matched
        where:
        typed                   | matched
        "3"                     | 1
        "003"                   | 1
        "+3"                    | 0
        "３"                    | 0
        "3.0"                   | 0
        "2147483648"            | 0
        "QUARTER"               | 1
    }

    def "independent detail reads a retired pin outside the candidate window with ordered recursive terms"() {
        given:
        add(1, GROUP, "reference_list", "Categories", 1)
        add(2, GROUP, "reference_list", "Categories", 2, 1)
        add(3, GROUP, "question", "Classify", 1)
        store.termField(id(11, 1), id(7, 3), "question", id(7, 1))
        ["later", "urgent"].eachWithIndex { term, position ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into reference_list_terms(entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, ?, ?::uuid)
                    """).params(id(7, 1), position + 1, term, "An invented classification: " + term, SEEDER).update()
        }
        store.retired(id(7, 1), LibraryStore.FIRST_STEWARD)

        when:
        def list = versions.detail(groupId(GROUP), versionId(id(7, 1)), READER)
        def question = versions.detail(groupId(GROUP), versionId(id(7, 3)), READER)
        then:
        list.kind() == "reference_list"
        list.takes() == null && list.gives() == null
        list.version().standing() == "retired"
        list.version().newer().versionId() == UUID.fromString(id(7, 2))
        list.terms() == [(UUID.fromString(id(7, 1))): ["later", "urgent"]]
        question.kind() == "question"
        question.gives()[0].list().versionId() == UUID.fromString(id(7, 1))
        question.terms() == list.terms()
        !question.searchMatches()

        when:
        versions.detail(groupId(OTHER), versionId(id(7, 1)), READER)
        then:
        thrown(ApiErrorException)
    }

    def "independent workflow detail returns both nonempty nested halves and ordered terms without crossing a second membership"() {
        given:
        add(1, GROUP, "reference_list", "Categories", 1)
        add(2, GROUP, "reference_list", "Categories", 2, 1)
        add(3, GROUP, "reference_list", "Modes", 1)
        add(4, GROUP, "workflow", "Decide how to handle a case", 1)
        store.member(OTHER, PERSON, "operator")
        StepRows.field(store, [id: id(11, 40), owner: id(7, 4), ownerKind: "workflow", side: "takes",
                              position: 1, name: "request", kind: "fields", mustBe: true])
        StepRows.field(store, [id: id(11, 41), owner: id(7, 4), ownerKind: "workflow", side: "gives",
                              position: 1, name: "decision", kind: "fields", mustBe: true])
        [
            [42, "takes", 40, 1, "category", 1],
            [43, "takes", 40, 2, "mode", 3],
            [44, "gives", 41, 1, "mode", 3],
            [45, "gives", 41, 2, "category", 1],
        ].each { serial, side, parent, position, name, list ->
            // DB-SPECIFIC: PostgreSQL ::uuid and ::declaration_side casts preserve the actual nested declaration.
            store.session.sql("""
                    insert into declaration_fields(declaration_field_id, entry_version_id, entry_kind, side,
                                                   parent_field_id, position, name, kind, term_list_version_id,
                                                   must_be_given, created_by)
                    values (?::uuid, ?::uuid, 'workflow', ?::declaration_side, ?::uuid, ?, ?, 'term', ?::uuid,
                            true, ?::uuid)
                    """).params(id(11, serial), id(7, 4), side, id(11, parent), position, name, id(7, list), SEEDER).update()
        }
        [1: ["later", "urgent"], 3: ["manual", "automatic"]].each { list, terms ->
            terms.eachWithIndex { term, position ->
                // DB-SPECIFIC: PostgreSQL ::uuid casts; term order is the persisted position, not lexical order.
                store.session.sql("""
                        insert into reference_list_terms(entry_version_id, position, term, meaning, created_by)
                        values (?::uuid, ?, ?, ?, ?::uuid)
                        """).params(id(7, list), position + 1, term, "An invented choice: " + term, SEEDER).update()
            }
        }
        store.retired(id(7, 1), LibraryStore.FIRST_STEWARD)
        def categories = new DeclarationAnswers.PinnedAnswer("Categories", UUID.fromString(id(7, 1)), 1,
                "retired", new DeclarationAnswers.NumberedAnswer(UUID.fromString(id(7, 2)), 2, null), null)
        def modes = new DeclarationAnswers.PinnedAnswer("Modes", UUID.fromString(id(7, 3)), 1,
                "in_service", null, null)
        def before = store.contents()

        when:
        def detail = versions.detail(groupId(GROUP), versionId(id(7, 4)), READER)
        then:
        detail.kind() == "workflow"
        detail.version() == new DeclarationAnswers.PinnedAnswer("Decide how to handle a case",
                UUID.fromString(id(7, 4)), 1, "in_service", null, null)
        detail.takes() == [new DeclarationAnswers.FieldAnswer(UUID.fromString(id(11, 40)), "request", null, null,
                "fields", null, null, null, false, null, true, null, null, [
                    new DeclarationAnswers.FieldAnswer(UUID.fromString(id(11, 42)), "category", null, null,
                            "term", null, null, categories, false, null, true, null, null, null, null, null),
                    new DeclarationAnswers.FieldAnswer(UUID.fromString(id(11, 43)), "mode", null, null,
                            "term", null, null, modes, false, null, true, null, null, null, null, null),
                ], null, null)]
        detail.gives() == [new DeclarationAnswers.FieldAnswer(UUID.fromString(id(11, 41)), "decision", null, null,
                "fields", null, null, null, false, null, true, null, null, [
                    new DeclarationAnswers.FieldAnswer(UUID.fromString(id(11, 44)), "mode", null, null,
                            "term", null, null, modes, false, null, true, null, null, null, null, null),
                    new DeclarationAnswers.FieldAnswer(UUID.fromString(id(11, 45)), "category", null, null,
                            "term", null, null, categories, false, null, true, null, null, null, null, null),
                ], null, null)]
        detail.terms() == [(UUID.fromString(id(7, 1))): ["later", "urgent"],
                          (UUID.fromString(id(7, 3))): ["manual", "automatic"]]
        detail.searchWay() == null && detail.searchMatches() == null
        store.contents() == before

        when:
        versions.detail(groupId(OTHER), versionId(id(7, 4)), READER)
        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_IN_VIEW
        store.contents() == before
    }

    private void add(int serial, String group, String kind, String name, int number, int entry = serial) {
        if (entry == serial) store.entry(id(6, entry), group, kind, name)
        store.seeded(id(7, serial), id(6, entry), number)
        store.content(id(7, serial), kind)
    }

    private static String id(int kind, int serial) {
        String.format(Locale.ROOT, "000000%02x-0000-4000-8000-%012x", kind, serial + 0xca00)
    }
}
