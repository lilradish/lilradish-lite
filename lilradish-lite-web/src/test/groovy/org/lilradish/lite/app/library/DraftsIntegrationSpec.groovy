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
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Writing a draft's content through the one way in, on a real server running the real baseline: a
 * reference list's note and terms stand in for whatever a kind writes.
 */
class DraftsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000801"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000802"

    static final String ANN = "00000002-0000-4000-8000-000000000801"

    static final String BEN = "00000002-0000-4000-8000-000000000802"

    static final UserId ANN_USER = new UserId("000801")

    static final UserId DAN_USER = new UserId("000804")

    static final String ENTRY = "00000006-0000-4000-8000-000000000801"

    static final String OTHER_ENTRY = "00000006-0000-4000-8000-000000000802"

    static final String VERSION = "00000007-0000-4000-8000-000000000801"

    static final String OTHER_VERSION = "00000007-0000-4000-8000-000000000802"

    static final String NOTE = "update reference_list_versions set note = 'Pick one.' where entry_version_id = ?::uuid"

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
        store = LibraryStore.copied(server, "drafts_" + (++databasesMade))
        drafts = new Drafts(store.session, store.transactions(), new GroupRoles(store.session))
        store.person(ANN, "000801")
        store.person(BEN, "000802")
        store.person("00000002-0000-4000-8000-000000000804", "000804")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "operator")
        store.member(GROUP, BEN, "overseer")
        store.entry(ENTRY, GROUP, "reference_list", "Complaint categories")
        store.version(VERSION, ENTRY, 1, BEN)
        store.content(VERSION, "reference_list")
    }

    /** A stopped entry is written alike: stopping is about running and not about writing. */
    def "writes a draft through its kind's content in the same change, the caller one who wrote it from then on"() {
        given:
        if (stopped) {
            store.stopped(ENTRY, FIRST_STEWARD)
        }

        when:
        def written = write(VERSION, ANN_USER) { draft ->
            store.session.sql(NOTE).param(draft.version().value()).update()
            draft.kind()
        }

        then:
        written == EntryKind.REFERENCE_LIST
        store.texts("select note from reference_list_versions") == ["Pick one."]
        store.texts("select created_by || ' ' || created_by_kind from entry_version_writers") == ["${ANN} person" as String]
        store.texts("select revision::text from entry_versions") == ["2"]

        where:
        stopped << [false, true]
    }

    def "a writer writing a draft again is still recorded once"() {
        given:
        store.writer(VERSION, ANN)

        when:
        write(VERSION, ANN_USER) { draft -> null }

        then:
        store.texts("select created_by::text from entry_version_writers") == [ANN]
    }

    def "the starter writing their own draft is recorded as no writer beside it"() {
        given:
        store.entry(OTHER_ENTRY, GROUP, "reference_list", "Channels")
        store.version(OTHER_VERSION, OTHER_ENTRY, 1, ANN)
        store.content(OTHER_VERSION, "reference_list")

        when:
        drafts.write(groupId(GROUP), EntryKind.REFERENCE_LIST, entryId(OTHER_ENTRY), versionId(OTHER_VERSION),
                ANN_USER, 1) { draft -> store.session.sql(NOTE).param(draft.version().value()).update() }

        then:
        store.texts("select note from reference_list_versions where note is not null") == ["Pick one."]
        store.count("select count(*) from entry_version_writers") == 0
    }

    /** A submitted version's content stops changing the moment it is submitted, and never changes again. */
    def "a version that is not a draft is written by nobody, and what its kind would write is never asked"() {
        given:
        store.submitted(VERSION, ANN)
        if (at == "in service" || at == "retired") {
            store.approved(VERSION, FIRST_STEWARD)
        }
        if (at == "retired") {
            store.retired(VERSION, FIRST_STEWARD)
        }
        def before = store.contents()
        def asked = false

        when:
        write(VERSION, ANN_USER) { draft -> asked = true }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_STANDING_REFUSES
        !asked
        store.contents() == before

        where:
        at << ["submitted", "in service", "retired"]
    }

    def "a draft of no entry of the kind in the group, or asked by somebody in no role there, is refused unasked"() {
        given:
        store.entry(OTHER_ENTRY, OTHER_GROUP, "reference_list", "Categories")
        store.version(OTHER_VERSION, OTHER_ENTRY, 1, BEN)
        def before = store.contents()
        def asked = false

        when:
        drafts.write(groupId(GROUP), kind, entryId(entry), versionId(named), caller, 1) { draft -> asked = true }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == refusal
        !asked
        store.contents() == before

        where:
        kind                     | entry       | named         | caller   || refusal
        EntryKind.REFERENCE_LIST | OTHER_ENTRY | OTHER_VERSION | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        EntryKind.REFERENCE_LIST | ENTRY       | OTHER_VERSION | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        EntryKind.QUESTION       | ENTRY       | VERSION       | ANN_USER || RefusalCode.VERSION_NOT_IN_VIEW
        EntryKind.REFERENCE_LIST | ENTRY       | VERSION       | DAN_USER || RefusalCode.GROUP_NOT_IN_VIEW
    }

    /** Behind is a reading somebody wrote over since; ahead is one no reading of this draft ever gave. */
    def "a write naming a revision the draft is not at is refused before its kind is asked, and nothing lands"() {
        given:
        store.session.sql("update entry_versions set revision = 3 where entry_version_id = ?::uuid").param(VERSION).update()
        def before = store.contents()
        def asked = false

        when:
        write(VERSION, ANN_USER, seen) { draft -> asked = true }

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.DRAFT_WRITTEN_SINCE_READ
        refused.message == "Somebody changed this draft since it was read; nothing was saved."
        !asked
        store.contents() == before

        where:
        seen << [1, 2, 4]
    }

    def "a revision no reading could give is refused before the draft is so much as looked at"() {
        given:
        def before = store.contents()
        def asked = false

        when:
        write(VERSION, ANN_USER, seen) { draft -> asked = true }

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Drafts seen revision must be at least 1, but was ${seen}" as String
        !asked
        store.contents() == before

        where:
        seen << [0, -1, Integer.MIN_VALUE]
    }

    /**
     * Two saves made from one reading: the second waits on the draft the first holds, and once the first has
     * landed it finds the revision moved on and is refused, so the first is never silently written over.
     */
    def "a second write from the same reading, waiting on the first, is refused once the first has landed"() {
        given:
        def writing = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def askedSecond = false

        when:
        def first = attempting(racing) {
            write(VERSION, ANN_USER) { draft ->
                store.session.sql(NOTE).param(draft.version().value()).update()
                writing.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        writing.await(10, TimeUnit.SECONDS)
        def second = attempting(racing) { write(VERSION, ANN_USER) { draft -> askedSecond = true } }
        store.untilWaiting(1)
        release.countDown()

        then:
        first.get(10, TimeUnit.SECONDS) == true
        def outcome = second.get(10, TimeUnit.SECONDS)
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.DRAFT_WRITTEN_SINCE_READ
        !askedSecond
        store.texts("select note from reference_list_versions") == ["Pick one."]
        store.texts("select revision::text from entry_versions") == ["2"]
    }

    def "what a kind's content fails with undoes the whole change, the caller recorded as no writer"() {
        given:
        def before = store.contents()
        def failure = new IllegalStateException("the content could not be written")

        when:
        write(VERSION, ANN_USER) { draft ->
            store.session.sql(NOTE).param(draft.version().value()).update()
            throw failure
        }

        then:
        def thrownOut = thrown(IllegalStateException)
        thrownOut.is(failure)
        store.contents() == before
    }

    /**
     * Two terms given one position collide only when the change ends. Brought forward, the store refuses them
     * at a statement inside the change: the change never reaches the moment before it commits.
     */
    def "a uniqueness checked only at commit refuses inside the change, before it would commit, and nothing lands"() {
        given:
        def before = store.contents()
        def reachedCommit = false

        when:
        write(VERSION, ANN_USER) { draft ->
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                void beforeCommit(boolean readOnly) {
                    reachedCommit = true
                }
            })
            ["Billing", "Delivery"].each { term ->
                store.session.sql("""
                        insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                        values (?::uuid, 1, ?, 'A meaning.', ?::uuid)
                        """).params(draft.version().value(), term, ANN).update()
            }
        }

        then:
        thrown(DuplicateKeyException)
        !reachedCommit
        store.contents() == before
    }

    /**
     * The write holds the draft while its content is written. A submission of that draft waits on it, and
     * what it submits is the draft as the write left it, never a draft half written: without the term the
     * write adds, the submission would be refused.
     */
    def "a submission arriving while a draft is being written waits until the writing has landed"() {
        given:
        def writing = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def submissions = new VersionChanges(store.session, store.transactions(), new GroupRoles(store.session),
                new ContentChecks(store.session, [new QuestionContentCheck(store.session), new WorkflowContentCheck(store.session, LibraryStore.MODELS,
                                   new ReleasedCodeSteps(LibraryStore.CODE_STEPS)),
                                   new ReferenceListContentCheck(store.session)]))

        when:
        def written = attempting(racing) {
            write(VERSION, ANN_USER) { draft ->
                store.session.sql(NOTE).param(draft.version().value()).update()
                store.session.sql("""
                        insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                        values (?::uuid, 1, 'Billing', 'A charge is disputed.', ?::uuid)
                        """).params(draft.version().value(), ANN).update()
                writing.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        writing.await(10, TimeUnit.SECONDS)
        def submitting = attempting(racing) {
            submissions.submit(groupId(GROUP), EntryKind.REFERENCE_LIST, entryId(ENTRY), versionId(VERSION), ANN_USER)
        }
        store.untilWaiting(1)
        def submittedWhileWritten = store.count("select count(*) from entry_version_submissions")
        release.countDown()

        then:
        written.get(10, TimeUnit.SECONDS) == true
        submitting.get(10, TimeUnit.SECONDS) == null
        submittedWhileWritten == 0
        store.count("select count(*) from entry_version_submissions") == 1
        store.texts("select note from reference_list_versions") == ["Pick one."]
    }

    /**
     * The roles are taken by a change holding the group, which the write waits on; once it lands, what the
     * caller holds is read again, and the write is refused before its kind is ever asked.
     */
    def "a write whose caller loses their roles while it waits on the group is refused, its kind never asked"() {
        given:
        store.repeatableReadByDefault()
        def before = store.contents("group_members")
        def taking = store.takingRoles(GROUP, ANN)
        def asked = false

        when:
        def writing = attempting(racing) { write(VERSION, ANN_USER) { draft -> asked = true } }
        store.untilWaiting(1)
        taking.commit()
        taking.close()
        def outcome = writing.get(10, TimeUnit.SECONDS)

        then:
        outcome instanceof ApiErrorException
        outcome.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW
        !asked
        store.contents("group_members") == before
    }

    /** Every draft here is read at its first revision unless a case says otherwise. */
    private <T> T write(String version, UserId caller, DraftContent<T> content) {
        write(version, caller, 1, content)
    }

    private <T> T write(String version, UserId caller, int seen, DraftContent<T> content) {
        drafts.write(groupId(GROUP), EntryKind.REFERENCE_LIST, entryId(ENTRY), versionId(version), caller, seen,
                content)
    }
}
