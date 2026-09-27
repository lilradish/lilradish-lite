package org.lilradish.lite.domain.model;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * What a model costs in one currency, per million of the model's own units, whichever mode it runs
 * in. Scale is kept as given, so two prices compare by {@code compareTo} and never by {@code equals}.
 *
 * @param sentPerMillion the price of what is sent to it
 * @param cameBackPerMillion the price of what comes back from it
 */
public record ModelPrice(Currency currency, BigDecimal sentPerMillion, BigDecimal cameBackPerMillion) {

    public ModelPrice {
        if (currency == null) {
            throw new NullPointerException("ModelPrice currency must not be null");
        }
        // A code with no minor unit cannot carry a money figure.
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "ModelPrice currency " + currency.getCurrencyCode() + " is not one anything is priced in");
        }
        if (sentPerMillion == null) {
            throw new NullPointerException("ModelPrice in " + currency.getCurrencyCode() + " has no sent price");
        }
        if (sentPerMillion.signum() < 0) {
            throw new IllegalArgumentException("ModelPrice in " + currency.getCurrencyCode()
                    + " has a negative sent price: " + sentPerMillion.toPlainString());
        }
        if (cameBackPerMillion == null) {
            throw new NullPointerException("ModelPrice in " + currency.getCurrencyCode() + " has no came-back price");
        }
        if (cameBackPerMillion.signum() < 0) {
            throw new IllegalArgumentException("ModelPrice in " + currency.getCurrencyCode()
                    + " has a negative came-back price: " + cameBackPerMillion.toPlainString());
        }
    }
}
