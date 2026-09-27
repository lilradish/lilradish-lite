package org.lilradish.lite.app.codestep;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registered here rather than on the application class, which every slice test starts from and which would then
 * need the setting to start at all.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CodeStepDeployment.class)
final class CodeStepConfiguration {}
