package org.lilradish.lite.domain.workflow

import java.util.function.Predicate
import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.testutil.OfferedLists
import spock.lang.Specification

class SearchQuestionSpec extends Specification {

    static final EntryVersionId CATEGORIES = new EntryVersionId(new UUID(0x07L, 0x51))

    static final EntryVersionId ELSEWHERE = new EntryVersionId(new UUID(0x07L, 0x52))

    static final String ZERO_WIDTH_SPACE = Character.toString(0x200B)

    static final String ZERO_WIDTH_JOINER = Character.toString(0x200D)

    static final String START_OF_HEADING = Character.toString(0x1)

    static final Field SUMMARY = field("summary", new FieldShape.Text(500), new Demand.Given(true))

    /** A value a model gives back, standing always, read as one taken: its standing is not told. */
    static final Field STANDING_SUMMARY =
            field("summary", new FieldShape.Text(500), new Demand.Stands(true, FieldStanding.ALWAYS, null))

    static final Field OPTIONAL_COUNTS =
            field("count", new FieldShape.Plain(FieldKind.NUMBER), new Demand.Given(false), new HowMany.Many(3))

    long keysMade = 0

    def "a question as sent joins parts in order, keeps their spaces and uses nothing for a missing value"() {
        given:
        def source = new BindingSource.WorkflowInput(Pointer.parse("complaint"))
        def first = new SearchQuestionPart.Read(new UUID(0x30L, 1L), source)
        def missing = new SearchQuestionPart.Read(new UUID(0x30L, 2L), source)
        def last = new SearchQuestionPart.Read(new UUID(0x30L, 3L), source)
        def values = [(first.id()): "  Billing ", (last.id()): '{"count":2}']
        def readInOrder = []

        when:
        def sent = SearchQuestion.asSent([
                new SearchQuestionPart.LiteralPart("Find "), first,
                new SearchQuestionPart.LiteralPart(" / "), missing,
                new SearchQuestionPart.LiteralPart(" + "), last]) { read ->
            readInOrder << read.id()
            values[read.id()]
        }

        then:
        sent == 'Find   Billing  /  + {"count":2}'
        readInOrder == [first.id(), missing.id(), last.id()]
    }

    /**
     * Text shows nothing where it is spaces, tabs, line breaks and characters drawing nothing; a control is shown, so
     * it holds something. A value read in counts only where its source must be given.
     */
    def "a question could be sent holding nothing unless some text in it shows something or a value it reads must be given"() {
        given:
        def mustBeGiven = [] as Set<UUID>
        def question = parts.collect { part ->
            if (part.text != null) {
                return new SearchQuestionPart.LiteralPart(part.text)
            }
            def read = new SearchQuestionPart.Read(new UUID(0x20L, ++keysMade),
                    new BindingSource.WorkflowInput(Pointer.parse("complaint")))
            if (part.given) {
                mustBeGiven << read.id()
            }
            read
        }

        expect:
        SearchQuestion.couldHoldNothing(question) { mustBeGiven.contains(it.id()) } == empty

        where:
        parts                                                  || empty
        []                                                     || true
        [[text: " "]]                                          || true
        [[text: " \t\n"], [text: ZERO_WIDTH_SPACE]]            || true
        [[text: ZERO_WIDTH_JOINER + " "]]                      || true
        [[text: "Find"]]                                       || false
        [[given: false]]                                       || true
        [[given: false], [given: false], [text: " "]]          || true
        [[given: true]]                                        || false
        [[text: " "], [given: false], [given: true]]           || false
    }

    def "what a question could hold is judged of a question and of whether each value it reads must be given, both named"() {
        when:
        SearchQuestion.couldHoldNothing(question, sourceMustBeGiven)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        question | sourceMustBeGiven       || expectedMessage
        null     | Predicate.isEqual(null) || "SearchQuestion question must not be null"
        []       | null                    || "SearchQuestion sourceMustBeGiven must not be null"
    }

