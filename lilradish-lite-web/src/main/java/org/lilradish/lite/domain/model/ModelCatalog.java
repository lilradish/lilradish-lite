package org.lilradish.lite.domain.model;

import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Every model this deployment holds, at least one and each under its own name. Built once and never
 * changed: what is held changes only by deploying, so every answer is worked out here in advance.
 */
public final class ModelCatalog {

    private final List<DeployedModel> models;

    private final Map<ModelName, DeployedModel> modelsByName;

    private final Map<ModelName, Map<Currency, ModelPrice>> pricesByName;

    private final List<Currency> currencies;

    public ModelCatalog(List<DeployedModel> models) {
        if (models.isEmpty()) {
            throw new IllegalArgumentException("ModelCatalog holds no models");
        }
        Map<ModelName, DeployedModel> modelsByName = HashMap.newHashMap(models.size());
        Map<ModelName, Map<Currency, ModelPrice>> pricesByName = HashMap.newHashMap(models.size());
        TreeSet<Currency> currencies = new TreeSet<>(Comparator.comparing(Currency::getCurrencyCode));
        for (DeployedModel model : models) {
            if (model == null) {
                throw new NullPointerException("ModelCatalog holds a null model");
            }
            if (modelsByName.putIfAbsent(model.name(), model) != null) {
                throw new IllegalArgumentException(
                        "ModelCatalog lists " + model.name().value() + " more than once");
            }
            Map<Currency, ModelPrice> prices =
                    HashMap.newHashMap(model.pricesPerMillion().size());
            for (ModelPrice price : model.pricesPerMillion()) {
                prices.put(price.currency(), price);
                currencies.add(price.currency());
            }
            pricesByName.put(model.name(), prices);
        }
        this.models = List.copyOf(models);
        this.modelsByName = modelsByName;
        this.pricesByName = pricesByName;
        this.currencies = List.copyOf(currencies);
    }

    public Optional<DeployedModel> find(ModelName name) {
        return Optional.ofNullable(modelsByName.get(name));
    }

    /** In the order the deployment lists them. */
    public List<DeployedModel> all() {
        return models;
    }

    /** Empty both where the model is not held and where it has no price in that currency. */
    public Optional<ModelPrice> price(ModelName name, Currency currency) {
        Map<Currency, ModelPrice> prices = pricesByName.get(name);
        return prices == null ? Optional.empty() : Optional.ofNullable(prices.get(currency));
    }

    /** Each currency any model is priced in, once, ordered by its code. */
    public List<Currency> currencies() {
        return currencies;
    }
}
