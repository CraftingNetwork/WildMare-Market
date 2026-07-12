package com.wildmare.market.task;

import com.wildmare.market.alert.PriceAlertService;
import com.wildmare.market.config.Messages;
import com.wildmare.market.leaderboard.LeaderboardService;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.placeholder.PlaceholderSnapshotService;
import com.wildmare.market.service.PlayerSettingsService;
import com.wildmare.market.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** Owns all repeating refresh, alert, leaderboard, and placeholder tasks. */
public final class ScheduledTasks implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Messages messages;
    private final AssetRegistry assetRegistry;
    private final MarketDataService marketDataService;
    private final PriceAlertService alertService;
    private final LeaderboardService leaderboardService;
    private final PlaceholderSnapshotService placeholderSnapshots;
    private final PlayerSettingsService settingsService;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final AtomicInteger assetCursor = new AtomicInteger();
    private final Map<String, Instant> movementCooldowns = new ConcurrentHashMap<>();

    public ScheduledTasks(JavaPlugin plugin, Messages messages, AssetRegistry assetRegistry,
                          MarketDataService marketDataService, PriceAlertService alertService,
                          LeaderboardService leaderboardService,
                          PlaceholderSnapshotService placeholderSnapshots,
                          PlayerSettingsService settingsService) {
        this.plugin = plugin;
        this.messages = messages;
        this.assetRegistry = assetRegistry;
        this.marketDataService = marketDataService;
        this.alertService = alertService;
        this.leaderboardService = leaderboardService;
        this.placeholderSnapshots = placeholderSnapshots;
        this.settingsService = settingsService;
    }

    public void start() {
        close();
        if (plugin.getConfig().getBoolean("refresh.enabled", true)) {
            long interval = secondsToTicks(plugin.getConfig().getLong("refresh.interval-seconds", 30));
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBatch, 20L, interval));
        }
        long alertInterval = secondsToTicks(
                plugin.getConfig().getLong("alerts.evaluation-interval-seconds", 30));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () ->
                alertService.evaluateAll().exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Price alert evaluation failed", throwable);
                    return 0;
                }), 60L, alertInterval));

        long leaderboardInterval = secondsToTicks(
                plugin.getConfig().getLong("leaderboards.refresh-interval-seconds", 300));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () ->
                leaderboardService.refresh().exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Leaderboard refresh failed", throwable);
                    return null;
                }), 100L, leaderboardInterval));

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOnlinePlaceholders,
                80L, secondsToTicks(60L)));
    }

    private void refreshBatch() {
        List<AssetDefinition> assets = assetRegistry.enabled();
        if (assets.isEmpty()) return;
        int amount = Math.max(1, plugin.getConfig().getInt("refresh.assets-per-cycle", 25));
        int start = Math.floorMod(assetCursor.getAndAdd(amount), assets.size());
        List<AssetDefinition> batch = new ArrayList<>(Math.min(amount, assets.size()));
        for (int index = 0; index < Math.min(amount, assets.size()); index++) {
            batch.add(assets.get((start + index) % assets.size()));
        }
        marketDataService.refresh(batch).whenComplete((result, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.FINE, "Scheduled market refresh failed", throwable);
                return;
            }
            for (AssetDefinition asset : batch) {
                marketDataService.cachedQuote(asset.symbol()).ifPresent(this::notifyMovement);
            }
        });
    }

    private void notifyMovement(MarketQuote quote) {
        BigDecimal threshold = BigDecimal.valueOf(
                plugin.getConfig().getDouble("refresh.movement-notification-percent", 8.0)).abs();
        if (quote.changePercent().abs().compareTo(threshold) < 0) return;
        Instant last = movementCooldowns.get(quote.symbol());
        if (last != null && Duration.between(last, Instant.now()).toMinutes() < 15) return;
        movementCooldowns.put(quote.symbol(), Instant.now());

        int decimals = plugin.getConfig().getInt("currency-decimals", 2);
        var locale = TextUtil.parseLocale(plugin.getConfig().getString("locale", "en-US"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("symbol", quote.symbol());
        values.put("change_percent", signed(quote.changePercent()));
        values.put("change_color", TextUtil.colorName(quote.changePercent()));
        values.put("price", TextUtil.currency(quote.price(),
                plugin.getConfig().getString("currency-symbol", "$"), decimals, locale));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("wildmaremarket.use")) continue;
            settingsService.get(player.getUniqueId()).thenAccept(settings -> {
                if (!settings.movementNotifications()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    messages.send(player, "movement.notification", values);
                    player.sendActionBar(messages.component("movement.notification", values));
                });
            });
        }
    }

    private void refreshOnlinePlaceholders() {
        for (Player player : Bukkit.getOnlinePlayers()) placeholderSnapshots.refresh(player);
    }

    private static long secondsToTicks(long seconds) {
        return Math.max(20L, seconds * 20L);
    }

    private static String signed(BigDecimal value) {
        String number = TextUtil.decimal(value, 2);
        return value.signum() > 0 ? "+" + number : number;
    }

    @Override
    public void close() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }
}
