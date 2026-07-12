# Financial API Setup

WildMare Market includes provider abstraction and three implementations.

## Finnhub

Official API documentation: `https://finnhub.io/docs/api`

1. Create a Finnhub account and obtain an API token.
2. Open `providers.yml`.
3. Set `providers.finnhub.enabled` to `true`.
4. Replace `PUT_YOUR_FINNHUB_KEY_HERE` with the token.
5. Keep the base URL unless your plan or proxy requires another endpoint.
6. Confirm configured stock/ETF/index symbols are supported by the account plan.
7. Run `/marketadmin provider finnhub` and `/marketadmin refresh AAPL`.

The implementation uses the quote endpoint for current quotes and the candle endpoint for historical bars. Provider plans may restrict historical coverage or certain market classes.

## CoinGecko

Official API documentation: `https://docs.coingecko.com/`

The implementation uses CoinGecko coin IDs from `assets.yml`, the simple price endpoint for quotes, and market chart data for history.

For a Demo API key:

1. Enter the key in `providers.coingecko.api-key`.
2. Keep `api-key-header` as `x-cg-demo-api-key`.
3. Keep the provider enabled.
4. Test with `/marketadmin refresh BTC`.

Without a key, public access may still work but can be more limited. Respect provider rate limits and terms.

## Fictional Provider

The fictional provider requires no external API and is intended for server-created assets, demonstrations, development, and fallback testing. It generates bounded virtual movement around an internal price state. It is not presented as a real-world quote source.

Create a fictional asset with:

```text
/marketadmin addasset WILD2
```

Or edit `assets.yml` for full control over the name, initial price, material, and model data.

## Fallback Provider

Set `fallback-provider` to a provider ID only when that provider can meaningfully price the affected asset. Do not route a real symbol to a fictional fallback unless players are clearly informed that the price is simulated.

## Rate Limits and Cache

- Quotes are shared across players.
- Fresh cached values are returned without another provider call.
- Refresh tasks operate in batches.
- In-flight duplicate requests are coalesced.
- HTTP concurrency, retries, delay, timeout, quote TTL, and history TTL are configurable.
- Persistent quote cache can provide the latest previously stored value after restart, but stale prices remain blocked from trading unless explicitly allowed.
