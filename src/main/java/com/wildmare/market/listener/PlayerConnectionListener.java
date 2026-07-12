package com.wildmare.market.listener;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.placeholder.PlaceholderSnapshotService;
import com.wildmare.market.service.PlayerSettingsService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/** Initializes and releases per-player market state on connection changes. */
public final class PlayerConnectionListener implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseService databaseService;
    private final PlayerSettingsService settingsService;
    private final PlaceholderSnapshotService placeholderSnapshots;

    public PlayerConnectionListener(JavaPlugin plugin, DatabaseService databaseService,
                                    PlayerSettingsService settingsService,
                                    PlaceholderSnapshotService placeholderSnapshots) {
        this.plugin = plugin;
        this.databaseService = databaseService;
        this.settingsService = settingsService;
        this.placeholderSnapshots = placeholderSnapshots;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        databaseService.ensurePlayer(event.getPlayer().getUniqueId(), event.getPlayer().getName())
                .exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Unable to initialize market player", throwable);
                    return null;
                });
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> placeholderSnapshots.refresh(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        settingsService.invalidate(event.getPlayer().getUniqueId());
        placeholderSnapshots.invalidate(event.getPlayer().getUniqueId());
    }
}
