package com.wildmare.market.portfolio;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.model.Holding;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.model.PortfolioPosition;
import com.wildmare.market.model.PortfolioSummary;
import com.wildmare.market.util.DecimalUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Calculates holdings, valuation, allocation, and portfolio performance. */
public final class PortfolioService {
    private final DatabaseService databaseService;
    private final AssetRegistry assetRegistry;
    private final MarketDataService marketDataService;

    public PortfolioService(DatabaseService databaseService, AssetRegistry assetRegistry,
                            MarketDataService marketDataService) {
        this.databaseService = databaseService;
        this.assetRegistry = assetRegistry;
        this.marketDataService = marketDataService;
    }

    public CompletableFuture<List<PortfolioPosition>> positions(UUID playerId) {
        return databaseService.getHoldings(playerId).thenCompose(holdings -> {
            List<CompletableFuture<PortfolioPosition>> futures = new ArrayList<>();
            for (Holding holding : holdings) {
                CompletableFuture<MarketQuote> quoteFuture = assetRegistry.getEnabled(holding.symbol())
                        .map(asset -> marketDataService.quote(asset, false))
                        .orElseGet(() -> CompletableFuture.completedFuture(null));
                futures.add(quoteFuture.handle((quote, throwable) -> {
                    MarketQuote resolved = throwable == null ? quote : null;
                    BigDecimal price = resolved == null ? BigDecimal.ZERO : resolved.price();
                    BigDecimal value = price.multiply(holding.quantity());
                    BigDecimal cost = holding.costBasis();
                    BigDecimal profit = value.subtract(cost);
                    BigDecimal percent = DecimalUtil.percentage(profit, cost);
                    return new PortfolioPosition(holding, resolved, value, cost, profit, percent);
                }));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> futures.stream().map(CompletableFuture::join)
                            .sorted(Comparator.comparing(position -> position.holding().symbol()))
                            .toList());
        });
    }

    public CompletableFuture<PortfolioSummary> summary(UUID playerId) {
        CompletableFuture<List<PortfolioPosition>> positions = positions(playerId);
        CompletableFuture<BigDecimal> realized = databaseService.getRealizedProfit(playerId);
        return positions.thenCombine(realized, (items, realizedProfit) -> {
            BigDecimal value = BigDecimal.ZERO;
            BigDecimal invested = BigDecimal.ZERO;
            BigDecimal unrealized = BigDecimal.ZERO;
            BigDecimal daily = BigDecimal.ZERO;
            PortfolioPosition best = null;
            PortfolioPosition worst = null;
            List<Holding> holdings = new ArrayList<>();
            for (PortfolioPosition position : items) {
                holdings.add(position.holding());
                value = value.add(position.positionValue());
                invested = invested.add(position.costBasis());
                unrealized = unrealized.add(position.profit());
                if (position.quote() != null) {
                    daily = daily.add(position.quote().change()
                            .multiply(position.holding().quantity()));
                }
                if (best == null || position.profitPercent().compareTo(best.profitPercent()) > 0) {
                    best = position;
                }
                if (worst == null || position.profitPercent().compareTo(worst.profitPercent()) < 0) {
                    worst = position;
                }
            }
            return new PortfolioSummary(
                    holdings, value, invested, unrealized, realizedProfit, daily,
                    best == null ? "—" : best.holding().symbol(),
                    worst == null ? "—" : worst.holding().symbol()
            );
        });
    }
}
