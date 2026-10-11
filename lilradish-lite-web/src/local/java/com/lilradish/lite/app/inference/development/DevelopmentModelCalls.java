package com.lilradish.lite.app.inference.development;

import com.lilradish.lite.development.DevelopmentOnly;
import com.lilradish.lite.domain.inference.CallOutcome;
import com.lilradish.lite.domain.inference.CallProgress;
import com.lilradish.lite.domain.inference.CallRequest;
import com.lilradish.lite.domain.inference.ModelCalls;
import com.lilradish.lite.domain.model.CameBackMeasure;
import com.lilradish.lite.domain.model.DeployedModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Answers every call at once with a fixed answer for its purpose, and calls no model: producing, one
 * document; reviewing, what that document leaves to review assured; helping, a short answer. The answer depends
 * on the purpose alone: nothing a run holds or sends can steer it. Both counts are this system's measure, and
 * are said to be. A model that may give back less than the answer is given as much of it as fits, cut
 * off, as a model stopped at its limit would be.
 */
@DevelopmentOnly
@Component
public final class DevelopmentModelCalls implements ModelCalls {

    // Fits only the question version the dev seed's model step pins; any other question does not fit.
    private static final String PRODUCED = "{\"values\":{\"category\":\"Billing\",\"details\":{\"product\":"
            + "\"Travel kettle, 1.2 litre (stand-in answer; no model was called)\",\"order_reference\":\"4417-2093\"}},"
            + "\"confidences\":{\"category\":90}}";

    // Its category is sure enough to stand, so deciding it too would not fit: only the details wait on review.
    private static final String REVIEWED = "{\"decisions\":{\"details\":{\"outcome\":\"assured\"}}}";

    private static final String HELPED =
            "**Stand-in answer: no model was called, and nothing in this run was read.**\n\nA helper's answer reads like this:\n\n1. Check that the order reference matches the one in the customer's email before accepting the details.\n2. If the category looks wrong, reject it with a short reason, such as \"The customer asks about a late parcel, not a charge.\", so the next attempt has something to go on.";

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
            return CallOutcome.CameBack.measuredHere(model, request.sent(), answer, false, null, null, 0L);
        }
        String cut = answer.substring(0, answer.offsetByCodePoints(0, most.intValueExact()));
        return CallOutcome.CameBack.measuredHere(model, request.sent(), cut, true, null, null, 0L);
    }
}
