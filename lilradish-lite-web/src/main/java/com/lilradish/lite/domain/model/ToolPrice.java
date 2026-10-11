package com.lilradish.lite.domain.model;

import java.math.BigDecimal;
import java.util.Currency;

public record ToolPrice(Currency currency, BigDecimal perUse) {

    public ToolPrice {
        if (currency == null) {
            throw new NullPointerException("ToolPrice currency must not be null");
        }
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "ToolPrice currency " + currency.getCurrencyCode() + " is not one anything is priced in");
        }
        if (perUse == null) {
            throw new NullPointerException("ToolPrice in " + currency.getCurrencyCode() + " has no per-use price");
        }
        if (perUse.signum() < 0) {
            throw new IllegalArgumentException("ToolPrice in " + currency.getCurrencyCode()
                    + " has a negative per-use price: " + perUse.toPlainString());
        }
    }
}
