package org.lilradish.lite.testutil.library

import java.util.Currency
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.DeployedTool
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelToolKind
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.model.ToolPrice
import org.lilradish.lite.domain.search.SearchWay

/** Store-independent fixtures keep pure logic specs eligible for mutation testing. */
final class DeployedModels {

    static final ModelCatalog HELD = new ModelCatalog([
            new DeployedModel(new ModelName("general"), [new ModelMode("research")], 100_000, 4.0G, 4_000, []),
            new DeployedModel(new ModelName("small"), [], 100_000, 4.0G, 4_000, [])])


    static final SearchWay WEB = new SearchWay("web", new ModelToolName("web_search"), 200, 4000, 2000, 1000)


    static final ModelCatalog SEARCHING = new ModelCatalog([
            new DeployedModel(new ModelName("general"), [new ModelMode("research")], 100_000, 4.0G, 4_000, [],
                    [new DeployedTool(WEB.requiredTool(), ModelToolKind.WEB_SEARCH, 5,
                            [new ToolPrice(Currency.getInstance("USD"), new BigDecimal("0.01"))]),
                     new DeployedTool(new ModelToolName("archive_search"), ModelToolKind.WEB_SEARCH, 2, [])]),
            new DeployedModel(new ModelName("small"), [], 100_000, 4.0G, 4_000, [])])

    private DeployedModels() {}
}
