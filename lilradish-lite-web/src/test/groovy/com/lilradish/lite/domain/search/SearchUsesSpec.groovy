package com.lilradish.lite.domain.search

import static com.lilradish.lite.domain.inference.ModelCallOutcome.CAME_BACK
import static com.lilradish.lite.domain.inference.ModelCallOutcome.ERRORED
import static com.lilradish.lite.domain.inference.ModelCallOutcome.NOTHING_CAME_BACK
import static com.lilradish.lite.domain.inference.ModelCallOutcome.TURNED_AWAY

import com.lilradish.lite.domain.inference.ModelCallOutcome
import spock.lang.Specification

class SearchUsesSpec extends Specification {

    def "a count below none is refused"() {
        when:
        new SearchUses(uses, reported)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(String.valueOf(uses))

        where:
        [uses, reported] << [[-1L, Long.MIN_VALUE], [true, false]].combinations()
    }

    def "a call taken up counts as its answer said, however many that is, and otherwise at the most it asked for"() {
        expect:
        SearchUses.counted(ended, said, 3) == new SearchUses(uses, reported)

        where:
        ended             | said || uses | reported
        CAME_BACK         | 2L   || 2    | true
        CAME_BACK         | 0L   || 0    | true
        CAME_BACK         | 3L   || 3    | true
        CAME_BACK         | 5L   || 5    | true
        CAME_BACK         | null || 3    | false
        ERRORED           | null || 3    | false
        NOTHING_CAME_BACK | null || 3    | false
    }

    def "a call turned away has no count at all, while every other ending has one"() {
        expect:
        ModelCallOutcome.values().findAll { SearchUses.counted(it, null, 3) == null } == [TURNED_AWAY]
    }

    def "a call asking for no search at all is refused whatever its ending"() {
        when:
        SearchUses.counted(ended, null, asked)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains("asked")

        where:
        [ended, asked] << [ModelCallOutcome.values().toList(), [0L, -1L]].combinations()
    }

    def "what an answer said is refused where nothing came back to say it"() {
        when:
        SearchUses.counted(ended, said, 3)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains("came back")

        where:
        [ended, said] << [[ERRORED, NOTHING_CAME_BACK, TURNED_AWAY], [0L, 2L]].combinations()
    }

    def "an answer saying fewer than no searches is refused"() {
        when:
        SearchUses.counted(CAME_BACK, -1L, 3)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains("-1")
    }

    def "an ending that is none is refused by name"() {
        when:
        SearchUses.counted(null, null, 3)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "SearchUses ended must not be null"
    }
}
