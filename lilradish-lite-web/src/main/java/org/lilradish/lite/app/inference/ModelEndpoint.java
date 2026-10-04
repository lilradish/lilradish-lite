package org.lilradish.lite.app.inference;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.model.ModelName;
import org.lilradish.lite.domain.model.ModelToolName;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Where the deployment's models are called, and what each is called there. Paired with the catalog by
 * model name as the application starts, so a model the catalog holds and the endpoint cannot call,
 * or the other way about, stops the start. The key is never bound here: it is read from the
 * environment alone.
 *
 * @param models a list rather than a map keyed by name: a map key loses its underscores, and one
 *     from the environment is lowercased, so a name would be rewritten instead of refused
 */
@ConfigurationProperties(prefix = "lilradish.model-calls.endpoint", ignoreUnknownFields = false)
public record ModelEndpoint(URI baseUrl, Duration connectTimeout, Duration readTimeout, List<VendorModel> models) {

    private static final String PREFIX = "lilradish.model-calls.endpoint";

    private static final String COMPLETIONS = "/chat/completions";

    private static final String COMPATIBLE = "openai_compatible";

    public ModelEndpoint {
        if (baseUrl == null) {
            throw new NullPointerException(PREFIX + ".base-url must be set");
        }
        // Named and never echoed: an address can carry a secret in its user part or its query.
        if (!"https".equalsIgnoreCase(baseUrl.getScheme()) && !"http".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException(PREFIX + ".base-url must be an http or https address");
        }
        if (baseUrl.getHost() == null) {
            throw new IllegalArgumentException(PREFIX + ".base-url must name a host");
        }
        // Either would stand before the path the calls are made to.
        if (baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null) {
            throw new IllegalArgumentException(PREFIX + ".base-url must carry no query and no fragment");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        List<VendorModel> listed = models == null ? List.of() : models;
        for (int index = 0; index < listed.size(); index++) {
            VendorModel model = listed.get(index);
            if (model == null) {
                throw new NullPointerException(PREFIX + ".models holds a null model");
            }
            if (model.name() == null) {
                throw new NullPointerException(PREFIX + ".models[" + index + "] has no name");
            }
        }
        models = List.copyOf(listed);
    }

    URI completions() {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + COMPLETIONS);
    }

    /** Each model the catalog holds, by name, as the endpoint calls it; refused naming the first mismatch. */
    Map<ModelName, VendorModel> pairedWith(ModelCatalog catalog) {
        Map<ModelName, VendorModel> byName = HashMap.newHashMap(models.size());
        for (VendorModel model : models) {
            if (byName.putIfAbsent(model.name(), model) != null) {
                throw new IllegalStateException(
                        PREFIX + ".models lists " + model.name().value() + " more than once");
            }
            if (catalog.find(model.name()).isEmpty()) {
                throw new IllegalStateException(
                        PREFIX + ".models lists " + model.name().value() + ", which the model catalog does not hold");
            }
        }
        for (DeployedModel deployed : catalog.all()) {
            VendorModel model = byName.get(deployed.name());
            if (model == null) {
                throw new IllegalStateException("The model catalog holds "
                        + deployed.name().value() + ", which " + PREFIX + ".models does not list");
            }
            Set<ModelMode> given = HashSet.newHashSet(model.modes().size());
            for (VendorMode mode : model.modes()) {
                given.add(mode.mode());
                if (!deployed.modes().contains(mode.mode())) {
                    throw new IllegalStateException(
                            PREFIX + ".models gives " + deployed.name().value() + " the mode "
                                    + mode.mode().value() + ", which the model catalog does not offer it");
                }
            }
            for (ModelMode offered : deployed.modes()) {
                if (!given.contains(offered)) {
                    throw new IllegalStateException(
                            "The model catalog offers " + deployed.name().value() + " the mode " + offered.value()
                                    + ", which " + PREFIX + ".models does not give it");
                }
            }
        }
        return Map.copyOf(byName);
    }

