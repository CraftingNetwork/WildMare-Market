package com.wildmare.market.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable timestamped historical price point. */
public record HistoryPoint(Instant timestamp, BigDecimal price) {
}
