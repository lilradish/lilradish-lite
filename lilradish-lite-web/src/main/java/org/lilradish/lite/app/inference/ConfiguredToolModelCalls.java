package org.lilradish.lite.app.inference;

import org.lilradish.lite.domain.inference.CallProgress;

/** Sends tool calls through the deployment's resolved HTTP call ways. */
final class ConfiguredToolModelCalls implements ToolModelCalls {

    private final ModelCallTransport transport;

    ConfiguredToolModelCalls(ModelCallTransport transport) {
        this.transport = transport;
    }

    @Override
    public ToolCallOutcome call(ToolCallRequest request, CallProgress progress) {
        return transport.send(request.call(), request.tools(), progress);
    }
}
