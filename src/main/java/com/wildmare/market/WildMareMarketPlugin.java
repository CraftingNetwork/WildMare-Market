package com.wildmare.market;

import com.wildmare.market.alert.PriceAlertService;
import com.wildmare.market.api.provider.ProviderRegistry;
import com.wildmare.market.command.MarketAdminCommand;
import com.wildmare.market.command.MarketCommand;
import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.config.Messages;
import com.wildmare.market.database.DatabaseService;
import com.wildmare.market.economy.EconomyService;
import com.wildmare.market.gui.GuiManager;
import com.wildmare.market.gui.ItemFactory;
import com.wildmare.market.leaderboard.LeaderboardService;
import com.wildmare.market.listener.GuiListener;
import com.wildmare.market.listener.PlayerConnectionListener;
import com.wildmare.market.market.AssetRegistry;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.market.MarketHoursService;
import com.wildmare.market.notification.NotificationService;
import com.wildmare.market.notification.SoundService;
import com.wildmare.market.placeholder.PlaceholderSnapshotService;
import com.wildmare.market.placeholder.WildMareExpansion;
import com.wildmare.market.portfolio.PortfolioService;
import com.wildmare.market.service.PlayerSettingsService;
import com.wildmare.market.task.ScheduledTasks;
import com.wildmare.market.transaction.TradingService;
import com.wildmare.market.transaction.TransactionService;
import com.wildmare.market.watchlist.WatchlistService;
import com.wildmare.market.webhook.DiscordWebhookService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

