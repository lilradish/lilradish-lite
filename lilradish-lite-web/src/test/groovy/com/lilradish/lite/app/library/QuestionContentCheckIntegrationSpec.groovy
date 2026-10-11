package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.groupId
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.declaration.OfferedTerms
import com.lilradish.lite.domain.inference.SendMeasure
import com.lilradish.lite.domain.referencelist.ListNote
import com.lilradish.lite.domain.referencelist.Term
import com.lilradish.lite.domain.referencelist.TermMeaning
import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
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
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    QuestionContentCheck check

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "question_check_" + (++databasesMade))
        check = new QuestionContentCheck(store.session, LibraryStore.FILES, new SearchWays([]))
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

    def "submitting names every unknown instruction reference, accepting known, escaped and unfinished text"() {
        given:
        taken(TAKEN, 1, "complaint", 4000)
        givenBack(FIRST, null, 1, "summary", "text", 1000, "always")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update question_versions set instruction = ? where entry_version_id = ?::uuid")
                .params(instruction, VERSION).update()

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

        then:
        problems*.code() == names.collect { ContentProblemCode.INSTRUCTION_NAMES_UNKNOWN }
        problems*.place() == names.collect { new ContentPlace.AtInstruction(it) }

        where:
        instruction                                      || names
        "Read {{complaint}}."                             || []
        "Literal \\{{missing}}, unfinished {{missing"      || []
        "No input named."                                 || []
        "{{unknown}} {{complaint.path}} {{complaint[0]}}"  || ["unknown", "complaint.path", "complaint[0]"]
        "{{ }} and {{}}"                                  || [" ", ""]
    }

    def "a nested file is accepted at deployment bounds and refused at the first excess or missing size"() {
        given:
        givenBack(DETAILS, null, 1, "details", "fields", null, "always")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                parent_field_id, position, name, kind, file_types, file_maximum_bytes,
                                                holds_many, many_limit, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', ?::uuid, 1, 'attachment', 'file',
                        array['.pdf'], ?, true, ?, true, ?::uuid)
                """).params(FIRST, VERSION, DETAILS, maximumBytes, most, SEEDER).update()

        when:
        def found = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        found.problems()*.code() == codes
        found.problems()*.place() == codes.collect { it == ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT
                ? new ContentPlace.Whole(ContentPart.GIVES)
                : new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(FIRST)) }
        found.problems()*.fileLimits() == codes.collect {
            it in [ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT, ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT]
                    ? new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L) : null
        }
        found.total() == codes.size()

        where:
        maximumBytes | most || codes
        null          | 100  || [ContentProblemCode.FILE_SIZE_MISSING]
        104_857_600L  | 1    || []
        1L            | 100  || []
        104_857_601L  | 1    || [ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT]
        1L            | 101  || [ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT]
        104_857_601L  | 101  || [ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT,
                                    ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT]
    }

    def "an old nested file field with sixty-five types is read but has a named content problem"() {
        given:
        givenBack(DETAILS, null, 1, "details", "fields", null, "always")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                parent_field_id, position, name, kind, file_types, file_maximum_bytes,
                                                must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', ?::uuid, 1, 'attachment', 'file',
                        array['.pdf'], 1024, true, ?::uuid)
                """).params(FIRST, VERSION, DETAILS, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; generate_series(); || converts integer operands to text.
        store.session.sql("""
                update declaration_fields
                   set file_types = ARRAY(select '.x' || n from generate_series(0, ?) n)
                 where declaration_field_id = ?::uuid
                """).params(count - 1, FIRST).update()

        when:
        def found = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        found.problems() == (count == 65
                ? [new ContentProblem(ContentProblemCode.FILE_TYPES_TOO_MANY,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(FIRST)), null, null)] : [])
        found.total() == (count == 65 ? 1 : 0)
        // DB-SPECIFIC: PostgreSQL :: casts to text, uuid; array_length().
        store.texts("select array_length(file_types, 1)::text from declaration_fields where declaration_field_id = ?::uuid",
                FIRST) == [count.toString()]

        where:
        count << [64, 65]
    }

    def "a question giving back files at the handover total holds, and one extra byte is named at gives"() {
        given:
        givenBack(DETAILS, null, 1, "details", "fields", null, "always")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update declaration_fields set holds_many = true, many_limit = 2 where declaration_field_id = ?::uuid")
                .params(DETAILS).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                parent_field_id, position, name, kind, file_types, file_maximum_bytes,
                                                holds_many, many_limit, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', ?::uuid, 1, 'attachment', 'file',
                        array['.pdf'], 33554432, true, 16, true, ?::uuid)
                """).params(FIRST, VERSION, DETAILS, SEEDER).update()
        if (extra) {
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
            store.session.sql("""
                    insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                    position, name, kind, file_types, file_maximum_bytes,
                                                    must_be_given, standing, created_by)
                    values (?::uuid, ?::uuid, 'question', 'gives', 2, 'extra', 'file',
                            array['.pdf'], 1, false, 'always', ?::uuid)
                    """).params(SECOND, VERSION, SEEDER).update()
        }

        expect:
        check.problemsIn(groupId(GROUP), versionId(VERSION)).problems() == expected

        where:
        extra || expected
        false || []
        true  || [new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT,
                new ContentPlace.Whole(ContentPart.GIVES), null,
                new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L))]
    }

    def "a question taking a nested file is refused at that field even when its size is within deployment"() {
        given:
        givenBack(FIRST, null, 1, "summary", "text", 100, "always")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                position, name, kind, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', 'takes', 1, 'details', 'fields', true, ?::uuid)
                """).params(DETAILS, VERSION, SEEDER).update()
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                parent_field_id, position, name, kind, file_types, file_maximum_bytes,
                                                must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', 'takes', ?::uuid, 1, 'attachment', 'file',
                        array['.pdf'], 104857600, true, ?::uuid)
                """).params(TAKEN, VERSION, DETAILS, SEEDER).update()

        when:
        def found = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        found.problems() == [new ContentProblem(ContentProblemCode.QUESTION_TAKES_FILE,
                new ContentPlace.AtField(ContentPart.TAKES, UUID.fromString(TAKEN)), null, null)]
        found.total() == 1
    }

    def "a drawing names each unknown top-level input and leaves literal or malformed braces as text"() {
        given:
        taken(TAKEN, 1, "complaint", 100)
        givenBack(FIRST, null, 1, "summary", "text", 100, "always")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params("## {{complaint}}\n{{missing}} \\{{literal}} {{nested.value}} {{other}} {{" + "a" * 64 + "}} {{open", VERSION).update()

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

        then:
        problems == [
                new ContentProblem(ContentProblemCode.DRAWING_NAMES_UNKNOWN,
                        new ContentPlace.AtDrawing("missing"), null, null),
                new ContentProblem(ContentProblemCode.DRAWING_NAMES_UNKNOWN,
                        new ContentPlace.AtDrawing("other"), null, null)]
    }

    def "an invalid outer opener leaves an inner legal unknown name to be reported"() {
        given:
        givenBack(FIRST, null, 1, "summary", "text", 100, "always")
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params("{{bad {{missing}} {{{other}}", VERSION).update()

        when:
        def result = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        result.problems() == [
                new ContentProblem(ContentProblemCode.DRAWING_NAMES_UNKNOWN,
                        new ContentPlace.AtDrawing("missing"), null, null),
                new ContentProblem(ContentProblemCode.DRAWING_NAMES_UNKNOWN,
                        new ContentPlace.AtDrawing("other"), null, null)]
        result.total() == 2
    }

    def "drawing references follow the same legal name rule as declared fields"() {
        given:
        givenBack(FIRST, null, 1, "summary", "text", 100, "always")
        def names = ["a", "z9_", "a" * 63, "a" * 64, "A", "1a", "_a", "a-b", "a.b", "a b", "é"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set drawing = ? where entry_version_id = ?::uuid")
                .params(names.collect { "{{${it}}}" }.join(" "), VERSION).update()
        def declaredNames = names.findAll { name ->
            try {
                new FieldName(name)
                true
            } catch (IllegalArgumentException ignored) {
                false
            }
        }

        when:
        def result = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        result.problems()*.place() == declaredNames.collect { new ContentPlace.AtDrawing(it) }
        result.problems()*.code() == declaredNames.collect { ContentProblemCode.DRAWING_NAMES_UNKNOWN }
        result.total() == declaredNames.size()
    }

    /** Two fields beside each other sharing a name: the later is the one named, by its own key. */
    def "a field held inside another is named by its own key, and a field of fields holding none by its"() {
        given:
        givenBack(DETAILS, null, 1, "details", "fields", null, "never")
        givenBack(FIRST, DETAILS, 1, "product", "text", 128, null)
        givenBack(SECOND, DETAILS, 2, "product", "text", null, null)
        givenBack(EMPTY, null, 2, "notes", "fields", null, "always")

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

        then:
        problems == [
                new ContentProblem(ContentProblemCode.NAME_REPEATED,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(SECOND)), null, null),
                new ContentProblem(ContentProblemCode.LONGEST_MISSING,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(SECOND)), null, null),
                new ContentProblem(ContentProblemCode.NO_FIELDS_HELD,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(EMPTY)), null, null)]
    }

    /**
     * A field one past what one asking may send is named where it is, and nothing is measured beside it; one
     * the field allows, the asking as a whole still runs past, and says by how much as the measure counts it.
     */
    def "a field past what one asking may send is named by its key, and an asking past it is named as a whole"() {
        given:
        givenBack(FIRST, null, 1, "summary", "text", longest, "always")

        expect:
        check.problemsIn(groupId(GROUP), versionId(VERSION)).problems() == named

        where:
        longest          || named
        LONGEST_TEXT + 1 || [new ContentProblem(ContentProblemCode.LIMIT_PAST_LARGEST,
                new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(FIRST)), null, null)]
        LONGEST_TEXT     || [new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST,
                new ContentPlace.Whole(ContentPart.ASKING), excessOf(asking([summary(LONGEST_TEXT)])), null)]
    }

    /** The measure's own edge, read through the store: exactly the most one asking may send, and one character more. */
    def "a question whose asking sends exactly the most one may has nothing to name, and one character more does"() {
        given:
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set instruction = ? where entry_version_id = ?::uuid")
                .params(SAID_AT_EDGE, VERSION).update()
        givenBack(FIRST, null, 1, "summary", "text", EDGE + over, "always")
        assert SendMeasure.mostSent(saying(SAID_AT_EDGE, [summary(EDGE)]), true) == Declaration.MOST_SENT

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

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
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

        then:
        problems == [
                new ContentProblem(ContentProblemCode.LIMIT_PAST_LARGEST,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(DETAILS)), null, null),
                new ContentProblem(ContentProblemCode.LIMIT_PAST_LARGEST,
                        new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(FIRST)), null, null)]
    }

    /** What a question takes is sent with every asking, so it is measured with what it gives back. */
    def "a question taking text is measured with what it takes"() {
        given:
        taken(TAKEN, 1, "letter", 1000)
        givenBack(FIRST, null, 1, "summary", "text", LONGEST_TEXT, "always")
        def withTheTake = new Asking(new Instruction(SAID), [summary(1000, "letter", false)], [summary(LONGEST_TEXT)])

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

        then:
        problems == [new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST, new ContentPlace.Whole(ContentPart.ASKING),
                excessOf(withTheTake), null)]
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
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

        then:
        problems == [new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST, new ContentPlace.Whole(ContentPart.ASKING),
                excessOf(asking([summary(LONGEST_TEXT), category(offered)])), null)]
    }

    def "a question pinning a list of the group's is not measured as though the list offered nothing"() {
        given:
        pinnedCategories()

        when:
        def problems = check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

        then:
        problems*.excess() != [excessOf(asking([summary(LONGEST_TEXT), category(new OfferedTerms([], null))]))]
    }

    def "a question counts every invalid field but lists only the first two hundred"() {
        given:
        def keys = (1..205).collect { index -> new UUID(0x0bL, 0x1000L + index).toString() }
        keys.eachWithIndex { field, index ->
            givenBack(field, null, index + 1, "field${index + 1}", "text", null, "always")
        }

        when:
        def report = check.problemsIn(groupId(GROUP), versionId(VERSION))

        then:
        report.total() == 205
        report.problems().size() == 200
        report.problems()*.code().every { it == ContentProblemCode.LONGEST_MISSING }
        report.problems().first().place() == new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(keys.first()))
        report.problems().last().place() == new ContentPlace.AtField(ContentPart.GIVES, UUID.fromString(keys[199]))
    }

    def "a question pinning a list the group does not hold is a store gone wrong, and is not measured"() {
        given:
        store.entry(LIST, OTHER_GROUP, "reference_list", "Regions")
        store.seeded(LIST_VERSION, LIST, 1)
        store.content(LIST_VERSION, "reference_list", null)
        givenBack(FIRST, null, 1, "summary", "text", 1000, "always")
        givenTerm(SECOND, 2, "region", LIST_VERSION)

        when:
        check.problemsIn(groupId(GROUP), versionId(VERSION)).problems()

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Question version " + VERSION + " pins a list this group does not hold"
    }

    def "a question telling whoever answers it what to do, and giving back what holds, has nothing to name"() {
        given:
        givenBack(DETAILS, null, 1, "summary", "text", 1000, "always")

        expect:
        check.problemsIn(groupId(GROUP), versionId(VERSION)).problems() == []
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid, field_kind, field_standing.
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update declaration_fields set holds_many = true, many_limit = ? where declaration_field_id = ?::uuid")
                .params(most, field).update()
    }

    /** A text the version takes, which need not be given. */
    private void taken(String field, int position, String name, int longest) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position,
                                                name, kind, text_limit, must_be_given, created_by)
                values (?::uuid, ?::uuid, 'question', 'takes', ?, ?, 'text', ?, false, ?::uuid)
                """).params(field, VERSION, position, name, longest, SEEDER).update()
    }

    /** A term the version gives back, standing always, of the list version named. */
    private void givenTerm(String field, int position, String name, String list) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position,
                                                name, kind, term_list_version_id, must_be_given, standing, created_by)
                values (?::uuid, ?::uuid, 'question', 'gives', ?, ?, 'term', ?::uuid, true, 'always', ?::uuid)
                """).params(field, VERSION, position, name, list, SEEDER).update()
    }

    private void termOf(String list, int position, String term, String meaning) {
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("""
                insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                values (?::uuid, ?, ?, ?, ?::uuid)
                """).params(list, position, term, meaning, SEEDER).update()
    }
}
