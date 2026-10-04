package org.lilradish.lite.app.library

import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.search.SearchWay
import org.lilradish.lite.domain.workflow.SearchQuestionPart
import spock.lang.Specification

class StoredWorkflowSpec extends Specification {

    def "a search stands a field always or never"() {
        given:
        def question = [new SearchQuestionPart.LiteralPart("Find it")]

        when:
        def search = new StoredWorkflow.Runs.Search("web_search", question, [(SearchWay.BODY): standing])

        then:
        search.standings() == [(SearchWay.BODY): standing]
        search.way() == "web_search"
        search.question() == question

        where:
        standing << [FieldStanding.ALWAYS, FieldStanding.NEVER]
    }

    /** The store refuses it as well, so a version read could never be submitted standing a field so. */
    def "a search never stands a field above a confidence, as a search gives none"() {
        when:
        new StoredWorkflow.Runs.Search("web_search", [],
                [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.ABOVE_CONFIDENCE])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StoredWorkflow.Runs.Search must not stand a field above a confidence, as a search gives none"
    }
}