/** WildMare Market plugin bootstrap and dependency-composition root. */
public final class WildMareMarketPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private Messages messages;
    private DatabaseService databaseService;
    private EconomyService economyService;
    private AssetRegistry assetRegistry;
    private ProviderRegistry providerRegistry;
    private MarketHoursService marketHoursService;
    private MarketDataService marketDataService;
    private TransactionService transactionService;
    private TradingService tradingService;
    private PortfolioService portfolioService;
    private WatchlistService watchlistService;
    private PlayerSettingsService settingsService;
    private DiscordWebhookService webhookService;
    private SoundService soundService;
    private NotificationService notificationService;
    private PriceAlertService alertService;
    private LeaderboardService leaderboardService;
    private PlaceholderSnapshotService placeholderSnapshots;
    private GuiManager guiManager;
    private ScheduledTasks scheduledTasks;
    private WildMareExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        try {
            configManager = new ConfigManager(this);
            configManager.load();
            messages = new Messages(configManager);

            databaseService = new DatabaseService(this, configManager);
            databaseService.initialize();
            economyService = new EconomyService(this);
            assetRegistry = new AssetRegistry(configManager);
            assetRegistry.load();
            providerRegistry = new ProviderRegistry(this, configManager);
            marketHoursService = new MarketHoursService(configManager);
            marketDataService = new MarketDataService(this, configManager, assetRegistry,
                    providerRegistry, databaseService);
            transactionService = new TransactionService(databaseService);
            tradingService = new TradingService(this, assetRegistry, marketDataService,
                    marketHoursService, databaseService, economyService, transactionService);
            portfolioService = new PortfolioService(databaseService, assetRegistry, marketDataService);
            watchlistService = new WatchlistService(this, databaseService, assetRegistry,
                    marketDataService);
            settingsService = new PlayerSettingsService(databaseService);
            webhookService = new DiscordWebhookService(this, configManager);
            soundService = new SoundService(configManager);
            notificationService = new NotificationService(this, messages, soundService,
                    webhookService, settingsService);
            alertService = new PriceAlertService(this, databaseService, assetRegistry,
                    marketDataService, notificationService);
            leaderboardService = new LeaderboardService(this, databaseService, marketDataService);
            placeholderSnapshots = new PlaceholderSnapshotService(this, portfolioService,
                    databaseService);

            ItemFactory itemFactory = new ItemFactory(this, configManager);
            guiManager = new GuiManager(this, configManager, messages, itemFactory,
                    assetRegistry, marketDataService, marketHoursService, databaseService,
                    economyService, tradingService, transactionService, portfolioService,
                    watchlistService, alertService, leaderboardService, settingsService, soundService);

            registerCommands();
            registerListeners();
            registerPlaceholderExpansion();

            scheduledTasks = new ScheduledTasks(this, messages, assetRegistry, marketDataService,
                    alertService, leaderboardService, placeholderSnapshots, settingsService);
            scheduledTasks.start();

            marketDataService.loadPersistedCache().exceptionally(throwable -> {
                getLogger().log(Level.WARNING, "Unable to load persisted market cache", throwable);
                return null;
            }).thenRun(() -> marketDataService.refreshAll().exceptionally(throwable -> {
                getLogger().log(Level.INFO,
                        "Initial market refresh was not fully available; cached data will be used where possible.",
                        throwable);
                return null;
            }));
            leaderboardService.refresh().exceptionally(throwable -> {
                getLogger().log(Level.FINE, "Initial leaderboard refresh failed", throwable);
                return null;
            });

            getLogger().info("WildMare Market enabled with " + assetRegistry.enabled().size()
                    + " enabled assets, " + economyService.providerName()
                    + " economy, and " + databaseService.storageType() + " storage.");
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "WildMare Market could not start safely", throwable);
            closeServices();
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void registerCommands() {
        MarketCommand playerCommand = new MarketCommand(this, messages, guiManager,
                assetRegistry, marketDataService, watchlistService, alertService, soundService);
        PluginCommand market = Objects.requireNonNull(getCommand("market"),
                "market command missing from plugin.yml");
        market.setExecutor(playerCommand);
        market.setTabCompleter(playerCommand);

        MarketAdminCommand adminCommand = new MarketAdminCommand(this, messages, assetRegistry,
                providerRegistry, marketDataService, databaseService, transactionService,
                this::reloadWildMare);
        PluginCommand admin = Objects.requireNonNull(getCommand("marketadmin"),
                "marketadmin command missing from plugin.yml");
        admin.setExecutor(adminCommand);
        admin.setTabCompleter(adminCommand);
    }

    private void registerListeners() {
        PluginManager manager = getServer().getPluginManager();
        manager.registerEvents(new GuiListener(this, guiManager, messages, watchlistService,
                alertService, settingsService, marketDataService, soundService,
                placeholderSnapshots), this);
        manager.registerEvents(new PlayerConnectionListener(this, databaseService,
                settingsService, placeholderSnapshots), this);
    }

    private void registerPlaceholderExpansion() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI was not detected; placeholder integration is disabled.");
            return;
        }
        placeholderExpansion = new WildMareExpansion(this, placeholderSnapshots,
                marketDataService, marketHoursService, leaderboardService);
        if (!placeholderExpansion.register()) {
            getLogger().warning("PlaceholderAPI expansion registration failed.");
        }
    }

    /** Reloads mutable configuration without interrupting database or economy operations. */
    public synchronized void reloadWildMare() {
        configManager.reload();
        assetRegistry.load();
        providerRegistry.reload();
        marketHoursService.reload();
        settingsService.clear();
        placeholderSnapshots.clear();
        marketDataService.clear();
        marketDataService.loadPersistedCache().exceptionally(throwable -> null);
        if (scheduledTasks != null) scheduledTasks.start();
    }

    @Override
    public void onDisable() {
        closeServices();
    }

    private void closeServices() {
        if (scheduledTasks != null) scheduledTasks.close();
        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.unregister();
            } catch (Throwable ignored) {
            }
        }
        if (tradingService != null) tradingService.close();
        if (webhookService != null) webhookService.close();
        if (providerRegistry != null) providerRegistry.close();
        if (databaseService != null) databaseService.close();
    }
}
