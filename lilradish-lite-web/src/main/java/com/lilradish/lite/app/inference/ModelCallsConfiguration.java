package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.inference.ModelCalls;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.model.ModelName;
import java.net.http.HttpClient;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
 * Enabled explicitly so a
 * development or test start can keep its stand-in without binding endpoints it lacks.
 *
 * <p>The client is built here and never published: a client without the resend would turn a
 * turnaway into an error, and one injected elsewhere would send what nobody recorded.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "lilradish.model-calls.endpoint", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ModelEndpoint.class, ResendPolicy.class})
class ModelCallsConfiguration {

    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~+/-]+=*");

    @Bean
    OpenAiCompatibleCallConversion openAiCompatibleCallConversion() {
        return new OpenAiCompatibleCallConversion();
    }

    @Bean
    ModelCallConversions modelCallConversions(List<ModelCallOutbound> outbound, List<ModelCallInbound> inbound) {
        return new ModelCallConversions(outbound, inbound);
    }

    @Bean
    ModelCallShutdown modelCallShutdown() {
        return new ModelCallShutdown();
    }

    @Bean
    LongestSend longestSend(ModelEndpoint endpoint) {
        return LongestSend.of(endpoint);
    }

    // Eager so a missing route, conversion or key stops the start rather than the first call.
    @Bean
    @Lazy(false)
    ModelCallTransport modelCallTransport(
            ModelCatalog catalog,
            ModelEndpoint endpoint,
            ModelCallConversions conversions,
            ResendPolicy resend,
            ModelCallShutdown shutdown,
            ConfigurableEnvironment environment) {
        TurnAwayResend turnAwayResend = new TurnAwayResend(resend, shutdown, InstantSource.system());
        var ways = endpoint.callWays(catalog);
        for (var entry : ways.entrySet()) {
            for (var toolName : entry.getKey().tools()) {
                var tool = catalog.tool(entry.getKey().model(), toolName).orElseThrow();
                conversions.requireKind(
                        entry.getValue().outbound(),
                        entry.getValue().inbound(),
                        tool.kind(),
                        entry.getKey().model().value(),
                        toolName.value());
            }
        }
        Map<ModelName, RestClient> clients = new HashMap<>();
        for (var model : endpoint.models()) {
            // Boot's binder permits unresolved strings; validate strictly at startup and discard the result.
            environment.resolveRequiredPlaceholders(model.id());
            clients.put(model.name(), modelClient(endpoint, apiKey(environment, model), turnAwayResend));
        }
        return new ModelCallTransport(clients, ways, conversions);
    }

    @Bean
    ModelCalls modelCalls(ModelCallTransport transport) {
        return new OpenAiCompatibleModelCalls(transport);
    }

    @Bean
    ToolModelCalls toolModelCalls(ModelCallTransport transport) {
        return new ConfiguredToolModelCalls(transport);
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
     * taken; every refusal names the model and property without echoing either configured value.
     */
    static String apiKey(ConfigurableEnvironment environment, ModelEndpoint.VendorModel model) {
        PropertySource<?> processEnvironment =
                environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (model.apiKeyVariable() == null
                || !ENVIRONMENT_VARIABLE.matcher(model.apiKeyVariable()).matches()) {
            throw new IllegalStateException("Model " + model.name().value()
                    + " has an api-key-variable that must name a process environment variable");
        }
        Object key = processEnvironment != null && processEnvironment.getSource() instanceof Map<?, ?> variables
                ? variables.get(model.apiKeyVariable())
                : null;
        if (!(key instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Model " + model.name().value()
                    + " has an api-key-variable whose process environment variable holds no key");
        }
        if (!BEARER_TOKEN.matcher(text).matches()) {
            throw new IllegalStateException(
                    "Model " + model.name().value()
                            + " has an api-key-variable whose process environment variable holds characters a bearer token cannot");
        }
        return text;
    }
}
