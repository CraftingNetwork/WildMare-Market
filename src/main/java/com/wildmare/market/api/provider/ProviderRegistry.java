package com.wildmare.market.api.provider;

import com.wildmare.market.api.MarketDataProvider;
import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.AssetType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Holds configured market-data providers and supports safe live reloads. */
public final class ProviderRegistry implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Map<String, MarketDataProvider> providers = new LinkedHashMap<>();
    private final Map<AssetType, String> defaults = new LinkedHashMap<>();
    private String fallbackProvider = "";

    public ProviderRegistry(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        reload();
    }

    public synchronized void reload() {
        closeProviders();
        providers.clear();
        defaults.clear();

        YamlConfiguration config = configManager.get("providers.yml");
        ConfigurationSection http = required(config, "http");
        ConfigurationSection providerRoot = required(config, "providers");
        register(new FinnhubProvider(plugin, required(providerRoot, "finnhub"), http));
        register(new CoinGeckoProvider(plugin, required(providerRoot, "coingecko"), http));
        register(new FictionalProvider(required(providerRoot, "fictional")));

        ConfigurationSection defaultSection = required(config, "defaults");
        defaults.put(AssetType.STOCK, normalize(defaultSection.getString("stocks", "finnhub")));
        defaults.put(AssetType.ETF, normalize(defaultSection.getString("etfs", "finnhub")));
        defaults.put(AssetType.INDEX, normalize(defaultSection.getString("indexes", "finnhub")));
        defaults.put(AssetType.COMMODITY, normalize(defaultSection.getString("commodities", "finnhub")));
        defaults.put(AssetType.FOREX, normalize(defaultSection.getString("forex", "finnhub")));
        defaults.put(AssetType.CRYPTO, normalize(defaultSection.getString("crypto", "coingecko")));
        defaults.put(AssetType.FICTIONAL, normalize(defaultSection.getString("fictional", "fictional")));
        fallbackProvider = normalize(config.getString("fallback-provider", ""));
    }

    public synchronized void register(MarketDataProvider provider) {
        providers.put(normalize(provider.id()), provider);
    }

    public synchronized Optional<MarketDataProvider> resolve(AssetDefinition asset) {
        MarketDataProvider direct = providers.get(normalize(asset.provider()));
        if (direct != null && direct.isEnabled()) return Optional.of(direct);
        MarketDataProvider byType = providers.get(defaults.get(asset.type()));
        if (byType != null && byType.isEnabled()) return Optional.of(byType);
        return Optional.empty();
    }

    public synchronized Optional<MarketDataProvider> fallback() {
        MarketDataProvider provider = providers.get(fallbackProvider);
        return provider != null && provider.isEnabled() ? Optional.of(provider) : Optional.empty();
    }

    public synchronized Optional<MarketDataProvider> get(String id) {
        return Optional.ofNullable(providers.get(normalize(id)));
    }

    public synchronized Collection<MarketDataProvider> all() {
        return java.util.List.copyOf(providers.values());
    }

    private static ConfigurationSection required(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) throw new IllegalStateException("Missing configuration section: " + path);
        return section;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private void closeProviders() {
        providers.values().forEach(provider -> {
            try {
                provider.close();
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public synchronized void close() {
        closeProviders();
        providers.clear();
    }
}
