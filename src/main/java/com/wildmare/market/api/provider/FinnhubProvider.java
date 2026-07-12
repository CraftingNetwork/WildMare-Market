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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Finnhub REST implementation for latest quotes and historical candles. */
public final class FinnhubProvider extends AbstractHttpProvider {
    public FinnhubProvider(JavaPlugin plugin, ConfigurationSection providerSection,
                           ConfigurationSection httpSection) {
        super(plugin, "finnhub", providerSection, httpSection);
    }

    @Override
    public CompletableFuture<MarketQuote> fetchQuote(AssetDefinition asset) {
        String symbol = encode(asset.apiSymbol());
        URI uri = URI.create(baseUrl + "/quote?symbol=" + symbol + "&token=" + encode(apiKey));
        return getJson(uri, Map.of()).thenApply(json -> {
            JsonObject object = json.getAsJsonObject();
            BigDecimal price = decimal(object, "c");
            if (price.signum() <= 0) throw new ProviderException("Finnhub returned no price for " + asset.symbol());
            long timestamp = object.has("t") ? object.get("t").getAsLong() : Instant.now().getEpochSecond();
            return new MarketQuote(
                    asset.symbol(), id(), price, decimal(object, "d"), decimal(object, "dp"),
                    decimal(object, "o"), decimal(object, "h"), decimal(object, "l"),
                    decimal(object, "pc"), BigDecimal.ZERO,
                    Instant.ofEpochSecond(timestamp), Instant.now()
            );
        });
    }

    @Override
    public CompletableFuture<MarketHistory> fetchHistory(AssetDefinition asset, HistoryPeriod period) {
        Instant to = Instant.now();
        Instant from = to.minus(period.duration());
        String resolution = resolution(period);
        URI uri = URI.create(baseUrl + "/stock/candle?symbol=" + encode(asset.apiSymbol())
                + "&resolution=" + resolution + "&from=" + from.getEpochSecond()
                + "&to=" + to.getEpochSecond() + "&token=" + encode(apiKey));
        return getJson(uri, Map.of()).thenApply(json -> {
            JsonObject object = json.getAsJsonObject();
            if (!object.has("s") || !"ok".equalsIgnoreCase(object.get("s").getAsString())) {
                throw new ProviderException("Finnhub history is unavailable for " + asset.symbol());
            }
            JsonArray times = object.getAsJsonArray("t");
            JsonArray closes = object.getAsJsonArray("c");
            List<HistoryPoint> points = new ArrayList<>(Math.min(times.size(), closes.size()));
            for (int index = 0; index < Math.min(times.size(), closes.size()); index++) {
                points.add(new HistoryPoint(
                        Instant.ofEpochSecond(times.get(index).getAsLong()),
                        closes.get(index).getAsBigDecimal()
                ));
            }
            return new MarketHistory(asset.symbol(), id(), period, points, Instant.now());
        });
    }

    private static BigDecimal decimal(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsBigDecimal() : BigDecimal.ZERO;
    }

    private static String resolution(HistoryPeriod period) {
        return switch (period) {
            case ONE_HOUR -> "5";
            case ONE_DAY -> "15";
            case SEVEN_DAYS -> "60";
            case THIRTY_DAYS -> "D";
            case NINETY_DAYS, ONE_YEAR -> "D";
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
