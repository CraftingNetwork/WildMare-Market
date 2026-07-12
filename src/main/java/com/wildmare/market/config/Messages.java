package com.wildmare.market.config;

import com.wildmare.market.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Renders configuration-backed MiniMessage text for command senders. */
public final class Messages {
    private final ConfigManager configManager;

    public Messages(ConfigManager configManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
    }

    public Component component(String key) {
        return component(key, Collections.emptyMap());
    }

    public Component component(String key, Map<String, ?> placeholders) {
        return TextUtil.component(raw(key), placeholders);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, ?> placeholders) {
        String prefix = config().getString("prefix", "");
        String message = raw(key);
        sender.sendMessage(TextUtil.component(prefix + " " + message, placeholders));
    }

    public void sendRaw(CommandSender sender, String miniMessage, Map<String, ?> placeholders) {
        sender.sendMessage(TextUtil.component(miniMessage, placeholders));
    }

    public void sendList(CommandSender sender, String key) {
        for (String line : config().getStringList(key)) {
            sender.sendMessage(TextUtil.component(line));
        }
    }

    public String raw(String key) {
        return config().getString(key, "<red>Missing message: " + key + "</red>");
    }

    public List<String> rawList(String key) {
        return config().getStringList(key);
    }

    private YamlConfiguration config() {
        return configManager.get("messages.yml");
    }
}
