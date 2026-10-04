package org.lilradish.lite.app.inference;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.model.ModelToolName;

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
