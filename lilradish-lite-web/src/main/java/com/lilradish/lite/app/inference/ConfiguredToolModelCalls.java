package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.inference.CallProgress;

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
