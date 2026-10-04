package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.declaration.FieldStanding.ABOVE_CONFIDENCE
import static org.lilradish.lite.domain.declaration.FieldStanding.ALWAYS
import static org.lilradish.lite.domain.declaration.FieldStanding.NEVER
import static org.lilradish.lite.domain.run.fixture.Runs.gives
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.pinned
import static org.lilradish.lite.domain.run.fixture.Runs.standing
import static org.lilradish.lite.domain.run.fixture.Runs.standingAll
import static org.lilradish.lite.domain.run.fixture.Runs.takes
import static org.lilradish.lite.domain.run.fixture.Runs.webSearch
import static org.lilradish.lite.domain.run.fixture.Runs.workflowHalf

import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.search.SearchWay
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.SearchQuestionPart
import spock.lang.Specification

class StepRunsSpec extends Specification {

    static final List<SearchQuestionPart> ASKS = [new SearchQuestionPart.LiteralPart("Demo Sample")]

    static final SearchWay ANOTHER_WAY = new SearchWay("other_search", new ModelToolName("search"), 10, 10, 10, 10)

    static final Map<FieldName, ?> ALL_BUT_OTHER =
            [(SearchWay.TITLE): ALWAYS, (SearchWay.BODY): ALWAYS, (SearchWay.REFERENCES): ALWAYS]

    static final Map<FieldName, ?> ONE_IN_PLACE_OF_OTHER = ALL_BUT_OTHER + [(new FieldName("summary")): ALWAYS]

    static final Map<FieldName, ?> ONE_MORE = standingAll(ALWAYS) + [(new FieldName("summary")): ALWAYS]

    static final Map<FieldName, ?> BODY_ABOVE_CONFIDENCE = standingAll(ALWAYS) + [(SearchWay.BODY): ABOVE_CONFIDENCE]

    def "a question is refused where it names other than one key per field it gives back"() {
        when:
        new StepRuns.Question(pinned(1), new Instruction("Answer what is asked."), takes([]),
                gives([standing("first"), standing("second")]), (0..<keys).collect { key(300 + it) }, [:])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepRuns.Question names " + keys + " keys for the 2 fields it gives back"

        where:
        keys << [0, 1, 3]
    }

    def "a workflow keeps the declaration of the version it pins"() {
        given:
        def entry = pinned(1)
        def declared = workflowHalf(DeclarationSide.GIVES)

        when:
        def workflow = new StepRuns.Workflow(entry, declared)

        then:
        workflow.pinned().is(entry)
        workflow.gives().is(declared)
    }

    def "a workflow is refused without its pin or its declaration"() {
        when:
        new StepRuns.Workflow(entry, declared)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        entry     | declared                            || message
        null      | workflowHalf(DeclarationSide.GIVES)  || "StepRuns.Workflow pinned must not be null"
        pinned(1) | null                                || "StepRuns.Workflow gives must not be null"
    }
    def "a search keeps its question and how each field of its way stands, always or never, as handed then"() {
        given:
        def question = [new SearchQuestionPart.LiteralPart("Find "),
                        new SearchQuestionPart.Read(key(310), new BindingSource.WorkflowInput(Pointer.parse("topic")))]
        def standings = [(SearchWay.TITLE): ALWAYS, (SearchWay.BODY): NEVER, (SearchWay.REFERENCES): NEVER,
                         (SearchWay.OTHER): ALWAYS]

        when:
        def search = new StepRuns.Search("web_search", released, question, standings)
        question.clear()
        standings.clear()

        then:
        search.question() == [new SearchQuestionPart.LiteralPart("Find "),
                              new SearchQuestionPart.Read(key(310), new BindingSource.WorkflowInput(Pointer.parse("topic")))]
        search.standings() == [(SearchWay.TITLE): ALWAYS, (SearchWay.BODY): NEVER, (SearchWay.REFERENCES): NEVER,
                               (SearchWay.OTHER): ALWAYS]

        where:
        released << [null, webSearch()]
    }

    def "a search is refused where it is handed another way, asks nothing, or stands its fields otherwise"() {
        when:
        new StepRuns.Search("web_search", released, question, standings)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        released    | question | standings             || message
        ANOTHER_WAY | ASKS     | standingAll(ALWAYS)   || "StepRuns.Search names the way web_search but is handed the way other_search"
        webSearch() | []       | standingAll(ALWAYS)   || "StepRuns.Search must ask something"
        null        | []       | standingAll(NEVER)    || "StepRuns.Search must ask something"
        webSearch() | ASKS     | [:]                   || "StepRuns.Search must say how each field of its way stands, and of no other"
        webSearch() | ASKS     | ALL_BUT_OTHER         || "StepRuns.Search must say how each field of its way stands, and of no other"
        null        | ASKS     | ONE_MORE              || "StepRuns.Search must say how each field of its way stands, and of no other"
        webSearch() | ASKS     | ONE_IN_PLACE_OF_OTHER || "StepRuns.Search must say how each field of its way stands, and of no other"
        webSearch() | ASKS     | BODY_ABOVE_CONFIDENCE || "StepRuns.Search must not stand a field above a confidence"
    }

    def "a search is refused without a way, a question or how its fields stand"() {
        when:
        new StepRuns.Search(way, null, question, standings)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        way          | question | standings           || message
        null         | ASKS     | standingAll(ALWAYS) || "StepRuns.Search way must not be null"
        "web_search" | null     | standingAll(ALWAYS) || "StepRuns.Search question must not be null"
        "web_search" | ASKS     | null                || "StepRuns.Search standings must not be null"
    }

}
