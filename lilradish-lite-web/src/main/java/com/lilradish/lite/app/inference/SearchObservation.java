package com.lilradish.lite.app.inference;

import com.lilradish.lite.domain.model.ModelToolName;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/** What the provider says one search sought and found; either text may be absent. */
public record SearchObservation(
        ModelToolName tool,
        @DoNotLog @Nullable String searchedFor,
        @DoNotLog @Nullable String found) {

    public SearchObservation {
        if (tool == null) {
            throw new NullPointerException("SearchObservation tool must not be null");
        }
    }
}
