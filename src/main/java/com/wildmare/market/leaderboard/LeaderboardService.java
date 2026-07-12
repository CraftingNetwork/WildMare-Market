package com.wildmare.market.leaderboard;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.model.Holding;
import com.wildmare.market.model.LeaderboardEntry;
import com.wildmare.market.model.LeaderboardMetric;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.util.DecimalUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Builds cached portfolio and activity leaderboards from persisted data. */
public final class LeaderboardService {
    private final JavaPlugin plugin;
    private final DatabaseService databaseService;
    private final MarketDataService marketDataService;
    private final AtomicReference<Map<LeaderboardMetric, List<LeaderboardEntry>>> cache =
            new AtomicReference<>(Map.of());

    public LeaderboardService(JavaPlugin plugin, DatabaseService databaseService,
                              MarketDataService marketDataService) {
        this.plugin = plugin;
        this.databaseService = databaseService;
        this.marketDataService = marketDataService;
    }

    public CompletableFuture<Void> refresh() {
        int maximumEntries = plugin.getConfig().getInt("leaderboards.maximum-entries", 100);
        int candidateLimit = Math.max(maximumEntries * 10, 500);
        CompletableFuture<List<DatabaseService.PlayerHoldingRow>> holdingsFuture =
                databaseService.loadLeaderboardHoldings(candidateLimit);
        CompletableFuture<Map<UUID, DatabaseService.PlayerMetrics>> metricsFuture =
                databaseService.loadPlayerMetrics();
        CompletableFuture<Map<UUID, BigDecimal>> dailyFuture =
                databaseService.loadSnapshotsAtOrBefore(Instant.now().minus(Duration.ofDays(1)));
        CompletableFuture<Map<UUID, BigDecimal>> weeklyFuture =
                databaseService.loadSnapshotsAtOrBefore(Instant.now().minus(Duration.ofDays(7)));
        CompletableFuture<Map<UUID, BigDecimal>> monthlyFuture =
                databaseService.loadSnapshotsAtOrBefore(Instant.now().minus(Duration.ofDays(30)));

        return holdingsFuture.thenCompose(rows -> {
            Set<String> symbols = new HashSet<>();
            rows.forEach(row -> row.holdings().forEach(holding -> symbols.add(holding.symbol())));
            Map<String, CompletableFuture<MarketQuote>> quotes = new LinkedHashMap<>();
            for (String symbol : symbols) {
                quotes.put(symbol, marketDataService.quote(symbol, false)
                        .exceptionally(throwable -> null));
            }
            return CompletableFuture.allOf(quotes.values().toArray(CompletableFuture[]::new))
                    .thenCompose(ignored -> CompletableFuture.allOf(
                            metricsFuture, dailyFuture, weeklyFuture, monthlyFuture))
                    .thenCompose(ignored -> {
                        Map<String, MarketQuote> resolvedQuotes = new HashMap<>();
                        quotes.forEach((symbol, future) -> resolvedQuotes.put(symbol, future.join()));
                        Map<UUID, BigDecimal> currentValues = new LinkedHashMap<>();
                        Map<UUID, BigDecimal> costBases = new LinkedHashMap<>();
                        Map<UUID, String> names = new LinkedHashMap<>();
                        for (DatabaseService.PlayerHoldingRow row : rows) {
                            BigDecimal value = BigDecimal.ZERO;
                            BigDecimal cost = BigDecimal.ZERO;
                            for (Holding holding : row.holdings()) {
                                MarketQuote quote = resolvedQuotes.get(holding.symbol());
                                if (quote != null) {
                                    value = value.add(holding.quantity().multiply(quote.price()));
                                }
                                cost = cost.add(holding.costBasis());
                            }
                            currentValues.put(row.playerId(), value);
                            costBases.put(row.playerId(), cost);
                            names.put(row.playerId(), row.playerName());
                        }

                        Set<UUID> excluded = excludedPlayers();
                        Map<LeaderboardMetric, Map<UUID, BigDecimal>> raw =
                                new EnumMap<>(LeaderboardMetric.class);
                        for (LeaderboardMetric metric : LeaderboardMetric.values()) {
                            raw.put(metric, new LinkedHashMap<>());
                        }
                        Map<UUID, DatabaseService.PlayerMetrics> metrics = metricsFuture.join();
                        for (Map.Entry<UUID, BigDecimal> entry : currentValues.entrySet()) {
                            UUID playerId = entry.getKey();
                            if (excluded.contains(playerId)) continue;
                            BigDecimal current = entry.getValue();
                            BigDecimal cost = costBases.getOrDefault(playerId, BigDecimal.ZERO);
                            DatabaseService.PlayerMetrics playerMetrics = metrics.get(playerId);
                            raw.get(LeaderboardMetric.PORTFOLIO_VALUE).put(playerId, current);
                            raw.get(LeaderboardMetric.PERCENT_RETURN).put(
                                    playerId, DecimalUtil.percentage(current.subtract(cost), cost));
                            raw.get(LeaderboardMetric.REALIZED_PROFIT).put(
                                    playerId, playerMetrics == null
                                            ? BigDecimal.ZERO : playerMetrics.realizedProfit());
                            raw.get(LeaderboardMetric.MOST_ACTIVE).put(
                                    playerId, BigDecimal.valueOf(playerMetrics == null
                                            ? 0 : playerMetrics.totalTrades()));
                            raw.get(LeaderboardMetric.DAILY_PERFORMANCE).put(
                                    playerId, historicalReturn(current, dailyFuture.join().get(playerId)));
                            raw.get(LeaderboardMetric.WEEKLY_PERFORMANCE).put(
                                    playerId, historicalReturn(current, weeklyFuture.join().get(playerId)));
                            raw.get(LeaderboardMetric.MONTHLY_PERFORMANCE).put(
                                    playerId, historicalReturn(current, monthlyFuture.join().get(playerId)));
                        }

                        Map<LeaderboardMetric, List<LeaderboardEntry>> built =
                                new EnumMap<>(LeaderboardMetric.class);
                        for (Map.Entry<LeaderboardMetric, Map<UUID, BigDecimal>> metricEntry : raw.entrySet()) {
                            List<Map.Entry<UUID, BigDecimal>> sorted = metricEntry.getValue().entrySet()
                                    .stream()
                                    .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                                    .limit(maximumEntries)
                                    .toList();
                            List<LeaderboardEntry> entries = new ArrayList<>(sorted.size());
                            for (int index = 0; index < sorted.size(); index++) {
                                Map.Entry<UUID, BigDecimal> value = sorted.get(index);
                                String name = names.getOrDefault(value.getKey(),
                                        metrics.containsKey(value.getKey())
                                                ? metrics.get(value.getKey()).playerName() : "Unknown");
                                entries.add(new LeaderboardEntry(
                                        value.getKey(), name, value.getValue(), index + 1));
                            }
                            built.put(metricEntry.getKey(), List.copyOf(entries));
                        }
                        cache.set(Map.copyOf(built));
                        return databaseService.savePortfolioSnapshots(currentValues);
                    });
        });
    }

