package org.lilradish.lite.app.inference;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.inference.ModelCalls;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start unless exactly one thing calls the deployment's models, and says what is wrong: none
 * where no endpoint is configured, or which ones where more than one stands behind {@link ModelCalls}.
 * Read off the bean definitions: unsupplied or doubled, the fault would otherwise surface as whichever
 * bean asked first.
 *
 * <p>Refuses as well, by name alone, a setting under the model calls' prefix that nothing binds. Left to
 * the binder it would stop the start all the same, but its failure report prints the value, and a key
 * written there would be printed.
 */
@Component
final class ModelCallsRequired implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final ConfigurationPropertyName PREFIX = ConfigurationPropertyName.of("lilradish.model-calls");

    // Every name the endpoint and the resend settings bind, in the binder's uniform form, an index as []. A
    // component added to either record and not here is refused at the start by name: kept in step, never silently.
    private static final Set<String> BOUND = Set.of(
            "endpoint.baseurl",
            "endpoint.connecttimeout",
            "endpoint.readtimeout",
            "endpoint.models[].name",
            "endpoint.models[].id",
            "endpoint.models[].modes[].mode",
            "endpoint.models[].modes[].reasoningeffort",
            "resend.times",
            "resend.firstwait",
            "resend.longestwait");

    // Told rather than injected: a post-processor is made before constructor autowiring is in place.
    private @Nullable Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        requireNothingUnbound();
        // Definitions rather than instances, and no eager initialisation: this runs before any singleton is created.
        String[] modelCalls = beanFactory.getBeanNamesForType(ModelCalls.class, true, false);
        if (modelCalls.length == 0) {
            throw new ApplicationContextException("This deployment carries no ModelCalls, so it has no way to call "
                    + "a model. Set lilradish.model-calls.endpoint.base-url to call the deployment's endpoint.");
        }
        if (modelCalls.length > 1) {
            throw new ApplicationContextException("This deployment carries more than one ModelCalls, so which one "
                    + "calls a model is not decided: " + String.join(", ", modelCalls) + ".");
        }
    }

    /** Only where the binder would report a name: the process environment and system properties it passes over. */
    private void requireNothingUnbound() {
        UnboundElementsSourceFilter reported = new UnboundElementsSourceFilter();
        Set<String> unbound = new TreeSet<>();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(
                Objects.requireNonNull(environment, "ModelCallsRequired was run before it was told its environment"))) {
            if (source instanceof IterableConfigurationPropertySource iterable && reported.apply(source)) {
                for (ConfigurationPropertyName name : iterable.filter(PREFIX::isAncestorOf)) {
                    if (!BOUND.contains(boundForm(name))) {
                        unbound.add(name.toString());
                    }
                }
            }
        }
        if (!unbound.isEmpty()) {
            throw new ApplicationContextException("Nothing binds " + String.join(", ", unbound)
                    + ". The model endpoint's key is read from the environment variable "
                    + ModelCallsConfiguration.API_KEY_VARIABLE + " alone.");
        }
    }

    private static String boundForm(ConfigurationPropertyName name) {
        StringBuilder form = new StringBuilder();
        for (int element = PREFIX.getNumberOfElements(); element < name.getNumberOfElements(); element++) {
            if (name.isNumericIndex(element)) {
                form.append("[]");
            } else {
                if (!form.isEmpty()) {
                    form.append('.');
                }
                form.append(name.getElement(element, ConfigurationPropertyName.Form.UNIFORM));
            }
        }
        return form.toString();
    }
}
