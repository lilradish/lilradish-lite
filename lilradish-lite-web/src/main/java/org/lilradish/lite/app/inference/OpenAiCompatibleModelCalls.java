package org.lilradish.lite.app.inference;

import java.util.List;
import org.lilradish.lite.domain.inference.CallOutcome;
import org.lilradish.lite.domain.inference.CallProgress;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.inference.ModelCalls;

/** Makes no-tool model calls through the configured transport. */
final class OpenAiCompatibleModelCalls implements ModelCalls {

    private final ModelCallTransport transport;

    OpenAiCompatibleModelCalls(ModelCallTransport transport) {
        this.transport = transport;
    }

    @Override
    public CallOutcome call(CallRequest request, CallProgress progress) {
        return transport.send(request, List.of(), progress).outcome();
    }
}
