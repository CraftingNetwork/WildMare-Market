package com.wildmare.market.model;

import java.math.BigDecimal;

/** Immutable calculated position including value, allocation, and profit. */
public record PortfolioPosition(
        Holding holding,
        MarketQuote quote,
        BigDecimal positionValue,
        BigDecimal costBasis,
        BigDecimal profit,
        BigDecimal profitPercent
) {
}
