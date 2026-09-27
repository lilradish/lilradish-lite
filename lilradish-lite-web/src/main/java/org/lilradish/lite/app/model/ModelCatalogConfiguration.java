package org.lilradish.lite.app.model;

import org.lilradish.lite.domain.model.ModelCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Registered here rather than on the application class, which every slice test starts from and
 * which would then need models to start at all.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelDeployment.class)
final class ModelCatalogConfiguration {

    // Never lazy, even where lazy initialisation is switched on: a list it cannot hold must stop the start.
    @Bean
    @Lazy(false)
    ModelCatalog modelCatalog(ModelDeployment deployment) {
        return new ModelCatalog(deployment.models());
    }
}
