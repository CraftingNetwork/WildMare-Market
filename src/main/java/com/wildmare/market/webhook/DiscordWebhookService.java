package com.wildmare.market.webhook;

import com.google.gson.JsonObject;
import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.util.TextUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Asynchronously sends sanitized optional Discord webhook notifications. */
public final class DiscordWebhookService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final HttpClient client;

    public DiscordWebhookService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public CompletableFuture<Void> sendAlert(Map<String, ?> placeholders) {
        YamlConfiguration config = configManager.get("webhooks.yml");
        if (!config.getBoolean("discord.enabled", false)) {
            return CompletableFuture.completedFuture(null);
        }
        String url = config.getString("discord.url", "");
        if (url.isBlank()) return CompletableFuture.completedFuture(null);
        JsonObject payload = new JsonObject();
        payload.addProperty("username", config.getString("discord.username", "WildMare Market"));
        String avatar = config.getString("discord.avatar-url", "");
        if (!avatar.isBlank()) payload.addProperty("avatar_url", avatar);
        payload.addProperty("content", TextUtil.replace(
                config.getString("discord.alert-message", "{player}: {symbol} reached {price}."),
                placeholders));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        plugin.getLogger().warning("Discord webhook returned HTTP " + response.statusCode());
                    }
                }).exceptionally(throwable -> {
                    plugin.getLogger().log(Level.WARNING, "Discord webhook failed", throwable);
                    return null;
                });
    }

    @Override
    public void close() {
    }
}
