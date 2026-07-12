package com.wildmare.market.market;

import com.wildmare.market.api.MarketDataProvider;
import com.wildmare.market.api.ProviderException;
import com.wildmare.market.api.provider.ProviderRegistry;
import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.HistoryPeriod;
import com.wildmare.market.model.MarketHistory;
import com.wildmare.market.model.MarketQuote;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Coordinates providers, shared cache, fallback, persistence, and refresh telemetry. */
public final class MarketDataService {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final AssetRegistry assetRegistry;
    private final ProviderRegistry providerRegistry;
    private final DatabaseService databaseService;
    private final Map<String, MarketQuote> quoteCache = new ConcurrentHashMap<>();
    private final Map<String, MarketHistory> historyCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<MarketQuote>> quoteRequests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<MarketHistory>> historyRequests = new ConcurrentHashMap<>();
    private final Map<String, Instant> providerFailures = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recentTradeSymbols = new ConcurrentLinkedDeque<>();
    private final AtomicLong successfulRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();

    public MarketDataService(JavaPlugin plugin, ConfigManager configManager,
                             AssetRegistry assetRegistry, ProviderRegistry providerRegistry,
                             DatabaseService databaseService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.assetRegistry = assetRegistry;
        this.providerRegistry = providerRegistry;
        this.databaseService = databaseService;
    }

    public CompletableFuture<Void> loadPersistedCache() {
        CompletableFuture<Void> quotes = plugin.getConfig().getBoolean("cache.persist-market-cache", true)
                ? databaseService.loadMarketQuotes().thenAccept(quoteCache::putAll)
                : CompletableFuture.completedFuture(null);
        CompletableFuture<Void> recent = databaseService.getRecentlyTradedSymbols(100)
                .thenAccept(symbols -> {
                    recentTradeSymbols.clear();
                    symbols.forEach(recentTradeSymbols::addLast);
                });
        return CompletableFuture.allOf(quotes, recent);
    }

    public CompletableFuture<MarketQuote> quote(String symbol, boolean forceRefresh) {
        AssetDefinition asset = assetRegistry.getEnabled(symbol)
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset: " + symbol));
        return quote(asset, forceRefresh);
    }

    public CompletableFuture<MarketQuote> quote(AssetDefinition asset, boolean forceRefresh) {
        MarketQuote cached = quoteCache.get(asset.symbol());
        if (!forceRefresh && cached != null && isQuoteFresh(cached)) {
            return CompletableFuture.completedFuture(cached);
        }
        return quoteRequests.computeIfAbsent(asset.symbol(), ignored ->
                fetchQuote(asset).whenComplete((quote, throwable) ->
                        quoteRequests.remove(asset.symbol())));
    }

    private CompletableFuture<MarketQuote> fetchQuote(AssetDefinition asset) {
        Optional<MarketDataProvider> resolved = providerRegistry.resolve(asset);
        if (resolved.isEmpty()) {
            failedRequests.incrementAndGet();
            return CompletableFuture.failedFuture(new ProviderException(
                    "No enabled provider is available for " + asset.symbol()));
        }
        MarketDataProvider provider = resolved.get();
        return provider.fetchQuote(asset)
                .exceptionallyCompose(throwable -> {
                    recordProviderFailure(provider.id(), throwable);
                    Optional<MarketDataProvider> fallback = providerRegistry.fallback();
                    if (fallback.isPresent() && !fallback.get().id().equals(provider.id())) {
                        return fallback.get().fetchQuote(asset);
                    }
                    return CompletableFuture.failedFuture(throwable);
                })
                .thenApply(quote -> quote.withCachedAt(Instant.now()))
                .whenComplete((quote, throwable) -> {
                    if (throwable != null) {
                        failedRequests.incrementAndGet();
                    } else {
                        successfulRequests.incrementAndGet();
                        quoteCache.put(asset.symbol(), quote);
                        if (plugin.getConfig().getBoolean("cache.persist-market-cache", true)) {
                            databaseService.saveMarketQuote(quote);
                        }
                    }
                });
    }

    public CompletableFuture<MarketHistory> history(String symbol, HistoryPeriod period,
                                                     boolean forceRefresh) {
        AssetDefinition asset = assetRegistry.getEnabled(symbol)
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset: " + symbol));
        String key = asset.symbol() + ":" + period.key();
        MarketHistory cached = historyCache.get(key);
        if (!forceRefresh && cached != null && isHistoryFresh(cached)) {
            return CompletableFuture.completedFuture(cached);
        }
        return historyRequests.computeIfAbsent(key, ignored ->
                fetchHistory(asset, period).whenComplete((history, throwable) ->
                        historyRequests.remove(key)));
    }

