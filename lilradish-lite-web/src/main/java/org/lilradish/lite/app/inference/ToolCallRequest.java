package org.lilradish.lite.app.inference;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.model.DeployedTool;
import org.lilradish.lite.domain.model.ModelToolName;

/** A model call and the read-only tools it may use in this one call. */
public record ToolCallRequest(CallRequest call, List<ToolUseLimit> tools) {

    public ToolCallRequest {
        if (call == null) {
            throw new NullPointerException("ToolCallRequest call must not be null");
        }
        if (call.purpose() != ModelCallPurpose.PRODUCE) {
            throw new IllegalArgumentException("Only a production call may use tools");
        }
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("ToolCallRequest must name at least one tool");
        }
        Set<ModelToolName> named = HashSet.newHashSet(tools.size());
        for (ToolUseLimit use : tools) {
            if (use == null) {
                throw new NullPointerException("ToolCallRequest holds a null tool use");
            }
            if (!named.add(use.tool())) {
                throw new IllegalArgumentException(
                        "ToolCallRequest repeats tool " + use.tool().value());
            }
            DeployedTool offered = call.model().tools().stream()
                    .filter(tool -> tool.name().equals(use.tool()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Model " + call.model().name().value() + " does not offer tool "
                                    + use.tool().value()));
            if (use.maximum() > offered.maxUsesPerCall()) {
                throw new IllegalArgumentException("ToolCallRequest asks " + use.maximum() + " uses of "
                        + use.tool().value() + ", more than "
                        + call.model().name().value() + " offers");
            }
        }
        tools = List.copyOf(tools);
    }
}
