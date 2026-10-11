package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.model.ModelToolKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The conversion implementations a deployment may name, resolved before calls begin. */
public final class ModelCallConversions {

    private final Map<String, ModelCallOutbound> outbound;

    private final Map<String, ModelCallInbound> inbound;

    public ModelCallConversions(List<ModelCallOutbound> outbound, List<ModelCallInbound> inbound) {
        Map<String, ModelCallOutbound> outgoing = new HashMap<>();
        for (ModelCallOutbound converter : outbound) {
            if (converter.id() == null || converter.id().isBlank()) {
                throw new IllegalStateException("An outbound model call converter has no id");
            }
            if (outgoing.putIfAbsent(converter.id(), converter) != null) {
                throw new IllegalStateException(
                        "More than one outbound model call converter is named " + converter.id());
            }
        }
        Map<String, ModelCallInbound> incoming = new HashMap<>();
        for (ModelCallInbound converter : inbound) {
            if (converter.id() == null || converter.id().isBlank()) {
                throw new IllegalStateException("An inbound model call converter has no id");
            }
            if (incoming.putIfAbsent(converter.id(), converter) != null) {
                throw new IllegalStateException(
                        "More than one inbound model call converter is named " + converter.id());
            }
        }
        this.outbound = Map.copyOf(outgoing);
        this.inbound = Map.copyOf(incoming);
    }

    public void requireKind(String outboundId, String inboundId, ModelToolKind kind, String model, String tool) {
        if (!outbound(outboundId).supportedToolKinds().contains(kind)
                || !inbound(inboundId).supportedToolKinds().contains(kind)) {
            throw new IllegalStateException(
                    "Model " + model + " tool " + tool + " has no read-only conversion for its kind " + kind);
        }
    }

    public ModelCallOutbound outbound(String id) {
        ModelCallOutbound found = outbound.get(id);
        if (found == null) {
            throw new IllegalStateException("No outbound model call converter is named " + id);
        }
        return found;
    }

    public ModelCallInbound inbound(String id) {
        ModelCallInbound found = inbound.get(id);
        if (found == null) {
            throw new IllegalStateException("No inbound model call converter is named " + id);
        }
        return found;
    }
}
