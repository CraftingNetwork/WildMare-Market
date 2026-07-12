package com.wildmare.market.command;

import com.wildmare.market.alert.PriceAlertService;
import com.wildmare.market.config.Messages;
import com.wildmare.market.gui.GuiManager;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.model.AlertCondition;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.HistoryPeriod;
import com.wildmare.market.model.LeaderboardMetric;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.model.TradeSide;
import com.wildmare.market.notification.SoundService;
import com.wildmare.market.util.TextUtil;
import com.wildmare.market.watchlist.WatchlistService;
import com.wildmare.market.watchlist.WatchlistSort;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Player command and tab-completion entry point. */
public final class MarketCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of(
            "help", "browse", "stocks", "crypto", "portfolio", "watchlist", "alerts",
            "history", "leaderboard", "search", "quote", "buy", "sell");

    private final JavaPlugin plugin;
    private final Messages messages;
    private final GuiManager gui;
    private final AssetRegistry assetRegistry;
    private final MarketDataService marketDataService;
    private final WatchlistService watchlistService;
    private final PriceAlertService alertService;
    private final SoundService soundService;

    public MarketCommand(JavaPlugin plugin, Messages messages, GuiManager gui,
                         AssetRegistry assetRegistry, MarketDataService marketDataService,
                         WatchlistService watchlistService, PriceAlertService alertService,
                         SoundService soundService) {
        this.plugin = plugin;
        this.messages = messages;
        this.gui = gui;
        this.assetRegistry = assetRegistry;
        this.marketDataService = marketDataService;
        this.watchlistService = watchlistService;
        this.alertService = alertService;
        this.soundService = soundService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        if (args.length == 0) {
            if (!allowed(player, "wildmaremarket.menu")) return true;
            gui.openMain(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> messages.sendList(player, "help.player");
            case "browse" -> {
                if (allowed(player, "wildmaremarket.menu")) gui.openBrowser(player, "ALL", 0);
            }
            case "stocks" -> {
                if (allowed(player, "wildmaremarket.menu")) gui.openBrowser(player, "STOCKS", 0);
            }
            case "crypto" -> {
                if (allowed(player, "wildmaremarket.menu")) gui.openBrowser(player, "CRYPTO", 0);
            }
            case "portfolio" -> {
                if (allowed(player, "wildmaremarket.portfolio")) gui.openPortfolio(player);
            }
            case "watchlist" -> handleWatchlist(player, args);
            case "alerts" -> handleAlerts(player, args);
            case "history" -> handleHistory(player, args);
            case "leaderboard" -> {
                if (allowed(player, "wildmaremarket.leaderboard")) {
                    gui.openLeaderboard(player, args.length > 1
                            ? LeaderboardMetric.parse(args[1]) : LeaderboardMetric.PORTFOLIO_VALUE);
                }
            }
            case "search" -> handleSearch(player, args);
            case "quote" -> handleQuote(player, args);
            case "buy" -> handleTrade(player, args, TradeSide.BUY);
            case "sell" -> handleTrade(player, args, TradeSide.SELL);
            default -> messages.send(player, "general.unknown-command");
        }
        return true;
    }

    private void handleWatchlist(Player player, String[] args) {
        if (!allowed(player, "wildmaremarket.watchlist")) return;
        if (args.length == 1) {
            gui.openWatchlist(player, WatchlistSort.NAME);
            return;
        }
        if (args.length < 3) {
            usage(player, "/market watchlist \\<add|remove> \\<symbol>");
            return;
        }
        String symbol = args[2].toUpperCase(Locale.ROOT);
        CompletableFuture<WatchlistService.Result> future = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> watchlistService.add(player, symbol);
            case "remove" -> watchlistService.remove(player.getUniqueId(), symbol);
            default -> null;
        };
        if (future == null) {
            usage(player, "/market watchlist \\<add|remove> \\<symbol>");
            return;
        }
        future.whenComplete((result, throwable) -> sync(() -> {
            if (throwable != null) {
                messages.send(player, "general.internal-error");
                return;
            }
            Map<String, Object> values = Map.of("symbol", symbol,
                    "limit", plugin.getConfig().getInt("watchlist.default-limit", 20));
            switch (result) {
                case ADDED -> messages.send(player, "watchlist.added", values);
                case REMOVED -> messages.send(player, "watchlist.removed", values);
                case ALREADY_EXISTS -> messages.send(player, "watchlist.already-added", values);
                case NOT_FOUND -> messages.send(player, "watchlist.not-found", values);
                case FULL -> messages.send(player, "watchlist.full", values);
                case UNKNOWN_ASSET -> messages.send(player, "market.unknown-asset", values);
            }
        }));
    }

    private void handleAlerts(Player player, String[] args) {
        if (!allowed(player, "wildmaremarket.alerts")) return;
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            gui.openAlerts(player);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("add")) {
            if (args.length < 5) {
                usage(player, "/market alerts add \\<symbol> "
                        + "\\<above|below|percent_up|percent_down|daily_gain|daily_loss> \\<target>");
                return;
            }
            try {
                AlertCondition condition = AlertCondition.parse(args[3]);
                BigDecimal target = positiveDecimal(args[4]);
                String symbol = args[2].toUpperCase(Locale.ROOT);
                alertService.create(player, symbol, condition, target)
                        .whenComplete((result, throwable) -> sync(() -> {
                            if (throwable != null) {
                                messages.send(player, "general.internal-error");
                                return;
                            }
                            Map<String, Object> values = Map.of(
                                    "symbol", symbol,
                                    "limit", plugin.getConfig().getInt("alerts.default-limit", 10));
                            switch (result) {
                                case CREATED -> messages.send(player, "alerts.created", values);
                                case FULL -> messages.send(player, "alerts.full", values);
                                case UNKNOWN_ASSET -> messages.send(player, "market.unknown-asset", values);
                                case INVALID_TARGET -> messages.send(player, "general.invalid-number");
                            }
                        }));
            } catch (IllegalArgumentException exception) {
                messages.send(player, "general.invalid-number");
            }
            return;
        }
        if (args.length < 3) {
            usage(player, "/market alerts \\<delete|pause|resume|edit> \\<alert-id> [target]");
            return;
        }
        UUID alertId;
        try {
            alertId = UUID.fromString(args[2]);
        } catch (IllegalArgumentException exception) {
            messages.send(player, "general.invalid-alert-id");
            return;
        }
        CompletableFuture<Boolean> future;
        try {
            future = switch (action) {
                case "delete" -> alertService.delete(player.getUniqueId(), alertId);
                case "pause" -> alertService.toggle(player.getUniqueId(), alertId, false);
                case "resume" -> alertService.toggle(player.getUniqueId(), alertId, true);
                case "edit" -> args.length >= 4
                        ? alertService.edit(player.getUniqueId(), alertId, positiveDecimal(args[3]))
                        : CompletableFuture.completedFuture(false);
                default -> CompletableFuture.completedFuture(false);
            };
        } catch (IllegalArgumentException exception) {
            messages.send(player, "general.invalid-number");
            return;
        }
        future.whenComplete((changed, throwable) -> sync(() -> {
            if (throwable != null || !changed) messages.send(player, "general.internal-error");
            else messages.send(player, action.equals("delete") ? "alerts.deleted" : "alerts.paused");
        }));
    }

    private void handleHistory(Player player, String[] args) {
        if (!allowed(player, "wildmaremarket.history")) return;
        if (args.length == 1) {
            gui.openHistory(player);
            return;
        }
        String symbol = args[1].toUpperCase(Locale.ROOT);
        if (assetRegistry.getEnabled(symbol).isEmpty()) {
            messages.send(player, "market.unknown-asset", Map.of("symbol", symbol));
            return;
        }
        gui.showChart(player, symbol,
                args.length > 2 ? HistoryPeriod.parse(args[2]) : HistoryPeriod.SEVEN_DAYS);
    }

    private void handleSearch(Player player, String[] args) {
        if (args.length < 2) {
            usage(player, "/market search \\<symbol or company name>");
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        List<AssetDefinition> results = assetRegistry.search(query);
        if (results.isEmpty()) {
            messages.send(player, "market.unknown-asset", Map.of("symbol", query));
            return;
        }
        if (results.size() == 1) gui.openDetail(player, results.getFirst().symbol());
        else gui.openBrowser(player, "SEARCH:" + query, 0);
    }

    private void handleQuote(Player player, String[] args) {
        if (args.length < 2) {
            usage(player, "/market quote \\<symbol>");
            return;
        }
        String symbol = args[1].toUpperCase(Locale.ROOT);
        AssetDefinition asset = assetRegistry.getEnabled(symbol).orElse(null);
        if (asset == null) {
            messages.send(player, "market.unknown-asset", Map.of("symbol", symbol));
            return;
        }
        messages.send(player, "general.loading");
        marketDataService.quote(asset, false).whenComplete((quote, throwable) -> sync(() -> {
            if (throwable != null) messages.send(player, "market.unavailable");
            else sendQuote(player, asset, quote);
        }));
    }

    private void sendQuote(Player player, AssetDefinition asset, MarketQuote quote) {
        String symbol = plugin.getConfig().getString("currency-symbol", "$");
        int decimals = plugin.getConfig().getInt("currency-decimals", 2);
        Locale locale = TextUtil.parseLocale(plugin.getConfig().getString("locale", "en-US"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", asset.name());
        values.put("symbol", asset.symbol());
        values.put("price", TextUtil.currency(quote.price(), symbol, decimals, locale));
        values.put("change_percent", signed(quote.changePercent()));
        values.put("change_color", TextUtil.colorName(quote.changePercent()));
        values.put("age", TextUtil.age(quote.sourceTimestamp()));
        messages.send(player, "market.quote", values);
    }

    private void handleTrade(Player player, String[] args, TradeSide side) {
        String permission = side == TradeSide.BUY ? "wildmaremarket.buy" : "wildmaremarket.sell";
        if (!allowed(player, "wildmaremarket.trade") || !allowed(player, permission)) return;
        if (args.length < 3) {
            usage(player, "/market " + side.name().toLowerCase(Locale.ROOT)
                    + " \\<symbol> \\<amount>");
            return;
        }
        String symbol = args[1].toUpperCase(Locale.ROOT);
        BigDecimal quantity;
        try {
            quantity = positiveDecimal(args[2]);
        } catch (IllegalArgumentException exception) {
            messages.send(player, "general.invalid-number");
            return;
        }
        if (assetRegistry.getEnabled(symbol).isEmpty()) {
            messages.send(player, "market.unknown-asset", Map.of("symbol", symbol));
            return;
        }
        gui.prepareTrade(player, symbol, side, quantity).whenComplete((request, throwable) -> sync(() -> {
            if (throwable != null) {
                messages.send(player, "market.unavailable");
                soundService.play(player, "failure");
            } else gui.openConfirmation(player, request);
        }));
    }

    private void usage(Player player, String usage) {
        messages.send(player, "general.usage", Map.of("usage", usage));
    }

    private boolean allowed(Player player, String permission) {
        if (player.hasPermission(permission)) return true;
        messages.send(player, "general.no-permission");
        return false;
    }

    private void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    private static BigDecimal positiveDecimal(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() <= 0) throw new NumberFormatException("non-positive");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid positive decimal", exception);
        }
    }

    private static String signed(BigDecimal value) {
        String formatted = TextUtil.decimal(value, 2);
        return value.signum() > 0 ? "+" + formatted : formatted;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
        if (args.length == 1) return match(SUBCOMMANDS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("quote", "buy", "sell", "history").contains(sub)) {
            return match(symbols(), args[1]);
        }
        if (args.length == 2 && sub.equals("watchlist")) return match(List.of("add", "remove"), args[1]);
        if (args.length == 3 && sub.equals("watchlist")) return match(symbols(), args[2]);
        if (args.length == 2 && sub.equals("alerts")) {
            return match(List.of("list", "add", "delete", "pause", "resume", "edit"), args[1]);
        }
        if (args.length == 3 && sub.equals("alerts") && args[1].equalsIgnoreCase("add")) {
            return match(symbols(), args[2]);
        }
        if (args.length == 4 && sub.equals("alerts") && args[1].equalsIgnoreCase("add")) {
            return match(List.of("above", "below", "percent_up", "percent_down",
                    "daily_gain", "daily_loss"), args[3]);
        }
        if (args.length == 3 && sub.equals("history")) {
            return match(List.of("1h", "1d", "7d", "30d", "90d", "1y"), args[2]);
        }
        if (args.length == 2 && sub.equals("leaderboard")) {
            return match(List.of("value", "realized", "return", "active",
                    "daily", "weekly", "monthly"), args[1]);
        }
        return List.of();
    }

    private List<String> symbols() {
        return assetRegistry.enabled().stream().map(AssetDefinition::symbol).toList();
    }

    private static List<String> match(List<String> options, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted().toList();
    }
}
