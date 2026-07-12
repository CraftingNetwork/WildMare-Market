# Architecture

## Composition Root

`WildMareMarketPlugin` creates the service graph and owns lifecycle shutdown. Dependencies are passed through constructors rather than accessed through a global singleton.

## Main Layers

1. **Configuration** — loads editable YAML files and MiniMessage messages.
2. **Asset Registry** — validates and indexes configured symbols and categories.
3. **Provider Layer** — exposes `MarketDataProvider` and provider implementations.
4. **Market Data Service** — centralizes shared quote/history cache, stale checks, provider fallback, persisted cache, recent-trade symbols, and provider failure telemetry.
5. **Database Layer** — HikariCP connections, prepared statements, transactions, schema creation, and ordered migrations.
6. **Economy Layer** — wraps Vault deposit, withdrawal, balance, and response validation.
7. **Trading Layer** — creates immutable trade requests, validates rules, serializes each player's trade execution, and coordinates Vault plus database compensation.
8. **Portfolio Layer** — joins holdings with cached/latest quotes and calculates value, allocation, average entry, realized/unrealized profit, and performance.
9. **Presentation Layer** — inventory GUI, MiniMessage text, commands, listeners, sounds, and chart rendering.
10. **Background Services** — batched market refresh, alert evaluation, snapshots, leaderboards, and PlaceholderAPI cache refresh.

## Threading Model

- REST requests use provider-owned executor pools and Java `HttpClient.sendAsync`.
- Runtime database operations execute through the database executor.
- Expensive portfolio, alert, and leaderboard work is composed with `CompletableFuture`.
- Bukkit inventory, sound, title, action-bar, and economy-facing callbacks return to the primary server thread.
- Startup database connection and schema migration are synchronous so the plugin either starts with a valid schema or disables safely.
- All scheduled Bukkit tasks are cancelled during plugin shutdown or reload.

## Transaction Safety

Each submitted trade has a UUID and an atomic processed state. A semaphore serializes transactions per player. The flow validates the player, permissions, quantity, market hours, current quote age, limits, balance/holdings, and cooldown before settlement.

Buy flow:

1. Withdraw Vault funds.
2. Atomically update holdings, portfolio totals, statistics, transaction, and audit rows.
3. Refund Vault funds if database settlement fails.

Sell flow:

1. Atomically update holdings and create a pending settlement result.
2. Deposit Vault proceeds.
3. Compensate the database holding if the economy deposit fails.

Completed, failed, and cancelled requests are recorded. Prepared statements are used throughout the database layer.

## Provider Extension Point

Implement `MarketDataProvider`, return asynchronous quote/history futures, and register the implementation in `ProviderRegistry`. Core trading code never depends on a provider-specific JSON shape.
