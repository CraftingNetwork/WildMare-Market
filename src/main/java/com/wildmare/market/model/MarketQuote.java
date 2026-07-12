package com.wildmare.market.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Immutable latest-available market quote with provider and cache timestamps. */
public record MarketQuote(
        String symbol,
        String provider,
        BigDecimal price,
        BigDecimal change,
        BigDecimal changePercent,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal previousClose,
        BigDecimal volume,
        Instant sourceTimestamp,
        Instant cachedAt
) {
    public MarketQuote {
        symbol = Objects.requireNonNull(symbol, "symbol").toUpperCase();
        provider = Objects.requireNonNull(provider, "provider");
        price = safe(price);
        change = safe(change);
        changePercent = safe(changePercent);
        open = safe(open);
        high = safe(high);
        low = safe(low);
        previousClose = safe(previousClose);
        volume = safe(volume);
        sourceTimestamp = sourceTimestamp == null ? Instant.now() : sourceTimestamp;
        cachedAt = cachedAt == null ? Instant.now() : cachedAt;
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public boolean isTradable() {
        return price.signum() > 0;
    }

    public MarketQuote withCachedAt(Instant instant) {
        return new MarketQuote(symbol, provider, price, change, changePercent, open, high, low,
                previousClose, volume, sourceTimestamp, instant);
    }
}
