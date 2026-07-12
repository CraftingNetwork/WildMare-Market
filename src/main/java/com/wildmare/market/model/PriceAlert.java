package com.wildmare.market.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable persisted player price alert. */
public record PriceAlert(
        UUID alertId,
        UUID playerId,
        String symbol,
        AlertCondition condition,
        BigDecimal target,
        boolean active,
        boolean triggered,
        Instant lastTriggeredAt,
        Instant createdAt,
        Instant updatedAt
) {
}
