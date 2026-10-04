package org.lilradish.lite.domain.model;

import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A read-only tool a model may use, its per-call bound, and its price in each held currency. */
public record DeployedTool(ModelToolName name, ModelToolKind kind, long maxUsesPerCall, List<ToolPrice> pricesPerUse) {

    public DeployedTool {
        if (name == null) {
            throw new NullPointerException("DeployedTool name must not be null");
        }
        if (kind == null) {
            throw new NullPointerException("DeployedTool " + name.value() + " has no read-only kind");
        }
        if (maxUsesPerCall <= 0) {
            throw new IllegalArgumentException(
                    "DeployedTool " + name.value() + " max uses per call must be positive: " + maxUsesPerCall);
        }
        List<ToolPrice> listed = pricesPerUse == null ? List.of() : pricesPerUse;
        Set<Currency> priced = HashSet.newHashSet(listed.size());
        for (ToolPrice price : listed) {
            if (price == null) {
                throw new NullPointerException("DeployedTool " + name.value() + " holds a null price");
            }
            if (!priced.add(price.currency())) {
                throw new IllegalArgumentException("DeployedTool " + name.value() + " prices "
                        + price.currency().getCurrencyCode() + " more than once");
            }
        }
        pricesPerUse = List.copyOf(listed);
    }
}
