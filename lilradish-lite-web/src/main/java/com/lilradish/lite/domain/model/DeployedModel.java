package com.lilradish.lite.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Both limits count in the model's own units, which this system can
 * only estimate.
 *
 * @param modes those it offers beyond running as it is, in the order listed; a list because, bound
 *     as a set, a mode listed twice would be merged before it could be refused
 * @param charactersPerUnit how many characters this system counts as one of the model's units
 * @param pricesPerMillion in the order listed; none listed is none held
 */
public record DeployedModel(
        ModelName name,
        List<ModelMode> modes,
        long sentPerCallLimit,
        BigDecimal charactersPerUnit,
        long cameBackPerCallLimit,
        List<ModelPrice> pricesPerMillion,
        List<DeployedTool> tools) {

    private static final int CHARACTERS_PER_UNIT_SCALE = 4;

    private static final BigDecimal MOST_COUNTED = BigDecimal.valueOf(Long.MAX_VALUE);

    public DeployedModel {
        if (name == null) {
            throw new NullPointerException("DeployedModel name must not be null");
        }
        List<ModelMode> offered = modes == null ? List.of() : modes;
        Set<ModelMode> named = HashSet.newHashSet(offered.size());
        for (ModelMode mode : offered) {
            if (mode == null) {
                throw new NullPointerException("DeployedModel " + name.value() + " holds a null mode");
            }
            if (!named.add(mode)) {
                throw new IllegalArgumentException(
                        "DeployedModel " + name.value() + " offers " + mode.value() + " more than once");
            }
        }
        modes = List.copyOf(offered);
        if (sentPerCallLimit <= 0) {
            throw new IllegalArgumentException("DeployedModel " + name.value()
                    + " sent-per-call limit is missing or not positive: " + sentPerCallLimit);
        }
        if (charactersPerUnit == null) {
            throw new NullPointerException("DeployedModel " + name.value() + " has no characters-per-unit");
        }
        if (charactersPerUnit.signum() <= 0) {
            throw new IllegalArgumentException("DeployedModel " + name.value()
                    + " characters-per-unit is not positive: " + charactersPerUnit.toPlainString());
        }
        if (charactersPerUnit.stripTrailingZeros().scale() > CHARACTERS_PER_UNIT_SCALE) {
            throw new IllegalArgumentException("DeployedModel " + name.value() + " characters-per-unit has more than "
                    + CHARACTERS_PER_UNIT_SCALE + " decimal places: " + charactersPerUnit.toPlainString());
        }
        if (cameBackPerCallLimit <= 0) {
            throw new IllegalArgumentException("DeployedModel " + name.value()
                    + " came-back-per-call limit is missing or not positive: " + cameBackPerCallLimit);
        }
        List<ModelPrice> listed = pricesPerMillion == null ? List.of() : pricesPerMillion;
        Set<Currency> priced = HashSet.newHashSet(listed.size());
        for (ModelPrice price : listed) {
            if (price == null) {
                throw new NullPointerException("DeployedModel " + name.value() + " holds a null price");
            }
            if (!priced.add(price.currency())) {
                throw new IllegalArgumentException("DeployedModel " + name.value() + " prices "
                        + price.currency().getCurrencyCode() + " more than once");
            }
        }
        pricesPerMillion = List.copyOf(listed);
        List<DeployedTool> offeredTools = tools == null ? List.of() : tools;
        Set<ModelToolName> toolNames = HashSet.newHashSet(offeredTools.size());
        for (DeployedTool tool : offeredTools) {
            if (tool == null) {
                throw new NullPointerException("DeployedModel " + name.value() + " holds a null tool");
            }
            if (!toolNames.add(tool.name())) {
                throw new IllegalArgumentException("DeployedModel " + name.value() + " offers "
                        + tool.name().value() + " more than once");
            }
        }
        tools = List.copyOf(offeredTools);
    }

    /**
     * Rounded up from the count given. Independently displayed parts may round differently from their whole.
     */
    public long unitsOf(long characters) {
        return units(characters).longValueExact();
    }

    /**
     * Sole comparison with the sent limit: returns excess model units, or zero where it fits.
     * A character count of {@link Long#MAX_VALUE}, or excess beyond a long, returns {@link Long#MAX_VALUE}.
     */
    public long unitsPast(long characters) {
        if (characters == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        BigDecimal past = units(characters).subtract(BigDecimal.valueOf(sentPerCallLimit));
        return past.signum() <= 0 ? 0 : past.min(MOST_COUNTED).longValueExact();
    }

    public boolean takes(long characters) {
        return unitsPast(characters) == 0;
    }

    private BigDecimal units(long characters) {
        if (characters < 0) {
            throw new IllegalArgumentException(
                    "DeployedModel " + name.value() + " cannot count a negative number of characters: " + characters);
        }
        return BigDecimal.valueOf(characters).divide(charactersPerUnit, 0, RoundingMode.CEILING);
    }
}
