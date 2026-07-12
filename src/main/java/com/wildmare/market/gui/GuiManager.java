package com.wildmare.market.gui;

import com.wildmare.market.alert.PriceAlertService;
import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.config.Messages;
import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.economy.EconomyService;
import com.wildmare.market.leaderboard.LeaderboardService;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.market.MarketHoursService;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.AssetType;
import com.wildmare.market.model.HistoryPeriod;
import com.wildmare.market.model.Holding;
import com.wildmare.market.model.LeaderboardEntry;
import com.wildmare.market.model.LeaderboardMetric;
import com.wildmare.market.model.MarketHistory;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.model.PlayerSettings;
import com.wildmare.market.model.PortfolioPosition;
import com.wildmare.market.model.PortfolioSummary;
import com.wildmare.market.model.PriceAlert;
import com.wildmare.market.model.TradeRequest;
import com.wildmare.market.model.TradeSide;
import com.wildmare.market.model.TransactionRecord;
import com.wildmare.market.notification.SoundService;
import com.wildmare.market.portfolio.PortfolioService;
import com.wildmare.market.service.PlayerSettingsService;
import com.wildmare.market.transaction.TradingService;
import com.wildmare.market.transaction.TransactionService;
import com.wildmare.market.util.ChartUtil;
import com.wildmare.market.util.DecimalUtil;
import com.wildmare.market.util.TextUtil;
import com.wildmare.market.watchlist.WatchlistService;
import com.wildmare.market.watchlist.WatchlistSort;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Builds and updates all WildMare Market inventory interfaces. */
public final class GuiManager {
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Messages messages;
    private final ItemFactory itemFactory;
    private final AssetRegistry assetRegistry;
    private final MarketDataService marketDataService;
    private final MarketHoursService marketHoursService;
    private final DatabaseService databaseService;
    private final EconomyService economyService;
    private final TradingService tradingService;
    private final TransactionService transactionService;
    private final PortfolioService portfolioService;
    private final WatchlistService watchlistService;
    private final PriceAlertService alertService;
    private final LeaderboardService leaderboardService;
    private final PlayerSettingsService settingsService;
    private final SoundService soundService;

    public GuiManager(JavaPlugin plugin, ConfigManager configManager, Messages messages,
                      ItemFactory itemFactory, AssetRegistry assetRegistry,
                      MarketDataService marketDataService, MarketHoursService marketHoursService,
                      DatabaseService databaseService, EconomyService economyService,
                      TradingService tradingService, TransactionService transactionService,
                      PortfolioService portfolioService, WatchlistService watchlistService,
                      PriceAlertService alertService, LeaderboardService leaderboardService,
                      PlayerSettingsService settingsService, SoundService soundService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = messages;
        this.itemFactory = itemFactory;
        this.assetRegistry = assetRegistry;
        this.marketDataService = marketDataService;
        this.marketHoursService = marketHoursService;
        this.databaseService = databaseService;
        this.economyService = economyService;
        this.tradingService = tradingService;
        this.transactionService = transactionService;
        this.portfolioService = portfolioService;
        this.watchlistService = watchlistService;
        this.alertService = alertService;
        this.leaderboardService = leaderboardService;
        this.settingsService = settingsService;
        this.soundService = soundService;
    }

