package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.entryId
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A draft pinning a version of another entry, on a real server running the real baseline: a workflow's
 * draft whose step runs a question stands in for whatever pins a version.
 */
class OpenDraftIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"

    static final String ANN = "00000002-0000-4000-8000-000000000801"

    static final UserId ANN_USER = new UserId("000801")

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000801"

    static final String QUESTION = "00000006-0000-4000-8000-000000000802"

    static final String SUBMITTED_QUESTION = "00000006-0000-4000-8000-000000000803"

    static final String OTHER_QUESTION = "00000006-0000-4000-8000-000000000804"

    static final String DRAFT = "00000007-0000-4000-8000-000000000801"

    static final String RETIRED = "00000007-0000-4000-8000-000000000802"

    static final String IN_SERVICE = "00000007-0000-4000-8000-000000000803"

    static final String QUESTION_DRAFT = "00000007-0000-4000-8000-000000000804"

    static final String SUBMITTED = "00000007-0000-4000-8000-000000000805"

    static final String OTHER_IN_SERVICE = "00000007-0000-4000-8000-000000000806"

    static final String OTHER_DRAFT = "00000007-0000-4000-8000-000000000807"

    static final String STEP = "0000000c-0000-4000-8000-000000000801"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    Drafts drafts

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newFixedThreadPool(2)

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "pins_" + (++databasesMade))
        drafts = new Drafts(store.session, store.transactions(), new GroupRoles(store.session))
        store.person(ANN, "000801")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.member(OTHER_GROUP, ANN, "owner")
        store.entry(WORKFLOW, GROUP, "workflow", "Handle a complaint")
        store.version(DRAFT, WORKFLOW, 1, ANN)
        store.content(DRAFT, "workflow")
        store.entry(QUESTION, GROUP, "question", "Summarise a complaint")
        store.seeded(RETIRED, QUESTION, 1, true)
        store.seeded(IN_SERVICE, QUESTION, 2)
        store.version(QUESTION_DRAFT, QUESTION, 3, ANN)
        store.entry(SUBMITTED_QUESTION, GROUP, "question", "Classify a complaint")
        store.version(SUBMITTED, SUBMITTED_QUESTION, 1, ANN)
        store.submitted(SUBMITTED, ANN)
        store.entry(OTHER_QUESTION, OTHER_GROUP, "question", "Summarise an invoice")
        store.seeded(OTHER_IN_SERVICE, OTHER_QUESTION, 1)
        store.version(OTHER_DRAFT, OTHER_QUESTION, 2, ANN)
    }

    def "a draft pins a version of its own group in service, found with its kind, and a step holds it"() {
        when:
        def pinned = write { draft ->
            def pinnable = draft.pinInService(versionId(IN_SERVICE))
            step(draft, pinnable)
            pinnable
        }

        then:
        pinned.version() == versionId(IN_SERVICE)
        pinned.kind() == EntryKind.QUESTION
        store.texts("select pinned_version_id || ' ' || pinned_kind from workflow_steps") == ["${IN_SERVICE} question" as String]
    }

    /** Another group's version is refused whatever its standing, alike with one that is not there at all. */
    def "every version not this group's or not in service is refused alike, whether it exists or not"() {
        given:
        def before = store.contents()

        when:
        write { draft -> step(draft, draft.pinInService(versionId(target))) }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_PINNABLE
        refused.message == "Only a version of this group's in service may be pinned."
        store.contents() == before

        where:
        target << [RETIRED, QUESTION_DRAFT, SUBMITTED, OTHER_IN_SERVICE, OTHER_DRAFT, "00000009-0000-4000-8000-000000000009"]
    }

    /** The retirement holds the version and has not committed; pinning it waits, then reads it retired or not. */
    def "pinning a version waits on its retirement, and is refused where the retirement lands"() {
        given:
        store.repeatableReadByDefault()
        def retiring = store.holding("update entry_versions set retired_at = now(), retired_by = '${FIRST_STEWARD}'," +
                " retired_by_kind = 'person' where entry_version_id = '${IN_SERVICE}'")

        when:
        def pinning = attempting(racing) { write { draft -> step(draft, draft.pinInService(versionId(IN_SERVICE))) } }
        store.untilWaiting(1)
        retirementLands ? retiring.commit() : retiring.rollback()
        retiring.close()
        def outcome = pinning.get(10, TimeUnit.SECONDS)

        then:
        retirementLands == (outcome instanceof ApiErrorException)
        !retirementLands || outcome.errorCode() == RefusalCode.VERSION_NOT_PINNABLE
        store.count("select count(*) from workflow_steps") == (retirementLands ? 0 : 1)

        where:
        retirementLands << [true, false]
    }

    /** What a draft pinned while it was in service goes on pinning it once it is retired. */
    def "retiring a version waits on a draft pinning it until that draft's change ends"() {
        given:
        def pinned = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def retirements = new VersionChanges(store.session, store.transactions(), new GroupRoles(store.session),
                new ContentChecks(store.session, [new QuestionContentCheck(store.session), new WorkflowContentCheck(store.session, LibraryStore.MODELS,
                                   new ReleasedCodeSteps(LibraryStore.CODE_STEPS)),
                                   new ReferenceListContentCheck(store.session)]))

        when:
        def pinning = attempting(racing) {
            write { draft ->
                step(draft, draft.pinInService(versionId(IN_SERVICE)))
                pinned.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        pinned.await(10, TimeUnit.SECONDS)
        def retiring = attempting(racing) {
            retirements.retire(groupId(GROUP), EntryKind.QUESTION, entryId(QUESTION), versionId(IN_SERVICE), ANN_USER)
        }
        store.untilWaiting(1)
        def retiredWhilePinned = store.count("select count(*) from entry_versions where retired_at is not null")
        release.countDown()

        then:
        pinning.get(10, TimeUnit.SECONDS) == true
        retiring.get(10, TimeUnit.SECONDS) == null
        retiredWhilePinned == 1
        store.texts("select pinned_version_id::text from workflow_steps") == [IN_SERVICE]
        store.count("select count(*) from entry_versions where retired_at is not null") == 2
    }

    def "a draft asked anything once its change has ended refuses to answer"() {
        given:
        OpenDraft kept = null
        write { draft -> kept = draft }

        when:
        question == "pinInService" ? kept.pinInService(versionId(IN_SERVICE)) : kept."${question}"()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "OpenDraft was asked after the change writing it had ended"

        where:
        question << ["pinInService", "version", "kind"]
    }

    /** A finding holds only for the draft that found it, so one kept from an earlier change pins nothing here. */
    def "a version another draft found pinnable is refused by the draft it is handed to, and nothing is pinned"() {
        given:
        OpenDraft.PinnableVersion kept = null
        write { draft -> kept = draft.pinInService(versionId(IN_SERVICE)) }
        def before = store.contents()

        when:
        write { draft -> step(draft, kept) }

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "OpenDraft was handed a version another draft found pinnable"
        store.contents() == before
        store.count("select count(*) from workflow_steps") == 0
    }

    def "a draft whose change has ended refuses even its own finding"() {
        given:
        OpenDraft kept = null
        OpenDraft.PinnableVersion found = null
        write { draft ->
            kept = draft
            found = draft.pinInService(versionId(IN_SERVICE))
        }

        when:
        kept.requireIssued(found)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "OpenDraft was asked after the change writing it had ended"
    }

    /** Named at whatever revision the draft stands at, so no write here is refused for another's. */
    private Object write(Closure content) {
        int seen = store.count("select revision from entry_versions where entry_version_id = ?::uuid", DRAFT)
        drafts.write(groupId(GROUP), EntryKind.WORKFLOW, entryId(WORKFLOW), versionId(DRAFT), ANN_USER, seen,
                content as DraftContent)
    }

    /** What a kind's pin writer does: asks the draft whether the finding is its own, then writes the pin. */
    private void step(OpenDraft draft, OpenDraft.PinnableVersion pinned) {
        draft.requireIssued(pinned)
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                                            pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, 1, 'summarise', 'entry', ?::uuid, ?::entry_kind, ?::uuid)
                """).params(STEP, draft.version().value(), pinned.version().value(),
                pinned.kind().name().toLowerCase(Locale.ROOT), ANN).update()
    }
}
