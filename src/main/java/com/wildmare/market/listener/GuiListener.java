package com.wildmare.market.listener;

import com.wildmare.market.alert.PriceAlertService;
import com.wildmare.market.config.Messages;
import com.wildmare.market.gui.GuiManager;
import com.wildmare.market.gui.ItemFactory;
import com.wildmare.market.gui.MarketMenuHolder;
import com.wildmare.market.leaderboard.LeaderboardService;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.model.HistoryPeriod;
import com.wildmare.market.model.LeaderboardMetric;
import com.wildmare.market.model.TradeRequest;
import com.wildmare.market.model.TradeResult;
import com.wildmare.market.model.TradeSide;
import com.wildmare.market.notification.SoundService;
import com.wildmare.market.placeholder.PlaceholderSnapshotService;
import com.wildmare.market.service.PlayerSettingsService;
import com.wildmare.market.util.PermissionLimit;
import com.wildmare.market.util.TextUtil;
import com.wildmare.market.watchlist.WatchlistService;
import com.wildmare.market.watchlist.WatchlistSort;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Protects and routes all WildMare inventory interactions. */
public final class GuiListener implements Listener {
    private final JavaPlugin plugin;
    private final GuiManager gui;
    private final Messages messages;
    private final ItemFactory items;
    private final WatchlistService watchlistService;
    private final PriceAlertService alertService;
    private final PlayerSettingsService settingsService;
    private final MarketDataService marketDataService;
    private final SoundService soundService;
    private final PlaceholderSnapshotService placeholderSnapshots;
    private final Set<UUID> confirmedRequests = ConcurrentHashMap.newKeySet();

