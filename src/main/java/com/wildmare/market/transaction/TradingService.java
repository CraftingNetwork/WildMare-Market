package com.wildmare.market.transaction;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.economy.EconomyService;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.market.MarketHoursService;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.model.TradeRequest;
import com.wildmare.market.model.TradeResult;
import com.wildmare.market.model.TradeSide;
import com.wildmare.market.model.TransactionRecord;
import com.wildmare.market.model.TransactionStatus;
import com.wildmare.market.model.TransactionType;
import com.wildmare.market.util.DecimalUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;

/** Validates and serializes safe Vault-backed virtual asset transactions. */
public final class TradingService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final AssetRegistry assetRegistry;
    private final MarketDataService marketDataService;
    private final MarketHoursService marketHoursService;
    private final DatabaseService databaseService;
    private final EconomyService economyService;
    private final TransactionService transactionService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Semaphore> playerLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTradeAt = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> processedRequests = new ConcurrentHashMap<>();

    public TradingService(JavaPlugin plugin, AssetRegistry assetRegistry,
                          MarketDataService marketDataService, MarketHoursService marketHoursService,
                          DatabaseService databaseService, EconomyService economyService,
                          TransactionService transactionService) {
        this.plugin = plugin;
        this.assetRegistry = assetRegistry;
        this.marketDataService = marketDataService;
        this.marketHoursService = marketHoursService;
        this.databaseService = databaseService;
        this.economyService = economyService;
        this.transactionService = transactionService;
    }

    public CompletableFuture<TradeRequest> prepare(Player player, String symbol,
                                                   TradeSide side, BigDecimal quantity) {
        return marketDataService.quote(symbol, false).thenApply(quote ->
                new TradeRequest(UUID.randomUUID(), player.getUniqueId(), player.getName(),
                        symbol.toUpperCase(), side, quantity, quote, Instant.now()));
    }


    /** Records an abandoned confirmation exactly once and prevents later execution. */
    public CompletableFuture<Boolean> cancel(TradeRequest request) {
        if (request == null || !claimRequest(request.requestId())) {
            return CompletableFuture.completedFuture(false);
        }
        TransactionRecord cancelled = new TransactionRecord(
                UUID.randomUUID(), request.playerId(), request.symbol(),
                request.side() == TradeSide.BUY ? TransactionType.BUY : TransactionType.SELL,
                request.quantity() == null ? BigDecimal.ZERO : request.quantity(),
                request.quote() == null ? BigDecimal.ZERO : request.quote().price(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                TransactionStatus.CANCELLED, "trade.cancelled",
                request.quote() == null ? Instant.now() : request.quote().sourceTimestamp(),
                Instant.now());
        return transactionService.log(cancelled)
                .thenApply(ignored -> true)
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Unable to log cancelled trade", throwable);
                    return false;
                });
    }

    public CompletableFuture<TradeResult> execute(TradeRequest request) {
        Semaphore lock = playerLocks.computeIfAbsent(request.playerId(), ignored -> new Semaphore(1));
        return CompletableFuture.runAsync(() -> {
            try {
                lock.acquire();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Trade lock interrupted", exception);
            }
        }, executor).thenCompose(ignored -> executeLocked(request))
                .whenComplete((result, throwable) -> lock.release());
    }

    private CompletableFuture<TradeResult> executeLocked(TradeRequest request) {
        if (!claimRequest(request.requestId())) {
            return completedFailure("trade.duplicate", request);
        }

        AssetDefinition asset = assetRegistry.getEnabled(request.symbol()).orElse(null);
        if (asset == null) return completedFailure("market.unknown-asset", request);
        if (!plugin.getConfig().getBoolean("trading.enabled", true)) {
            return completedFailure("general.feature-disabled", request);
        }
        if (request.quantity() == null || request.quantity().signum() <= 0) {
            return completedFailure("trade.invalid-quantity", request);
        }

        Player online = Bukkit.getPlayer(request.playerId());
        if (online == null || !online.isOnline()) {
            return completedFailure("trade.failed", request);
        }
        String permission = request.side() == TradeSide.BUY
                ? "wildmaremarket.buy" : "wildmaremarket.sell";
        if (!online.hasPermission("wildmaremarket.trade") || !online.hasPermission(permission)) {
            return completedFailure("general.no-permission", request);
        }
        if (DecimalUtil.hasFraction(request.quantity())
                && (!plugin.getConfig().getBoolean("trading.fractional-enabled", true)
                || !asset.fractional()
                || !online.hasPermission("wildmaremarket.trade.fractional"))) {
            return completedFailure("trade.fractional-denied", request);
        }
        if (!marketHoursService.canTrade(asset)) {
            return completedFailure("trade.market-closed", request);
        }

        MarketQuote quote = request.quote();
        if (quote == null || !marketDataService.isTradePriceValid(quote)) {
            return completedFailure("market.stale-price", request);
        }
        long confirmationTimeout = plugin.getConfig().getLong(
                "trading.confirmation-timeout-seconds", 30L);
        if (Duration.between(request.createdAt(), Instant.now()).getSeconds() > confirmationTimeout) {
            return completedFailure("trade.confirmation-expired", request);
        }

        long cooldown = plugin.getConfig().getLong("trading.cooldown-milliseconds", 1000L);
        long now = System.currentTimeMillis();
        Long previous = lastTradeAt.get(request.playerId());
        if (previous != null && now - previous < cooldown) {
            return completedFailure("trade.cooldown", request);
        }
        lastTradeAt.put(request.playerId(), now);

        BigDecimal subtotal = quote.price().multiply(request.quantity());
        BigDecimal feePercent = BigDecimal.valueOf(
                plugin.getConfig().getDouble("trading.fee-percent", 0.5));
        BigDecimal minimumFee = BigDecimal.valueOf(
                plugin.getConfig().getDouble("trading.minimum-fee", 0.01));
        BigDecimal fee = DecimalUtil.fee(subtotal, feePercent, minimumFee);
        BigDecimal total = request.side() == TradeSide.BUY
                ? subtotal.add(fee)
                : subtotal.subtract(fee);
        if (request.side() == TradeSide.SELL && total.signum() <= 0) {
            return completedFailure("trade.below-minimum", request);
        }

        BigDecimal minimum = BigDecimal.valueOf(
                plugin.getConfig().getDouble("trading.minimum-transaction-value", 1.0));
        BigDecimal maximum = BigDecimal.valueOf(
                plugin.getConfig().getDouble("trading.maximum-transaction-value", 1_000_000.0));
        if (subtotal.compareTo(minimum) < 0) return completedFailure("trade.below-minimum", request);
        if (subtotal.compareTo(maximum) > 0) return completedFailure("trade.above-maximum", request);

        return request.side() == TradeSide.BUY
                ? buy(request, subtotal, fee, total)
                : sell(request, subtotal, fee, total);
    }

    private CompletableFuture<TradeResult> buy(TradeRequest request, BigDecimal subtotal,
                                                BigDecimal fee, BigDecimal total) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(request.playerId());
        return economyService.balance(player).thenCompose(balance -> {
            if (BigDecimal.valueOf(balance).compareTo(total) < 0) {
                return completedFailure("trade.insufficient-balance", request);
            }
            return economyService.withdraw(player, total.doubleValue()).thenCompose(withdrawn -> {
                if (!withdrawn) return completedFailure("trade.economy-failure", request);
                TransactionRecord record = completedRecord(request, subtotal, fee, total);
                return databaseService.applyBuy(record, request.playerName())
                        .thenApply(ignored -> {
                            marketDataService.recordTrade(request.symbol());
                            return new TradeResult(
                                    true, "trade.purchase", record.transactionId(), request.symbol(),
                                    request.quantity(), total, BigDecimal.ZERO);
                        })
                        .exceptionallyCompose(throwable ->
                                economyService.deposit(player, total.doubleValue())
                                        .thenCompose(refunded -> {
                                            if (!refunded) {
                                                plugin.getLogger().severe(
                                                        "CRITICAL: failed to refund economy after database buy failure for "
                                                                + request.playerId());
                                            }
                                            plugin.getLogger().log(Level.SEVERE,
                                                    "Buy database transaction failed and was compensated", throwable);
                                            return completedFailure("trade.failed", request);
                                        }));
            });
        });
    }

    private CompletableFuture<TradeResult> sell(TradeRequest request, BigDecimal subtotal,
                                                 BigDecimal fee, BigDecimal proceeds) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(request.playerId());
        return databaseService.getHolding(request.playerId(), request.symbol()).thenCompose(optional -> {
            if (optional.isEmpty()
                    || optional.get().quantity().compareTo(request.quantity()) < 0) {
                return completedFailure("trade.insufficient-holdings", request);
            }
            return economyService.deposit(player, proceeds.doubleValue()).thenCompose(deposited -> {
                if (!deposited) return completedFailure("trade.economy-failure", request);
                TransactionRecord record = completedRecord(request, subtotal, fee, proceeds);
                return databaseService.applySell(record, request.playerName())
                        .thenApply(realized -> {
                            marketDataService.recordTrade(request.symbol());
                            return new TradeResult(
                                    true, "trade.sale", record.transactionId(), request.symbol(),
                                    request.quantity(), proceeds, realized);
                        })
                        .exceptionallyCompose(throwable ->
                                economyService.withdraw(player, proceeds.doubleValue())
                                        .thenCompose(reversed -> {
                                            if (!reversed) {
                                                plugin.getLogger().severe(
                                                        "CRITICAL: failed to reverse economy deposit after database sell failure for "
                                                                + request.playerId());
                                            }
                                            plugin.getLogger().log(Level.SEVERE,
                                                    "Sell database transaction failed and was compensated", throwable);
                                            return completedFailure("trade.failed", request);
                                        }));
            });
        });
    }

    private TransactionRecord completedRecord(TradeRequest request, BigDecimal subtotal,
                                              BigDecimal fee, BigDecimal total) {
        return new TransactionRecord(
                UUID.randomUUID(), request.playerId(), request.symbol(),
                request.side() == TradeSide.BUY ? TransactionType.BUY : TransactionType.SELL,
                request.quantity(), request.quote().price(), subtotal, fee, total,
                BigDecimal.ZERO, TransactionStatus.COMPLETED, null,
                request.quote().sourceTimestamp(), Instant.now()
        );
    }

    private CompletableFuture<TradeResult> completedFailure(String messageKey, TradeRequest request) {
        TransactionRecord failure = new TransactionRecord(
                UUID.randomUUID(), request.playerId(), request.symbol(),
                request.side() == TradeSide.BUY ? TransactionType.BUY : TransactionType.SELL,
                request.quantity() == null ? BigDecimal.ZERO : request.quantity(),
                request.quote() == null ? BigDecimal.ZERO : request.quote().price(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                TransactionStatus.FAILED, messageKey,
                request.quote() == null ? Instant.now() : request.quote().sourceTimestamp(),
                Instant.now()
        );
        TradeResult result = TradeResult.failed(messageKey, request.symbol(), request.quantity());
        return transactionService.logFailure(failure).handle((ignored, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.WARNING, "Unable to log failed trade", throwable);
            }
            return result;
        });
    }

    private boolean claimRequest(UUID requestId) {
        boolean claimed = processedRequests.putIfAbsent(requestId, Instant.now()) == null;
        if (claimed) pruneState();
        return claimed;
    }

    private void pruneState() {
        if (processedRequests.size() > 10_000) {
            long timeout = Math.max(60L, plugin.getConfig().getLong(
                    "trading.confirmation-timeout-seconds", 30L) + 60L);
            Instant cutoff = Instant.now().minusSeconds(timeout);
            processedRequests.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
        if (lastTradeAt.size() > 10_000) {
            long cutoff = System.currentTimeMillis() - Math.max(60_000L,
                    plugin.getConfig().getLong("trading.cooldown-milliseconds", 1000L) * 2L);
            lastTradeAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
