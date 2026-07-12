package com.wildmare.market.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable, uniquely identified trade confirmation request. */
public record TradeRequest(
        UUID requestId,
        UUID playerId,
        String playerName,
        String symbol,
        TradeSide side,
        BigDecimal quantity,
        MarketQuote quote,
        Instant createdAt
) {
}
