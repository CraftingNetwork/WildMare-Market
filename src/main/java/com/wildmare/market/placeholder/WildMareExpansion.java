package com.wildmare.market.placeholder;

import com.wildmare.market.leaderboard.LeaderboardService;
import com.wildmare.market.market.MarketDataService;
import com.wildmare.market.market.MarketHoursService;
import com.wildmare.market.model.LeaderboardEntry;
import com.wildmare.market.model.LeaderboardMetric;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.util.DecimalUtil;
import com.wildmare.market.util.TextUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** PlaceholderAPI expansion for WildMare Market. */
public final class WildMareExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final PlaceholderSnapshotService snapshotService;
    private final MarketDataService marketDataService;
    private final MarketHoursService marketHoursService;
    private final LeaderboardService leaderboardService;

    public WildMareExpansion(JavaPlugin plugin, PlaceholderSnapshotService snapshotService,
                             MarketDataService marketDataService,
                             MarketHoursService marketHoursService,
                             LeaderboardService leaderboardService) {
        this.plugin = plugin;
        this.snapshotService = snapshotService;
        this.marketDataService = marketDataService;
        this.marketHoursService = marketHoursService;
        this.leaderboardService = leaderboardService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "wildmaremarket";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameters) {
        String parameter = parameters.toLowerCase(Locale.ROOT);
        if (parameter.startsWith("price_")) {
            return marketDataService.cachedQuote(parameter.substring(6).toUpperCase(Locale.ROOT))
                    .map(MarketQuote::price).map(this::currency).orElse("N/A");
        }
        if (parameter.startsWith("change_")) {
            return marketDataService.cachedQuote(parameter.substring(7).toUpperCase(Locale.ROOT))
                    .map(MarketQuote::changePercent)
                    .map(value -> TextUtil.decimal(value, 2)).orElse("N/A");
        }
        if (parameter.startsWith("leaderboard_value_")) {
            int position = positiveInt(parameter.substring("leaderboard_value_".length()));
            return leaderboard(position).map(entry -> currency(entry.value())).orElse("N/A");
        }
        if (parameter.startsWith("leaderboard_player_")) {
            int position = positiveInt(parameter.substring("leaderboard_player_".length()));
            return leaderboard(position).map(LeaderboardEntry::playerName).orElse("N/A");
        }
        if (parameter.equals("market_status")) {
            return marketHoursService.globalStatus();
        }
        if (player == null) return "N/A";

        PlaceholderSnapshotService.Snapshot snapshot = snapshotService.get(player.getUniqueId());
        var portfolio = snapshot.portfolio();
        return switch (parameter) {
            case "portfolio_value" -> currency(portfolio.totalValue());
            case "total_invested" -> currency(portfolio.totalInvested());
            case "total_profit" -> currency(portfolio.unrealizedProfit().add(portfolio.realizedProfit()));
            case "profit_percent" -> TextUtil.decimal(DecimalUtil.percentage(
                    portfolio.unrealizedProfit().add(portfolio.realizedProfit()),
                    portfolio.totalInvested()), 2);
            case "owned_assets" -> String.valueOf(portfolio.holdings().size());
            case "total_trades" -> TextUtil.decimal(snapshot.stat("total_trades"), 0);
            case "best_asset" -> portfolio.bestAsset();
            case "worst_asset" -> portfolio.worstAsset();
            case "rank" -> String.valueOf(leaderboardService.rank(
                    player.getUniqueId(), LeaderboardMetric.PORTFOLIO_VALUE));
            default -> null;
        };
    }

    private java.util.Optional<LeaderboardEntry> leaderboard(int position) {
        if (position < 1) return java.util.Optional.empty();
        List<LeaderboardEntry> entries = leaderboardService.entries(
                LeaderboardMetric.PORTFOLIO_VALUE, position);
        return entries.size() >= position
                ? java.util.Optional.of(entries.get(position - 1))
                : java.util.Optional.empty();
    }

    private String currency(BigDecimal value) {
        String symbol = plugin.getConfig().getString("currency-symbol", "$");
        int decimals = plugin.getConfig().getInt("currency-decimals", 2);
        Locale configuredLocale = TextUtil.parseLocale(
                plugin.getConfig().getString("locale", "en-US"));
        return TextUtil.currency(value, symbol, decimals, configuredLocale);
    }

    private static int positiveInt(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
