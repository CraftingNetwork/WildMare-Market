package com.wildmare.market.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Immutable result returned after a trade settlement attempt. */
public record TradeResult(
        boolean success,
        String messageKey,
        UUID transactionId,
        String symbol,
        BigDecimal quantity,
        BigDecimal total,
        BigDecimal realizedProfit
) {
    public static TradeResult failed(String key, String symbol, BigDecimal quantity) {
        return new TradeResult(false, key, null, symbol, quantity, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
