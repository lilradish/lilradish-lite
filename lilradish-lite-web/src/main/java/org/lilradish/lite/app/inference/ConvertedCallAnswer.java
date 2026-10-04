package org.lilradish.lite.app.inference;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.model.ModelToolName;

/** The provider's content and any decline words before the workflow checks the answer's declared shape. */
public record ConvertedCallAnswer(
        @DoNotLog String answer,
        @Nullable Long sentCount,
        @Nullable Long cameBackCount,
        boolean cutOff,
        Map<ModelToolName, Long> reportedUses,
        @DoNotLog List<SearchObservation> observations,
        @Nullable DidNotFitReason didNotFitReason,
        @DoNotLog @Nullable String declinedWords,
        long generatedToolCallCharacters) {

    public ConvertedCallAnswer {
        if (answer == null || reportedUses == null || observations == null) {
            throw new NullPointerException("ConvertedCallAnswer must contain an answer and tool reports");
        }
        if (sentCount != null && sentCount < 1 || cameBackCount != null && cameBackCount < 0) {
            throw new IllegalArgumentException("ConvertedCallAnswer has an invalid model count");
        }
        if (generatedToolCallCharacters < 0) {
            throw new IllegalArgumentException("ConvertedCallAnswer has an invalid generated tool-call count");
        }
        if (didNotFitReason != null && !didNotFitReason.mayEndCallBeforeUnwrapping()) {
            throw new IllegalArgumentException("ConvertedCallAnswer has no such conversion outcome");
        }
        if (declinedWords != null && didNotFitReason != DidNotFitReason.MODEL_DECLINED) {
            throw new IllegalArgumentException("ConvertedCallAnswer has decline words only with model decline");
        }
        for (var entry : reportedUses.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("ConvertedCallAnswer has an invalid tool count");
            }
        }
        reportedUses = Map.copyOf(reportedUses);
        observations = List.copyOf(observations);
    }
}
