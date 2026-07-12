# Configuration Guide

## `config.yml`

- `locale`, `currency-symbol`, `currency-decimals`, and `quantity-decimals` control formatting.
- `trading.fee-percent` is a percentage, so `0.50` means 0.5%.
- `trading.minimum-fee` prevents near-zero fees.
- `trading.minimum-transaction-value` and `maximum-transaction-value` apply to the trade subtotal.
- `trading.max-price-age-seconds` blocks settlement against expired quotes.
- `trading.confirmation-timeout-seconds` invalidates abandoned confirmation menus.
- Quote and history TTL values control shared cache freshness.
- Refresh batches prevent one request per player and distribute calls across cycles.
- Watchlist and alert limits can be increased by numbered permission nodes.

## `assets.yml`

Each asset supports:

```yaml
SYMBOL:
  enabled: true
  name: "Display Name"
  type: STOCK
  provider: finnhub
  api-symbol: "SYMBOL"
  fractional: true
  material: PAPER
  custom-model-data: 0
```

Valid types are `STOCK`, `ETF`, `INDEX`, `CRYPTO`, `FOREX`, `COMMODITY`, and `FICTIONAL`.

A provider may require a different identifier from the player-facing symbol. For example, CoinGecko uses coin IDs such as `bitcoin`, while the menu symbol can remain `BTC`.

Disabled index, forex, and commodity examples are included. Validate symbol support with the selected provider before enabling them. The fictional provider can be used for server-created instruments and test markets.

## `providers.yml`

- `fallback-provider` may be empty or the ID of another configured provider.
- Timeouts, retries, delay, maximum concurrent requests, and user agent are shared HTTP controls.
- Provider credentials remain server-side.
- A provider marked `enabled: false` is never called.

## `database.yml`

`SQLITE` is the default. `MYSQL` and `MARIADB` use the remote section and HikariCP pool settings. Decimal financial columns preserve quantities and prices without binary floating-point storage.

## `market-hours.yml`

The session is evaluated in the configured IANA time zone. Opening and closing times use `HH:mm`. Overnight sessions are supported when closing time is earlier than opening time. Holidays use ISO dates (`YYYY-MM-DD`). Categories in `always-open-types` bypass traditional market hours.

## `messages.yml` and `menus.yml`

All text uses MiniMessage. Menu names use Unicode small-cap characters and purple gradients, but no resource pack is required. Materials and custom model data may be replaced without recompiling.

After editing mutable configuration, run:

```text
/marketadmin reload
```

Database connection settings require a full restart.
