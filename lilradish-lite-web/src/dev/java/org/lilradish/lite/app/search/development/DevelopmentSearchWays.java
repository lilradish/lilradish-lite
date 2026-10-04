package org.lilradish.lite.app.search.development;

import org.lilradish.lite.development.DevelopmentOnly;
import org.lilradish.lite.domain.model.ModelToolName;
import org.lilradish.lite.domain.search.SearchWay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Needs the search tool the development model offers, and its outputs at their limits together stay within what
 * that model may give back in one call.
 */
@DevelopmentOnly
@Configuration(proxyBeanMethods = false)
class DevelopmentSearchWays {

    @Bean
    SearchWay sampleWebSearch() {
        return new SearchWay("sample_web_search", new ModelToolName("web_search"), 200, 16_000, 8_000, 4_000);
    }
}
