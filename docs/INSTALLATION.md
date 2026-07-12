# Installation Guide

## Requirements

- Paper or Purpur 1.20.4+
- Java 21 runtime
- Vault
- A Vault-compatible economy provider
- PlaceholderAPI only when placeholders are required

## Build from Source

```bash
mvn clean package
```

Copy `target/WildMareMarket-1.0.0.jar` to the server's `plugins/` directory.

## First Start

1. Stop the server before installing or replacing the JAR.
2. Install Vault and the economy plugin first.
3. Start the server.
4. Confirm that `plugins/WildMareMarket/` contains all YAML files and the SQLite database when SQLite is selected.
5. Stop the server before entering production API credentials.
6. Configure `providers.yml`, `assets.yml`, and `market-hours.yml`.
7. Restart the server.
8. Run:

```text
/marketadmin status
/marketadmin provider
/marketadmin refresh BTC
/market quote BTC
```

## Provider Setup

Finnhub is disabled by default until a key is entered. CoinGecko is enabled with an optional Demo API key header. The fictional provider is enabled for server-created virtual instruments.

Never paste API keys into chat, command arguments, menu lore, PlaceholderAPI placeholders, or public configuration repositories.

## Remote Database

For MySQL or MariaDB, create an empty database and credentials with access only to that database. Set `type` and `remote` values in `database.yml`. The plugin creates and migrates tables automatically.

## Updating

1. Back up the database and configuration directory.
2. Stop the server.
3. Replace the JAR.
4. Start the server and inspect the console for migration results.
5. Run `/marketadmin status`.

Do not manually mark a migration as applied unless its SQL completed successfully.