    public void openMain(Player player) {
        CompletableFuture<PortfolioSummary> summaryFuture = portfolioService.summary(player.getUniqueId());
        CompletableFuture<Double> balanceFuture = economyService.balance(player);
        summaryFuture.thenCombine(balanceFuture, MainData::new)
                .whenComplete((data, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "Unable to build main market menu", throwable);
                        data = new MainData(emptySummary(), 0.0);
                    }
                    MainData finalData = data;
                    sync(() -> {
                        MarketMenuHolder holder = MarketMenuHolder.basic(MenuType.MAIN, player.getUniqueId());
                        Inventory inventory = create(holder, "main.title", "main.size", Map.of());
                        fill(inventory);
                        Map<String, Object> placeholders = new LinkedHashMap<>();
                        placeholders.put("market_status", globalMarketStatus());
                        placeholders.put("asset_count", assetRegistry.enabled().size());
                        placeholders.put("cache_count", marketDataService.quoteCacheSize());
                        placeholders.put("portfolio_value", currency(finalData.summary().holdingsValue()));
                        placeholders.put("profit", currency(finalData.summary().unrealizedProfit()));
                        placeholders.put("profit_color", TextUtil.colorName(finalData.summary().unrealizedProfit()));
                        for (String key : List.of("overview", "stocks", "etfs", "indexes", "crypto",
                                "search", "trending", "recent", "gainers", "losers", "portfolio", "watchlist",
                                "alerts", "history", "leaderboard", "status", "settings", "help")) {
                            String path = "main.items." + key;
                            int slot = menus().getInt(path + ".slot", -1);
                            if (slot < 0) continue;
                            ItemStack item = itemFactory.fromConfig(path, placeholders);
                            inventory.setItem(slot, itemFactory.withAction(item, "main:" + key));
                        }
                        open(player, inventory);
                    });
                });
    }

    public void openBrowser(Player player, String context, int page) {
        List<AssetDefinition> assets = resolveAssets(context);
        int pages = Math.max(1, (int) Math.ceil(assets.size() / (double) CONTENT_SLOTS.length));
        int safePage = Math.max(0, Math.min(page, pages - 1));
        Map<String, Object> title = Map.of(
                "category", contextDisplay(context),
                "page", safePage + 1,
                "pages", pages
        );
        sync(() -> {
            MarketMenuHolder holder = new MarketMenuHolder(
                    MenuType.BROWSER, player.getUniqueId(), context, safePage, null, null, null);
            Inventory inventory = create(holder, "browser.title", "browser.size", title);
            fill(inventory);
            int from = safePage * CONTENT_SLOTS.length;
            int to = Math.min(assets.size(), from + CONTENT_SLOTS.length);
            for (int index = from; index < to; index++) {
                AssetDefinition asset = assets.get(index);
                int slot = CONTENT_SLOTS[index - from];
                MarketQuote cached = marketDataService.cachedQuote(asset.symbol()).orElse(null);
                inventory.setItem(slot, assetItem(asset, cached));
                marketDataService.quote(asset, false).whenComplete((quote, throwable) -> sync(() -> {
                    if (!isCurrent(player, holder)) return;
                    holder.getInventory().setItem(slot, assetItem(asset, throwable == null ? quote : null));
                }));
            }
            if (safePage > 0) {
                inventory.setItem(45, itemFactory.withTags(
                        itemFactory.fromConfig("theme.previous", Map.of()),
                        "browser-page", null, String.valueOf(safePage - 1), context));
            }
            inventory.setItem(49, itemFactory.withAction(
                    itemFactory.fromConfig("theme.back", Map.of()), "back-main"));
            if (safePage < pages - 1) {
                inventory.setItem(53, itemFactory.withTags(
                        itemFactory.fromConfig("theme.next", Map.of()),
                        "browser-page", null, String.valueOf(safePage + 1), context));
            }
            open(player, inventory);
        });
    }

    public void openDetail(Player player, String symbol) {
        AssetDefinition asset = assetRegistry.getEnabled(symbol).orElse(null);
        if (asset == null) {
            messages.send(player, "market.unknown-asset", Map.of("symbol", symbol));
            return;
        }
        CompletableFuture<MarketQuote> quoteFuture = marketDataService.quote(asset, false);
        CompletableFuture<Holding> holdingFuture = databaseService.getHolding(
                player.getUniqueId(), asset.symbol()).thenApply(optional -> optional.orElse(null));
        CompletableFuture<Boolean> watchedFuture = watchlistService.contains(
                player.getUniqueId(), asset.symbol());
        CompletableFuture.allOf(quoteFuture, holdingFuture, watchedFuture).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                messages.send(player, "market.unavailable");
                return;
            }
            MarketQuote quote = quoteFuture.join();
            Holding holding = holdingFuture.join();
            boolean watched = watchedFuture.join();
            sync(() -> {
                Map<String, Object> title = Map.of("symbol", asset.symbol());
                MarketMenuHolder holder = new MarketMenuHolder(
                        MenuType.DETAIL, player.getUniqueId(), asset.symbol(), 0, null, null, null);
                Inventory inventory = create(holder, "detail.title", "detail.size", title);
                fill(inventory);
                inventory.setItem(menus().getInt("detail.info-slot", 13),
                        detailItem(asset, quote, holding));
                inventory.setItem(menus().getInt("detail.buy-slot", 29),
                        itemFactory.withTags(itemFactory.fromConfig("detail.buy", Map.of()),
                                "trade-side", asset.symbol(), TradeSide.BUY.name(), null));
                inventory.setItem(menus().getInt("detail.sell-slot", 33),
                        itemFactory.withTags(itemFactory.fromConfig("detail.sell", Map.of()),
                                "trade-side", asset.symbol(), TradeSide.SELL.name(), null));
                String watchPath = watched ? "detail.watchlist-remove" : "detail.watchlist-add";
                inventory.setItem(menus().getInt("detail.watchlist-slot", 31),
                        itemFactory.withTags(itemFactory.fromConfig(watchPath, Map.of()),
                                "watchlist-toggle", asset.symbol(), null, null));
                inventory.setItem(menus().getInt("detail.chart-slot", 40),
                        itemFactory.withTags(itemFactory.fromConfig("detail.chart", Map.of()),
                                "chart", asset.symbol(), HistoryPeriod.SEVEN_DAYS.key(), null));
                inventory.setItem(menus().getInt("detail.alert-slot", 41),
                        itemFactory.withTags(itemFactory.fromConfig("detail.alert", Map.of()),
                                "alert-instruction", asset.symbol(), null, null));
                inventory.setItem(menus().getInt("detail.refresh-slot", 22),
                        itemFactory.withTags(itemFactory.fromConfig("detail.refresh", Map.of()),
                                "refresh-detail", asset.symbol(), null, null));
                inventory.setItem(menus().getInt("detail.back-slot", 49),
                        itemFactory.withAction(itemFactory.fromConfig("theme.back", Map.of()), "back-main"));
                open(player, inventory);
            });
        });
    }

    public void openQuantity(Player player, String symbol, TradeSide side) {
        AssetDefinition asset = assetRegistry.getEnabled(symbol).orElse(null);
        if (asset == null) {
            messages.send(player, "market.unknown-asset", Map.of("symbol", symbol));
            return;
        }
        CompletableFuture<MarketQuote> quoteFuture = marketDataService.quote(asset, false);
        CompletableFuture<Double> balanceFuture = economyService.balance(player);
        CompletableFuture<Holding> holdingFuture = databaseService.getHolding(
                player.getUniqueId(), symbol).thenApply(optional -> optional.orElse(null));
        CompletableFuture.allOf(quoteFuture, balanceFuture, holdingFuture).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                messages.send(player, "market.unavailable");
                return;
            }
            MarketQuote quote = quoteFuture.join();
            BigDecimal balance = BigDecimal.valueOf(balanceFuture.join());
            Holding holding = holdingFuture.join();
            sync(() -> {
                Map<String, Object> title = Map.of("symbol", symbol);
                MarketMenuHolder holder = new MarketMenuHolder(
                        MenuType.QUANTITY, player.getUniqueId(), symbol, 0, side, null, null);
                String titlePath = side == TradeSide.BUY ? "quantity.title-buy" : "quantity.title-sell";
                Inventory inventory = createLiteral(holder,
                        menus().getString(titlePath, "{symbol}"), menus().getInt("quantity.size", 27), title);
                fill(inventory);
                List<Map<?, ?>> choices = menus().getMapList("quantity.choices");
                for (Map<?, ?> choice : choices) {
                    int slot = integer(choice.get("slot"), 10);
                    Object quantityValue = choice.containsKey("quantity") ? choice.get("quantity") : 1;
                    Object materialValue = choice.containsKey("material") ? choice.get("material") : "PAPER";
                    BigDecimal quantity = new BigDecimal(String.valueOf(quantityValue));
                    Material material = Material.matchMaterial(String.valueOf(materialValue));
                    Map<String, Object> placeholders = Map.of(
                            "quantity", quantity(quantity), "symbol", symbol,
                            "total", currency(estimatedTotal(side, quote.price(), quantity)));
                    ItemStack item = template("templates.quantity", material, placeholders, 0);
                    inventory.setItem(slot, itemFactory.withTags(item, "quantity-choice",
                            symbol, quantity.toPlainString(), side.name()));
                }
                if (side == TradeSide.SELL && holding != null) {
                    for (Map<?, ?> percentageChoice : menus().getMapList("quantity.sell-percentages")) {
                        int percent = integer(percentageChoice.get("percent"), 25);
                        int slot = integer(percentageChoice.get("slot"), 19);
                        Object materialValue = percentageChoice.containsKey("material")
                                ? percentageChoice.get("material") : "PAPER";
                        Material material = Material.matchMaterial(String.valueOf(materialValue));
                        BigDecimal percentageQuantity = holding.quantity()
                                .multiply(BigDecimal.valueOf(percent))
                                .divide(BigDecimal.valueOf(100), 12, RoundingMode.DOWN);
                        Map<String, Object> placeholders = Map.of(
                                "quantity", quantity(percentageQuantity), "symbol", symbol,
                                "total", currency(estimatedTotal(side, quote.price(), percentageQuantity)));
                        ItemStack item = template("templates.quantity", material, placeholders, 0);
                        inventory.setItem(slot, itemFactory.withTags(item, "quantity-choice",
                                symbol, percentageQuantity.toPlainString(), side.name()));
                    }
                }
                BigDecimal maximum = side == TradeSide.BUY
                        ? maximumAffordable(balance, quote.price())
                        : holding == null ? BigDecimal.ZERO : holding.quantity();
                String maxNamePath = side == TradeSide.BUY
                        ? "quantity.maximum.name-buy" : "quantity.maximum.name-sell";
                Material maxMaterial = material(menus().getString("quantity.maximum.material", "NETHER_STAR"));
                Map<String, Object> maximumPlaceholders = Map.of(
                        "quantity", quantity(maximum), "symbol", symbol);
                ItemStack maximumItem = itemFactory.create(maxMaterial,
                        menus().getString(maxNamePath, "Maximum"),
                        menus().getStringList("quantity.maximum.lore").stream()
                                .map(line -> TextUtil.replace(line, maximumPlaceholders)).toList(), 0);
                inventory.setItem(menus().getInt("quantity.maximum.slot", 15),
                        itemFactory.withTags(maximumItem, "quantity-choice", symbol,
                                maximum.toPlainString(), side.name()));
                Map<String, Object> customPlaceholders = Map.of(
                        "side", side.name().toLowerCase(Locale.ROOT), "symbol", symbol);
                inventory.setItem(menus().getInt("quantity.custom.slot", 16),
                        itemFactory.withAction(itemFactory.fromConfig("quantity.custom", customPlaceholders),
                                "custom-command"));
                inventory.setItem(menus().getInt("quantity.back-slot", 22),
                        itemFactory.withTags(itemFactory.fromConfig("theme.back", Map.of()),
                                "open-detail", symbol, null, null));
                open(player, inventory);
            });
        });
    }

    public void openConfirmation(Player player, TradeRequest request) {
        economyService.balance(player).whenComplete((balanceDouble, throwable) -> {
            if (throwable != null) {
                messages.send(player, "trade.economy-failure");
                return;
            }
            BigDecimal subtotal = request.quote().price().multiply(request.quantity());
            BigDecimal fee = fee(subtotal);
            BigDecimal total = request.side() == TradeSide.BUY
                    ? subtotal.add(fee) : subtotal.subtract(fee);
            BigDecimal remaining = request.side() == TradeSide.BUY
                    ? BigDecimal.valueOf(balanceDouble).subtract(total)
                    : BigDecimal.valueOf(balanceDouble).add(total);
            sync(() -> {
                MarketMenuHolder holder = new MarketMenuHolder(
                        MenuType.CONFIRMATION, player.getUniqueId(), request.symbol(), 0,
                        request.side(), request, null);
                String titlePath = request.side() == TradeSide.BUY
                        ? "confirmation.title-buy" : "confirmation.title-sell";
                Inventory inventory = createLiteral(holder, menus().getString(titlePath, ""),
                        menus().getInt("confirmation.size", 27), Map.of());
                fill(inventory);
                Map<String, Object> placeholders = new LinkedHashMap<>();
                placeholders.put("side", request.side().name());
                placeholders.put("symbol", request.symbol());
                placeholders.put("quantity", quantity(request.quantity()));
                placeholders.put("price", currency(request.quote().price()));
                placeholders.put("subtotal", currency(subtotal));
                placeholders.put("fee", currency(fee));
                placeholders.put("total", currency(total));
                placeholders.put("remaining_balance", currency(remaining));
                placeholders.put("timestamp", TextUtil.timestamp(request.quote().sourceTimestamp()));
                inventory.setItem(menus().getInt("confirmation.summary-slot", 13),
                        template("templates.confirmation", Material.PAPER, placeholders, 0));
                inventory.setItem(menus().getInt("confirmation.confirm-slot", 11),
                        itemFactory.withAction(itemFactory.fromConfig(
                                "confirmation.confirm", Map.of()), "confirm-trade"));
                inventory.setItem(menus().getInt("confirmation.cancel-slot", 15),
                        itemFactory.withTags(itemFactory.fromConfig(
                                "confirmation.cancel", Map.of()), "open-detail",
                                request.symbol(), null, null));
                open(player, inventory);
            });
        });
    }

    public void openPortfolio(Player player) {
        CompletableFuture<List<PortfolioPosition>> positionsFuture =
                portfolioService.positions(player.getUniqueId());
        CompletableFuture<PortfolioSummary> summaryFuture =
                portfolioService.summary(player.getUniqueId());
        CompletableFuture<Double> balanceFuture = economyService.balance(player);
        CompletableFuture.allOf(positionsFuture, summaryFuture, balanceFuture)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        messages.send(player, "general.internal-error");
                        return;
                    }
                    List<PortfolioPosition> positions = positionsFuture.join();
                    PortfolioSummary summary = summaryFuture.join();
                    sync(() -> {
                        MarketMenuHolder holder = MarketMenuHolder.basic(
                                MenuType.PORTFOLIO, player.getUniqueId());
                        Inventory inventory = create(holder, "portfolio.title",
                                "portfolio.size", Map.of());
                        fill(inventory);
                        Map<String, Object> summaryPlaceholders = new LinkedHashMap<>();
                        summaryPlaceholders.put("portfolio_value", currency(summary.holdingsValue()));
                        summaryPlaceholders.put("total_invested", currency(summary.totalInvested()));
                        summaryPlaceholders.put("available_balance", currency(BigDecimal.valueOf(balanceFuture.join())));
                        summaryPlaceholders.put("unrealized", signedCurrency(summary.unrealizedProfit()));
                        summaryPlaceholders.put("unrealized_color", TextUtil.colorName(summary.unrealizedProfit()));
                        summaryPlaceholders.put("realized", signedCurrency(summary.realizedProfit()));
                        summaryPlaceholders.put("realized_color", TextUtil.colorName(summary.realizedProfit()));
                        summaryPlaceholders.put("daily_change", signedCurrency(summary.dailyChange()));
                        summaryPlaceholders.put("daily_color", TextUtil.colorName(summary.dailyChange()));
                        summaryPlaceholders.put("best_asset", summary.bestAsset());
                        summaryPlaceholders.put("worst_asset", summary.worstAsset());
                        summaryPlaceholders.put("owned_assets", positions.size());
                        inventory.setItem(4, template("templates.portfolio-summary", Material.NETHER_STAR,
                                summaryPlaceholders, 0));
                        int count = Math.min(CONTENT_SLOTS.length, positions.size());
                        for (int index = 0; index < count; index++) {
                            PortfolioPosition position = positions.get(index);
                            AssetDefinition asset = assetRegistry.get(position.holding().symbol()).orElse(null);
                            Material material = asset == null ? Material.PAPER : asset.material();
                            Map<String, Object> placeholders = new LinkedHashMap<>();
                            placeholders.put("symbol", position.holding().symbol());
                            placeholders.put("quantity", quantity(position.holding().quantity()));
                            placeholders.put("average_price", currency(position.holding().averagePrice()));
                            placeholders.put("latest_price", currency(position.quote() == null
                                    ? BigDecimal.ZERO : position.quote().price()));
                            placeholders.put("position_value", currency(position.positionValue()));
                            placeholders.put("profit", currency(position.profit()));
                            placeholders.put("profit_percent",
                                    TextUtil.decimal(position.profitPercent(), 2));
                            placeholders.put("profit_color", TextUtil.colorName(position.profit()));
                            placeholders.put("allocation_percent", TextUtil.decimal(
                                    DecimalUtil.percentage(position.positionValue(), summary.holdingsValue()), 2));
                            ItemStack item = template("templates.holding", material, placeholders,
                                    asset == null ? 0 : asset.customModelData());
                            inventory.setItem(CONTENT_SLOTS[index], itemFactory.withTags(item,
                                    "open-detail", position.holding().symbol(), null, null));
                        }
                        inventory.setItem(49, itemFactory.withAction(
                                itemFactory.fromConfig("theme.back", Map.of()), "back-main"));
                        open(player, inventory);
                    });
                });
    }

    public void openWatchlist(Player player, WatchlistSort sort) {
        watchlistService.entries(player.getUniqueId(), sort).whenComplete((entries, throwable) -> {
            if (throwable != null) {
                messages.send(player, "general.internal-error");
                return;
            }
            sync(() -> {
                MarketMenuHolder holder = new MarketMenuHolder(
                        MenuType.WATCHLIST, player.getUniqueId(), sort.name(), 0,
                        null, null, null);
                Inventory inventory = create(holder, "watchlist.title", "watchlist.size", Map.of());
                fill(inventory);
                int count = Math.min(CONTENT_SLOTS.length, entries.size());
                for (int index = 0; index < count; index++) {
                    WatchlistService.WatchlistEntry entry = entries.get(index);
                    AssetDefinition asset = assetRegistry.get(entry.symbol()).orElse(null);
                    if (asset == null) continue;
                    ItemStack item = assetItem(asset, entry.quote());
                    inventory.setItem(CONTENT_SLOTS[index], itemFactory.withTags(item,
                            "open-detail", entry.symbol(), null, null));
                }
                for (Map.Entry<String, WatchlistSort> sortEntry : Map.of(
                        "sort-name", WatchlistSort.NAME,
                        "sort-price", WatchlistSort.PRICE,
                        "sort-change", WatchlistSort.CHANGE).entrySet()) {
                    String path = "watchlist.items." + sortEntry.getKey();
                    int slot = menus().getInt(path + ".slot", 47);
                    inventory.setItem(slot, itemFactory.withTags(
                            itemFactory.fromConfig(path, Map.of()), "watchlist-sort", null,
                            sortEntry.getValue().name(), null));
                }
                inventory.setItem(49, itemFactory.withAction(
                        itemFactory.fromConfig("theme.back", Map.of()), "back-main"));
                open(player, inventory);
            });
        });
    }

    public void openAlerts(Player player) {
        alertService.list(player.getUniqueId()).whenComplete((alerts, throwable) -> {
            if (throwable != null) {
                messages.send(player, "general.internal-error");
                return;
            }
            sync(() -> {
                MarketMenuHolder holder = MarketMenuHolder.basic(
                        MenuType.ALERTS, player.getUniqueId());
                Inventory inventory = create(holder, "alerts.title", "alerts.size", Map.of());
                fill(inventory);
                int count = Math.min(CONTENT_SLOTS.length, alerts.size());
                for (int index = 0; index < count; index++) {
                    PriceAlert alert = alerts.get(index);
                    Map<String, Object> placeholders = Map.of(
                            "symbol", alert.symbol(),
                            "condition", alert.condition().name(),
                            "target", TextUtil.decimal(alert.target(), plugin.getConfig().getInt("currency-decimals", 2)),
                            "status", alert.active()
                                    ? menus().getString("alerts.status-active", "ACTIVE")
                                    : menus().getString("alerts.status-paused", "PAUSED"),
                            "triggered", alert.triggered()
                                    ? menus().getString("alerts.yes", "YES")
                                    : menus().getString("alerts.no", "NO")
                    );
                    ItemStack item = template("templates.alert", Material.BELL, placeholders, 0);
                    inventory.setItem(CONTENT_SLOTS[index], itemFactory.withTags(item,
                            "alert-manage", alert.symbol(), alert.alertId().toString(),
                            String.valueOf(alert.active())));
                }
                inventory.setItem(49, itemFactory.withAction(
                        itemFactory.fromConfig("theme.back", Map.of()), "back-main"));
                open(player, inventory);
            });
        });
    }

    public void openHistory(Player player) {
        transactionService.history(player.getUniqueId(), CONTENT_SLOTS.length)
                .whenComplete((records, throwable) -> {
                    if (throwable != null) {
                        messages.send(player, "general.internal-error");
                        return;
                    }
                    sync(() -> {
                        MarketMenuHolder holder = MarketMenuHolder.basic(
                                MenuType.HISTORY, player.getUniqueId());
                        Inventory inventory = create(holder, "history.title", "history.size", Map.of());
                        fill(inventory);
                        int count = Math.min(CONTENT_SLOTS.length, records.size());
                        for (int index = 0; index < count; index++) {
                            TransactionRecord record = records.get(index);
                            Map<String, Object> placeholders = new LinkedHashMap<>();
                            placeholders.put("type", record.type().name());
                            placeholders.put("symbol", record.symbol());
                            placeholders.put("quantity", quantity(record.quantity()));
                            placeholders.put("price", currency(record.unitPrice()));
                            placeholders.put("fee", currency(record.fee()));
                            placeholders.put("total", currency(record.total()));
                            placeholders.put("status", record.status().name());
                            placeholders.put("date", TextUtil.timestamp(record.createdAt()));
                            ItemStack item = template("templates.transaction",
                                    record.type().name().contains("SELL")
                                            ? Material.RED_DYE : Material.LIME_DYE,
                                    placeholders, 0);
                            inventory.setItem(CONTENT_SLOTS[index], item);
                        }
                        inventory.setItem(49, itemFactory.withAction(
                                itemFactory.fromConfig("theme.back", Map.of()), "back-main"));
                        open(player, inventory);
                    });
                });
    }

    public void openLeaderboard(Player player, LeaderboardMetric metric) {
        sync(() -> {
            MarketMenuHolder holder = new MarketMenuHolder(
                    MenuType.LEADERBOARD, player.getUniqueId(), "", 0,
                    null, null, metric);
            Inventory inventory = create(holder, "leaderboard.title",
                    "leaderboard.size", Map.of());
            fill(inventory);
            List<LeaderboardEntry> entries = leaderboardService.entries(metric, CONTENT_SLOTS.length);
            for (int index = 0; index < entries.size(); index++) {
                LeaderboardEntry entry = entries.get(index);
                Map<String, Object> placeholders = Map.of(
                        "rank", entry.rank(),
                        "player", entry.playerName(),
                        "metric", metric.name(),
                        "value", leaderboardValue(metric, entry.value())
                );
                Material material = index == 0 ? Material.GOLD_INGOT
                        : index == 1 ? Material.IRON_INGOT
                        : index == 2 ? Material.COPPER_INGOT : Material.PAPER;
                inventory.setItem(CONTENT_SLOTS[index],
                        template("templates.leaderboard", material, placeholders, 0));
            }
            for (Map<?, ?> metricConfig : menus().getMapList("leaderboard.metrics")) {
                String rawMetric = String.valueOf(metricConfig.get("value"));
                LeaderboardMetric option;
                try {
                    option = LeaderboardMetric.valueOf(rawMetric);
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                int slot = integer(metricConfig.get("slot"), 45);
                Material material = material(String.valueOf(metricConfig.get("material")));
                Map<String, Object> placeholders = Map.of(
                        "name", String.valueOf(metricConfig.get("name")));
                ItemStack button = template("templates.leaderboard-metric", material,
                        placeholders, 0);
                inventory.setItem(slot, itemFactory.withTags(button,
                        "leaderboard-metric", null, option.name(), null));
            }
            inventory.setItem(53, itemFactory.withAction(
                    itemFactory.fromConfig("theme.back", Map.of()), "back-main"));
            open(player, inventory);
        });
    }

    public void openSettings(Player player) {
        settingsService.get(player.getUniqueId()).whenComplete((settings, throwable) -> {
            if (throwable != null) {
                messages.send(player, "general.internal-error");
                return;
            }
            sync(() -> {
                MarketMenuHolder holder = MarketMenuHolder.basic(
                        MenuType.SETTINGS, player.getUniqueId());
                Inventory inventory = create(holder, "settings.title", "settings.size", Map.of());
                fill(inventory);
                ConfigurationSection items = menus().getConfigurationSection("settings.items");
                if (items != null) {
                    for (String key : items.getKeys(false)) {
                        String path = "settings.items." + key;
                        int slot = menus().getInt(path + ".slot", -1);
                        Material material = material(menus().getString(path + ".material", "PAPER"));
                        String displayName = menus().getString(path + ".name", key);
                        boolean enabled = settingValue(settings, key);
                        Map<String, Object> placeholders = Map.of(
                                "name", displayName,
                                "status", enabled
                                        ? menus().getString("settings.status-enabled", "<green>ENABLED</green>")
                                        : menus().getString("settings.status-disabled", "<red>DISABLED</red>"));
                        ItemStack item = template("templates.setting", material, placeholders, 0);
                        inventory.setItem(slot, itemFactory.withTags(item,
                                "setting-toggle", null, key, null));
                    }
                }
                inventory.setItem(menus().getInt("settings.back-slot", 22),
                        itemFactory.withAction(itemFactory.fromConfig("theme.back", Map.of()),
                                "back-main"));
                open(player, inventory);
            });
        });
    }

    public void showChart(Player player, String symbol, HistoryPeriod period) {
        marketDataService.history(symbol, period, false).whenComplete((history, throwable) -> {
            if (throwable != null || history == null || history.points().isEmpty()) {
                messages.send(player, "chart.unavailable");
                return;
            }
            BigDecimal first = history.points().getFirst().price();
            BigDecimal last = history.points().getLast().price();
            BigDecimal change = last.subtract(first);
            BigDecimal low = history.points().stream()
                    .map(point -> point.price()).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal high = history.points().stream()
                    .map(point -> point.price()).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            Map<String, Object> placeholders = Map.of(
                    "symbol", symbol,
                    "period", period.key(),
                    "chart", ChartUtil.sparkline(history.points(), 45),
                    "color", TextUtil.colorName(change),
                    "low", currency(low),
                    "high", currency(high)
            );
            messages.sendRaw(player, messages.raw("chart.header"), placeholders);
            messages.sendRaw(player, messages.raw("chart.graph"), placeholders);
            messages.sendRaw(player, messages.raw("chart.range"), placeholders);
        });
    }

    public CompletableFuture<TradeRequest> prepareTrade(Player player, String symbol,
                                                        TradeSide side, BigDecimal quantity) {
        return tradingService.prepare(player, symbol, side, quantity);
    }

    public TradingService tradingService() {
        return tradingService;
    }

    public ItemFactory itemFactory() {
        return itemFactory;
    }

    private Inventory create(MarketMenuHolder holder, String titlePath, String sizePath,
                             Map<String, ?> placeholders) {
        return createLiteral(holder, menus().getString(titlePath, ""),
                menus().getInt(sizePath, 54), placeholders);
    }

    private Inventory createLiteral(MarketMenuHolder holder, String title, int size,
                                    Map<String, ?> placeholders) {
        int normalized = Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
        Inventory inventory = Bukkit.createInventory(holder, normalized,
                TextUtil.component(title, placeholders));
        holder.inventory(inventory);
        return inventory;
    }

    private ItemStack assetItem(AssetDefinition asset, MarketQuote quote) {
        if (quote == null) {
            ItemStack item = template("templates.asset-loading", asset.material(),
                    Map.of("name", asset.name()), asset.customModelData());
            return itemFactory.withTags(item, "asset-open", asset.symbol(), null, null);
        }
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("name", asset.name());
        placeholders.put("symbol", asset.symbol());
        placeholders.put("price", currency(quote.price()));
        placeholders.put("change_percent", signed(quote.changePercent(), 2));
        placeholders.put("change_color", TextUtil.colorName(quote.changePercent()));
        placeholders.put("market_status", marketHoursService.isOpen(asset)
                ? messages.raw("market.open") : messages.raw("market.closed"));
        placeholders.put("age", TextUtil.age(quote.sourceTimestamp()));
        ItemStack item = template("templates.asset", asset.material(),
                placeholders, asset.customModelData());
        return itemFactory.withTags(item, "asset-open", asset.symbol(), null, null);
    }

    private ItemStack detailItem(AssetDefinition asset, MarketQuote quote, Holding holding) {
        BigDecimal owned = holding == null ? BigDecimal.ZERO : holding.quantity();
        BigDecimal average = holding == null ? BigDecimal.ZERO : holding.averagePrice();
        BigDecimal value = owned.multiply(quote.price());
        BigDecimal unrealized = value.subtract(owned.multiply(average));
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("name", asset.name());
        placeholders.put("symbol", asset.symbol());
        placeholders.put("type", asset.type().name());
        placeholders.put("price", currency(quote.price()));
        placeholders.put("previous_close", currency(quote.previousClose()));
        placeholders.put("change", signedCurrency(quote.change()));
        placeholders.put("change_percent", signed(quote.changePercent(), 2));
        placeholders.put("change_color", TextUtil.colorName(quote.changePercent()));
        placeholders.put("open", currency(quote.open()));
        placeholders.put("high", currency(quote.high()));
        placeholders.put("low", currency(quote.low()));
        placeholders.put("volume", TextUtil.decimal(quote.volume(), 0));
        placeholders.put("market_status", marketHoursService.isOpen(asset)
                ? messages.raw("market.open") : messages.raw("market.closed"));
        placeholders.put("age", TextUtil.age(quote.sourceTimestamp()));
        placeholders.put("owned", quantity(owned));
        placeholders.put("average_cost", currency(average));
        placeholders.put("position_value", currency(value));
        placeholders.put("profit_color", TextUtil.colorName(unrealized));
        placeholders.put("unrealized", signedCurrency(unrealized));
        return template("templates.detail", asset.material(), placeholders, asset.customModelData());
    }

    private ItemStack template(String path, Material material, Map<String, ?> placeholders,
                               int customModelData) {
        String name = menus().getString(path + ".name", " ");
        List<String> lore = menus().getStringList(path + ".lore");
        return itemFactory.create(material, TextUtil.replace(name, placeholders),
                lore.stream().map(line -> TextUtil.replace(line, placeholders)).toList(),
                customModelData);
    }

    private List<AssetDefinition> resolveAssets(String context) {
        String normalized = context.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("SEARCH:")) {
            return assetRegistry.search(context.substring("SEARCH:".length()));
        }
        return switch (normalized) {
            case "STOCKS", "STOCK" -> assetRegistry.enabledByType(AssetType.STOCK);
            case "ETFS", "ETF" -> assetRegistry.enabledByType(AssetType.ETF);
            case "INDEXES", "INDEX" -> assetRegistry.enabledByType(AssetType.INDEX);
            case "CRYPTO", "CRYPTOCURRENCY" -> assetRegistry.enabledByType(AssetType.CRYPTO);
            case "TRENDING" -> quotesToAssets(marketDataService.trending(100));
            case "RECENT", "RECENTLY_TRADED" -> {
                List<AssetDefinition> recent = marketDataService.recentlyTraded(100);
                yield recent.isEmpty() ? assetRegistry.enabled() : recent;
            }
            case "GAINERS" -> quotesToAssets(marketDataService.gainers(100));
            case "LOSERS" -> quotesToAssets(marketDataService.losers(100));
            default -> assetRegistry.enabled();
        };
    }

    private List<AssetDefinition> quotesToAssets(List<MarketQuote> quotes) {
        List<AssetDefinition> assets = new ArrayList<>();
        for (MarketQuote quote : quotes) {
            assetRegistry.getEnabled(quote.symbol()).ifPresent(assets::add);
        }
        return assets.isEmpty() ? assetRegistry.enabled() : assets;
    }

    private String contextDisplay(String context) {
        String normalized = context.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("SEARCH:")) {
            return menus().getString("browser.search-label", "Search") + " — " + context.substring(7);
        }
        String key = switch (normalized) {
            case "STOCKS", "STOCK" -> "stocks";
            case "ETFS", "ETF" -> "etfs";
            case "INDEXES", "INDEX" -> "indexes";
            case "CRYPTO", "CRYPTOCURRENCY" -> "crypto";
            case "TRENDING" -> "trending";
            case "RECENT", "RECENTLY_TRADED" -> "recent";
            case "GAINERS" -> "gainers";
            case "LOSERS" -> "losers";
            default -> "all";
        };
        return menus().getString("browser.categories." + key, key);
    }

    private String globalMarketStatus() {
        boolean anyTraditionalOpen = assetRegistry.enabled().stream()
                .filter(asset -> asset.type() != AssetType.CRYPTO
                        && asset.type() != AssetType.FICTIONAL)
                .anyMatch(marketHoursService::isOpen);
        return anyTraditionalOpen ? messages.raw("market.open") : messages.raw("market.closed");
    }

    private BigDecimal maximumAffordable(BigDecimal balance, BigDecimal price) {
        if (price.signum() <= 0 || balance.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal quantity = balance.divide(price, 12, RoundingMode.DOWN);
        for (int iteration = 0; iteration < 3; iteration++) {
            BigDecimal subtotal = price.multiply(quantity);
            BigDecimal fee = fee(subtotal);
            quantity = balance.subtract(fee).max(BigDecimal.ZERO)
                    .divide(price, 12, RoundingMode.DOWN);
        }
        return quantity.max(BigDecimal.ZERO);
    }

    private BigDecimal estimatedTotal(TradeSide side, BigDecimal price, BigDecimal quantity) {
        BigDecimal subtotal = price.multiply(quantity);
        BigDecimal fee = fee(subtotal);
        return side == TradeSide.BUY ? subtotal.add(fee) : subtotal.subtract(fee);
    }

    private BigDecimal fee(BigDecimal subtotal) {
        BigDecimal percent = BigDecimal.valueOf(
                plugin.getConfig().getDouble("trading.fee-percent", 0.5));
        BigDecimal minimum = BigDecimal.valueOf(
                plugin.getConfig().getDouble("trading.minimum-fee", 0.01));
        return DecimalUtil.fee(subtotal, percent, minimum);
    }

    private void fill(Inventory inventory) {
        if (!plugin.getConfig().getBoolean("gui.fill-empty-slots", true)) return;
        ItemStack filler = itemFactory.fromConfig("theme.filler", Map.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, filler);
        }
    }

    private void open(Player player, Inventory inventory) {
        player.openInventory(inventory);
        soundService.play(player, "menu-open");
    }

    private boolean isCurrent(Player player, MarketMenuHolder holder) {
        return player.isOnline()
                && player.getOpenInventory().getTopInventory().getHolder() == holder;
    }

    private void sync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) runnable.run();
        else Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private String currency(BigDecimal value) {
        String symbol = plugin.getConfig().getString("currency-symbol", "$");
        int decimals = plugin.getConfig().getInt("currency-decimals", 2);
        Locale configuredLocale = TextUtil.parseLocale(
                plugin.getConfig().getString("locale", "en-US"));
        return TextUtil.currency(value, symbol, decimals, configuredLocale);
    }

    private String signedCurrency(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + currency(value);
    }

    private String signed(BigDecimal value, int decimals) {
        return (value.signum() > 0 ? "+" : "") + TextUtil.decimal(value, decimals);
    }

    private String quantity(BigDecimal value) {
        return TextUtil.decimal(value, plugin.getConfig().getInt("quantity-decimals", 8));
    }

    private String leaderboardValue(LeaderboardMetric metric, BigDecimal value) {
        return switch (metric) {
            case PORTFOLIO_VALUE, REALIZED_PROFIT -> currency(value);
            case MOST_ACTIVE -> TextUtil.decimal(value, 0);
            default -> signed(value, 2) + "%";
        };
    }

    private boolean settingValue(PlayerSettings settings, String key) {
        return switch (key) {
            case "alert_chat" -> settings.alertChat();
            case "alert_actionbar" -> settings.alertActionBar();
            case "alert_sound" -> settings.alertSound();
            case "alert_title" -> settings.alertTitle();
            case "movement_notifications" -> settings.movementNotifications();
            default -> false;
        };
    }

    private static Material material(String raw) {
        Material material = Material.matchMaterial(raw == null ? "PAPER" : raw);
        return material == null ? Material.PAPER : material;
    }

    private static int integer(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private YamlConfiguration menus() {
        return configManager.get("menus.yml");
    }

    private static PortfolioSummary emptySummary() {
        return new PortfolioSummary(List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "—", "—");
    }

    private record MainData(PortfolioSummary summary, double balance) {
    }
}