    /** Told as one value taken, its own label, help and standing dropped, given only where the whole path is. */
    def "the field a part reads is told as a model is told a value it takes, to be given only where its whole path must be"() {
        expect:
        SearchQuestion.told(new Pointer.Reach.At(field, given), OfferedLists.offering([(CATEGORIES): ["Billing"]])) == told

        where:
        field                 | given || told
        SUMMARY               | true  || asked("summary", FieldKind.TEXT, 500, null, true)
        SUMMARY               | false || asked("summary", FieldKind.TEXT, 500, null, false)
        STANDING_SUMMARY      | true  || asked("summary", FieldKind.TEXT, 500, null, true)
        OPTIONAL_COUNTS       | true  || asked("count", FieldKind.NUMBER, null, 3, true)
        OPTIONAL_COUNTS       | false || asked("count", FieldKind.NUMBER, null, 3, false)
    }

    def "a part reading a term is told the terms its list offers, and one reading fields is told each of them"() {
        given:
        def offered = OfferedLists.offering([(CATEGORIES): ["Billing", "Delivery"]])
        def line = field("line", new FieldShape.Nested([
                field("category", new FieldShape.Term(CATEGORIES), new Demand.Given(true)),
                field("note", new FieldShape.Text(20), new Demand.Given(false))]), new Demand.Given(true))

        when:
        def told = SearchQuestion.told(new Pointer.Reach.At(line, true), offered)

        then:
        told == new AskedField(new FieldName("line"), FieldKind.FIELDS, null, null, null, [
                new AskedField(new FieldName("category"), FieldKind.TERM, null, null, offered[CATEGORIES], [], true, false),
                new AskedField(new FieldName("note"), FieldKind.TEXT, 20, null, null, [], false, false)], true, false)
    }

    /** What could not be told is not measured, so it is none rather than a guess. */
    def "a part reading what could not be told is told nothing"() {
        expect:
        SearchQuestion.told(new Pointer.Reach.At(field, true), OfferedLists.offering([(CATEGORIES): ["Billing"]])) == null

        where:
        field << [
                field("summary", new FieldShape.Text(null), new Demand.Given(true)),
                field("category", new FieldShape.Term(null), new Demand.Given(true)),
                field("line", new FieldShape.Nested([]), new Demand.Given(true)),
                field("labels", new FieldShape.Text(20), new Demand.Given(true), new HowMany.Many(null))]
    }

    def "a file at any depth cannot become text told in a search question"() {
        given:
        def file = field("attachment", new FieldShape.File([new MediaTypeName("application/pdf")], 1024L),
                new Demand.Given(true))
        def nested = field("record", new FieldShape.Nested([file]), new Demand.Given(true))

        expect:
        [file, nested].every { source ->
            def read = new Pointer.Reach.At(source, true)
            SearchQuestion.takesFile(read) && SearchQuestion.told(read, [:]) == null
        }
        !SearchQuestion.takesFile(new Pointer.Reach.At(SUMMARY, true))
    }

    /** Every list a version pins is handed over with it, so one missing is a store gone wrong, never a value left out. */
    def "a part reading a term from a list not handed over fails rather than going unmeasured"() {
        when:
        SearchQuestion.told(new Pointer.Reach.At(field, true), OfferedLists.offering([(CATEGORIES): ["Billing"]]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A value read into a searching step's question takes a term from a list not handed over"

        where:
        field << [
                field("category", new FieldShape.Term(ELSEWHERE), new Demand.Given(true)),
                field("line", new FieldShape.Nested([field("category", new FieldShape.Term(ELSEWHERE), new Demand.Given(true))]),
                        new Demand.Given(true))]
    }

    def "what is told of a part is judged of what it reads and of the lists it may take a term from, both named"() {
        when:
        SearchQuestion.told(read, lists)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        read                                 | lists || expectedMessage
        null                                 | [:]   || "SearchQuestion read must not be null"
        new Pointer.Reach.At(SUMMARY, true)  | null  || "SearchQuestion lists must not be null"
    }

    private static Field field(String name, FieldShape shape, Demand demand, HowMany howMany = new HowMany.One()) {
        new Field(new FieldName(name), null, null, shape, howMany, demand)
    }

    private static AskedField asked(String name, FieldKind kind, Integer longest, Integer most, boolean mustBeGiven) {
        new AskedField(new FieldName(name), kind, longest, most, null, [], mustBeGiven, false)
    }
}
