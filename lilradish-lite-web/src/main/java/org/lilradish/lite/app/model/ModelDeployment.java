package org.lilradish.lite.app.model;

import java.math.BigDecimal;
import java.util.List;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.DeployedTool;
import org.lilradish.lite.domain.model.ModelMode;
import org.lilradish.lite.domain.model.ModelName;
import org.lilradish.lite.domain.model.ModelPrice;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** What the deployment lists; names are bound in lists so spelling is preserved and checked. */
@ConfigurationProperties(prefix = "lilradish.deployed", ignoreUnknownFields = false)
record ModelDeployment(List<ConfiguredModel> models) {

    ModelDeployment {
        models = models == null ? List.of() : models;
    }

    record ConfiguredModel(
            ModelName name,
            List<ModelMode> modes,
            long sentPerCallLimit,
            BigDecimal charactersPerUnit,
            long cameBackPerCallLimit,
            List<ModelPrice> pricesPerMillion,
            List<DeployedTool> tools) {

        DeployedModel model() {
            return new DeployedModel(
                    name, modes, sentPerCallLimit, charactersPerUnit, cameBackPerCallLimit, pricesPerMillion, tools);
        }
    }
}
