package com.wildmare.market.model;

import java.math.BigDecimal;
import java.util.List;

/** Immutable aggregate portfolio view used by menus and placeholders. */
public record PortfolioSummary(
        List<Holding> holdings,
        BigDecimal holdingsValue,
        BigDecimal totalInvested,
        BigDecimal unrealizedProfit,
        BigDecimal realizedProfit,
        BigDecimal dailyChange,
        String bestAsset,
        String worstAsset
) {
    public PortfolioSummary {
        holdings = List.copyOf(holdings);
    }

    public BigDecimal totalValue() {
        return holdingsValue;
    }
}
