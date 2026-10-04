package org.lilradish.lite.app.inference;

import java.util.List;
import java.util.Map;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.inference.CallOutcome;
import org.lilradish.lite.domain.model.ModelToolName;

/** A call's familiar ending, plus what the provider reported about its tools. */
public record ToolCallOutcome(
        CallOutcome outcome,
        Map<ModelToolName, Long> reportedUses,
        @DoNotLog List<SearchObservation> observations) {

    public ToolCallOutcome {
        if (outcome == null) {
            throw new NullPointerException("ToolCallOutcome outcome must not be null");
        }
        if (reportedUses == null || observations == null) {
            throw new NullPointerException("ToolCallOutcome tool reports must not be null");
        }
        for (var entry : reportedUses.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("ToolCallOutcome holds an invalid tool count");
            }
        }
        reportedUses = Map.copyOf(reportedUses);
        observations = List.copyOf(observations);
        if (!(outcome instanceof CallOutcome.CameBack) && (!reportedUses.isEmpty() || !observations.isEmpty())) {
            throw new IllegalArgumentException("A call without an answer cannot report tool use");
        }
    }
}
