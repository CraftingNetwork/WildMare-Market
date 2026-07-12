package com.wildmare.market.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable persisted player holding and average purchase price. */
public record Holding(
        UUID playerId,
        String symbol,
        BigDecimal quantity,
        BigDecimal averagePrice,
        Instant updatedAt
) {
    public BigDecimal costBasis() {
        return quantity.multiply(averagePrice);
    }
}
