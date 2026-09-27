package org.lilradish.lite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.libprunus.core.config.ConfigurationRepository;
import org.libprunus.core.config.CoreRuntimeConfig;
import org.libprunus.spring.config.CoreRuntimeConfigAutoConfiguration;
import org.libprunus.spring.config.CoreRuntimeProperties;
import org.libprunus.spring.config.json.SafeJacksonAutoConfiguration;
import org.libprunus.spring.config.json.SafeJacksonProperties;
import org.libprunus.spring.error.ApiErrorAutoConfiguration;
import org.libprunus.spring.error.ApiErrorHandler;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class LibprunusSpringAutoConfigurationTest {

    private static final AutoConfigurations LIBPRUNUS_AUTO_CONFIGURATIONS = AutoConfigurations.of(
            CoreRuntimeConfigAutoConfiguration.class,
            SafeJacksonAutoConfiguration.class,
            ApiErrorAutoConfiguration.class);

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(LIBPRUNUS_AUTO_CONFIGURATIONS);

    private final WebApplicationContextRunner webContextRunner =
            new WebApplicationContextRunner().withConfiguration(LIBPRUNUS_AUTO_CONFIGURATIONS);

    @Test
    void registersCoreRuntimeBeansAndPublishesConfigAsRepositorySnapshot() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CoreRuntimeProperties.class);
            assertThat(context).hasSingleBean(CoreRuntimeConfig.class);
            assertThat(context).hasSingleBean(ConfigurationRepository.class);
            assertThat(context.getBean(ConfigurationRepository.class).getGlobalSnapshot())
                    .isSameAs(context.getBean(CoreRuntimeConfig.class));
        });
    }

    @Test
    void hardensJacksonWhenJsonMapperIsOnTheClasspath() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SafeJacksonProperties.class);
            assertThat(context)
                    .getBean("prunusPolymorphicTypeValidatorCustomizer")
                    .isInstanceOf(JsonMapperBuilderCustomizer.class);
        });
    }

    @Test
    void backsOffJacksonHardeningWhenJsonMapperIsAbsent() {
        contextRunner.withClassLoader(new FilteredClassLoader(JsonMapper.class)).run(context -> {
            assertThat(context).doesNotHaveBean(SafeJacksonProperties.class);
            assertThat(context).doesNotHaveBean("prunusPolymorphicTypeValidatorCustomizer");
        });
    }

    @Test
    void registersApiErrorHandlerForServletWebApplications() {
        webContextRunner.run(context -> assertThat(context).hasSingleBean(ApiErrorHandler.class));
    }

    @Test
    void omitsApiErrorHandlerForNonWebApplications() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(ApiErrorHandler.class));
    }
}
