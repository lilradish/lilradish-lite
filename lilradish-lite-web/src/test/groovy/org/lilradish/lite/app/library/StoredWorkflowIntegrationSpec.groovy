package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A workflow version's content read off rows written by hand, as a migration or a restore writes them, on a real
 * server running the real baseline.
 */
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

    /** Stored under the store's word for running a model as it is, and read as no mode, which is what that means. */
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

    /** What nothing has chosen yet reads as nothing chosen: an entry without its version, and no kind at all. */
    def "a step whose kind or version is not chosen yet reads as running nothing yet"() {
        given:
        store.content(VERSION, "workflow")
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by)
                values (?::uuid, ?::uuid, 1, 'later', ?::step_kind, ?::uuid)
                """).params(STEP, VERSION, kind, SEEDER).update()

        expect:
        StoredWorkflow.read(store.session, versionId(VERSION)).get().steps()*.runs() == [new StoredWorkflow.Runs.Unchosen()]

        where:
        kind << [null, "entry"]
    }

    /** The settings the store holds come back as the domain holds them, a ceiling past what a reader holds failing. */
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

    /** The store keeps a model producing with no model named, which no step this system writes holds. */
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

    /** The store keeps numbers far longer than any reader of a document takes, which no constant written here holds. */
    def "a constant stored in a way this system will not read fails the read"() {
        given:
        store.content(VERSION, "workflow")
        store.step(STEP, VERSION, 1, ASKED, "question")
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
}
