package com.lilradish.lite.app.inference.development;

import com.lilradish.lite.development.DevelopmentOnly;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the development substitute's call routes from its deployment file. */
@DevelopmentOnly
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DevelopmentCallRoutes.class)
class DevelopmentCallConfiguration {}
