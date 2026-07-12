package com.wildmare.market.alert;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.model.AlertCondition;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.model.PriceAlert;
import com.wildmare.market.notification.NotificationService;
import com.wildmare.market.util.PermissionLimit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** Creates, manages, and evaluates player price alerts. */
public final class PriceAlertService {
    private final JavaPlugin plugin;
    private final DatabaseService databaseService;
    private final AssetRegistry assetRegistry;
    private final MarketDataService marketDataService;
    private final NotificationService notificationService;

    public PriceAlertService(JavaPlugin plugin, DatabaseService databaseService,
                             AssetRegistry assetRegistry, MarketDataService marketDataService,
                             NotificationService notificationService) {
        this.plugin = plugin;
        this.databaseService = databaseService;
        this.assetRegistry = assetRegistry;
        this.marketDataService = marketDataService;
        this.notificationService = notificationService;
    }

    public CompletableFuture<CreateResult> create(Player player, String symbol,
                                                   AlertCondition condition, BigDecimal target) {
        String normalized = symbol.toUpperCase();
        if (assetRegistry.getEnabled(normalized).isEmpty()) {
            return CompletableFuture.completedFuture(CreateResult.UNKNOWN_ASSET);
        }
        if (target == null || target.signum() <= 0) {
            return CompletableFuture.completedFuture(CreateResult.INVALID_TARGET);
        }
        int defaultLimit = plugin.getConfig().getInt("alerts.default-limit", 10);
        int maximumLimit = plugin.getConfig().getInt("alerts.maximum-limit", 100);
        int limit = PermissionLimit.resolve(player, "wildmaremarket.limit.alerts.",
                defaultLimit, maximumLimit);
        return databaseService.getAlerts(player.getUniqueId()).thenCompose(alerts -> {
            if (alerts.size() >= limit) {
                return CompletableFuture.completedFuture(CreateResult.FULL);
            }
            return databaseService.createAlert(player.getUniqueId(), normalized, condition, target)
                    .thenApply(alert -> CreateResult.CREATED);
        });
    }

    public CompletableFuture<List<PriceAlert>> list(UUID playerId) {
        return databaseService.getAlerts(playerId);
    }

    public CompletableFuture<Boolean> toggle(UUID playerId, UUID alertId, boolean active) {
        return databaseService.setAlertActive(playerId, alertId, active);
    }

    public CompletableFuture<Boolean> edit(UUID playerId, UUID alertId, BigDecimal target) {
        if (target == null || target.signum() <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return databaseService.updateAlertTarget(playerId, alertId, target);
    }

    public CompletableFuture<Boolean> delete(UUID playerId, UUID alertId) {
        return databaseService.deleteAlert(playerId, alertId);
    }

    public CompletableFuture<Integer> evaluateAll() {
        return databaseService.getActiveAlerts().thenCompose(alerts -> {
            Map<String, List<PriceAlert>> bySymbol = alerts.stream()
                    .collect(Collectors.groupingBy(PriceAlert::symbol));
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (Map.Entry<String, List<PriceAlert>> entry : bySymbol.entrySet()) {
                CompletableFuture<Integer> future = marketDataService.quote(entry.getKey(), false)
                        .thenCompose(quote -> evaluateSymbol(entry.getValue(), quote))
                        .exceptionally(throwable -> 0);
                futures.add(future);
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> futures.stream().mapToInt(CompletableFuture::join).sum());
        });
    }

    private CompletableFuture<Integer> evaluateSymbol(List<PriceAlert> alerts, MarketQuote quote) {
        boolean repeat = plugin.getConfig().getBoolean("alerts.repeat-triggered-alerts", false);
        List<CompletableFuture<Void>> updates = new ArrayList<>();
        int[] triggeredCount = {0};
        for (PriceAlert alert : alerts) {
            if (alert.triggered() && !repeat) continue;
            if (!alert.condition().matches(quote, alert.target())) continue;
            triggeredCount[0]++;
            Player player = Bukkit.getPlayer(alert.playerId());
            if (player != null && player.isOnline()) {
                notificationService.alert(player, alert, quote);
            }
            updates.add(databaseService.markAlertTriggered(alert.alertId(), repeat));
        }
        return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> triggeredCount[0]);
    }

    public enum CreateResult {
        CREATED, FULL, UNKNOWN_ASSET, INVALID_TARGET
    }
}
