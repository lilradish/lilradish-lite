package com.lilradish.lite.app.library

import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.search.SearchWay
import spock.lang.Specification

class StoredWorkflowSpec extends Specification {

    def "a search stands a field always or never"() {
        given:
        def question = new EntryVersionId(UUID.fromString("00100000-0000-4000-8000-000000000001"))

        when:
        def search = new StoredWorkflow.Runs.Search("web_search", question,
                [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): standing])

        then:
        search.standings() == [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): standing]
        search.way() == "web_search"
        search.question() == question

        where:
        standing << [FieldStanding.ALWAYS, FieldStanding.NEVER]
    }

    /** The store refuses it as well, so a version read could never be submitted standing a field so. */
    def "a search never stands a field above a confidence, as a search gives none"() {
        when:
        new StoredWorkflow.Runs.Search("web_search", null,
                [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.ABOVE_CONFIDENCE])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StoredWorkflow.Runs.Search must not stand a field above a confidence, as a search gives none"
    }
}
