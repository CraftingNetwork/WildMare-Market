# Database Schema and Migrations

Migrations are loaded in the order declared by `src/main/resources/migrations/index.txt`. Applied versions are tracked in `wm_schema_history`.

## Tables

- `wm_players` — UUID, latest player name, creation time, last seen
- `wm_portfolios` — invested totals, realized result, timestamps
- `wm_holdings` — quantity and average purchase price per player and symbol
- `wm_transactions` — immutable completed, failed, and cancelled trade records
- `wm_watchlists` — player watchlist symbols
- `wm_price_alerts` — condition, target, active state, trigger state, timestamps
- `wm_market_cache` — latest persisted quote fields and provider timestamps
- `wm_asset_history` — historical provider points used by charts and snapshots
- `wm_player_statistics` — trade count, volume, fees, realized result, extremes, dates
- `wm_audit_logs` — trade and administrator audit trail
- `wm_portfolio_snapshots` — time-series portfolio value and return snapshots
- `wm_player_settings` — notification preferences
- `wm_asset_trade_counts` — per-player per-symbol trade frequency and volume

## Storage Rules

- Player identity uses UUID strings rather than names.
- Financial values use decimal columns.
- All application SQL uses prepared statements.
- Settlement operations use database transactions.
- Runtime database calls execute asynchronously.
- HikariCP closes connections and statements through try-with-resources.
- Migrations run before command/listener registration; a migration failure disables the plugin rather than continuing with a partial schema.

## Backup

For SQLite, back up `wildmare-market.db` while the server is stopped. For MySQL/MariaDB, use the database server's consistent snapshot or dump tooling. Back up YAML files with the database so symbol/provider mappings remain synchronized with historical records.
