package com.wildmare.market.service;

import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.model.PlayerSettings;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Caches and persists player notification preferences. */
public final class PlayerSettingsService {
    private final DatabaseService databaseService;
    private final ConcurrentMap<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();

    public PlayerSettingsService(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    public CompletableFuture<PlayerSettings> get(UUID playerId) {
        PlayerSettings cached = cache.get(playerId);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return databaseService.getPlayerSettings(playerId).thenApply(settings -> {
            cache.put(playerId, settings);
            return settings;
        });
    }

    public CompletableFuture<PlayerSettings> toggle(UUID playerId, String key) {
        return get(playerId).thenCompose(current -> {
            PlayerSettings updated = current.toggle(key);
            cache.put(playerId, updated);
            return databaseService.savePlayerSettings(updated).thenApply(ignored -> updated);
        });
    }

    public void invalidate(UUID playerId) {
        cache.remove(playerId);
    }

    public void clear() {
        cache.clear();
    }
}
