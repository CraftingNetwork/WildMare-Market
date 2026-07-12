package com.wildmare.market.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/** Loads, reloads, and persists the plugin YAML configuration set. */
public final class ConfigManager {
    private static final String[] FILES = {
            "messages.yml", "menus.yml", "assets.yml", "providers.yml",
            "database.yml", "market-hours.yml", "sounds.yml", "webhooks.yml"
    };

    private final JavaPlugin plugin;
    private final Map<String, YamlConfiguration> configurations = new LinkedHashMap<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void load() {
        plugin.saveDefaultConfig();
        for (String name : FILES) {
            File file = new File(plugin.getDataFolder(), name);
            if (!file.exists()) {
                plugin.saveResource(name, false);
            }
            configurations.put(name, YamlConfiguration.loadConfiguration(file));
        }
    }

    public void reload() {
        plugin.reloadConfig();
        configurations.clear();
        load();
    }

    public YamlConfiguration get(String name) {
        YamlConfiguration configuration = configurations.get(name);
        if (configuration == null) {
            throw new IllegalArgumentException("Unknown configuration file: " + name);
        }
        return configuration;
    }

    public void save(String name) {
        YamlConfiguration configuration = get(name);
        try {
            configuration.save(new File(plugin.getDataFolder(), name));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Unable to save " + name, exception);
        }
    }

    public JavaPlugin plugin() {
        return plugin;
    }
}
