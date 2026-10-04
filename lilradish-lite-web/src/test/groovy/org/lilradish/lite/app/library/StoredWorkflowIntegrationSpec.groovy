package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.search.SearchWay
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.SearchQuestionPart
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification


class StoredWorkflowIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000a81"

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000a81"

    static final String VERSION = "00000007-0000-4000-8000-000000000a81"

    static final String QUESTION = "00000006-0000-4000-8000-000000000a82"

    static final String ASKED = "00000007-0000-4000-8000-000000000a82"

    static final String OTHER_QUESTION = "00000006-0000-4000-8000-000000000a83"

    static final String ASKED_AGAIN = "00000007-0000-4000-8000-000000000a83"

    static final String STEP = "0000000c-0000-4000-8000-000000000a81"

    static final String FIRST_STEP = "0000000c-0000-4000-8000-000000000a82"

    static final String SECOND_STEP = "0000000c-0000-4000-8000-000000000a83"

    static final String OTHER_WORKFLOW = "00000006-0000-4000-8000-000000000a84"

    static final String NEXT_VERSION = "00000007-0000-4000-8000-000000000a84"

    static final String WRITTEN = "00000020-0000-4000-8000-000000000a81"

    static final String READING_INPUT = "00000020-0000-4000-8000-000000000a82"

    static final String READING_STEP = "00000020-0000-4000-8000-000000000a83"

    static final String OTHER_WRITTEN = "00000020-0000-4000-8000-000000000a84"

    static final String OTHER_READING_INPUT = "00000020-0000-4000-8000-000000000a85"

    static final String THIRD_WORKFLOW = "00000006-0000-4000-8000-000000000a85"

    static final String THIRD_VERSION = "00000007-0000-4000-8000-000000000a85"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "stored_workflows_" + (++databasesMade))
        store.group(GROUP, "SUPPORT", "Customer support")
        store.entry(QUESTION, GROUP, "question", "Classify")
        store.seeded(ASKED, QUESTION, 1)
        store.entry(WORKFLOW, GROUP, "workflow", "Handle")
        store.version(VERSION, WORKFLOW, 1, FIRST_STEWARD)
    }

    def "a version holding no workflow content reads as none"() {
        expect:
        StoredWorkflow.read(store.session, versionId(VERSION)).isEmpty()
    }


    def "a model run as it is reads as naming no mode, and a mode named reads as that mode"() {
        given:
        store.content(VERSION, "workflow")
        store.step(STEP, VERSION, 1, ASKED, "question")
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = ?,
                                          reviewer_model = 'small', reviewer_mode = 'ordinary', tries = 2
                """).param(mode).update()

        when:
        def step = StoredWorkflow.read(store.session, versionId(VERSION)).get().steps()[0]

        then:
        step.producer() == new Producer.Model(new ModelChoice(new ModelName("general"), read), false)
        step.reviewer() == new ModelChoice(new ModelName("small"), null)

        where:
        mode       || read
        "ordinary" || null
        "research" || new ModelMode("research")
    }


    def "a step whose kind or version is not chosen yet reads as running nothing yet"() {
        given:
        store.content(VERSION, "workflow")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, step_kind.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by)
                values (?::uuid, ?::uuid, 1, 'later', ?::step_kind, ?::uuid)
                """).params(STEP, VERSION, kind, SEEDER).update()

        expect:
        StoredWorkflow.read(store.session, versionId(VERSION)).get().steps()*.runs() == [new StoredWorkflow.Runs.Unchosen()]

        where:
        kind << [null, "entry"]
    }


    def "a search reads its way, its question in the order written, each part read by its key, and each standing chosen"() {
        given:
        store.content(VERSION, "workflow")
        store.step(FIRST_STEP, VERSION, 1, ASKED, "question")
        searchStep(STEP, VERSION, 2, "web_search", "always", "never", null, "always")
        part(READING_STEP, VERSION, STEP, 2, null, FIRST_STEP, "summary")
        part(WRITTEN, VERSION, STEP, 0, "Find ", null, null)
        part(READING_INPUT, VERSION, STEP, 1, null, null, "complaint")

        when:
        def stored = StoredWorkflow.read(store.session, versionId(VERSION)).get()

        then:
        stored.steps()*.runs() == [
                new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, versionId(ASKED)),
                new StoredWorkflow.Runs.Search("web_search", [
                        new SearchQuestionPart.LiteralPart("Find "),
                        new SearchQuestionPart.Read(UUID.fromString(READING_INPUT),
                                new BindingSource.WorkflowInput(Pointer.parse("complaint"))),
                        new SearchQuestionPart.Read(UUID.fromString(READING_STEP),
                                new BindingSource.StepOutput(UUID.fromString(FIRST_STEP), Pointer.parse("summary")))],
                        [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.NEVER,
                         (SearchWay.OTHER): FieldStanding.ALWAYS])]
        !(stored.steps()[1].runs() as StoredWorkflow.Runs.Search).standings().containsKey(SearchWay.REFERENCES)
        stored.steps()[1].bindings().isEmpty()
        stored.pinned() as List == [versionId(ASKED)]
    }

    def "a search nothing is chosen for yet reads as running no way, asking nothing and standing nothing"() {
        given:
        store.content(VERSION, "workflow")
        searchStep(STEP, VERSION, 1, null, null, null, null, null)

        expect:
        StoredWorkflow.read(store.session, versionId(VERSION)).get().steps()*.runs() ==
                [new StoredWorkflow.Runs.Search(null, [], [:])]
    }


    def "versions read together each keep only their own steps' parts"() {
        given:
        store.entry(OTHER_WORKFLOW, GROUP, "workflow", "Escalate")
        store.version(NEXT_VERSION, OTHER_WORKFLOW, 1, FIRST_STEWARD)
        store.content(VERSION, "workflow")
        store.content(NEXT_VERSION, "workflow")
        searchStep(STEP, VERSION, 1, "web_search", null, null, null, null)
        searchStep(SECOND_STEP, NEXT_VERSION, 1, "web_search", null, null, null, null)
        part(WRITTEN, NEXT_VERSION, SECOND_STEP, 0, "Later", null, null)
        part(OTHER_WRITTEN, VERSION, STEP, 0, "Earlier", null, null)

        when:
        def read = StoredWorkflow.readAll(store.session, [versionId(VERSION), versionId(NEXT_VERSION)])

        then:
        read.keySet() == [versionId(VERSION), versionId(NEXT_VERSION)] as Set
        read[versionId(VERSION)].steps()*.runs()*.question() == [[new SearchQuestionPart.LiteralPart("Earlier")]]
        read[versionId(NEXT_VERSION)].steps()*.runs()*.question() == [[new SearchQuestionPart.LiteralPart("Later")]]
    }


    def "versions holding searching steps are read together in as many statements as one, and one more than none"() {
        given:
        store.entry(OTHER_WORKFLOW, GROUP, "workflow", "Escalate")
        store.version(NEXT_VERSION, OTHER_WORKFLOW, 1, FIRST_STEWARD)
        store.entry(THIRD_WORKFLOW, GROUP, "workflow", "Close")
        store.version(THIRD_VERSION, THIRD_WORKFLOW, 1, FIRST_STEWARD)
        [VERSION, NEXT_VERSION, THIRD_VERSION].each { store.content(it, "workflow") }
        searchStep(STEP, VERSION, 1, "web_search", null, null, null, null)
        part(WRITTEN, VERSION, STEP, 0, "Find ", null, null)
        part(READING_INPUT, VERSION, STEP, 1, null, null, "complaint")
        searchStep(SECOND_STEP, NEXT_VERSION, 1, "web_search", null, null, null, null)
        part(OTHER_WRITTEN, NEXT_VERSION, SECOND_STEP, 0, "Find ", null, null)
        part(OTHER_READING_INPUT, NEXT_VERSION, SECOND_STEP, 1, null, null, "complaint")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, created_by)
                values (?::uuid, ?::uuid, 1, 'later', ?::uuid)
                """).params(FIRST_STEP, THIRD_VERSION, SEEDER).update()
        def statements = []
        def counted = JdbcClient.create(new CountingDataSource(store.database, statements))
        StoredWorkflow.readAll(counted, [versionId(THIRD_VERSION)])
        def none = statements.size()
        statements.clear()
        StoredWorkflow.readAll(counted, [versionId(VERSION)])
        def one = statements.size()
        statements.clear()

        when:
        def read = StoredWorkflow.readAll(counted, [versionId(VERSION), versionId(NEXT_VERSION)])

        then:
        statements.size() == one
        one == none + 1

        and: "every part of both read"
        read[versionId(VERSION)].steps()*.runs()*.question()*.size() == [2]
        read[versionId(NEXT_VERSION)].steps()*.runs()*.question()*.size() == [2]
    }


    def "a ceiling past the largest a reader holds exactly is a store gone wrong"() {
        given:
        store.content(VERSION, "workflow")
        store.session.sql("update workflow_versions set ceiling = 9007199254740992").update()

        when:
        StoredWorkflow.read(store.session, versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version ${VERSION} holds a setting this system will not read" as String
    }

    def "every version a step pins is named once, in the order the steps run"() {
        given:
        store.entry(OTHER_QUESTION, GROUP, "question", "Triage")
        store.seeded(ASKED_AGAIN, OTHER_QUESTION, 1)
        store.content(VERSION, "workflow")
        store.step(STEP, VERSION, 3, ASKED, "question")
        store.step(SECOND_STEP, VERSION, 2, ASKED, "question")
        store.step(FIRST_STEP, VERSION, 1, ASKED_AGAIN, "question")

        when:
        def stored = StoredWorkflow.read(store.session, versionId(VERSION)).get()

        then:
        stored.pinned() as List == [versionId(ASKED_AGAIN), versionId(ASKED)]
    }

    def "each step's place among the steps is found by its key, and no key of another is"() {
        given:
        store.content(VERSION, "workflow")
        store.step(STEP, VERSION, 2, ASKED, "question")
        store.step(FIRST_STEP, VERSION, 1, ASKED, "question")

        when:
        def positions = StoredWorkflow.read(store.session, versionId(VERSION)).get().positions()

        then:
        positions == [(UUID.fromString(FIRST_STEP)): 0, (UUID.fromString(STEP)): 1]
        !positions.containsKey(UUID.fromString("0000000c-0000-4000-8000-0000000000ff"))
    }


    def "a step stored in a way this system will not read fails the read, naming the step"() {
        given:
        store.content(VERSION, "workflow")
        store.step(STEP, VERSION, 1, ASKED, "question")
        store.session.sql("update workflow_steps set producer = 'model'").update()

        when:
        StoredWorkflow.read(store.session, versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version ${VERSION} holds a step ${STEP} this system will not read" as String
    }


    def "a constant stored in a way this system will not read fails the read"() {
        given:
        store.content(VERSION, "workflow")
        store.step(STEP, VERSION, 1, ASKED, "question")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; jsonb type.
        store.session.sql("""
                insert into bindings (entry_version_id, workflow_step_id, target_path, constant, created_by)
                values (?::uuid, ?::uuid, 'complaint', cast('1e1500' as jsonb), ?::uuid)
                """).params(VERSION, STEP, SEEDER).update()

        when:
        StoredWorkflow.read(store.session, versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A stored constant is not one this system writes"
    }

    private void searchStep(String step, String version, int position, String way, String title, String body,
                            String references, String other) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, field_standing.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, search_way,
                                            search_title_standing, search_body_standing, search_references_standing,
                                            search_other_standing, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'search', ?, ?::field_standing, ?::field_standing, ?::field_standing,
                        ?::field_standing, ?::uuid)
                """).params(step, version, position, "search_" + position, way, title, body, references, other, SEEDER)
                .update()
    }

    private void part(String part, String version, String step, int position, String literal, String sourceStep,
                      String sourcePath) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into search_question_parts (search_question_part_id, entry_version_id, workflow_step_id,
                                                   position, literal_text, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::uuid, ?, ?::uuid)
                """).params(part, version, step, position, literal, sourceStep, sourcePath, SEEDER).update()
    }
}
