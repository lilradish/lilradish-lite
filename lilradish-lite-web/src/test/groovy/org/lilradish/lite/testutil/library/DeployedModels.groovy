package org.lilradish.lite.testutil.library

import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName

/**
 * What the deployment holds for the library's specs: one model offering a mode beyond running as it is, one offering
 * none. Kept apart from any store, so a spec of pure logic naming it is still one mutation testing runs.
 */
final class DeployedModels {

    static final ModelCatalog HELD = new ModelCatalog([
            new DeployedModel(new ModelName("general"), [new ModelMode("research")], 100_000, 4.0G, 4_000, []),
            new DeployedModel(new ModelName("small"), [], 100_000, 4.0G, 4_000, [])])

    private DeployedModels() {}
}