    private CompletableFuture<MarketHistory> fetchHistory(AssetDefinition asset, HistoryPeriod period) {
        Optional<MarketDataProvider> resolved = providerRegistry.resolve(asset);
        CompletableFuture<MarketHistory> providerFuture;
        if (resolved.isEmpty()) {
            providerFuture = CompletableFuture.failedFuture(new ProviderException(
                    "No enabled provider is available for " + asset.symbol()));
        } else {
            MarketDataProvider provider = resolved.get();
            providerFuture = provider.fetchHistory(asset, period)
                    .exceptionallyCompose(throwable -> {
                        recordProviderFailure(provider.id(), throwable);
                        Optional<MarketDataProvider> fallback = providerRegistry.fallback();
                        if (fallback.isPresent() && !fallback.get().id().equals(provider.id())) {
                            return fallback.get().fetchHistory(asset, period);
                        }
                        return CompletableFuture.failedFuture(throwable);
                    });
        }
        return providerFuture.exceptionallyCompose(throwable ->
                        databaseService.loadMarketHistory(asset.symbol(), period)
                                .thenCompose(cached -> cached
                                        .map(CompletableFuture::completedFuture)
                                        .orElseGet(() -> CompletableFuture.failedFuture(throwable))))
                .thenApply(history -> history.withCachedAt(Instant.now()))
                .whenComplete((history, throwable) -> {
                    if (throwable == null) {
                        historyCache.put(asset.symbol() + ":" + period.key(), history);
                        int maximum = plugin.getConfig().getInt(
                                "cache.maximum-history-points", 500);
                        databaseService.saveMarketHistory(history, maximum)
                                .exceptionally(databaseFailure -> {
                                    plugin.getLogger().log(Level.FINE,
                                            "Unable to persist market history", databaseFailure);
                                    return null;
                                });
                    }
                });
    }

    public CompletableFuture<RefreshResult> refresh(Collection<AssetDefinition> assets) {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (AssetDefinition asset : assets) {
            futures.add(quote(asset, true)
                    .thenApply(ignored -> true)
                    .exceptionally(throwable -> false));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    long success = futures.stream().filter(CompletableFuture::join).count();
                    return new RefreshResult(success, futures.size() - success);
                });
    }

    public CompletableFuture<RefreshResult> refreshAll() {
        return refresh(assetRegistry.enabled());
    }

    public Optional<MarketQuote> cachedQuote(String symbol) {
        if (symbol == null) return Optional.empty();
        return Optional.ofNullable(quoteCache.get(symbol.toUpperCase()));
    }

    public Map<String, MarketQuote> cachedQuotes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(quoteCache));
    }

    public void recordTrade(String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        String normalized = symbol.toUpperCase(java.util.Locale.ROOT);
        recentTradeSymbols.remove(normalized);
        recentTradeSymbols.addFirst(normalized);
        while (recentTradeSymbols.size() > 100) recentTradeSymbols.pollLast();
    }

    public List<AssetDefinition> recentlyTraded(int limit) {
        return recentTradeSymbols.stream()
                .limit(Math.max(1, limit))
                .map(assetRegistry::getEnabled)
                .flatMap(Optional::stream)
                .toList();
    }

    public List<MarketQuote> trending(int limit) {
        return quoteCache.values().stream()
                .sorted((left, right) ->
                        right.changePercent().abs().compareTo(left.changePercent().abs()))
                .limit(limit)
                .toList();
    }

    public List<MarketQuote> gainers(int limit) {
        return quoteCache.values().stream()
                .sorted((left, right) -> right.changePercent().compareTo(left.changePercent()))
                .limit(limit)
                .toList();
    }

    public List<MarketQuote> losers(int limit) {
        return quoteCache.values().stream()
                .sorted((left, right) -> left.changePercent().compareTo(right.changePercent()))
                .limit(limit)
                .toList();
    }

    public boolean isQuoteFresh(MarketQuote quote) {
        long ttl = plugin.getConfig().getLong("cache.quote-ttl-seconds", 30L);
        return Duration.between(quote.cachedAt(), Instant.now()).getSeconds() <= ttl;
    }

    public boolean isTradePriceValid(MarketQuote quote) {
        if (quote == null || !quote.isTradable()) return false;
        if (plugin.getConfig().getBoolean("trading.allow-trading-with-stale-price", false)) return true;
        long maxAge = plugin.getConfig().getLong("trading.max-price-age-seconds", 120L);
        return Duration.between(quote.sourceTimestamp(), Instant.now()).getSeconds() <= maxAge;
    }

    private boolean isHistoryFresh(MarketHistory history) {
        long ttl = plugin.getConfig().getLong("cache.history-ttl-seconds", 300L);
        return Duration.between(history.cachedAt(), Instant.now()).getSeconds() <= ttl;
    }

    public void clear() {
        quoteCache.clear();
        historyCache.clear();
        recentTradeSymbols.clear();
    }

    public int quoteCacheSize() {
        return quoteCache.size();
    }

    public int historyCacheSize() {
        return historyCache.size();
    }

    public long successfulRequests() {
        return successfulRequests.get();
    }

    public long failedRequests() {
        return failedRequests.get();
    }

    public Map<String, Instant> providerFailures() {
        return Map.copyOf(providerFailures);
    }

    private void recordProviderFailure(String providerId, Throwable throwable) {
        Instant previous = providerFailures.put(providerId, Instant.now());
        if (previous == null || Duration.between(previous, Instant.now()).toSeconds() >= 60) {
            plugin.getLogger().log(Level.WARNING,
                    "Market provider " + providerId + " is unavailable: " + rootMessage(throwable));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record RefreshResult(long successful, long failed) {
        public boolean fullySuccessful() {
            return failed == 0;
        }
    }
}
