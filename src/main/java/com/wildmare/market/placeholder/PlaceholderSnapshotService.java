package com.wildmare.market.placeholder;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.model.PortfolioSummary;
import com.wildmare.market.portfolio.PortfolioService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Maintains non-blocking player data used by PlaceholderAPI requests. */
public final class PlaceholderSnapshotService {
    private final JavaPlugin plugin;
    private final PortfolioService portfolioService;
    private final DatabaseService databaseService;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    public PlaceholderSnapshotService(JavaPlugin plugin, PortfolioService portfolioService,
                                      DatabaseService databaseService) {
        this.plugin = plugin;
        this.portfolioService = portfolioService;
        this.databaseService = databaseService;
    }

    public CompletableFuture<Void> refresh(Player player) {
        CompletableFuture<PortfolioSummary> summary = portfolioService.summary(player.getUniqueId());
        CompletableFuture<Map<String, BigDecimal>> statistics =
                databaseService.getStatistics(player.getUniqueId());
        return summary.thenAcceptBoth(statistics, (portfolio, stats) ->
                snapshots.put(player.getUniqueId(), new Snapshot(portfolio, Map.copyOf(stats))))
                .exceptionally(throwable -> {
            plugin.getLogger().log(Level.FINE, "Unable to refresh placeholder snapshot for "
                    + player.getUniqueId(), throwable);
            return null;
        });
    }

    public Snapshot get(UUID playerId) {
        return snapshots.getOrDefault(playerId, Snapshot.empty());
    }

    public void invalidate(UUID playerId) {
        snapshots.remove(playerId);
    }

    public void clear() {
        snapshots.clear();
    }

    public record Snapshot(PortfolioSummary portfolio, Map<String, BigDecimal> statistics) {
        public static Snapshot empty() {
            return new Snapshot(new PortfolioSummary(
                    java.util.List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, "N/A", "N/A"), Map.of());
        }

        public BigDecimal stat(String key) {
            return statistics.getOrDefault(key, BigDecimal.ZERO);
        }
    }
}
