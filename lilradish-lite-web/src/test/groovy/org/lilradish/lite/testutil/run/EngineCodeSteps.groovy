package org.lilradish.lite.testutil.run

import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.codestep.CodeStepConfiguration
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * What a context holding the run engine takes of code steps: a release holding none, what runs one, and how long
 * the longest may run as the deployment's setting says. A test configuration, so no scan of the application takes it.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import([CodeRuns, CodeStepConfiguration])
class EngineCodeSteps {

    @Bean
    CodeSteps codeSteps() {
        CodeStepsHeld.NONE
    }
}
