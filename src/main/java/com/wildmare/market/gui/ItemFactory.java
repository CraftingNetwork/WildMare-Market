package com.wildmare.market.gui;

import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Creates MiniMessage item names, lore, model data, and persistent action tags. */
public final class ItemFactory {
    private final ConfigManager configManager;
    private final NamespacedKey actionKey;
    private final NamespacedKey symbolKey;
    private final NamespacedKey valueKey;
    private final NamespacedKey secondaryKey;

    public ItemFactory(JavaPlugin plugin, ConfigManager configManager) {
        this.configManager = configManager;
        this.actionKey = new NamespacedKey(plugin, "action");
        this.symbolKey = new NamespacedKey(plugin, "symbol");
        this.valueKey = new NamespacedKey(plugin, "value");
        this.secondaryKey = new NamespacedKey(plugin, "secondary");
    }

    public ItemStack fromConfig(String path, Map<String, ?> placeholders) {
        ConfigurationSection section = configManager.get("menus.yml").getConfigurationSection(path);
        if (section == null) {
            return create(Material.BARRIER, "<red>Missing menu item: " + path + "</red>",
                    List.of(), 0);
        }
        Material material = material(section.getString("material", "PAPER"));
        String name = section.getString("name", " ");
        List<String> lore = section.getStringList("lore");
        int customModelData = section.getInt("custom-model-data", 0);
        return create(material, TextUtil.replace(name, placeholders),
                lore.stream().map(line -> TextUtil.replace(line, placeholders)).toList(),
                customModelData);
    }

    public ItemStack create(Material material, String name, List<String> lore, int customModelData) {
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(TextUtil.component(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore.stream().map(TextUtil::component).toList());
        }
        if (customModelData > 0) meta.setCustomModelData(customModelData);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack withAction(ItemStack item, String action) {
        return withTags(item, action, null, null, null);
    }

    public ItemStack withTags(ItemStack item, String action, String symbol,
                              String value, String secondary) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (action != null) container.set(actionKey, PersistentDataType.STRING, action);
        if (symbol != null) container.set(symbolKey, PersistentDataType.STRING, symbol);
        if (value != null) container.set(valueKey, PersistentDataType.STRING, value);
        if (secondary != null) container.set(secondaryKey, PersistentDataType.STRING, secondary);
        item.setItemMeta(meta);
        return item;
    }

    public String action(ItemStack item) {
        return tag(item, actionKey);
    }

    public String symbol(ItemStack item) {
        return tag(item, symbolKey);
    }

    public String value(ItemStack item) {
        return tag(item, valueKey);
    }

    public String secondary(ItemStack item) {
        return tag(item, secondaryKey);
    }

    private String tag(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static Material material(String raw) {
        if (raw == null) return Material.PAPER;
        Material matched = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        return matched == null ? Material.PAPER : matched;
    }
}
