package com.wildmare.market.notification;

import com.wildmare.market.config.ConfigManager;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Plays configuration-backed Bukkit sounds safely. */
public final class SoundService {
    private final ConfigManager configManager;

    public SoundService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void play(Player player, String key) {
        if (!configManager.get("sounds.yml").getBoolean("enabled", true)) return;
        ConfigurationSection section = configManager.get("sounds.yml").getConfigurationSection(key);
        if (section == null) return;
        try {
            Sound sound = Sound.valueOf(section.getString("sound", "UI_BUTTON_CLICK")
                    .toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound,
                    (float) section.getDouble("volume", 1.0),
                    (float) section.getDouble("pitch", 1.0));
        } catch (IllegalArgumentException ignored) {
        }
    }
}
