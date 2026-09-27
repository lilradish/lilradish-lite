package org.lilradish.lite.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/* Imported by the door, so the turn is in every chain the door is, a web slice scanning no plain configuration.
 * Its order is read off the filter's class, never off this method. */
@Configuration(proxyBeanMethods = false)
class LargeBodyAdmissionConfiguration {

    @Bean
    LargeBodyAdmission largeBodyAdmission(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver refusals) {
        return new LargeBodyAdmission(refusals, System::nanoTime);
    }
}
