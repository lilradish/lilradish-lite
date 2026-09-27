package org.lilradish.lite.testutil.run

import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.testutil.library.DeployedModels
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * What a context holding the run engine takes of models: the library's models held, and a model answering only
 * what a spec scripts. Every call made is recorded, so a spec proves none was made where none was meant by asking
 * the bean for its requests; one made unscripted is never answered and never ended. A test configuration, so no
 * scan of the application takes it.
 */
@TestConfiguration(proxyBeanMethods = false)
class EngineModels {

    @Bean
    ModelCatalog modelCatalog() {
        DeployedModels.HELD
    }

    @Bean
    RecordedModelCalls modelCalls() {
        new RecordedModelCalls()
    }
}
