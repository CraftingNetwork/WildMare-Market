# Testing Guide

## Automated Tests

Run:

```bash
mvn clean test
```

Included JUnit tests cover decimal fees/percentages/fraction detection, history-period parsing, quote normalization/tradability, and Unicode chart sampling.

## Compilation

```bash
mvn clean package
```

The Maven build must complete without compiler warnings elevated to errors and produce the shaded JAR.

## Test Server Checklist

1. Start Paper/Purpur 1.20.4+ on Java 21 with Vault and an economy provider.
2. Verify schema migrations and no severe startup error.
3. Run `/marketadmin status` and inspect provider/database details.
4. Open every main-menu destination and page through asset lists.
5. Test a fresh quote, cached quote, forced refresh, unavailable provider, timeout, and rate-limit response.
6. Buy whole and fractional quantities; verify subtotal, fee, final total, balance, holding, average price, transaction, statistic, and audit rows.
7. Attempt insufficient funds, zero/negative input, stale quote, duplicate confirm click, market-closed trade, cooldown violation, below-minimum, above-maximum, and unsupported fractional trade.
8. Sell 25%, 50%, 75%, all, and more than owned.
9. Disconnect during quote loading and during a confirmation menu.
10. Force database/economy failure in a staging environment and verify compensation logs.
11. Add, remove, sort, and overflow a watchlist.
12. Create each alert type; pause, resume, edit, trigger, and delete it.
13. Verify chat, action bar, sound, title, and webhook notification preferences.
14. View every chart period and confirm history cache reuse.
15. Refresh leaderboards and compare values against portfolio snapshots/statistics.
16. Test every documented PlaceholderAPI placeholder before and after player data loads.
17. Reload YAML files and verify menu text, locale, currency, providers, assets, sounds, market hours, and scheduler intervals update.
18. Restart the server and verify persisted holdings, transactions, settings, cache, and migration state.
19. Stop the server during activity and confirm executor/task/database shutdown is clean.
20. Load-test concurrent menu opens and trades with a staging copy of the economy/database.

## Production Gate

Do not deploy until the selected data provider's symbol coverage, delay, rate limits, and terms have been validated for the enabled asset list. Never test compensation or destructive administrator commands against an unbacked-up production database.