    public List<LeaderboardEntry> entries(LeaderboardMetric metric, int limit) {
        return cache.get().getOrDefault(metric, List.of()).stream()
                .limit(Math.max(1, limit)).toList();
    }

    public int rank(UUID playerId, LeaderboardMetric metric) {
        return cache.get().getOrDefault(metric, List.of()).stream()
                .filter(entry -> entry.playerId().equals(playerId))
                .findFirst().map(LeaderboardEntry::rank).orElse(0);
    }

    public BigDecimal value(UUID playerId, LeaderboardMetric metric) {
        return cache.get().getOrDefault(metric, List.of()).stream()
                .filter(entry -> entry.playerId().equals(playerId))
                .findFirst().map(LeaderboardEntry::value).orElse(BigDecimal.ZERO);
    }

    private Set<UUID> excludedPlayers() {
        Set<UUID> excluded = new HashSet<>();
        for (String raw : plugin.getConfig().getStringList("leaderboards.excluded-uuids")) {
            try {
                excluded.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return excluded;
    }

    private static BigDecimal historicalReturn(BigDecimal current, BigDecimal historical) {
        if (historical == null || historical.signum() <= 0) return BigDecimal.ZERO;
        return DecimalUtil.percentage(current.subtract(historical), historical);
    }
}
