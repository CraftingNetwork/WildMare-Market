package com.wildmare.market.database;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** Applies ordered, versioned SQL migrations exactly once. */
public final class MigrationRunner {
    private final JavaPlugin plugin;

    public MigrationRunner(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void migrate(Connection connection) throws SQLException {
        createHistoryTable(connection);
        String product = productName(connection);
        for (String resource : migrationResources()) {
            int version = parseVersion(resource);
            if (isInstalled(connection, version)) continue;
            String sql = readResource("migrations/" + resource);
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (String statement : splitStatements(sql)) {
                    executeMigrationStatement(connection, statement, product);
                }
                try (PreparedStatement prepared = connection.prepareStatement(
                        "INSERT INTO wm_schema_history(version, description, installed_at) VALUES (?, ?, ?)")) {
                    prepared.setInt(1, version);
                    prepared.setString(2, resource);
                    prepared.setLong(3, Instant.now().toEpochMilli());
                    prepared.executeUpdate();
                }
                connection.commit();
                plugin.getLogger().info("Applied database migration " + resource);
            } catch (Exception exception) {
                connection.rollback();
                throw exception instanceof SQLException sqlException
                        ? sqlException
                        : new SQLException("Migration failed: " + resource, exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private void executeMigrationStatement(Connection connection, String sql, String product) throws SQLException {
        String adjusted = sql;
        if (product.contains("mysql") && adjusted.toUpperCase().startsWith("CREATE INDEX IF NOT EXISTS")) {
            adjusted = adjusted.replaceFirst("(?i)CREATE INDEX IF NOT EXISTS", "CREATE INDEX");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(adjusted);
        } catch (SQLException exception) {
            if (isDuplicateIndex(exception)) {
                plugin.getLogger().log(Level.FINE, "Ignoring an existing database index", exception);
                return;
            }
            throw exception;
        }
    }

    private static boolean isDuplicateIndex(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        return message.contains("duplicate key name")
                || message.contains("already exists")
                || message.contains("duplicate index");
    }

    private static String productName(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return metaData.getDatabaseProductName().toLowerCase();
    }

    private void createHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS wm_schema_history (
                      version INTEGER PRIMARY KEY,
                      description VARCHAR(255) NOT NULL,
                      installed_at BIGINT NOT NULL
                    )
                    """);
        }
    }

    private boolean isInstalled(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM wm_schema_history WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private List<String> migrationResources() {
        String index = readResource("migrations/index.txt");
        return index.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    private String readResource(String name) {
        try (InputStream input = plugin.getResource(name)) {
            if (input == null) throw new IllegalStateException("Missing resource: " + name);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines().reduce("", (left, right) -> left + right + "\n");
            }
        } catch (Exception exception) {
            throw new DatabaseException("Unable to read migration resource " + name, exception);
        }
    }

    private static int parseVersion(String name) {
        int separator = name.indexOf("__");
        if (!name.startsWith("V") || separator < 2) {
            throw new IllegalArgumentException("Invalid migration name: " + name);
        }
        return Integer.parseInt(name.substring(1, separator));
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            if (character == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            if (character == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;
            if (character == ';' && !inSingleQuote && !inDoubleQuote) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) statements.add(statement);
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        String finalStatement = current.toString().trim();
        if (!finalStatement.isEmpty()) statements.add(finalStatement);
        return statements;
    }
}
