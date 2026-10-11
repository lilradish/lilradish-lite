package com.lilradish.lite.app.run;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registered here rather than on the application class, which every slice test starts from and which would then
 * need the setting to start at all.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunDeployment.class)
final class RunConfiguration {}
