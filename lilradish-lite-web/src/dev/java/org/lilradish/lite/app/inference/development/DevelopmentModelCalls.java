package org.lilradish.lite.app.inference.development;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.lilradish.lite.development.DevelopmentOnly;
import org.lilradish.lite.domain.inference.CallOutcome;
import org.lilradish.lite.domain.inference.CallProgress;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.inference.ModelCalls;
import org.lilradish.lite.domain.model.CameBackMeasure;
import org.lilradish.lite.domain.model.DeployedModel;
import org.springframework.stereotype.Component;

/**
 * Answers every call at once with a fixed answer for its purpose, and calls no model: producing, one
 * document; reviewing, what that document leaves to review assured; helping, a sentence. The answer depends
 * on the purpose alone: nothing a run holds or sends can steer it. Both counts are this system's measure, and
 * are said to be. A model that may give back less than the answer is given as much of it as fits, cut
 * off, as a model stopped at its limit would be.
 */
@DevelopmentOnly
@Component
public final class DevelopmentModelCalls implements ModelCalls {

    // Fits only the question version the dev seed's model step pins; any other question does not fit.
    private static final String PRODUCED = "{\"values\":{\"category\":\"Billing\",\"details\":{\"product\":"
            + "\"A development stand-in produced this; no model was called.\",\"order_reference\":null}},"
            + "\"confidences\":{\"category\":90}}";

    // Its category is sure enough to stand, so deciding it too would not fit: only the details wait on review.
    private static final String REVIEWED = "{\"decisions\":{\"details\":{\"outcome\":\"assured\"}}}";

    private static final String HELPED = "A development stand-in answered this; no model was called.";

    @Override
    public CallOutcome call(CallRequest request, CallProgress progress) {
        progress.aboutToSend();
        String answer =
                switch (request.purpose()) {
                    case PRODUCE -> PRODUCED;
                    case REVIEW -> REVIEWED;
                    case HELP -> HELPED;
                };
        DeployedModel model = request.model();
        // The most characters whose units, rounded up as the model's measure rounds them, stay within the limit.
        BigDecimal most = BigDecimal.valueOf(model.cameBackPerCallLimit())
                .multiply(model.charactersPerUnit())
                .setScale(0, RoundingMode.FLOOR);
        if (most.compareTo(BigDecimal.valueOf(CameBackMeasure.characters(answer))) >= 0) {
            return CallOutcome.CameBack.measuredHere(model, request.sent(), answer, false);
        }
        String cut = answer.substring(0, answer.offsetByCodePoints(0, most.intValueExact()));
        return CallOutcome.CameBack.measuredHere(model, request.sent(), cut, true);
    }
}
