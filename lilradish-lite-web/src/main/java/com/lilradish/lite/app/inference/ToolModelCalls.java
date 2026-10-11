package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.inference.CallProgress;

/** Calls a model with read-only tools, preserving its ending and the provider's observations. */
public interface ToolModelCalls {

    ToolCallOutcome call(ToolCallRequest request, CallProgress progress);
}
