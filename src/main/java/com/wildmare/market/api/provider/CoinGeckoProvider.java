package com.wildmare.market.api.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wildmare.market.api.ProviderException;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.HistoryPeriod;
import com.wildmare.market.model.HistoryPoint;
import com.wildmare.market.model.MarketHistory;
import com.wildmare.market.model.MarketQuote;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** CoinGecko REST implementation for cryptocurrency quotes and market history. */
public final class CoinGeckoProvider extends AbstractHttpProvider {
    private final String apiKeyHeader;

    public CoinGeckoProvider(JavaPlugin plugin, ConfigurationSection providerSection,
                             ConfigurationSection httpSection) {
        super(plugin, "coingecko", providerSection, httpSection);
        this.apiKeyHeader = providerSection.getString("api-key-header", "x-cg-demo-api-key");
    }

    @Override
    public CompletableFuture<MarketQuote> fetchQuote(AssetDefinition asset) {
        URI uri = URI.create(baseUrl + "/simple/price?ids=" + encode(asset.apiSymbol())
                + "&vs_currencies=usd&include_24hr_change=true&include_last_updated_at=true");
        return getJson(uri, headers()).thenApply(json -> {
            JsonObject root = json.getAsJsonObject();
            if (!root.has(asset.apiSymbol())) {
                throw new ProviderException("CoinGecko returned no data for " + asset.symbol());
            }
            JsonObject object = root.getAsJsonObject(asset.apiSymbol());
            BigDecimal price = decimal(object, "usd");
            BigDecimal percent = decimal(object, "usd_24h_change");
            BigDecimal change = price.multiply(percent).divide(
                    BigDecimal.valueOf(100), 12, java.math.RoundingMode.HALF_UP);
            long updated = object.has("last_updated_at")
                    ? object.get("last_updated_at").getAsLong() : Instant.now().getEpochSecond();
            BigDecimal previous = price.subtract(change);
            return new MarketQuote(
                    asset.symbol(), id(), price, change, percent,
                    previous, price.max(previous), price.min(previous), previous, BigDecimal.ZERO,
                    Instant.ofEpochSecond(updated), Instant.now()
            );
        });
    }

    @Override
    public CompletableFuture<MarketHistory> fetchHistory(AssetDefinition asset, HistoryPeriod period) {
        long days = Math.max(1L, (long) Math.ceil(period.duration().toHours() / 24.0));
        URI uri = URI.create(baseUrl + "/coins/" + encode(asset.apiSymbol())
                + "/market_chart?vs_currency=usd&days=" + days);
        return getJson(uri, headers()).thenApply(json -> {
            JsonObject root = json.getAsJsonObject();
            if (!root.has("prices")) throw new ProviderException("CoinGecko returned no history");
            JsonArray prices = root.getAsJsonArray("prices");
            List<HistoryPoint> allPoints = new ArrayList<>(prices.size());
            for (int index = 0; index < prices.size(); index++) {
                JsonArray pair = prices.get(index).getAsJsonArray();
                allPoints.add(new HistoryPoint(
                        Instant.ofEpochMilli(pair.get(0).getAsLong()),
                        pair.get(1).getAsBigDecimal()
                ));
            }
            Instant cutoff = Instant.now().minus(period.duration());
            List<HistoryPoint> points = allPoints.stream()
                    .filter(point -> !point.timestamp().isBefore(cutoff))
                    .toList();
            if (points.isEmpty() && !allPoints.isEmpty()) {
                int from = Math.max(0, allPoints.size() - 12);
                points = List.copyOf(allPoints.subList(from, allPoints.size()));
            }
            return new MarketHistory(asset.symbol(), id(), period, points, Instant.now());
        });
    }

    private Map<String, String> headers() {
        if (apiKey == null || apiKey.isBlank()) return Map.of();
        Map<String, String> headers = new HashMap<>();
        headers.put(apiKeyHeader, apiKey);
        return headers;
    }

    private static BigDecimal decimal(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsBigDecimal() : BigDecimal.ZERO;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
