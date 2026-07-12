package com.wildmare.market.market;

import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.AssetType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Loads, validates, searches, enables, and persists configured assets. */
public final class AssetRegistry {
    private final ConfigManager configManager;
    private final Map<String, AssetDefinition> assets = new LinkedHashMap<>();

    public AssetRegistry(ConfigManager configManager) {
        this.configManager = configManager;
        load();
    }

    public void load() {
        assets.clear();
        YamlConfiguration config = configManager.get("assets.yml");
        ConfigurationSection root = config.getConfigurationSection("assets");
        if (root == null) return;
        for (String rawSymbol : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawSymbol);
            if (section == null) continue;
            try {
                String symbol = rawSymbol.toUpperCase(Locale.ROOT);
                AssetType type = AssetType.parse(section.getString("type", "STOCK"));
                Material material = Material.matchMaterial(section.getString("material", "PAPER"));
                AssetDefinition definition = new AssetDefinition(
                        symbol,
                        section.getString("name", symbol),
                        type,
                        section.getString("provider", ""),
                        section.getString("api-symbol", symbol),
                        section.getBoolean("enabled", true),
                        section.getBoolean("fractional", true),
                        material,
                        section.getInt("custom-model-data", 0),
                        BigDecimal.valueOf(section.getDouble("initial-price", 0.0))
                );
                assets.put(symbol, definition);
            } catch (Exception exception) {
                configManager.plugin().getLogger().warning(
                        "Skipping invalid asset " + rawSymbol + ": " + exception.getMessage());
            }
        }
    }

    public Optional<AssetDefinition> get(String symbol) {
        if (symbol == null) return Optional.empty();
        return Optional.ofNullable(assets.get(symbol.toUpperCase(Locale.ROOT)));
    }

    public Optional<AssetDefinition> getEnabled(String symbol) {
        return get(symbol).filter(AssetDefinition::enabled);
    }

    public List<AssetDefinition> enabled() {
        return assets.values().stream()
                .filter(AssetDefinition::enabled)
                .sorted(Comparator.comparing(AssetDefinition::symbol))
                .toList();
    }

    public List<AssetDefinition> enabledByType(AssetType type) {
        return assets.values().stream()
                .filter(AssetDefinition::enabled)
                .filter(asset -> asset.type() == type)
                .sorted(Comparator.comparing(AssetDefinition::symbol))
                .toList();
    }

    public List<AssetDefinition> search(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return assets.values().stream()
                .filter(AssetDefinition::enabled)
                .filter(asset -> asset.symbol().toLowerCase(Locale.ROOT).contains(normalized)
                        || asset.name().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparing(AssetDefinition::symbol))
                .toList();
    }

    public Collection<AssetDefinition> all() {
        return List.copyOf(assets.values());
    }

    public boolean setEnabled(String symbol, boolean enabled) {
        Optional<AssetDefinition> existing = get(symbol);
        if (existing.isEmpty()) return false;
        String normalized = existing.get().symbol();
        YamlConfiguration config = configManager.get("assets.yml");
        config.set("assets." + normalized + ".enabled", enabled);
        configManager.save("assets.yml");
        load();
        return true;
    }

    public boolean addFictional(String rawSymbol) {
        String symbol = rawSymbol.toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9._-]{1,16}") || assets.containsKey(symbol)) return false;
        YamlConfiguration config = configManager.get("assets.yml");
        String path = "assets." + symbol;
        config.set(path + ".enabled", true);
        config.set(path + ".name", symbol + " Virtual Asset");
        config.set(path + ".type", "FICTIONAL");
        config.set(path + ".provider", "fictional");
        config.set(path + ".api-symbol", symbol);
        config.set(path + ".fractional", true);
        config.set(path + ".initial-price", 100.0);
        config.set(path + ".material", "PURPLE_DYE");
        config.set(path + ".custom-model-data", 0);
        configManager.save("assets.yml");
        load();
        return true;
    }

    public boolean remove(String rawSymbol) {
        String symbol = rawSymbol.toUpperCase(Locale.ROOT);
        if (!assets.containsKey(symbol)) return false;
        YamlConfiguration config = configManager.get("assets.yml");
        config.set("assets." + symbol, null);
        configManager.save("assets.yml");
        load();
        return true;
    }
}
