package com.wildmare.market.model;

import java.math.BigDecimal;
import java.util.Locale;

/** Supported price and percentage alert conditions. */
public enum AlertCondition {
    PRICE_ABOVE,
    PRICE_BELOW,
    PERCENT_INCREASE,
    PERCENT_DECREASE,
    DAILY_GAIN,
    DAILY_LOSS;

    public boolean matches(MarketQuote quote, BigDecimal target) {
        return switch (this) {
            case PRICE_ABOVE -> quote.price().compareTo(target) >= 0;
            case PRICE_BELOW -> quote.price().compareTo(target) <= 0;
            case PERCENT_INCREASE, DAILY_GAIN -> quote.changePercent().compareTo(target.abs()) >= 0;
            case PERCENT_DECREASE, DAILY_LOSS -> quote.changePercent().compareTo(target.abs().negate()) <= 0;
        };
    }

    public static AlertCondition parse(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "above", "price_above" -> PRICE_ABOVE;
            case "below", "price_below" -> PRICE_BELOW;
            case "percent_up", "increase", "percent_increase" -> PERCENT_INCREASE;
            case "percent_down", "decrease", "percent_decrease" -> PERCENT_DECREASE;
            case "daily_gain" -> DAILY_GAIN;
            case "daily_loss" -> DAILY_LOSS;
            default -> throw new IllegalArgumentException("Unknown alert condition: " + raw);
        };
    }
}
