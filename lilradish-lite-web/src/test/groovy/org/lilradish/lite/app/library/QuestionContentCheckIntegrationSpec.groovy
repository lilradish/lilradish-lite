package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.Asking
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.inference.SendMeasure
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** What a question version's stored content is held to at submitting, read off a real server. */
class QuestionContentCheckIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000d01"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000d02"

    static final String QUESTION = "00000006-0000-4000-8000-000000000d01"

    static final String LIST = "00000006-0000-4000-8000-000000000d02"

    static final String VERSION = "00000007-0000-4000-8000-000000000d01"

    static final String LIST_VERSION = "00000007-0000-4000-8000-000000000d02"

    static final String DETAILS = "0000000b-0000-4000-8000-000000000d01"

    static final String FIRST = "0000000b-0000-4000-8000-000000000d02"

    static final String SECOND = "0000000b-0000-4000-8000-000000000d03"

    static final String EMPTY = "0000000b-0000-4000-8000-000000000d04"

    static final String TAKEN = "0000000b-0000-4000-8000-000000000d05"

    static final String SAID = "Say what it names."

    /** The longest text one field may declare: written between its two quotes, as long as one value may be. */
    static final int LONGEST_TEXT = 8_388_606

    /** The longest text a question giving back only it may declare, found by the measure itself. */
    static final int EDGE = edge()

    /**
     * The instruction lengthened by what the measure's steps leave short of the most at the edge, so that one
     * asking of a question giving back text of the edge's length sends exactly the most one may.
     */
    static final String SAID_AT_EDGE = SAID + "." * (Declaration.MOST_SENT - SendMeasure.mostSent(asking([summary(EDGE)]), true))

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    QuestionContentCheck check

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "question_check_" + (++databasesMade))
        check = new QuestionContentCheck(store.session)
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "SALES", "Sales")
        store.entry(QUESTION, GROUP, "question", "Summarise a complaint")
        store.seeded(VERSION, QUESTION, 1)
        store.content(VERSION, "question", SAID)
    }

    def "a question is checked as the question kind's content"() {
        expect:
        check.kind() == EntryKind.QUESTION
    }

    /** Two fields beside each other sharing a name: the later is the one named, by its own key. */
    def "a field held inside another is named by its own key, and a field of fields holding none by its"() {
        given:
        givenBack(DETAILS, null, 1, "details", "fields", null, "never")
        givenBack(FIRST, DETAILS, 1, "product", "text", 128, null)
        givenBack(SECOND, DETAILS, 2, "product", "text", null, null)
        givenBack(EMPTY, null, 2, "notes", "fields", null, "always")

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        problems == [
                new ContentProblem(ContentProblemCode.NAME_REPEATED,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(SECOND)), null),
                new ContentProblem(ContentProblemCode.LONGEST_MISSING,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(SECOND)), null),
                new ContentProblem(ContentProblemCode.NO_FIELDS_HELD,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(EMPTY)), null)]
    }

    /**
     * A field one past what one asking may send is named where it is, and nothing is measured beside it; one
     * the field allows, the asking as a whole still runs past, and says by how much as the measure counts it.
     */
    def "a field past what one asking may send is named by its key, and an asking past it is named as a whole"() {
        given:
        givenBack(FIRST, null, 1, "summary", "text", longest, "always")

        expect:
        check.problemsIn(groupId(GROUP), versionId(VERSION)) == named

        where:
        longest          || named
        LONGEST_TEXT + 1 || [new ContentProblem(ContentProblemCode.LIMIT_PAST_LARGEST,
                new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(FIRST)), null)]
        LONGEST_TEXT     || [new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST,
                new ContentPlace.Whole(ContentPart.ASKING), excessOf(asking([summary(LONGEST_TEXT)])))]
    }

    /** The measure's own edge, read through the store: exactly the most one asking may send, and one character more. */
    def "a question whose asking sends exactly the most one may has nothing to name, and one character more does"() {
        given:
        store.session.sql("update question_versions set instruction = ? where entry_version_id = ?::uuid")
                .params(SAID_AT_EDGE, VERSION).update()
        givenBack(FIRST, null, 1, "summary", "text", EDGE + over, "always")
        assert SendMeasure.mostSent(saying(SAID_AT_EDGE, [summary(EDGE)]), true) == Declaration.MOST_SENT

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        problems*.code() == codes
        problems*.excess() == excesses

        where:
        over || codes                                    | excesses
        0    || []                                       | []
        1    || [ContentProblemCode.ASKING_PAST_LARGEST] | [excessOf(saying(SAID_AT_EDGE, [summary(EDGE + 1)]))]
    }

    /** No field holds text at all, yet a number is written out too, so each many is named and nothing is measured. */
    def "a question holding a many within a many of numbers is named at each many, and the number within is not"() {
        given:
        givenBack(DETAILS, null, 1, "details", "fields", null, "always")
        givenBack(FIRST, DETAILS, 1, "items", "fields", null, null)
        givenBack(SECOND, FIRST, 1, "amount", "number", null, null)
        heldMany(DETAILS, Integer.MAX_VALUE)
        heldMany(FIRST, Integer.MAX_VALUE)

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        problems == [
                new ContentProblem(ContentProblemCode.LIMIT_PAST_LARGEST,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(DETAILS)), null),
                new ContentProblem(ContentProblemCode.LIMIT_PAST_LARGEST,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(FIRST)), null)]
    }

    /** What a question takes is sent with every asking, so it is measured with what it gives back. */
    def "a question taking text is measured with what it takes"() {
        given:
        taken(TAKEN, 1, "letter", 1000)
        givenBack(FIRST, null, 1, "summary", "text", LONGEST_TEXT, "always")
        def withTheTake = new Asking(new Instruction(SAID), [summary(1000, "letter", false)], [summary(LONGEST_TEXT)])

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        problems == [new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST, new ContentPlace.Whole(ContentPart.ASKING),
                excessOf(withTheTake))]
        problems*.excess() != [excessOf(asking([summary(LONGEST_TEXT)]))]
    }

    /** The terms a field pins are told, so they are measured, read from the group the version is submitted in. */
    def "a question pinning a list of the group's is measured with that list's terms"() {
        given:
        pinnedCategories()
        def offered = new OfferedTerms([
                new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge is disputed.")),
                new OfferedTerms.Offered(new Term("Delivery"), new TermMeaning("It came late."))],
                new ListNote("Choose the one asked for."))

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        problems == [new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST, new ContentPlace.Whole(ContentPart.ASKING),
                excessOf(asking([summary(LONGEST_TEXT), category(offered)])))]
    }

    def "a question pinning a list of the group's is not measured as though the list offered nothing"() {
        given:
        pinnedCategories()

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        problems*.excess() != [excessOf(asking([summary(LONGEST_TEXT), category(new OfferedTerms([], null))]))]
    }

    def "a question pinning a list the group does not hold is a store gone wrong, and is not measured"() {
        given:
        store.entry(LIST, OTHER_GROUP, "reference_list", "Regions")
        store.seeded(LIST_VERSION, LIST, 1)
        store.content(LIST_VERSION, "reference_list", null)
        givenBack(FIRST, null, 1, "summary", "text", 1000, "always")
        givenTerm(SECOND, 2, "region", LIST_VERSION)

        when:
        check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Question version " + VERSION + " pins a list this group does not hold"
    }

    def "a question telling whoever answers it what to do, and giving back what holds, has nothing to name"() {
        given:
        givenBack(DETAILS, null, 1, "summary", "text", 1000, "always")

        expect:
        check.problemsIn(groupId(GROUP), versionId(VERSION)) == []
    }

    def "a version holding no question content is a store gone wrong for a question submitted"() {
        when:
        check.problemsIn(groupId(GROUP), versionId("00000007-0000-4000-8000-000000000d09"))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Question version 00000007-0000-4000-8000-000000000d09 holds no question content"
    }

    private static int edge() {
        int fits = 1
        int past = 8_388_608
        while (past - fits > 1) {
            int middle = (fits + past).intdiv(2) as int
            if (SendMeasure.mostSent(asking([summary(middle)]), true) <= Declaration.MOST_SENT) {
                fits = middle
            } else {
                past = middle
            }
        }
        fits
    }

    private static Asking asking(List<AskedField> gives) {
        saying(SAID, gives)
    }

    private static Asking saying(String instruction, List<AskedField> gives) {
        new Asking(new Instruction(instruction), [], gives)
    }

    /** Given back, it must be given as {@link #givenBack} stores it; taken, it need not be, as {@link #taken} does. */
    private static AskedField summary(long longest, String name = "summary", boolean mustBeGiven = true) {
        new AskedField(new FieldName(name), FieldKind.TEXT, longest as int, null, null, [], mustBeGiven, false)
    }

    private static AskedField category(OfferedTerms offered) {
        new AskedField(new FieldName("category"), FieldKind.TERM, null, null, offered, [], true, false)
    }

    private static long excessOf(Asking asking) {
        SendMeasure.mostSent(asking, true) - Declaration.MOST_SENT
    }

    /** A list of the group's, of two terms, pinned by a term given back beside text at the most one field holds. */
    private void pinnedCategories() {
        store.entry(LIST, GROUP, "reference_list", "Categories")
        store.seeded(LIST_VERSION, LIST, 1)
        store.content(LIST_VERSION, "reference_list", "Choose the one asked for.")
        termOf(LIST_VERSION, 1, "Billing", "A charge is disputed.")
        termOf(LIST_VERSION, 2, "Delivery", "It came late.")
        givenBack(FIRST, null, 1, "summary", "text", LONGEST_TEXT, "always")
        givenTerm(SECOND, 2, "category", LIST_VERSION)
    }

    /** A field the version gives back, held by {@code parent} where one is named. */
    private void givenBack(String field, String parent, int position, String name, String kind, Integer longest,
                           String standing) {
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                parent_field_id, position, name, kind, text_limit, must_be_given,
                                                standing, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', ?::uuid, ?, ?, ?::field_kind, ?, true,
                        ?::field_standing, ?::uuid)
                """).params(field, VERSION, parent, position, name, kind, longest, standing, SEEDER).update()
    }

    /** The field named, made to hold as many as {@code most}. */
    private void heldMany(String field, int most) {
        store.session.sql("update declaration_fields set holds_many = true, many_limit = ? where declaration_field_id = ?::uuid")
                .params(most, field).update()
    }

    /** A text the version takes, which need not be given. */
    private void taken(String field, int position, String name, int longest) {
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position,
                                                name, kind, text_limit, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', 'takes', ?, ?, 'text', ?, false, ?::uuid)
                """).params(field, VERSION, position, name, longest, SEEDER).update()
    }

    /** A term the version gives back, standing always, of the list version named. */
    private void givenTerm(String field, int position, String name, String list) {
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position,
                                                name, kind, term_list_version_id, must_be_given, standing, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', ?, ?, 'term', ?::uuid, true, 'always', ?::uuid)
                """).params(field, VERSION, position, name, list, SEEDER).update()
    }

    private void termOf(String list, int position, String term, String meaning) {
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, ?, ?::uuid)
                """).params(list, position, term, meaning, SEEDER).update()
    }
}
