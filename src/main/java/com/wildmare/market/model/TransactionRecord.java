package com.wildmare.market.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable persisted trade or cancellation record. */
public record TransactionRecord(
        UUID transactionId,
        UUID playerId,
        String symbol,
        TransactionType type,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal,
        BigDecimal fee,
        BigDecimal total,
        BigDecimal realizedProfit,
        TransactionStatus status,
        String failureReason,
        Instant priceTimestamp,
        Instant createdAt
) {
}
