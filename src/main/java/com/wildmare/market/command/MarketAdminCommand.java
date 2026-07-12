package com.wildmare.market.command;

import com.wildmare.market.api.MarketDataProvider;
import com.wildmare.market.api.provider.ProviderRegistry;
import com.wildmare.market.config.Messages;
import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.Holding;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.model.TransactionRecord;
import com.wildmare.market.transaction.TransactionService;
import com.wildmare.market.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Administrative command surface for cache, providers, assets, and player data. */
public final class MarketAdminCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of(
            "help", "reload", "refresh", "status", "cache", "clearcache", "provider",
            "enable", "disable", "addasset", "removeasset", "resetportfolio", "setholding",
            "giveasset", "takeasset", "transaction", "debug");

    private final JavaPlugin plugin;
    private final Messages messages;
    private final AssetRegistry assetRegistry;
    private final ProviderRegistry providerRegistry;
    private final MarketDataService marketDataService;
    private final DatabaseService databaseService;
    private final TransactionService transactionService;
    private final Runnable reloadAction;

    public MarketAdminCommand(JavaPlugin plugin, Messages messages, AssetRegistry assetRegistry,
                              ProviderRegistry providerRegistry,
                              MarketDataService marketDataService,
                              DatabaseService databaseService,
                              TransactionService transactionService,
                              Runnable reloadAction) {
        this.plugin = plugin;
        this.messages = messages;
        this.assetRegistry = assetRegistry;
        this.providerRegistry = providerRegistry;
        this.marketDataService = marketDataService;
        this.databaseService = databaseService;
        this.transactionService = transactionService;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            messages.sendList(sender, "help.admin");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!allowed(sender, "wildmaremarket.admin.reload")) return true;
                reloadAction.run();
                messages.send(sender, "general.reloaded");
            }
            case "refresh" -> refresh(sender, args);
            case "status" -> status(sender);
            case "cache" -> cache(sender);
            case "clearcache" -> {
                if (!allowed(sender, "wildmaremarket.admin.refresh")) return true;
                marketDataService.clear();
                messages.send(sender, "admin.cache-cleared");
            }
            case "provider" -> providers(sender, args);
            case "enable", "disable" -> setAssetEnabled(sender, args, sub.equals("enable"));
            case "addasset" -> addAsset(sender, args);
            case "removeasset" -> removeAsset(sender, args);
            case "resetportfolio" -> resetPortfolio(sender, args);
            case "setholding", "giveasset", "takeasset" -> modifyHolding(sender, args, sub);
            case "transaction" -> transaction(sender, args);
            case "debug" -> debug(sender, args);
            default -> messages.send(sender, "general.unknown-command");
        }
        return true;
    }

    private void refresh(CommandSender sender, String[] args) {
        if (!allowed(sender, "wildmaremarket.admin.refresh")) return;
        messages.send(sender, "market.refreshing");
        CompletableFuture<MarketDataService.RefreshResult> future;
        if (args.length > 1) {
            AssetDefinition asset = assetRegistry.getEnabled(args[1]).orElse(null);
            if (asset == null) {
                messages.send(sender, "market.unknown-asset", Map.of("symbol", args[1]));
                return;
            }
            future = marketDataService.quote(asset, true)
                    .thenApply(quote -> new MarketDataService.RefreshResult(1, 0));
        } else future = marketDataService.refreshAll();
        future.whenComplete((result, throwable) -> sync(() -> {
            if (throwable != null || result.failed() > 0) {
                messages.send(sender, "market.refresh-failed");
            } else messages.send(sender, "market.refreshed");
        }));
    }

    private void status(CommandSender sender) {
        if (!allowed(sender, "wildmaremarket.admin")) return;
        messages.sendRaw(sender, messages.raw("admin.status-header"), Map.of());
        line(sender, "Plugin Version", plugin.getDescription().getVersion());
        line(sender, "Database", databaseService.storageType());
        line(sender, "Active DB Connections", databaseService.activeConnections());
        line(sender, "Enabled Assets", assetRegistry.enabled().size());
        line(sender, "Quote Cache", marketDataService.quoteCacheSize());
        line(sender, "History Cache", marketDataService.historyCacheSize());
        line(sender, "Successful API Requests", marketDataService.successfulRequests());
        line(sender, "Failed API Requests", marketDataService.failedRequests());
        line(sender, "Debug", plugin.getConfig().getBoolean("debug", false));
    }

    private void cache(CommandSender sender) {
        if (!allowed(sender, "wildmaremarket.admin")) return;
        line(sender, "Quote Entries", marketDataService.quoteCacheSize());
        line(sender, "History Entries", marketDataService.historyCacheSize());
        line(sender, "Provider Failures", marketDataService.providerFailures().size());
        for (Map.Entry<String, java.time.Instant> failure : marketDataService.providerFailures().entrySet()) {
            line(sender, "Failure " + failure.getKey(), TextUtil.timestamp(failure.getValue()));
        }
    }

    private void providers(CommandSender sender, String[] args) {
        if (!allowed(sender, "wildmaremarket.admin")) return;
        if (args.length > 1) {
            MarketDataProvider provider = providerRegistry.get(args[1]).orElse(null);
            if (provider == null) {
                messages.send(sender, "admin.provider-unavailable");
                return;
            }
            line(sender, "Provider", provider.id());
            line(sender, "Status", provider.status());
            return;
        }
        for (MarketDataProvider provider : providerRegistry.all()) {
            line(sender, "Provider " + provider.id(), provider.status());
        }
    }

    private void setAssetEnabled(CommandSender sender, String[] args, boolean enabled) {
        if (!allowed(sender, "wildmaremarket.admin.manageassets")) return;
        if (args.length < 2) {
            usage(sender, "/marketadmin " + (enabled ? "enable" : "disable") + " <symbol>");
            return;
        }
        String symbol = args[1].toUpperCase(Locale.ROOT);
        if (!assetRegistry.setEnabled(symbol, enabled)) {
            messages.send(sender, "market.unknown-asset", Map.of("symbol", symbol));
            return;
        }
        messages.send(sender, enabled ? "admin.asset-enabled" : "admin.asset-disabled",
                Map.of("symbol", symbol));
    }

    private void addAsset(CommandSender sender, String[] args) {
        if (!allowed(sender, "wildmaremarket.admin.manageassets")) return;
        if (args.length < 2) {
            usage(sender, "/marketadmin addasset <symbol>");
            return;
        }
        String symbol = args[1].toUpperCase(Locale.ROOT);
        if (!assetRegistry.addFictional(symbol)) {
            messages.send(sender, "admin.asset-add-failed");
            return;
        }
        messages.send(sender, "admin.asset-enabled", Map.of("symbol", symbol));
    }

    private void removeAsset(CommandSender sender, String[] args) {
        if (!allowed(sender, "wildmaremarket.admin.manageassets")) return;
        if (args.length < 2) {
            usage(sender, "/marketadmin removeasset <symbol>");
            return;
        }
        String symbol = args[1].toUpperCase(Locale.ROOT);
        if (!assetRegistry.remove(symbol)) {
            messages.send(sender, "market.unknown-asset", Map.of("symbol", symbol));
            return;
        }
        messages.send(sender, "admin.asset-disabled", Map.of("symbol", symbol));
    }

    private void resetPortfolio(CommandSender sender, String[] args) {
        if (!allowed(sender, "wildmaremarket.admin.manageplayers")) return;
        OfflinePlayer target = target(sender, args, 1);
        if (target == null) return;
        databaseService.resetPortfolio(target.getUniqueId()).whenComplete((ignored, throwable) -> sync(() -> {
            if (throwable != null) messages.send(sender, "general.internal-error");
            else messages.send(sender, "admin.player-updated");
        }));
    }

    private void modifyHolding(CommandSender sender, String[] args, String operation) {
        if (!allowed(sender, "wildmaremarket.admin.manageplayers")) return;
        if (args.length < 4) {
            usage(sender, "/marketadmin " + operation + " <player> <symbol> <amount>");
            return;
        }
        OfflinePlayer target = target(sender, args, 1);
        if (target == null) return;
        String symbol = args[2].toUpperCase(Locale.ROOT);
        AssetDefinition asset = assetRegistry.getEnabled(symbol).orElse(null);
        if (asset == null) {
            messages.send(sender, "market.unknown-asset", Map.of("symbol", symbol));
            return;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(args[3]);
            if (amount.signum() < 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            messages.send(sender, "general.invalid-number");
            return;
        }
        CompletableFuture<Holding> holdingFuture = databaseService.getHolding(
                target.getUniqueId(), symbol).thenApply(optional -> optional.orElse(null));
        CompletableFuture<MarketQuote> quoteFuture = marketDataService.quote(asset, false);
        holdingFuture.thenCombine(quoteFuture, (holding, quote) -> {
            BigDecimal existing = holding == null ? BigDecimal.ZERO : holding.quantity();
            BigDecimal resulting = switch (operation) {
                case "giveasset" -> existing.add(amount);
                case "takeasset" -> existing.subtract(amount).max(BigDecimal.ZERO);
                default -> amount;
            };
            BigDecimal average = holding == null ? quote.price() : holding.averagePrice();
            return new HoldingChange(resulting, average);
        }).thenCompose(change -> databaseService.setHolding(
                target.getUniqueId(), targetName(target), symbol,
                change.quantity(), change.averagePrice()))
                .whenComplete((ignored, throwable) -> sync(() -> {
                    if (throwable != null) messages.send(sender, "general.internal-error");
                    else messages.send(sender, "admin.player-updated");
                }));
    }

    private void transaction(CommandSender sender, String[] args) {
        if (!allowed(sender, "wildmaremarket.admin.manageplayers")) return;
        OfflinePlayer target = target(sender, args, 1);
        if (target == null) return;
        transactionService.history(target.getUniqueId(), 10).whenComplete((records, throwable) -> sync(() -> {
            if (throwable != null) {
                messages.send(sender, "general.internal-error");
                return;
            }
            messages.sendRaw(sender, messages.raw("admin.recent-transactions-header"),
                    Map.of("player", targetName(target)));
            if (records.isEmpty()) {
                messages.send(sender, "admin.no-transactions");
                return;
            }
            for (TransactionRecord record : records) {
                messages.sendRaw(sender, messages.raw("admin.transaction-line"),
                        Map.of("date", TextUtil.timestamp(record.createdAt()),
                                "type", record.type(), "quantity", record.quantity(),
                                "symbol", record.symbol(), "total", record.total(),
                                "status", record.status()));
            }
        }));
    }

    private void debug(CommandSender sender, String[] args) {
        if (!allowed(sender, "wildmaremarket.admin.debug")) return;
        boolean enabled = args.length > 1
                ? args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true")
                : !plugin.getConfig().getBoolean("debug", false);
        plugin.getConfig().set("debug", enabled);
        plugin.saveConfig();
        messages.send(sender, "admin.debug-updated", Map.of("value", enabled));
    }

    private OfflinePlayer target(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            messages.send(sender, "admin.player-required");
            return null;
        }
        String input = args[index];
        OfflinePlayer target = Bukkit.getPlayerExact(input);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(input);
        if (target == null) {
            try {
                target = Bukkit.getOfflinePlayer(UUID.fromString(input));
            } catch (IllegalArgumentException ignored) {
                messages.send(sender, "admin.unknown-player");
                return null;
            }
        }
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            messages.send(sender, "admin.unknown-player");
            return null;
        }
        return target;
    }

    private String targetName(OfflinePlayer target) {
        return target.getName() == null ? target.getUniqueId().toString() : target.getName();
    }

    private void line(CommandSender sender, String key, Object value) {
        messages.sendRaw(sender, messages.raw("admin.status-line"),
                Map.of("key", key, "value", value));
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "general.usage", Map.of("usage", usage));
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        messages.send(sender, "general.no-permission");
        return false;
    }

    private void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
        if (args.length == 1) return match(SUBCOMMANDS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("refresh", "enable", "disable", "removeasset").contains(sub)) {
            return match(assetRegistry.all().stream().map(AssetDefinition::symbol).toList(), args[1]);
        }
        if (args.length == 2 && sub.equals("provider")) {
            return match(providerRegistry.all().stream().map(MarketDataProvider::id).toList(), args[1]);
        }
        if (args.length == 2 && List.of("resetportfolio", "setholding", "giveasset",
                "takeasset", "transaction").contains(sub)) {
            return match(Bukkit.getOnlinePlayers().stream().map(player -> player.getName()).toList(), args[1]);
        }
        if (args.length == 3 && List.of("setholding", "giveasset", "takeasset").contains(sub)) {
            return match(assetRegistry.enabled().stream().map(AssetDefinition::symbol).toList(), args[2]);
        }
        if (args.length == 2 && sub.equals("debug")) return match(List.of("on", "off"), args[1]);
        return List.of();
    }

    private static List<String> match(List<String> options, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted().toList();
    }

    private record HoldingChange(BigDecimal quantity, BigDecimal averagePrice) {
    }
}
