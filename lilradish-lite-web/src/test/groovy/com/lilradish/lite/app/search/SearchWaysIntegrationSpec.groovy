package com.lilradish.lite.app.search

import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.search.SearchWay
import org.springframework.beans.factory.BeanCreationException
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import spock.lang.Specification

class SearchWaysIntegrationSpec extends Specification {

    def 'Spring starts with no released ways when no way bean is supplied'() {
        given:
        def context = new AnnotationConfigApplicationContext()
        context.register(SearchWays)

        when:
        context.refresh()
        def ways = context.getBean(SearchWays)

        then:
        ways.all().isEmpty()
        ways.find('web_search').isEmpty()

        cleanup:
        context.close()
    }

    def 'Spring gathers released way beans under their declared names'() {
        given:
        def context = new AnnotationConfigApplicationContext()
        context.register(SearchWays, ReleasedWays)

        when:
        context.refresh()
        def ways = context.getBean(SearchWays)

        then:
        ways.all().keySet().toList() == ['archive_search', 'web_search']
        ways.find('web_search').get().requiredTool() == new ModelToolName('search')
        ways.find('missing').isEmpty()

        cleanup:
        context.close()
    }

    def 'Spring refuses to start on a way whose outputs together cannot be one value, naming it'() {
        given:
        def context = new AnnotationConfigApplicationContext()
        context.register(SearchWays, ReleasedWays, UnwritableWay)

        when:
        context.refresh()

        then:
        def refusal = thrown(BeanCreationException)
        refusal.mostSpecificCause instanceof IllegalArgumentException
        refusal.mostSpecificCause.message.contains('oversized_search')
        refusal.mostSpecificCause.message.contains('together')
        !refusal.mostSpecificCause.message.contains('LIMIT_PAST_LARGEST')
        !refusal.mostSpecificCause.message.contains('web_search')
        !context.active

        cleanup:
        context.close()
    }

    // A spec's nested @Configuration is component-scanned into every @SpringBootTest context; @TestConfiguration is not.
    @TestConfiguration(proxyBeanMethods = false)
    static class UnwritableWay {

        @Bean
        SearchWay oversizedSearch() {
            new SearchWay('oversized_search', new ModelToolName('search'), 4_000_000, 4_000_000, 400_000, 1)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReleasedWays {

        @Bean
        SearchWay webSearch() {
            new SearchWay('web_search', new ModelToolName('search'), 10, 100, 100, 10)
        }

        @Bean
        SearchWay archiveSearch() {
            new SearchWay('archive_search', new ModelToolName('archive'), 10, 100, 100, 10)
        }
    }
}
