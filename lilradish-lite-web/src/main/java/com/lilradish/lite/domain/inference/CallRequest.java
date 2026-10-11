package com.lilradish.lite.domain.inference;

import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelMode;
import com.lilradish.lite.domain.model.SentText;
import org.jspecify.annotations.Nullable;

/**
 * Rechecks the sent count against this request's own model, so skipping the earlier check still fails before sending.
 *
 * @param mode none runs the model as it is
 */
public record CallRequest(DeployedModel model, @Nullable ModelMode mode, ModelCallPurpose purpose, SentText sent) {

    public CallRequest {
        if (model == null) {
            throw new NullPointerException("CallRequest model must not be null");
        }
        if (mode != null && !model.modes().contains(mode)) {
            throw new IllegalArgumentException("CallRequest to " + model.name().value() + " asks for mode "
                    + mode.value() + ", which it does not offer");
        }
        if (purpose == null) {
            throw new NullPointerException("CallRequest to " + model.name().value() + " has no purpose");
        }
        if (sent == null) {
            throw new NullPointerException("CallRequest to " + model.name().value() + " sends nothing");
        }
        if (sent.system().isEmpty()) {
            throw new IllegalArgumentException("CallRequest to " + model.name().value() + " sends no system text");
        }
        if (sent.user().isEmpty()) {
            throw new IllegalArgumentException("CallRequest to " + model.name().value() + " sends no user text");
        }
        if (!model.takes(sent.characters())) {
            throw new IllegalArgumentException("CallRequest to " + model.name().value() + " would send "
                    + model.unitsOf(sent.characters()) + " units, more than the " + model.sentPerCallLimit()
                    + " it takes");
        }
    }
}
