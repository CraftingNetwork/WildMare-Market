package com.wildmare.market.database;

import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.model.AlertCondition;
import com.wildmare.market.model.Holding;
import com.wildmare.market.model.HistoryPeriod;
import com.wildmare.market.model.HistoryPoint;
import com.wildmare.market.model.MarketHistory;
import com.wildmare.market.model.MarketQuote;
import com.wildmare.market.model.PriceAlert;
import com.wildmare.market.model.PlayerSettings;
import com.wildmare.market.model.TransactionRecord;
import com.wildmare.market.model.TransactionStatus;
import com.wildmare.market.model.TransactionType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Level;

/** Owns the HikariCP pool and asynchronous prepared-statement persistence API. */
public final class DatabaseService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HikariDataSource dataSource;
    private String storageType;

    public DatabaseService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void initialize() {
        YamlConfiguration config = configManager.get("database.yml");
        storageType = config.getString("type", "SQLITE").toUpperCase();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("WildMareMarketPool");
        hikari.setConnectionTimeout(config.getLong("pool.connection-timeout-milliseconds", 10000L));
        hikari.setIdleTimeout(config.getLong("pool.idle-timeout-milliseconds", 600000L));
        hikari.setMaxLifetime(config.getLong("pool.max-lifetime-milliseconds", 1800000L));

        if ("SQLITE".equals(storageType)) {
            File databaseFile = new File(plugin.getDataFolder(),
                    config.getString("sqlite.file", "wildmare-market.db"));
            hikari.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setMaximumPoolSize(1);
            hikari.setMinimumIdle(1);
            hikari.addDataSourceProperty("foreign_keys", "true");
            hikari.addDataSourceProperty("busy_timeout", "5000");
        } else {
            String host = config.getString("remote.host", "127.0.0.1");
            int port = config.getInt("remote.port", 3306);
            String database = config.getString("remote.database", "wildmare_market");
            boolean ssl = config.getBoolean("remote.use-ssl", false);
            String scheme = "mariadb";
            hikari.setJdbcUrl("jdbc:" + scheme + "://" + host + ":" + port + "/" + database
                    + "?useSSL=" + ssl + "&serverTimezone=UTC");
            hikari.setUsername(config.getString("remote.username", "root"));
            hikari.setPassword(config.getString("remote.password", ""));
            hikari.setDriverClassName("org.mariadb.jdbc.Driver");
            hikari.setMaximumPoolSize(Math.max(2, config.getInt("pool.maximum-pool-size", 10)));
            hikari.setMinimumIdle(Math.max(1, config.getInt("pool.minimum-idle", 2)));
        }

        dataSource = new HikariDataSource(hikari);
        try (Connection connection = dataSource.getConnection()) {
            new MigrationRunner(plugin).migrate(connection);
        } catch (SQLException exception) {
            throw new DatabaseException("Unable to initialize database", exception);
        }
    }

    public String storageType() {
        return storageType;
    }

    public int activeConnections() {
        return dataSource == null ? 0 : dataSource.getHikariPoolMXBean().getActiveConnections();
    }

    public CompletableFuture<Void> ensurePlayer(UUID playerId, String playerName) {
        return runAsync(connection -> {
            ensurePlayer(connection, playerId, playerName);
            return null;
        });
    }

    public CompletableFuture<Optional<Holding>> getHolding(UUID playerId, String symbol) {
        return runAsync(connection -> Optional.ofNullable(getHolding(connection, playerId, symbol)));
    }

    public CompletableFuture<List<Holding>> getHoldings(UUID playerId) {
        return runAsync(connection -> {
            List<Holding> holdings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT symbol, quantity, average_price, updated_at FROM wm_holdings WHERE player_uuid = ? ORDER BY symbol")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        holdings.add(new Holding(
                                playerId,
                                resultSet.getString("symbol"),
                                decimal(resultSet, "quantity"),
                                decimal(resultSet, "average_price"),
                                Instant.ofEpochMilli(resultSet.getLong("updated_at"))
                        ));
                    }
                }
            }
            return holdings;
        });
    }

    public CompletableFuture<BigDecimal> getRealizedProfit(UUID playerId) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT realized_profit FROM wm_portfolios WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? decimal(resultSet, "realized_profit") : BigDecimal.ZERO;
                }
            }
        });
    }

    public CompletableFuture<Void> applyBuy(TransactionRecord record, String playerName) {
        return runTransaction(connection -> {
            ensurePlayer(connection, record.playerId(), playerName);
            Holding existing = getHolding(connection, record.playerId(), record.symbol());
            BigDecimal oldQuantity = existing == null ? BigDecimal.ZERO : existing.quantity();
            BigDecimal oldCost = existing == null ? BigDecimal.ZERO : existing.costBasis();
            BigDecimal newQuantity = oldQuantity.add(record.quantity());
            BigDecimal newAverage = oldCost.add(record.total())
                    .divide(newQuantity, 12, RoundingMode.HALF_UP);
            upsertHolding(connection, record.playerId(), record.symbol(), newQuantity, newAverage);
            updatePortfolio(connection, record.playerId(), BigDecimal.ZERO, record.total());
            insertTransaction(connection, record);
            updateStatistics(connection, record.playerId(), record.symbol(), TransactionType.BUY,
                    record.total(), record.fee(), BigDecimal.ZERO);
            insertAudit(connection, record.playerId(), "TRADE_BUY",
                    "transaction=" + record.transactionId() + ", symbol=" + record.symbol()
                            + ", quantity=" + record.quantity() + ", total=" + record.total());
            return null;
        });
    }

    public CompletableFuture<BigDecimal> applySell(TransactionRecord record, String playerName) {
        return runTransaction(connection -> {
            ensurePlayer(connection, record.playerId(), playerName);
            Holding existing = getHolding(connection, record.playerId(), record.symbol());
            if (existing == null || existing.quantity().compareTo(record.quantity()) < 0) {
                throw new DatabaseException("Insufficient holdings during atomic sell");
            }
            BigDecimal costBasisSold = existing.averagePrice().multiply(record.quantity());
            BigDecimal realized = record.subtotal().subtract(record.fee()).subtract(costBasisSold);
            BigDecimal remaining = existing.quantity().subtract(record.quantity());
            if (remaining.signum() == 0) {
                deleteHolding(connection, record.playerId(), record.symbol());
            } else {
                upsertHolding(connection, record.playerId(), record.symbol(), remaining, existing.averagePrice());
            }
            updatePortfolio(connection, record.playerId(), realized, costBasisSold.negate());
            TransactionRecord completed = new TransactionRecord(
                    record.transactionId(), record.playerId(), record.symbol(), record.type(),
                    record.quantity(), record.unitPrice(), record.subtotal(), record.fee(), record.total(),
                    realized, record.status(), record.failureReason(), record.priceTimestamp(), record.createdAt());
            insertTransaction(connection, completed);
            updateStatistics(connection, record.playerId(), record.symbol(), TransactionType.SELL,
                    record.total(), record.fee(), realized);
            insertAudit(connection, record.playerId(), "TRADE_SELL",
                    "transaction=" + record.transactionId() + ", symbol=" + record.symbol()
                            + ", quantity=" + record.quantity() + ", total=" + record.total()
                            + ", realized=" + realized);
            return realized;
        });
    }

    public CompletableFuture<Void> recordNonCompletedTransaction(TransactionRecord record) {
        return runTransaction(connection -> {
            insertTransaction(connection, record);
            insertAudit(connection, record.playerId(), "TRADE_" + record.status(),
                    "transaction=" + record.transactionId() + ", reason=" + record.failureReason());
            return null;
        });
    }

    public CompletableFuture<List<String>> getRecentlyTradedSymbols(int limit) {
        return runAsync(connection -> {
            List<String> symbols = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT symbol, MAX(created_at) AS last_trade
                    FROM wm_transactions
                    WHERE status = 'COMPLETED'
                    GROUP BY symbol
                    ORDER BY last_trade DESC
                    LIMIT ?
                    """)) {
                statement.setInt(1, Math.max(1, Math.min(limit, 500)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) symbols.add(resultSet.getString("symbol"));
                }
            }
            return symbols;
        });
    }

    public CompletableFuture<List<TransactionRecord>> getTransactions(UUID playerId, int limit) {
        return runAsync(connection -> {
            List<TransactionRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT transaction_id, symbol, transaction_type, quantity, unit_price, subtotal,
                           fee, total, realized_profit, status, failure_reason, price_timestamp, created_at
                    FROM wm_transactions WHERE player_uuid = ?
                    ORDER BY created_at DESC LIMIT ?
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, Math.max(1, Math.min(limit, 500)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        records.add(mapTransaction(resultSet, playerId));
                    }
                }
            }
            return records;
        });
    }

    public CompletableFuture<Boolean> addWatchlist(UUID playerId, String symbol) {
        return runAsync(connection -> {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM wm_watchlists WHERE player_uuid = ? AND symbol = ?")) {
                check.setString(1, playerId.toString());
                check.setString(2, symbol);
                try (ResultSet resultSet = check.executeQuery()) {
                    if (resultSet.next()) return false;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO wm_watchlists(player_uuid, symbol, created_at) VALUES (?, ?, ?)")) {
                insert.setString(1, playerId.toString());
                insert.setString(2, symbol);
                insert.setLong(3, Instant.now().toEpochMilli());
                insert.executeUpdate();
                return true;
            }
        });
    }

    public CompletableFuture<Boolean> removeWatchlist(UUID playerId, String symbol) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM wm_watchlists WHERE player_uuid = ? AND symbol = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, symbol);
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<List<String>> getWatchlist(UUID playerId) {
        return runAsync(connection -> {
            List<String> symbols = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT symbol FROM wm_watchlists WHERE player_uuid = ? ORDER BY created_at")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) symbols.add(resultSet.getString("symbol"));
                }
            }
            return symbols;
        });
    }

    public CompletableFuture<PriceAlert> createAlert(UUID playerId, String symbol,
                                                      AlertCondition condition, BigDecimal target) {
        PriceAlert alert = new PriceAlert(
                UUID.randomUUID(), playerId, symbol, condition, target,
                true, false, Instant.EPOCH, Instant.now(), Instant.now());
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO wm_price_alerts(alert_id, player_uuid, symbol, condition_type,
                    target_value, active, triggered, last_triggered_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, alert.alertId().toString());
                statement.setString(2, playerId.toString());
                statement.setString(3, symbol);
                statement.setString(4, condition.name());
                statement.setBigDecimal(5, target);
                statement.setInt(6, 1);
                statement.setInt(7, 0);
                statement.setLong(8, 0L);
                statement.setLong(9, alert.createdAt().toEpochMilli());
                statement.setLong(10, alert.updatedAt().toEpochMilli());
                statement.executeUpdate();
            }
            return alert;
        });
    }

    public CompletableFuture<List<PriceAlert>> getAlerts(UUID playerId) {
        return runAsync(connection -> loadAlerts(connection,
                "SELECT * FROM wm_price_alerts WHERE player_uuid = ? ORDER BY created_at DESC",
                statement -> statement.setString(1, playerId.toString())));
    }

    public CompletableFuture<List<PriceAlert>> getActiveAlerts() {
        return runAsync(connection -> loadAlerts(connection,
                "SELECT * FROM wm_price_alerts WHERE active = 1",
                statement -> { }));
    }

    public CompletableFuture<Boolean> setAlertActive(UUID playerId, UUID alertId, boolean active) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE wm_price_alerts SET active = ?, updated_at = ?
                    WHERE alert_id = ? AND player_uuid = ?
                    """)) {
                statement.setInt(1, active ? 1 : 0);
                statement.setLong(2, Instant.now().toEpochMilli());
                statement.setString(3, alertId.toString());
                statement.setString(4, playerId.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Boolean> updateAlertTarget(UUID playerId, UUID alertId, BigDecimal target) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE wm_price_alerts SET target_value = ?, triggered = 0, updated_at = ?
                    WHERE alert_id = ? AND player_uuid = ?
                    """)) {
                statement.setBigDecimal(1, target);
                statement.setLong(2, Instant.now().toEpochMilli());
                statement.setString(3, alertId.toString());
                statement.setString(4, playerId.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Boolean> deleteAlert(UUID playerId, UUID alertId) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM wm_price_alerts WHERE alert_id = ? AND player_uuid = ?")) {
                statement.setString(1, alertId.toString());
                statement.setString(2, playerId.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Void> markAlertTriggered(UUID alertId, boolean keepActive) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE wm_price_alerts SET triggered = 1, active = ?, last_triggered_at = ?, updated_at = ?
                    WHERE alert_id = ?
                    """)) {
                long now = Instant.now().toEpochMilli();
                statement.setInt(1, keepActive ? 1 : 0);
                statement.setLong(2, now);
                statement.setLong(3, now);
                statement.setString(4, alertId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> saveMarketQuote(MarketQuote quote) {
        return runAsync(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE wm_market_cache SET provider=?, price=?, change_amount=?, change_percent=?,
                    open_price=?, high_price=?, low_price=?, previous_close=?, volume=?,
                    source_timestamp=?, cached_at=? WHERE symbol=?
                    """)) {
                bindQuote(update, quote, false);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO wm_market_cache(symbol, provider, price, change_amount, change_percent,
                            open_price, high_price, low_price, previous_close, volume, source_timestamp, cached_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        bindQuote(insert, quote, true);
                        insert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    public CompletableFuture<Map<String, MarketQuote>> loadMarketQuotes() {
        return runAsync(connection -> {
            Map<String, MarketQuote> quotes = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM wm_market_cache");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    MarketQuote quote = new MarketQuote(
                            resultSet.getString("symbol"), resultSet.getString("provider"),
                            decimal(resultSet, "price"), decimal(resultSet, "change_amount"),
                            decimal(resultSet, "change_percent"), decimal(resultSet, "open_price"),
                            decimal(resultSet, "high_price"), decimal(resultSet, "low_price"),
                            decimal(resultSet, "previous_close"), decimal(resultSet, "volume"),
                            Instant.ofEpochMilli(resultSet.getLong("source_timestamp")),
                            Instant.ofEpochMilli(resultSet.getLong("cached_at"))
                    );
                    quotes.put(quote.symbol(), quote);
                }
            }
            return quotes;
        });
    }

    /** Persists a bounded historical series for restart and provider-outage fallback. */
    public CompletableFuture<Void> saveMarketHistory(MarketHistory history, int maximumPoints) {
        return runTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM wm_asset_history WHERE symbol = ? AND period_key = ?")) {
                delete.setString(1, history.symbol());
                delete.setString(2, history.period().key());
                delete.executeUpdate();
            }
            List<HistoryPoint> points = history.points();
            int from = Math.max(0, points.size() - Math.max(2, maximumPoints));
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO wm_asset_history(symbol, period_key, price_timestamp, price) VALUES (?, ?, ?, ?)")) {
                for (int index = from; index < points.size(); index++) {
                    HistoryPoint point = points.get(index);
                    insert.setString(1, history.symbol());
                    insert.setString(2, history.period().key());
                    insert.setLong(3, point.timestamp().toEpochMilli());
                    insert.setBigDecimal(4, point.price());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    /** Loads persisted history without treating it as a trading-price source. */
    public CompletableFuture<Optional<MarketHistory>> loadMarketHistory(
            String symbol, HistoryPeriod period) {
        return runAsync(connection -> {
            List<HistoryPoint> points = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT price_timestamp, price FROM wm_asset_history "
                            + "WHERE symbol = ? AND period_key = ? ORDER BY price_timestamp")) {
                statement.setString(1, symbol);
                statement.setString(2, period.key());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        points.add(new HistoryPoint(
                                Instant.ofEpochMilli(resultSet.getLong("price_timestamp")),
                                decimal(resultSet, "price")));
                    }
                }
            }
            if (points.isEmpty()) return Optional.empty();
            return Optional.of(new MarketHistory(
                    symbol, "persisted-cache", period, points, Instant.EPOCH));
        });
    }

    public CompletableFuture<Void> resetPortfolio(UUID playerId) {
        return runTransaction(connection -> {
            for (String sql : List.of(
                    "DELETE FROM wm_holdings WHERE player_uuid = ?",
                    "DELETE FROM wm_transactions WHERE player_uuid = ?",
                    "DELETE FROM wm_player_statistics WHERE player_uuid = ?",
                    "DELETE FROM wm_asset_trade_counts WHERE player_uuid = ?",
                    "DELETE FROM wm_portfolio_snapshots WHERE player_uuid = ?",
                    "DELETE FROM wm_portfolios WHERE player_uuid = ?")) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, playerId.toString());
                    statement.executeUpdate();
                }
            }
            insertAudit(connection, playerId, "ADMIN_RESET_PORTFOLIO", "Portfolio data reset");
            return null;
        });
    }

    public CompletableFuture<Void> setHolding(UUID playerId, String playerName, String symbol,
                                              BigDecimal quantity, BigDecimal averagePrice) {
        return runTransaction(connection -> {
            ensurePlayer(connection, playerId, playerName);
            if (quantity.signum() <= 0) {
                deleteHolding(connection, playerId, symbol);
            } else {
                upsertHolding(connection, playerId, symbol, quantity, averagePrice.max(BigDecimal.ZERO));
            }
            recalculateTotalInvested(connection, playerId);
            insertAudit(connection, playerId, "ADMIN_SET_HOLDING",
                    "symbol=" + symbol + ", quantity=" + quantity + ", averagePrice=" + averagePrice);
            return null;
        });
    }

    public CompletableFuture<List<PlayerHoldingRow>> loadLeaderboardHoldings(int playerLimit) {
        return runAsync(connection -> {
            Map<UUID, PlayerHoldingRowBuilder> builders = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.player_uuid, p.last_known_name, h.symbol, h.quantity, h.average_price, h.updated_at
                    FROM wm_players p INNER JOIN wm_holdings h ON p.player_uuid = h.player_uuid
                    ORDER BY p.updated_at DESC
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next() && builders.size() < playerLimit) {
                    UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));
                    PlayerHoldingRowBuilder builder = builders.computeIfAbsent(uuid,
                            ignored -> new PlayerHoldingRowBuilder(uuid, resultSetSafeName(resultSet)));
                    builder.holdings.add(new Holding(
                            uuid, resultSet.getString("symbol"),
                            decimal(resultSet, "quantity"), decimal(resultSet, "average_price"),
                            Instant.ofEpochMilli(resultSet.getLong("updated_at"))
                    ));
                }
            }
            List<PlayerHoldingRow> rows = new ArrayList<>();
            builders.values().forEach(builder ->
                    rows.add(new PlayerHoldingRow(builder.playerId, builder.playerName, List.copyOf(builder.holdings))));
            return rows;
        });
    }

    public CompletableFuture<Map<String, BigDecimal>> getStatistics(UUID playerId) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM wm_player_statistics WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Collections.emptyMap();
                    Map<String, BigDecimal> stats = new LinkedHashMap<>();
                    stats.put("total_trades", BigDecimal.valueOf(resultSet.getLong("total_trades")));
                    stats.put("successful_purchases", BigDecimal.valueOf(resultSet.getLong("successful_purchases")));
                    stats.put("successful_sales", BigDecimal.valueOf(resultSet.getLong("successful_sales")));
                    stats.put("total_trading_volume", decimal(resultSet, "total_trading_volume"));
                    stats.put("total_fees_paid", decimal(resultSet, "total_fees_paid"));
                    stats.put("total_realized_profit", decimal(resultSet, "total_realized_profit"));
                    stats.put("total_realized_loss", decimal(resultSet, "total_realized_loss"));
                    stats.put("largest_profitable_trade", decimal(resultSet, "largest_profitable_trade"));
                    stats.put("largest_losing_trade", decimal(resultSet, "largest_losing_trade"));
                    return stats;
                }
            }
        });
    }

    public CompletableFuture<Void> audit(UUID playerId, String action, String details) {
        return runAsync(connection -> {
            insertAudit(connection, playerId, action, details);
            return null;
        });
    }

    private void ensurePlayer(Connection connection, UUID playerId, String playerName) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wm_players SET last_known_name = ?, updated_at = ? WHERE player_uuid = ?")) {
            update.setString(1, playerName);
            update.setLong(2, now);
            update.setString(3, playerId.toString());
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO wm_players(player_uuid, last_known_name, created_at, updated_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, playerName);
                    insert.setLong(3, now);
                    insert.setLong(4, now);
                    insert.executeUpdate();
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wm_portfolios SET updated_at = ? WHERE player_uuid = ?")) {
            update.setLong(1, now);
            update.setString(2, playerId.toString());
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO wm_portfolios(player_uuid, realized_profit, total_invested, updated_at)
                        VALUES (?, 0, 0, ?)
                        """)) {
                    insert.setString(1, playerId.toString());
                    insert.setLong(2, now);
                    insert.executeUpdate();
                }
            }
        }
    }

    private Holding getHolding(Connection connection, UUID playerId, String symbol) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT quantity, average_price, updated_at FROM wm_holdings
                WHERE player_uuid = ? AND symbol = ?
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, symbol);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                return new Holding(playerId, symbol, decimal(resultSet, "quantity"),
                        decimal(resultSet, "average_price"),
                        Instant.ofEpochMilli(resultSet.getLong("updated_at")));
            }
        }
    }

    private void upsertHolding(Connection connection, UUID playerId, String symbol,
                               BigDecimal quantity, BigDecimal averagePrice) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE wm_holdings SET quantity = ?, average_price = ?, updated_at = ?
                WHERE player_uuid = ? AND symbol = ?
                """)) {
            update.setBigDecimal(1, quantity);
            update.setBigDecimal(2, averagePrice);
            update.setLong(3, now);
            update.setString(4, playerId.toString());
            update.setString(5, symbol);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO wm_holdings(player_uuid, symbol, quantity, average_price, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, symbol);
                    insert.setBigDecimal(3, quantity);
                    insert.setBigDecimal(4, averagePrice);
                    insert.setLong(5, now);
                    insert.executeUpdate();
                }
            }
        }
    }

    private void deleteHolding(Connection connection, UUID playerId, String symbol) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM wm_holdings WHERE player_uuid = ? AND symbol = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, symbol);
            statement.executeUpdate();
        }
    }

    private void updatePortfolio(Connection connection, UUID playerId,
                                 BigDecimal realizedDelta, BigDecimal investedDelta) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wm_portfolios
                SET realized_profit = realized_profit + ?,
                    total_invested = CASE WHEN total_invested + ? < 0 THEN 0 ELSE total_invested + ? END,
                    updated_at = ?
                WHERE player_uuid = ?
                """)) {
            statement.setBigDecimal(1, realizedDelta);
            statement.setBigDecimal(2, investedDelta);
            statement.setBigDecimal(3, investedDelta);
            statement.setLong(4, Instant.now().toEpochMilli());
            statement.setString(5, playerId.toString());
            statement.executeUpdate();
        }
    }

    private void recalculateTotalInvested(Connection connection, UUID playerId) throws SQLException {
        BigDecimal invested = BigDecimal.ZERO;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT quantity, average_price FROM wm_holdings WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    invested = invested.add(decimal(resultSet, "quantity")
                            .multiply(decimal(resultSet, "average_price")));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE wm_portfolios SET total_invested = ?, updated_at = ? WHERE player_uuid = ?")) {
            statement.setBigDecimal(1, invested);
            statement.setLong(2, Instant.now().toEpochMilli());
            statement.setString(3, playerId.toString());
            statement.executeUpdate();
        }
    }

    private void insertTransaction(Connection connection, TransactionRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO wm_transactions(transaction_id, player_uuid, symbol, transaction_type,
                quantity, unit_price, subtotal, fee, total, realized_profit, status, failure_reason,
                price_timestamp, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, record.transactionId().toString());
            statement.setString(2, record.playerId().toString());
            statement.setString(3, record.symbol());
            statement.setString(4, record.type().name());
            statement.setBigDecimal(5, record.quantity());
            statement.setBigDecimal(6, record.unitPrice());
            statement.setBigDecimal(7, record.subtotal());
            statement.setBigDecimal(8, record.fee());
            statement.setBigDecimal(9, record.total());
            statement.setBigDecimal(10, record.realizedProfit());
            statement.setString(11, record.status().name());
            statement.setString(12, record.failureReason());
            statement.setLong(13, record.priceTimestamp().toEpochMilli());
            statement.setLong(14, record.createdAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void updateStatistics(Connection connection, UUID playerId, String symbol,
                                  TransactionType type, BigDecimal volume, BigDecimal fee,
                                  BigDecimal realized) throws SQLException {
        long now = Instant.now().toEpochMilli();
        boolean exists;
        try (PreparedStatement check = connection.prepareStatement(
                "SELECT 1 FROM wm_player_statistics WHERE player_uuid = ?")) {
            check.setString(1, playerId.toString());
            try (ResultSet resultSet = check.executeQuery()) {
                exists = resultSet.next();
            }
        }
        if (!exists) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO wm_player_statistics(player_uuid, total_trades, successful_purchases,
                    successful_sales, total_trading_volume, total_fees_paid, total_realized_profit,
                    total_realized_loss, largest_profitable_trade, largest_losing_trade,
                    most_traded_asset, first_trade_at, last_trade_at)
                    VALUES (?, 0, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?, ?)
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setString(2, symbol);
                insert.setLong(3, now);
                insert.setLong(4, now);
                insert.executeUpdate();
            }
        }
        updateAssetTradeCount(connection, playerId, symbol, volume.abs(), now);
        String mostTradedAsset = mostTradedAsset(connection, playerId, symbol);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE wm_player_statistics SET
                  total_trades = total_trades + 1,
                  successful_purchases = successful_purchases + ?,
                  successful_sales = successful_sales + ?,
                  total_trading_volume = total_trading_volume + ?,
                  total_fees_paid = total_fees_paid + ?,
                  total_realized_profit = total_realized_profit + ?,
                  total_realized_loss = total_realized_loss + ?,
                  largest_profitable_trade = CASE WHEN ? > largest_profitable_trade THEN ? ELSE largest_profitable_trade END,
                  largest_losing_trade = CASE WHEN ? < largest_losing_trade THEN ? ELSE largest_losing_trade END,
                  most_traded_asset = ?,
                  first_trade_at = CASE WHEN first_trade_at = 0 THEN ? ELSE first_trade_at END,
                  last_trade_at = ?
                WHERE player_uuid = ?
                """)) {
            BigDecimal profit = realized.max(BigDecimal.ZERO);
            BigDecimal loss = realized.min(BigDecimal.ZERO).abs();
            update.setInt(1, type == TransactionType.BUY ? 1 : 0);
            update.setInt(2, type == TransactionType.SELL ? 1 : 0);
            update.setBigDecimal(3, volume.abs());
            update.setBigDecimal(4, fee);
            update.setBigDecimal(5, profit);
            update.setBigDecimal(6, loss);
            update.setBigDecimal(7, profit);
            update.setBigDecimal(8, profit);
            update.setBigDecimal(9, realized);
            update.setBigDecimal(10, realized);
            update.setString(11, mostTradedAsset);
            update.setLong(12, now);
            update.setLong(13, now);
            update.setString(14, playerId.toString());
            update.executeUpdate();
        }
    }

    private void updateAssetTradeCount(Connection connection, UUID playerId, String symbol,
                                       BigDecimal volume, long now) throws SQLException {
        boolean exists;
        try (PreparedStatement check = connection.prepareStatement(
                "SELECT 1 FROM wm_asset_trade_counts WHERE player_uuid = ? AND symbol = ?")) {
            check.setString(1, playerId.toString());
            check.setString(2, symbol);
            try (ResultSet resultSet = check.executeQuery()) {
                exists = resultSet.next();
            }
        }
        if (exists) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE wm_asset_trade_counts
                    SET trade_count = trade_count + 1, trade_volume = trade_volume + ?, updated_at = ?
                    WHERE player_uuid = ? AND symbol = ?
                    """)) {
                update.setBigDecimal(1, volume);
                update.setLong(2, now);
                update.setString(3, playerId.toString());
                update.setString(4, symbol);
                update.executeUpdate();
            }
        } else {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO wm_asset_trade_counts(player_uuid, symbol, trade_count, trade_volume, updated_at)
                    VALUES (?, ?, 1, ?, ?)
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setString(2, symbol);
                insert.setBigDecimal(3, volume);
                insert.setLong(4, now);
                insert.executeUpdate();
            }
        }
    }

    private String mostTradedAsset(Connection connection, UUID playerId, String fallback) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT symbol FROM wm_asset_trade_counts
                WHERE player_uuid = ?
                ORDER BY trade_count DESC, trade_volume DESC, symbol ASC
                LIMIT 1
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("symbol") : fallback;
            }
        }
    }

    private void insertAudit(Connection connection, UUID playerId, String action, String details) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO wm_audit_logs(audit_id, player_uuid, action, details, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, playerId == null ? null : playerId.toString());
            statement.setString(3, action);
            statement.setString(4, details.length() > 2048 ? details.substring(0, 2048) : details);
            statement.setLong(5, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private List<PriceAlert> loadAlerts(Connection connection, String sql,
                                        SqlConsumer<PreparedStatement> binder) throws SQLException {
        List<PriceAlert> alerts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.accept(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    alerts.add(new PriceAlert(
                            UUID.fromString(resultSet.getString("alert_id")),
                            UUID.fromString(resultSet.getString("player_uuid")),
                            resultSet.getString("symbol"),
                            AlertCondition.valueOf(resultSet.getString("condition_type")),
                            decimal(resultSet, "target_value"),
                            resultSet.getInt("active") == 1,
                            resultSet.getInt("triggered") == 1,
                            instant(resultSet.getLong("last_triggered_at")),
                            instant(resultSet.getLong("created_at")),
                            instant(resultSet.getLong("updated_at"))
                    ));
                }
            }
        }
        return alerts;
    }

    private static TransactionRecord mapTransaction(ResultSet resultSet, UUID playerId) throws SQLException {
        return new TransactionRecord(
                UUID.fromString(resultSet.getString("transaction_id")),
                playerId,
                resultSet.getString("symbol"),
                TransactionType.valueOf(resultSet.getString("transaction_type")),
                decimal(resultSet, "quantity"),
                decimal(resultSet, "unit_price"),
                decimal(resultSet, "subtotal"),
                decimal(resultSet, "fee"),
                decimal(resultSet, "total"),
                decimal(resultSet, "realized_profit"),
                TransactionStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("failure_reason"),
                Instant.ofEpochMilli(resultSet.getLong("price_timestamp")),
                Instant.ofEpochMilli(resultSet.getLong("created_at"))
        );
    }

    private static void bindQuote(PreparedStatement statement, MarketQuote quote, boolean insert)
            throws SQLException {
        int index = 1;
        if (insert) statement.setString(index++, quote.symbol());
        statement.setString(index++, quote.provider());
        statement.setBigDecimal(index++, quote.price());
        statement.setBigDecimal(index++, quote.change());
        statement.setBigDecimal(index++, quote.changePercent());
        statement.setBigDecimal(index++, quote.open());
        statement.setBigDecimal(index++, quote.high());
        statement.setBigDecimal(index++, quote.low());
        statement.setBigDecimal(index++, quote.previousClose());
        statement.setBigDecimal(index++, quote.volume());
        statement.setLong(index++, quote.sourceTimestamp().toEpochMilli());
        statement.setLong(index++, quote.cachedAt().toEpochMilli());
        if (!insert) statement.setString(index, quote.symbol());
    }

    private static BigDecimal decimal(ResultSet resultSet, String column) throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Instant instant(long millis) {
        return millis <= 0 ? Instant.EPOCH : Instant.ofEpochMilli(millis);
    }

    private static String resultSetSafeName(ResultSet resultSet) {
        try {
            return resultSet.getString("last_known_name");
        } catch (SQLException exception) {
            return "Unknown";
        }
    }

    private <T> CompletableFuture<T> runAsync(SqlFunction<Connection, T> function) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return function.apply(connection);
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "Database operation failed", exception);
                throw exception instanceof DatabaseException databaseException
                        ? databaseException
                        : new DatabaseException("Database operation failed", exception);
            }
        }, executor);
    }

    private <T> CompletableFuture<T> runTransaction(SqlFunction<Connection, T> function) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = function.apply(connection);
                    connection.commit();
                    return result;
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "Database transaction failed", exception);
                throw exception instanceof DatabaseException databaseException
                        ? databaseException
                        : new DatabaseException("Database transaction failed", exception);
            }
        }, executor);
    }



    public CompletableFuture<PlayerSettings> getPlayerSettings(UUID playerId) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM wm_player_settings WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return PlayerSettings.defaults(playerId);
                    return new PlayerSettings(
                            playerId,
                            resultSet.getInt("alert_chat") == 1,
                            resultSet.getInt("alert_actionbar") == 1,
                            resultSet.getInt("alert_sound") == 1,
                            resultSet.getInt("alert_title") == 1,
                            resultSet.getInt("movement_notifications") == 1
                    );
                }
            }
        });
    }

    public CompletableFuture<Void> savePlayerSettings(PlayerSettings settings) {
        return runAsync(connection -> {
            long now = Instant.now().toEpochMilli();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE wm_player_settings SET alert_chat=?, alert_actionbar=?, alert_sound=?,
                    alert_title=?, movement_notifications=?, updated_at=? WHERE player_uuid=?
                    """)) {
                update.setInt(1, settings.alertChat() ? 1 : 0);
                update.setInt(2, settings.alertActionBar() ? 1 : 0);
                update.setInt(3, settings.alertSound() ? 1 : 0);
                update.setInt(4, settings.alertTitle() ? 1 : 0);
                update.setInt(5, settings.movementNotifications() ? 1 : 0);
                update.setLong(6, now);
                update.setString(7, settings.playerId().toString());
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO wm_player_settings(player_uuid, alert_chat, alert_actionbar,
                            alert_sound, alert_title, movement_notifications, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        insert.setString(1, settings.playerId().toString());
                        insert.setInt(2, settings.alertChat() ? 1 : 0);
                        insert.setInt(3, settings.alertActionBar() ? 1 : 0);
                        insert.setInt(4, settings.alertSound() ? 1 : 0);
                        insert.setInt(5, settings.alertTitle() ? 1 : 0);
                        insert.setInt(6, settings.movementNotifications() ? 1 : 0);
                        insert.setLong(7, now);
                        insert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    public CompletableFuture<Map<UUID, PlayerMetrics>> loadPlayerMetrics() {
        return runAsync(connection -> {
            Map<UUID, PlayerMetrics> metrics = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.player_uuid, p.last_known_name,
                           COALESCE(pf.realized_profit, 0) AS realized_profit,
                           COALESCE(pf.total_invested, 0) AS total_invested,
                           COALESCE(s.total_trades, 0) AS total_trades
                    FROM wm_players p
                    LEFT JOIN wm_portfolios pf ON p.player_uuid = pf.player_uuid
                    LEFT JOIN wm_player_statistics s ON p.player_uuid = s.player_uuid
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uuid = UUID.fromString(resultSet.getString("player_uuid"));
                    metrics.put(uuid, new PlayerMetrics(
                            uuid,
                            resultSet.getString("last_known_name"),
                            decimal(resultSet, "realized_profit"),
                            decimal(resultSet, "total_invested"),
                            resultSet.getLong("total_trades")
                    ));
                }
            }
            return metrics;
        });
    }

    public CompletableFuture<Void> savePortfolioSnapshots(Map<UUID, BigDecimal> values) {
        if (values.isEmpty()) return CompletableFuture.completedFuture(null);
        long timestamp = Instant.now().toEpochMilli();
        return runTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO wm_portfolio_snapshots(player_uuid, snapshot_timestamp, portfolio_value)
                    VALUES (?, ?, ?)
                    """)) {
                for (Map.Entry<UUID, BigDecimal> entry : values.entrySet()) {
                    statement.setString(1, entry.getKey().toString());
                    statement.setLong(2, timestamp);
                    statement.setBigDecimal(3, entry.getValue());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            long retention = Instant.now().minus(java.time.Duration.ofDays(400)).toEpochMilli();
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "DELETE FROM wm_portfolio_snapshots WHERE snapshot_timestamp < ?")) {
                cleanup.setLong(1, retention);
                cleanup.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Map<UUID, BigDecimal>> loadSnapshotsAtOrBefore(Instant threshold) {
        return runAsync(connection -> {
            Map<UUID, BigDecimal> snapshots = new LinkedHashMap<>();
            try (PreparedStatement players = connection.prepareStatement(
                    "SELECT DISTINCT player_uuid FROM wm_portfolio_snapshots");
                 ResultSet playerRows = players.executeQuery()) {
                while (playerRows.next()) {
                    UUID uuid = UUID.fromString(playerRows.getString("player_uuid"));
                    try (PreparedStatement snapshot = connection.prepareStatement("""
                            SELECT portfolio_value FROM wm_portfolio_snapshots
                            WHERE player_uuid = ? AND snapshot_timestamp <= ?
                            ORDER BY snapshot_timestamp DESC LIMIT 1
                            """)) {
                        snapshot.setString(1, uuid.toString());
                        snapshot.setLong(2, threshold.toEpochMilli());
                        try (ResultSet resultSet = snapshot.executeQuery()) {
                            if (resultSet.next()) {
                                snapshots.put(uuid, decimal(resultSet, "portfolio_value"));
                            }
                        }
                    }
                }
            }
            return snapshots;
        });
    }

    @Override
    public void close() {
        executor.shutdownNow();
        if (dataSource != null) dataSource.close();
    }


    public record PlayerMetrics(
            UUID playerId,
            String playerName,
            BigDecimal realizedProfit,
            BigDecimal totalInvested,
            long totalTrades
    ) {
    }

    public record PlayerHoldingRow(UUID playerId, String playerName, List<Holding> holdings) {
    }

    private static final class PlayerHoldingRowBuilder {
        private final UUID playerId;
        private final String playerName;
        private final List<Holding> holdings = new ArrayList<>();

        private PlayerHoldingRowBuilder(UUID playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T, R> {
        R apply(T value) throws Exception;
    }

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T value) throws SQLException;
    }
}
