package com.wildmare.market.gui;

import com.wildmare.market.model.LeaderboardMetric;
import com.wildmare.market.model.TradeRequest;
import com.wildmare.market.model.TradeSide;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Carries immutable navigation context for a WildMare inventory. */
public final class MarketMenuHolder implements InventoryHolder {
    private final MenuType type;
    private final UUID playerId;
    private final String context;
    private final int page;
    private final TradeSide side;
    private final TradeRequest tradeRequest;
    private final LeaderboardMetric leaderboardMetric;
    private Inventory inventory;

    public MarketMenuHolder(MenuType type, UUID playerId, String context, int page,
                            TradeSide side, TradeRequest tradeRequest,
                            LeaderboardMetric leaderboardMetric) {
        this.type = type;
        this.playerId = playerId;
        this.context = context;
        this.page = page;
        this.side = side;
        this.tradeRequest = tradeRequest;
        this.leaderboardMetric = leaderboardMetric;
    }

    public static MarketMenuHolder basic(MenuType type, UUID playerId) {
        return new MarketMenuHolder(type, playerId, "", 0, null, null, null);
    }

    public MenuType type() {
        return type;
    }

    public UUID playerId() {
        return playerId;
    }

    public String context() {
        return context;
    }

    public int page() {
        return page;
    }

    public TradeSide side() {
        return side;
    }

    public TradeRequest tradeRequest() {
        return tradeRequest;
    }

    public LeaderboardMetric leaderboardMetric() {
        return leaderboardMetric;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
