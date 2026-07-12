package com.wildmare.market.model;

import java.time.Instant;
import java.util.List;

/** Immutable historical series returned by a market data provider. */
public record MarketHistory(
        String symbol,
        String provider,
        HistoryPeriod period,
        List<HistoryPoint> points,
        Instant cachedAt
) {
    public MarketHistory {
        points = List.copyOf(points);
        cachedAt = cachedAt == null ? Instant.now() : cachedAt;
    }

    public MarketHistory withCachedAt(Instant instant) {
        return new MarketHistory(symbol, provider, period, points, instant);
    }
}
