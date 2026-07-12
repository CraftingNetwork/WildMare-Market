# Commands and Permissions

## Player Commands

| Command | Description | Permission |
|---|---|---|
| `/market` | Open the main menu | `wildmaremarket.menu` |
| `/market help` | Show player help | `wildmaremarket.use` |
| `/market browse` | Browse all enabled assets | `wildmaremarket.use` |
| `/market stocks` | Browse stocks | `wildmaremarket.use` |
| `/market crypto` | Browse cryptocurrency | `wildmaremarket.use` |
| `/market portfolio` | Open portfolio | `wildmaremarket.portfolio` |
| `/market watchlist` | Open watchlist | `wildmaremarket.watchlist` |
| `/market watchlist add <symbol>` | Add an asset | `wildmaremarket.watchlist` |
| `/market watchlist remove <symbol>` | Remove an asset | `wildmaremarket.watchlist` |
| `/market alerts` | Open alert management | `wildmaremarket.alerts` |
| `/market alerts add <symbol> <condition> <target>` | Create an alert | `wildmaremarket.alerts` |
| `/market alerts delete|pause|resume <id>` | Manage an alert | `wildmaremarket.alerts` |
| `/market alerts edit <id> <target>` | Change an alert target | `wildmaremarket.alerts` |
| `/market history [symbol] [1h|1d|7d|30d|90d|1y]` | View history | `wildmaremarket.history` |
| `/market leaderboard [metric]` | View rankings | `wildmaremarket.leaderboard` |
| `/market search <symbol or name>` | Search assets | `wildmaremarket.use` |
| `/market quote <symbol>` | Show latest quote | `wildmaremarket.use` |
| `/market buy <symbol> <amount>` | Prepare a buy confirmation | `wildmaremarket.trade` + `wildmaremarket.buy` |
| `/market sell <symbol> <amount>` | Prepare a sell confirmation | `wildmaremarket.trade` + `wildmaremarket.sell` |

Aliases for `/market`: `/stocks`, `/stock`, `/wmmarket`, and `/trade`.

Alert conditions: `above`, `below`, `percent_up`, `percent_down`, `daily_gain`, and `daily_loss`.

Leaderboard metrics: portfolio value, realized profit, percentage return, most active, daily performance, weekly performance, and monthly performance. Tab completion exposes accepted metric keys.

## Administrator Commands

| Command | Permission |
|---|---|
| `/marketadmin help` | `wildmaremarket.admin` |
| `/marketadmin reload` | `wildmaremarket.admin.reload` |
| `/marketadmin refresh [symbol]` | `wildmaremarket.admin.refresh` |
| `/marketadmin status` | `wildmaremarket.admin` |
| `/marketadmin cache` | `wildmaremarket.admin` |
| `/marketadmin clearcache` | `wildmaremarket.admin.refresh` |
| `/marketadmin provider [name]` | `wildmaremarket.admin` |
| `/marketadmin enable <symbol>` | `wildmaremarket.admin.manageassets` |
| `/marketadmin disable <symbol>` | `wildmaremarket.admin.manageassets` |
| `/marketadmin addasset <symbol>` | `wildmaremarket.admin.manageassets` |
| `/marketadmin removeasset <symbol>` | `wildmaremarket.admin.manageassets` |
| `/marketadmin resetportfolio <player|uuid>` | `wildmaremarket.admin.manageplayers` |
| `/marketadmin setholding <player|uuid> <symbol> <amount>` | `wildmaremarket.admin.manageplayers` |
| `/marketadmin giveasset <player|uuid> <symbol> <amount>` | `wildmaremarket.admin.manageplayers` |
| `/marketadmin takeasset <player|uuid> <symbol> <amount>` | `wildmaremarket.admin.manageplayers` |
| `/marketadmin transaction <player|uuid>` | `wildmaremarket.admin.manageplayers` |
| `/marketadmin debug [on|off]` | `wildmaremarket.admin.debug` |

Offline player names are resolved only from the server cache to avoid a blocking profile lookup. UUIDs are accepted directly.

## Limit Permissions

The highest numbered permission owned by a player becomes the limit, bounded by the configured maximum:

```text
wildmaremarket.limit.watchlist.40
wildmaremarket.limit.alerts.25
```

Fractional trading requires `wildmaremarket.trade.fractional` in addition to the asset and global fractional settings.

## PlaceholderAPI

```text
%wildmaremarket_portfolio_value%
%wildmaremarket_total_invested%
%wildmaremarket_total_profit%
%wildmaremarket_profit_percent%
%wildmaremarket_owned_assets%
%wildmaremarket_total_trades%
%wildmaremarket_best_asset%
%wildmaremarket_worst_asset%
%wildmaremarket_rank%
%wildmaremarket_market_status%
%wildmaremarket_price_<symbol>%
%wildmaremarket_change_<symbol>%
%wildmaremarket_leaderboard_value_<position>%
%wildmaremarket_leaderboard_player_<position>%
```

Placeholders return `N/A`, an empty-safe statistic, or the latest cached snapshot when data is unavailable. Placeholder requests never trigger a synchronous API or database call.
