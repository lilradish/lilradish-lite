package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.model.ModelToolName;

/** How often a model may use one offered tool in this call. */
public record ToolUseLimit(ModelToolName tool, long maximum) {

    public ToolUseLimit {
        if (tool == null) {
            throw new NullPointerException("ToolUseLimit tool must not be null");
        }
        if (maximum <= 0) {
            throw new IllegalArgumentException("ToolUseLimit " + tool.value() + " maximum must be positive");
        }
    }
}
