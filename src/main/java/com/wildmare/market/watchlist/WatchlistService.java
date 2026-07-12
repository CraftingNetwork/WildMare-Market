package com.wildmare.market.watchlist;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.util.PermissionLimit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Manages bounded player watchlists and current quote views. */
public final class WatchlistService {
    private final JavaPlugin plugin;
    private final DatabaseService databaseService;
    private final AssetRegistry assetRegistry;
    private final MarketDataService marketDataService;

    public WatchlistService(JavaPlugin plugin, DatabaseService databaseService,
                            AssetRegistry assetRegistry, MarketDataService marketDataService) {
        this.plugin = plugin;
        this.databaseService = databaseService;
        this.assetRegistry = assetRegistry;
        this.marketDataService = marketDataService;
    }

    public CompletableFuture<Result> add(Player player, String symbol) {
        String normalized = symbol.toUpperCase();
        if (assetRegistry.getEnabled(normalized).isEmpty()) {
            return CompletableFuture.completedFuture(Result.UNKNOWN_ASSET);
        }
        int defaultLimit = plugin.getConfig().getInt("watchlist.default-limit", 20);
        int maximumLimit = plugin.getConfig().getInt("watchlist.maximum-limit", 100);
        int limit = PermissionLimit.resolve(player, "wildmaremarket.limit.watchlist.",
                defaultLimit, maximumLimit);
        return databaseService.getWatchlist(player.getUniqueId()).thenCompose(current -> {
            if (current.contains(normalized)) return CompletableFuture.completedFuture(Result.ALREADY_EXISTS);
            if (current.size() >= limit) return CompletableFuture.completedFuture(Result.FULL);
            return databaseService.addWatchlist(player.getUniqueId(), normalized)
                    .thenApply(added -> added ? Result.ADDED : Result.ALREADY_EXISTS);
        });
    }

    public CompletableFuture<Result> remove(UUID playerId, String symbol) {
        return databaseService.removeWatchlist(playerId, symbol.toUpperCase())
                .thenApply(removed -> removed ? Result.REMOVED : Result.NOT_FOUND);
    }

    public CompletableFuture<Result> toggle(Player player, String symbol) {
        String normalized = symbol.toUpperCase();
        return databaseService.getWatchlist(player.getUniqueId()).thenCompose(current ->
                current.contains(normalized)
                        ? remove(player.getUniqueId(), normalized)
                        : add(player, normalized));
    }

    public CompletableFuture<Boolean> contains(UUID playerId, String symbol) {
        return databaseService.getWatchlist(playerId)
                .thenApply(list -> list.contains(symbol.toUpperCase()));
    }

    public CompletableFuture<List<WatchlistEntry>> entries(UUID playerId, WatchlistSort sort) {
        return databaseService.getWatchlist(playerId).thenCompose(symbols -> {
            List<CompletableFuture<WatchlistEntry>> futures = new ArrayList<>();
            for (String symbol : symbols) {
                futures.add(marketDataService.quote(symbol, false)
                        .handle((quote, throwable) -> new WatchlistEntry(
                                symbol,
                                assetRegistry.get(symbol).map(asset -> asset.name()).orElse(symbol),
                                throwable == null ? quote : null)));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        Comparator<WatchlistEntry> comparator = switch (sort) {
                            case NAME -> Comparator.comparing(WatchlistEntry::name);
                            case PRICE -> Comparator.comparing(
                                    entry -> entry.quote() == null
                                            ? java.math.BigDecimal.ZERO : entry.quote().price());
                            case CHANGE -> Comparator.comparing(
                                    entry -> entry.quote() == null
                                            ? java.math.BigDecimal.ZERO : entry.quote().changePercent());
                        };
                        return futures.stream().map(CompletableFuture::join)
                                .sorted(comparator.reversed()).toList();
                    });
        });
    }

    public enum Result {
        ADDED, REMOVED, ALREADY_EXISTS, NOT_FOUND, FULL, UNKNOWN_ASSET
    }

    public record WatchlistEntry(String symbol, String name, MarketQuote quote) {
    }
}
