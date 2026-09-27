package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** What a model is told of a question version a step pins, read off a real server running the real baseline. */
class QuestionsAskedIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000b01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000b02"

    static final String QUESTION = "00000006-0000-4000-8000-000000000b01"

    static final String VERSION = "00000007-0000-4000-8000-000000000b01"

    static final String LIST = "00000006-0000-4000-8000-000000000b02"

    static final String LIST_VERSION = "00000007-0000-4000-8000-000000000b02"

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000b03"

    static final String WORKFLOW_VERSION = "00000007-0000-4000-8000-000000000b03"

    static final String UNAPPROVED_QUESTION = "00000006-0000-4000-8000-000000000b04"

    static final String UNAPPROVED = "00000007-0000-4000-8000-000000000b04"

    static final String FOREIGN_LIST = "00000006-0000-4000-8000-000000000b05"

    static final String FOREIGN_LIST_VERSION = "00000007-0000-4000-8000-000000000b05"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    QuestionsAsked asked

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "asked_" + (++databasesMade))
        asked = new QuestionsAsked(store.session, store.transactionManager())
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(LIST_VERSION, LIST, 1)
        store.content(LIST_VERSION, "reference_list")
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'Billing', 'A charge is what is disputed.', ?::uuid)
                """).params(LIST_VERSION, SEEDER).update()
        store.entry(QUESTION, GROUP, "question", "Summarise a complaint")
        store.seeded(VERSION, QUESTION, 1)
        store.content(VERSION, "question", "Say which category.")
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, label, help, kind,
                                                holds_many, text_limit, many_limit, term_list_version_id,
                                                must_be_given, standing, standing_threshold, created_by)
                values (?::uuid, 'question', 'takes', 1, 'complaint', 'The complaint', 'As written.', 'text', false,
                        4000, null, null, true, null, null, ?::uuid),
                       (?::uuid, 'question', 'gives', 1, 'category', 'Category', null, 'term', true, null, 2,
                        ?::uuid, true, 'above_confidence', 90, ?::uuid)
                """).params(VERSION, SEEDER, VERSION, LIST_VERSION, SEEDER).update()
    }

    /** Labels, help and the floor are held by the version and told to no model: none of them is in what is read. */
    def "a question version is told as its instruction and both halves, each list's terms beside its field"() {
        when:
        def asking = asked.of(groupId(GROUP), versionId(VERSION))

        then:
        asking.instruction() == new Instruction("Say which category.")
        asking.takes() ==
                [new AskedField(new FieldName("complaint"), FieldKind.TEXT, 4000, null, null, [], true, false)]
        asking.gives() == [new AskedField(new FieldName("category"), FieldKind.TERM, null, 2, new OfferedTerms(
                [new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge is what is disputed."))], null),
                [], true, true)]
    }

    /** The words are told to a model as the list's own, so a stored one their type refuses is a store gone wrong. */
    def "a pinned list holding words their types refuse fails rather than being told to a model"() {
        given:
        store.session.sql(change).params(invisible).update()

        when:
        asked.of(groupId(GROUP), versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Version ${VERSION} pins a list holding words this system will not show" as String
        failed.cause instanceof IllegalArgumentException

        where:
        change                                                                         | invisible
        "update reference_list_terms set term = 'Bill' || ? || 'ing'"                  | Character.toString(0x200B)
        "update reference_list_terms set meaning = 'A charge' || ?"                    | Character.toString(0x202E)
        "update reference_list_versions set note = 'Choose' || ?"                      | Character.toString(0xE0041)
    }

    /** Read inside a transaction reading one moment, it joins it and reads that moment. */
    def "a question version is told inside a transaction reading one moment"() {
        given:
        def snapshot = new TransactionTemplate(store.transactionManager())
        snapshot.isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE

        expect:
        snapshot.execute { asked.of(groupId(GROUP), versionId(VERSION)) }.instruction() ==
                new Instruction("Say which category.")
    }

    /** Joined, a transaction reading more than one moment would read the halves at one and the lists at another. */
    def "a question version is never read inside a transaction reading more than one moment"() {
        given:
        def weaker = new TransactionTemplate(store.transactionManager())
        weaker.isolationLevel = isolation

        when:
        weaker.execute { asked.of(groupId(GROUP), versionId(VERSION)) }

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "QuestionsAsked was asked inside a transaction reading more than one moment"

        where:
        isolation << [TransactionDefinition.ISOLATION_DEFAULT, TransactionDefinition.ISOLATION_READ_COMMITTED]
    }

    /** A step pins only what submitting took, so a version it would refuse is a store gone wrong. */
    def "a version submitting would refuse fails rather than being told to a model"() {
        given:
        store.session.sql(change).update()

        when:
        asked.of(groupId(GROUP), versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Question version ${VERSION} holds what submitting would refuse" as String

        where:
        change << ["update declaration_fields set text_limit = null where name = 'complaint'",
                   "update question_versions set instruction = null"]
    }

    /** Only the group's own questions: another group's, a workflow's and one nobody holds are all none. */
    def "a version of no question the group holds fails rather than being told as one"() {
        given:
        store.entry(WORKFLOW, GROUP, "workflow", "Handle")
        store.seeded(WORKFLOW_VERSION, WORKFLOW, 1)
        store.content(WORKFLOW_VERSION, "workflow")

        when:
        asked.of(groupId(group), versionId(version))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Group ${group} holds no approved question version ${version}" as String

        where:
        group       | version
        OTHER_GROUP | VERSION
        GROUP       | WORKFLOW_VERSION
        GROUP       | "00000007-0000-4000-8000-000000000b09"
    }

    /** Only a version once approved is what a step pins; one still a draft or waiting on approval is not. */
    def "a question version never approved fails rather than being told to a model"() {
        given:
        store.entry(UNAPPROVED_QUESTION, GROUP, "question", "Summarise a refund")
        store.version(UNAPPROVED, UNAPPROVED_QUESTION, 1, FIRST_STEWARD)
        store.content(UNAPPROVED, "question", "Say which category.")
        if (submitted) {
            store.submitted(UNAPPROVED, FIRST_STEWARD)
        }

        when:
        asked.of(groupId(GROUP), versionId(UNAPPROVED))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Group ${GROUP} holds no approved question version ${UNAPPROVED}" as String

        where:
        submitted << [false, true]
    }

    /** Terms of a list another group holds are that group's to tell, whichever question pins them. */
    def "a question version pinning another group's list fails rather than telling that list's terms"() {
        given:
        store.entry(FOREIGN_LIST, OTHER_GROUP, "reference_list", "Their categories")
        store.seeded(FOREIGN_LIST_VERSION, FOREIGN_LIST, 1)
        store.content(FOREIGN_LIST_VERSION, "reference_list")
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, 1, 'Secret', 'Only they may read this.', ?::uuid)
                """).params(FOREIGN_LIST_VERSION, SEEDER).update()
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind, holds_many,
                                                term_list_version_id, must_be_given, standing, created_by)
                values (?::uuid, 'question', 'gives', 2, 'theirs', 'term', false, ?::uuid, true, 'always', ?::uuid)
                """).params(VERSION, FOREIGN_LIST_VERSION, SEEDER).update()

        when:
        asked.of(groupId(GROUP), versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Question version ${VERSION} pins a list this group does not hold" as String
    }
}
