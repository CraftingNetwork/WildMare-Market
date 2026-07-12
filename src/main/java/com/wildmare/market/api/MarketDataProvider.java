package com.wildmare.market.api;

import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.HistoryPeriod;
import com.wildmare.market.model.MarketHistory;
import com.wildmare.market.model.MarketQuote;

import java.util.concurrent.CompletableFuture;

/** Asynchronous abstraction for quote and historical market data providers. */
public interface MarketDataProvider extends AutoCloseable {
    String id();

    boolean isEnabled();

    CompletableFuture<MarketQuote> fetchQuote(AssetDefinition asset);

    CompletableFuture<MarketHistory> fetchHistory(AssetDefinition asset, HistoryPeriod period);

    default String status() {
        return isEnabled() ? "AVAILABLE" : "DISABLED";
    }

    @Override
    default void close() {
    }
}
