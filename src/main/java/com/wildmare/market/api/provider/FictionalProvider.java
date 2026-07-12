package com.wildmare.market.api.provider;

import com.wildmare.market.api.MarketDataProvider;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.HistoryPeriod;
import com.wildmare.market.model.HistoryPoint;
import com.wildmare.market.model.MarketHistory;
import com.wildmare.market.model.MarketQuote;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Local virtual-price provider for server-created fictional assets. */
public final class FictionalProvider implements MarketDataProvider {
    private final boolean enabled;
    private final BigDecimal volatilityPercent;
    private final Map<String, BigDecimal> prices = new ConcurrentHashMap<>();

    public FictionalProvider(ConfigurationSection section) {
        this.enabled = section.getBoolean("enabled", true);
        this.volatilityPercent = BigDecimal.valueOf(section.getDouble("volatility-percent", 2.5));
    }

    @Override
    public String id() {
        return "fictional";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public CompletableFuture<MarketQuote> fetchQuote(AssetDefinition asset) {
        if (!enabled) return CompletableFuture.failedFuture(new IllegalStateException("Fictional provider is disabled"));
        BigDecimal previous = prices.computeIfAbsent(asset.symbol(), key ->
                asset.initialPrice().signum() > 0 ? asset.initialPrice() : BigDecimal.valueOf(100));
        double movement = ThreadLocalRandom.current().nextDouble(
                volatilityPercent.negate().doubleValue(), volatilityPercent.doubleValue());
        BigDecimal changePercent = BigDecimal.valueOf(movement);
        BigDecimal change = previous.multiply(changePercent)
                .divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        BigDecimal price = previous.add(change).max(BigDecimal.valueOf(0.01));
        prices.put(asset.symbol(), price);
        MarketQuote quote = new MarketQuote(
                asset.symbol(), id(), price, change, changePercent, previous,
                price.max(previous), price.min(previous), previous,
                BigDecimal.valueOf(ThreadLocalRandom.current().nextLong(1000, 100000)),
                Instant.now(), Instant.now()
        );
        return CompletableFuture.completedFuture(quote);
    }

    @Override
    public CompletableFuture<MarketHistory> fetchHistory(AssetDefinition asset, HistoryPeriod period) {
        BigDecimal current = prices.computeIfAbsent(asset.symbol(), key ->
                asset.initialPrice().signum() > 0 ? asset.initialPrice() : BigDecimal.valueOf(100));
        int count = switch (period) {
            case ONE_HOUR -> 12;
            case ONE_DAY -> 24;
            case SEVEN_DAYS -> 56;
            case THIRTY_DAYS -> 60;
            case NINETY_DAYS -> 90;
            case ONE_YEAR -> 120;
        };
        List<HistoryPoint> points = new ArrayList<>(count);
        BigDecimal price = current;
        Instant start = Instant.now().minus(period.duration());
        long stepSeconds = Math.max(1, period.duration().getSeconds() / Math.max(1, count - 1));
        for (int index = 0; index < count; index++) {
            double move = ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
            price = price.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(move / 100.0)))
                    .max(BigDecimal.valueOf(0.01));
            points.add(new HistoryPoint(start.plusSeconds(stepSeconds * index), price));
        }
        points.set(points.size() - 1, new HistoryPoint(Instant.now(), current));
        return CompletableFuture.completedFuture(new MarketHistory(asset.symbol(), id(), period, points, Instant.now()));
    }
}