    Map<CallKey, CallWay> callWays(ModelCatalog catalog) {
        Map<ModelName, VendorModel> paired = pairedWith(catalog);
        Map<CallKey, CallWay> ways = new HashMap<>();
        for (DeployedModel deployed : catalog.all()) {
            VendorModel vendor = Objects.requireNonNull(paired.get(deployed.name()));
            CallKey ordinary = new CallKey(deployed.name(), List.of());
            ways.put(ordinary, new CallWay(completions(), vendor, COMPATIBLE, COMPATIBLE));
            for (VendorCall call : vendor.calls()) {
                CallKey key = new CallKey(deployed.name(), call.tools());
                for (ModelToolName tool : key.tools()) {
                    if (deployed.tools().stream()
                            .noneMatch(offered -> offered.name().equals(tool))) {
                        throw new IllegalStateException(
                                PREFIX + ".models gives " + deployed.name().value() + " a call with " + tool.value()
                                        + ", which the catalog does not offer it");
                    }
                }
                if (ways.containsKey(key) && !key.tools().isEmpty()) {
                    throw new IllegalStateException(PREFIX + ".models gives "
                            + deployed.name().value() + " the same tool call more than once: " + key.tools());
                }
                ways.put(key, new CallWay(address(call.path()), vendor, call.outbound(), call.inbound()));
            }
            for (var offered : deployed.tools()) {
                if (ways.keySet().stream()
                        .noneMatch(key -> key.model().equals(deployed.name())
                                && key.tools().contains(offered.name()))) {
                    throw new IllegalStateException(
                            "The model catalog offers " + deployed.name().value()
                                    + " the tool " + offered.name().value() + ", but " + PREFIX
                                    + ".models gives no way to call it with that tool");
                }
            }
        }
        return Map.copyOf(ways);
    }

    private URI address(String path) {
        if (path == null
                || !path.startsWith("/")
                || path.startsWith("//")
                || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0
                || path.contains("..")) {
            throw new IllegalArgumentException(
                    PREFIX + ".models call path must be an absolute path without query or fragment");
        }
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    public record CallKey(ModelName model, List<ModelToolName> tools) {
        public CallKey {
            if (tools == null) {
                throw new NullPointerException("A model call has no tools list");
            }
            TreeSet<ModelToolName> unique =
                    new TreeSet<>((left, right) -> left.value().compareTo(right.value()));
            for (ModelToolName tool : tools) {
                if (tool == null) {
                    throw new NullPointerException("A model call holds a null tool");
                }
                if (!unique.add(tool)) {
                    throw new IllegalArgumentException("A model call repeats tool " + tool.value());
                }
            }
            tools = List.copyOf(unique);
        }
    }

    record CallWay(URI endpoint, VendorModel vendor, String outbound, String inbound) {}

    private static void requirePositive(Duration duration, String key) {
        if (duration == null) {
            throw new NullPointerException(PREFIX + "." + key + " must be set");
        }
        if (!duration.isPositive()) {
            throw new IllegalArgumentException(PREFIX + "." + key + " must be positive: " + duration);
        }
    }

    /**
     * @param id what the endpoint calls the model, which the deployment's own name need not be
     * @param modes how each mode the catalog offers the model is asked of the endpoint
     */
    record VendorModel(ModelName name, String id, List<VendorMode> modes, List<VendorCall> calls) {

        VendorModel(ModelName name, String id, List<VendorMode> modes) {
            this(name, id, modes, List.of());
        }

        @ConstructorBinding
        VendorModel {
            List<VendorMode> listed = modes == null ? List.of() : modes;
            // Bound one at a time, a nameless model is refused by the list, the one place that knows where it stands.
            modes = name == null ? listed : given(name, id, listed);
            calls = calls == null ? List.of() : List.copyOf(calls);
        }

        private static List<VendorMode> given(ModelName name, String id, List<VendorMode> listed) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(PREFIX + ".models gives " + name.value() + " no id");
            }
            Set<ModelMode> named = HashSet.newHashSet(listed.size());
            for (VendorMode mode : listed) {
                if (mode == null) {
                    throw new NullPointerException(PREFIX + ".models gives " + name.value() + " a null mode");
                }
                if (mode.mode() == null) {
                    throw new NullPointerException(PREFIX + ".models gives " + name.value() + " a mode with no name");
                }
                if (mode.reasoningEffort() == null) {
                    throw new NullPointerException(PREFIX + ".models gives " + name.value() + " the mode "
                            + mode.mode().value() + " no reasoning-effort");
                }
                if (!named.add(mode.mode())) {
                    throw new IllegalArgumentException(PREFIX + ".models gives " + name.value() + " the mode "
                            + mode.mode().value() + " more than once");
                }
            }
            return List.copyOf(listed);
        }

        ReasoningEffort effortOf(ModelMode mode) {
            for (VendorMode given : modes) {
                if (given.mode().equals(mode)) {
                    return given.reasoningEffort();
                }
            }
            throw new IllegalArgumentException(name.value() + " is given no mode " + mode.value());
        }
    }

    record VendorCall(List<ModelToolName> tools, String path, String outbound, String inbound) {
        VendorCall {
            tools = tools == null ? List.of() : List.copyOf(tools);
            if (outbound == null || outbound.isBlank() || inbound == null || inbound.isBlank()) {
                throw new IllegalArgumentException(PREFIX + ".models call has no converters");
            }
        }
    }

    /** Held whole by the model it is given to, which alone can name that model in a refusal. */
    record VendorMode(ModelMode mode, ReasoningEffort reasoningEffort) {}
}
