package com.lilradish.lite.domain.search;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.model.ToolPrice;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one call's searches came to, in the one currency a group reads its costs in, worked out as it is read and
 * stored nowhere: one amount, or none and why. The prices are the ones written down as the call was sent, but
 * the currency is the group's as it is read, so a currency changed since is the one shown. Never rounded, and
 * counted toward no ceiling.
 */
public sealed interface SearchFee
        permits SearchFee.Amount, SearchFee.NotInCurrency, SearchFee.ManyCurrencies, SearchFee.NoPrice {

    /**
     * No currency is ever turned into another: a chosen currency the price lacks gives no amount.
     *
     * @param pricesThen one search's price in each currency it had one in as the call was sent, each at most once
     * @param chosen the group's currency now; none where it has chosen none
     */
    static SearchFee of(SearchUses uses, List<ToolPrice> pricesThen, @Nullable Currency chosen) {
        requireNonNull(uses, "SearchFee uses must not be null");
        requireNonNull(pricesThen, "SearchFee prices must not be null");
        if (pricesThen.isEmpty()) {
            return new NoPrice();
        }
        if (chosen == null) {
            return pricesThen.size() == 1 ? Amount.of(uses, pricesThen.getFirst()) : new ManyCurrencies();
        }
        for (ToolPrice price : pricesThen) {
            if (price.currency().equals(chosen)) {
                return Amount.of(uses, price);
            }
        }
        return new NotInCurrency(chosen);
    }

    /**
     * @param perUse one search's price as it stood then, at the scale it was given in
     * @param amount the searches counted times that price, exactly, at that same scale
     */
    record Amount(Currency currency, BigDecimal perUse, BigDecimal amount) implements SearchFee {

        public Amount {
            requireNonNull(currency, "SearchFee amount currency must not be null");
            requireNonNull(perUse, "SearchFee amount perUse must not be null");
            requireNonNull(amount, "SearchFee amount must not be null");
        }

        private static Amount of(SearchUses uses, ToolPrice price) {
            return new Amount(
                    price.currency(), price.perUse(), price.perUse().multiply(BigDecimal.valueOf(uses.uses())));
        }
    }

    /** @param chosen the group's currency, in which searching had no price */
    record NotInCurrency(Currency chosen) implements SearchFee {

        public NotInCurrency {
            requireNonNull(chosen, "SearchFee chosen must not be null");
        }
    }

    /** Searching was priced in more than one currency, and the group has chosen none. */
    record ManyCurrencies() implements SearchFee {}

    /** Searching had no price at all, whatever the group has chosen. */
    record NoPrice() implements SearchFee {}
}
