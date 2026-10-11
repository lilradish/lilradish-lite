package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.inference.CallOutcome;
import com.lilradish.lite.domain.inference.CallProgress;
import com.lilradish.lite.domain.inference.CallRequest;
import com.lilradish.lite.domain.inference.ModelCalls;
import java.util.List;

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
