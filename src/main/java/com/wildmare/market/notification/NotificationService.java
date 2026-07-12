package com.wildmare.market.notification;

import com.wildmare.market.config.Messages;
import com.wildmare.market.model.PriceAlert;
import com.wildmare.market.model.PlayerSettings;
import com.wildmare.market.service.PlayerSettingsService;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.util.TextUtil;
import com.wildmare.market.webhook.DiscordWebhookService;
import net.kyori.adventure.text.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Delivers alerts through player-selected channels and optional webhooks. */
public final class NotificationService {
    private final JavaPlugin plugin;
    private final Messages messages;
    private final SoundService soundService;
    private final DiscordWebhookService webhookService;
    private final PlayerSettingsService settingsService;
    private final String currencySymbol;
    private final int currencyDecimals;
    private final java.util.Locale locale;

    public NotificationService(JavaPlugin plugin, Messages messages, SoundService soundService,
                               DiscordWebhookService webhookService,
                               PlayerSettingsService settingsService) {
        this.plugin = plugin;
        this.messages = messages;
        this.soundService = soundService;
        this.webhookService = webhookService;
        this.settingsService = settingsService;
        this.currencySymbol = plugin.getConfig().getString("currency-symbol", "$");
        this.currencyDecimals = plugin.getConfig().getInt("currency-decimals", 2);
        this.locale = TextUtil.parseLocale(plugin.getConfig().getString("locale", "en-US"));
    }

    public void alert(Player player, PriceAlert alert, MarketQuote quote) {
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("symbol", alert.symbol());
        placeholders.put("price", TextUtil.currency(quote.price(), currencySymbol, currencyDecimals, locale));
        placeholders.put("condition", alert.condition().name());
        placeholders.put("target", TextUtil.decimal(alert.target(), currencyDecimals));
        settingsService.get(player.getUniqueId()).thenAccept(settings -> {
            Runnable task = () -> {
                if (settings.alertChat()) messages.send(player, "alerts.triggered", placeholders);
                if (settings.alertActionBar()) {
                    player.sendActionBar(messages.component("alerts.triggered", placeholders));
                }
                if (settings.alertTitle()) {
                    player.showTitle(Title.title(
                            messages.component("alerts.title", placeholders),
                            messages.component("alerts.subtitle", placeholders),
                            Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2),
                                    Duration.ofMillis(500))));
                }
                if (settings.alertSound()) soundService.play(player, "alert");
            };
            if (Bukkit.isPrimaryThread()) task.run();
            else Bukkit.getScheduler().runTask(plugin, task);
        });
        webhookService.sendAlert(placeholders);
    }
}