    public GuiListener(JavaPlugin plugin, GuiManager gui, Messages messages,
                       WatchlistService watchlistService, PriceAlertService alertService,
                       PlayerSettingsService settingsService, MarketDataService marketDataService,
                       SoundService soundService,
                       PlaceholderSnapshotService placeholderSnapshots) {
        this.plugin = plugin;
        this.gui = gui;
        this.messages = messages;
        this.items = gui.itemFactory();
        this.watchlistService = watchlistService;
        this.alertService = alertService;
        this.settingsService = settingsService;
        this.marketDataService = marketDataService;
        this.soundService = soundService;
        this.placeholderSnapshots = placeholderSnapshots;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MarketMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.playerId().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack item = event.getCurrentItem();
        String action = items.action(item);
        if (action == null || action.isBlank()) return;
        String symbol = items.symbol(item);
        String value = items.value(item);
        String secondary = items.secondary(item);

        try {
            route(player, holder, item, action, symbol, value, secondary,
                    event.isShiftClick(), event.isRightClick());
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to process market inventory click", exception);
            messages.send(player, "general.internal-error");
            soundService.play(player, "failure");
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MarketMenuHolder holder)
                || holder.type() != com.wildmare.market.gui.MenuType.CONFIRMATION) {
            return;
        }
        TradeRequest request = holder.tradeRequest();
        if (request == null || confirmedRequests.remove(request.requestId())) return;
        gui.tradingService().cancel(request);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MarketMenuHolder) {
            int topSize = event.getView().getTopInventory().getSize();
            if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
        }
    }

    private void route(Player player, MarketMenuHolder holder, ItemStack item, String action,
                       String symbol, String value, String secondary,
                       boolean shift, boolean right) {
        if (action.startsWith("main:")) {
            routeMain(player, action.substring(5));
            return;
        }
        switch (action) {
            case "back-main" -> gui.openMain(player);
            case "asset-open" -> {
                if (shift) toggleWatchlist(player, symbol, false);
                else gui.openDetail(player, symbol);
            }
            case "open-detail" -> {
                if (holder.type() == com.wildmare.market.gui.MenuType.CONFIRMATION) {
                    messages.send(player, "general.cancelled");
                }
                gui.openDetail(player, symbol);
            }
            case "trade-side" -> {
                TradeSide side = TradeSide.valueOf(value);
                if (allowedTrade(player, side)) gui.openQuantity(player, symbol, side);
            }
            case "quantity-choice" -> {
                TradeSide side = TradeSide.valueOf(secondary);
                if (allowedTrade(player, side)) prepare(player, symbol, side, value);
            }
            case "confirm-trade" -> confirm(player, holder, item);
            case "custom-command" -> {
                player.closeInventory();
                messages.send(player, "market.custom-amount-instruction", Map.of(
                        "side", holder.side().name().toLowerCase(),
                        "symbol", holder.context()));
            }
            case "watchlist-toggle" -> {
                if (allowed(player, "wildmaremarket.watchlist")) toggleWatchlist(player, symbol, true);
            }
            case "chart" -> {
                if (allowed(player, "wildmaremarket.history")) {
                    gui.showChart(player, symbol, HistoryPeriod.parse(value));
                }
            }
            case "alert-instruction" -> {
                if (!allowed(player, "wildmaremarket.alerts")) return;
                player.closeInventory();
                messages.send(player, "alerts.create-instruction", Map.of("symbol", symbol));
            }
            case "refresh-detail" -> refreshDetail(player, symbol);
            case "browser-page" -> {
                soundService.play(player, "page-change");
                gui.openBrowser(player, secondary, integer(value));
            }
            case "watchlist-sort" -> {
                if (allowed(player, "wildmaremarket.watchlist")) {
                    gui.openWatchlist(player, WatchlistSort.valueOf(value));
                }
            }
            case "alert-manage" -> {
                if (allowed(player, "wildmaremarket.alerts")) {
                    manageAlert(player, value, secondary, shift, right);
                }
            }
            case "leaderboard-metric" -> {
                if (allowed(player, "wildmaremarket.leaderboard")) {
                    gui.openLeaderboard(player, LeaderboardMetric.valueOf(value));
                }
            }
            case "setting-toggle" -> toggleSetting(player, value);
            default -> plugin.getLogger().fine("Ignored unknown market GUI action: " + action);
        }
    }

    private void routeMain(Player player, String key) {
        switch (key) {
            case "overview" -> gui.openBrowser(player, "ALL", 0);
            case "stocks" -> gui.openBrowser(player, "STOCKS", 0);
            case "etfs" -> gui.openBrowser(player, "ETFS", 0);
            case "indexes" -> gui.openBrowser(player, "INDEXES", 0);
            case "crypto" -> gui.openBrowser(player, "CRYPTO", 0);
            case "trending" -> gui.openBrowser(player, "TRENDING", 0);
            case "recent" -> gui.openBrowser(player, "RECENT", 0);
            case "gainers" -> gui.openBrowser(player, "GAINERS", 0);
            case "losers" -> gui.openBrowser(player, "LOSERS", 0);
            case "portfolio" -> {
                if (allowed(player, "wildmaremarket.portfolio")) gui.openPortfolio(player);
            }
            case "watchlist" -> {
                if (allowed(player, "wildmaremarket.watchlist")) {
                    gui.openWatchlist(player, WatchlistSort.NAME);
                }
            }
            case "alerts" -> {
                if (allowed(player, "wildmaremarket.alerts")) gui.openAlerts(player);
            }
            case "history" -> {
                if (allowed(player, "wildmaremarket.history")) gui.openHistory(player);
            }
            case "leaderboard" -> {
                if (allowed(player, "wildmaremarket.leaderboard")) {
                    gui.openLeaderboard(player, LeaderboardMetric.PORTFOLIO_VALUE);
                }
            }
            case "settings" -> gui.openSettings(player);
            case "search" -> {
                player.closeInventory();
                messages.send(player, "market.search-instruction");
            }
            case "status" -> messages.send(player, "market.cache-status",
                    Map.of("quotes", marketDataService.quoteCacheSize(),
                            "history", marketDataService.historyCacheSize()));
            case "help" -> {
                player.closeInventory();
                messages.sendList(player, "help.player");
            }
            default -> gui.openMain(player);
        }
    }

    private void prepare(Player player, String symbol, TradeSide side, String rawQuantity) {
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(rawQuantity);
        } catch (NumberFormatException exception) {
            messages.send(player, "general.invalid-number");
            return;
        }
        gui.prepareTrade(player, symbol, side, quantity).whenComplete((request, throwable) -> sync(() -> {
            if (throwable != null) {
                messages.send(player, "market.unavailable");
                soundService.play(player, "failure");
            } else {
                gui.openConfirmation(player, request);
            }
        }));
    }

    private void confirm(Player player, MarketMenuHolder holder, ItemStack clicked) {
        TradeRequest request = holder.tradeRequest();
        if (request == null) {
            messages.send(player, "trade.confirmation-expired");
            return;
        }
        clicked.setType(Material.GRAY_DYE);
        items.withAction(clicked, "processing");
        confirmedRequests.add(request.requestId());
        player.closeInventory();
        gui.tradingService().execute(request).whenComplete((result, throwable) -> sync(() -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.WARNING, "Trade execution failed", throwable);
                messages.send(player, "trade.failed");
                soundService.play(player, "failure");
                return;
            }
            sendTradeResult(player, result);
            placeholderSnapshots.refresh(player);
        }));
    }

    private void sendTradeResult(Player player, TradeResult result) {
        if (!result.success()) {
            Map<String, Object> values = Map.of(
                    "symbol", result.symbol() == null ? "N/A" : result.symbol(),
                    "minimum", plugin.getConfig().getDouble("trading.minimum-transaction-value", 1.0),
                    "maximum", plugin.getConfig().getDouble("trading.maximum-transaction-value", 1_000_000.0));
            messages.send(player, result.messageKey(), values);
            soundService.play(player, "failure");
            return;
        }
        int decimals = plugin.getConfig().getInt("currency-decimals", 2);
        int quantityDecimals = plugin.getConfig().getInt("quantity-decimals", 8);
        var locale = TextUtil.parseLocale(plugin.getConfig().getString("locale", "en-US"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("symbol", result.symbol());
        values.put("quantity", TextUtil.decimal(result.quantity(), quantityDecimals));
        values.put("total", TextUtil.currency(result.total(),
                plugin.getConfig().getString("currency-symbol", "$"), decimals, locale));
        messages.send(player, "trade.success");
        messages.send(player, result.messageKey(), values);
        soundService.play(player, result.messageKey().equals("trade.purchase") ? "purchase" : "sale");
    }

    private void toggleWatchlist(Player player, String symbol, boolean reopenDetail) {
        watchlistService.toggle(player, symbol).whenComplete((result, throwable) -> sync(() -> {
            if (throwable != null) {
                messages.send(player, "general.internal-error");
                return;
            }
            Map<String, Object> values = Map.of(
                    "symbol", symbol,
                    "limit", PermissionLimit.resolve(player, "wildmaremarket.limit.watchlist.",
                            plugin.getConfig().getInt("watchlist.default-limit", 20),
                            plugin.getConfig().getInt("watchlist.maximum-limit", 100)));
            switch (result) {
                case ADDED -> messages.send(player, "watchlist.added", values);
                case REMOVED -> messages.send(player, "watchlist.removed", values);
                case ALREADY_EXISTS -> messages.send(player, "watchlist.already-added", values);
                case NOT_FOUND -> messages.send(player, "watchlist.not-found", values);
                case FULL -> messages.send(player, "watchlist.full", values);
                case UNKNOWN_ASSET -> messages.send(player, "market.unknown-asset", values);
            }
            if (reopenDetail) gui.openDetail(player, symbol);
        }));
    }

    private void refreshDetail(Player player, String symbol) {
        messages.send(player, "market.refreshing");
        marketDataService.quote(symbol, true).whenComplete((quote, throwable) -> sync(() -> {
            if (throwable != null) messages.send(player, "market.refresh-failed");
            else {
                messages.send(player, "market.refreshed");
                gui.openDetail(player, symbol);
            }
        }));
    }

    private void manageAlert(Player player, String rawId, String rawActive,
                             boolean shift, boolean right) {
        UUID alertId;
        try {
            alertId = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            messages.send(player, "general.internal-error");
            return;
        }
        if (shift && right) {
            alertService.delete(player.getUniqueId(), alertId).whenComplete((deleted, throwable) -> sync(() -> {
                if (throwable != null || !deleted) messages.send(player, "general.internal-error");
                else messages.send(player, "alerts.deleted");
                gui.openAlerts(player);
            }));
            return;
        }
        boolean active = Boolean.parseBoolean(rawActive);
        alertService.toggle(player.getUniqueId(), alertId, !active)
                .whenComplete((updated, throwable) -> sync(() -> {
                    if (throwable != null || !updated) messages.send(player, "general.internal-error");
                    else messages.send(player, "alerts.paused");
                    gui.openAlerts(player);
                }));
    }

    private void toggleSetting(Player player, String key) {
        settingsService.toggle(player.getUniqueId(), key).whenComplete((settings, throwable) -> sync(() -> {
            if (throwable != null) messages.send(player, "general.internal-error");
            else {
                messages.send(player, "settings.updated");
                gui.openSettings(player);
            }
        }));
    }


    private boolean allowedTrade(Player player, TradeSide side) {
        String permission = side == TradeSide.BUY
                ? "wildmaremarket.buy" : "wildmaremarket.sell";
        return allowed(player, "wildmaremarket.trade") && allowed(player, permission);
    }

    private boolean allowed(Player player, String permission) {
        if (player.hasPermission(permission)) return true;
        messages.send(player, "general.no-permission");
        soundService.play(player, "failure");
        return false;
    }

    private void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    private static int integer(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
