package org.lilradish.lite.app.inference;

import java.net.http.HttpClient;
import java.time.InstantSource;
import java.util.regex.Pattern;
import org.lilradish.lite.domain.inference.ModelCalls;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The deployment's models, called where its endpoint is configured. Off wherever no base address is
 * set, so a development or test start keeps its stand-in and never binds an endpoint it lacks.
 *
 * <p>The client is built here and never published: a client without the resend would turn a
 * turnaway into an error, and one injected elsewhere would send what nobody recorded.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "lilradish.model-calls.endpoint", name = "base-url")
@EnableConfigurationProperties({ModelEndpoint.class, ResendPolicy.class})
class ModelCallsConfiguration {

    static final String API_KEY_VARIABLE = "LILRADISH_MODEL_API_KEY";

    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");

    @Bean
    ModelCallShutdown modelCallShutdown() {
        return new ModelCallShutdown();
    }

    @Bean
    LongestSend longestSend(ModelEndpoint endpoint) {
        return LongestSend.of(endpoint);
    }

    // Never lazy, even where lazy initialisation is switched on: a mismatch or a missing key must stop the start.
    @Bean
    @Lazy(false)
    ModelCalls modelCalls(
            ModelCatalog catalog,
            ModelEndpoint endpoint,
            ResendPolicy resend,
            ModelCallShutdown shutdown,
            ConfigurableEnvironment environment) {
        TurnAwayResend turnAwayResend = new TurnAwayResend(resend, shutdown, InstantSource.system());
        return new OpenAiCompatibleModelCalls(
                modelClient(endpoint, apiKey(environment), turnAwayResend),
                endpoint.completions(),
                endpoint.pairedWith(catalog));
    }

    static RestClient modelClient(ModelEndpoint endpoint, String apiKey, TurnAwayResend turnAwayResend) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(endpoint.connectTimeout())
                .build());
        // Without one a call that never answers never ends: the factory sets no deadline of its own.
        requestFactory.setReadTimeout(endpoint.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor(turnAwayResend)
                .build();
    }

    /**
     * Read from the process environment alone, so a key written into a configuration file is never
     * taken; every refusal names the variable and never what it holds.
     */
    static String apiKey(ConfigurableEnvironment environment) {
        PropertySource<?> processEnvironment =
                environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        Object key = processEnvironment == null ? null : processEnvironment.getProperty(API_KEY_VARIABLE);
        if (!(key instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("The model endpoint is configured but the environment variable "
                    + API_KEY_VARIABLE + " holds no key to call it with");
        }
        if (!BEARER_TOKEN.matcher(text).matches()) {
            throw new IllegalStateException(
                    "The environment variable " + API_KEY_VARIABLE + " holds characters a bearer token cannot");
        }
        return text;
    }
}
