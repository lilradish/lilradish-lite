package org.lilradish.lite.app.search

import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.search.SearchWay
import spock.lang.Specification

class SearchWaysSpec extends Specification {

    def 'a release may hold no search way'() {
        when:
        def ways = new SearchWays([])

        then:
        ways.all().isEmpty()
        ways.find('missing').isEmpty()
    }

    def 'a release finds its named ways and publishes a stable name order'() {
        given:
        def later = new SearchWay('web_search', new ModelToolName('search'), 10, 100, 100, 10)
        def earlier = new SearchWay('archive_search', new ModelToolName('archive'), 10, 100, 100, 10)

        when:
        def ways = new SearchWays([later, earlier])

        then:
        ways.all().keySet().toList() == ['archive_search', 'web_search']
        ways.find('archive_search').get().is(earlier)
        ways.find('web_search').get().is(later)
        ways.find('unknown').isEmpty()
    }

    def 'a release refuses duplicate way names rather than choosing one'() {
        given:
        def first = new SearchWay('web_search', new ModelToolName('search'), 10, 100, 100, 10)
        def second = new SearchWay('web_search', new ModelToolName('archive'), 20, 200, 200, 20)

        when:
        new SearchWays([first, second])

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains('web_search')
        refusal.message.contains('more than once')
    }

    def 'a release refuses an absent way declaration'() {
        when:
        new SearchWays([null])

        then:
        def refusal = thrown(NullPointerException)
        refusal.message.contains('null way')
    }

    def 'a published catalog cannot be changed by its reader'() {
        given:
        def held = new SearchWay('web_search', new ModelToolName('search'), 10, 100, 100, 10)
        def ways = new SearchWays([held])

        when:
        ways.all().remove('web_search')

        then:
        thrown(UnsupportedOperationException)
        ways.find('web_search').get().is(held)
        ways.all().size() == 1
    }
}
