# WildMare Market

A professional virtual stock market and investment system for Paper and Purpur servers.

> **Virtual economy only.** WildMare Market never accepts real money, never supports deposits or withdrawals, never transfers cryptocurrency, and never connects player holdings to a real brokerage, wallet, exchange account, or gambling system. Every balance and asset position exists only inside the Minecraft server economy.

## 1. System Architecture and Dependencies

WildMare Market uses constructor-injected services with separate packages for market providers, cache, storage, economy, trading, portfolio calculations, GUI, commands, alerts, leaderboards, placeholders, scheduled work, and webhooks.

Required runtime:

- Paper or Purpur 1.20.4 or newer
- Java 21
- Vault
- A Vault-compatible economy provider, such as EssentialsX Economy

Optional runtime:

- PlaceholderAPI
- ProtocolLib (soft dependency reserved for optional advanced integrations; the base plugin does not require it)

Bundled/shaded libraries:

- HikariCP
- Gson
- SQLite JDBC
- MariaDB JDBC (also used for MySQL-compatible connections)

Build system: Maven.

## 2. Project Structure

```text
com.wildmare.market
├── WildMareMarketPlugin.java
├── alert
├── api
│   └── provider
├── command
├── config
├── database
├── economy
├── gui
├── leaderboard
├── listener
├── market
├── model
├── notification
├── placeholder
├── portfolio
├── service
├── task
├── transaction
├── util
├── watchlist
└── webhook
```

Resources include `plugin.yml`, nine editable YAML configuration files, and versioned SQL migrations.

## 3. Build

```bash
mvn clean package
```

The shaded plugin is produced at:

```text
target/WildMareMarket-1.0.0.jar
```

Run tests with:

```bash
mvn test
```

## 4. Installation

1. Stop the Minecraft server.
2. Install Vault and a Vault-compatible economy plugin.
3. Copy the built WildMare Market JAR into `plugins/`.
4. Optionally install PlaceholderAPI.
5. Start the server once to generate the configuration directory.
6. Configure a financial provider in `plugins/WildMareMarket/providers.yml`.
7. Review enabled assets in `assets.yml`.
8. Run `/marketadmin status` and `/marketadmin provider`.

See [Installation](docs/INSTALLATION.md) and [API Setup](docs/API_SETUP.md).

## 5. Included Systems

- Shared, expiring quote and history cache
- Finnhub, CoinGecko, and fictional provider implementations
- Provider abstraction, retry, timeout, concurrency limits, failure tracking, and optional fallback
- SQLite, MySQL, and MariaDB with HikariCP and versioned migrations
- Vault buy/sell flow with balance checks, price-age checks, market hours, fees, cooldowns, minimum/maximum values, fractional permissions, per-player serialization, duplicate prevention, and compensation on storage failure
- Holdings, average purchase price, current value, realized/unrealized profit, allocation, daily performance, and snapshots
- Watchlists, configurable limits, and sorting
- Price alerts with chat, action bar, sound, title, and optional Discord webhook delivery
- Historical Unicode charts for 1h, 1d, 7d, 30d, 90d, and 1y
- Portfolio leaderboards and PlaceholderAPI support
- Transaction history, player statistics, audit logs, recent trades, gainers, losers, and trending assets
- Configurable market sessions and always-open asset categories
- Dark purple MiniMessage GUI theme with small-cap menu text

## 6. Configuration Files

| File | Purpose |
|---|---|
| `config.yml` | Trading rules, cache, refresh, limits, locale, and debug settings |
| `messages.yml` | All command and player-facing messages |
| `menus.yml` | Inventory titles, slots, materials, model data, names, and lore |
| `assets.yml` | Symbols, categories, providers, API identifiers, and enabled state |
| `providers.yml` | API credentials, URLs, fallback, timeout, retry, and concurrency |
| `database.yml` | SQLite/MySQL/MariaDB and connection-pool settings |
| `market-hours.yml` | Session time zone, hours, days, holidays, and always-open categories |
| `sounds.yml` | Menu, trade, alert, failure, and milestone sounds |
| `webhooks.yml` | Optional Discord webhook settings |

No normal player-facing message is intentionally hardcoded into business classes.

## 7. Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Installation](docs/INSTALLATION.md)
- [Configuration](docs/CONFIGURATION.md)
- [API Setup](docs/API_SETUP.md)
- [Commands and Permissions](docs/COMMANDS_AND_PERMISSIONS.md)
- [Database Schema](docs/DATABASE_SCHEMA.md)
- [Testing](docs/TESTING.md)
- [Security](SECURITY.md)

## 8. Latest-Available Data Notice

Financial providers differ in coverage, delay, licensing, and rate limits. WildMare Market describes provider output as **live market data** or **latest available market data**, not guaranteed exchange-level real-time data. Server owners are responsible for selecting a provider and plan suitable for their configured symbols.
