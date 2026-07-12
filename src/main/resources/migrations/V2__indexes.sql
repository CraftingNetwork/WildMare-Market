CREATE INDEX IF NOT EXISTS idx_wm_transactions_player_created ON wm_transactions (player_uuid, created_at);
CREATE INDEX IF NOT EXISTS idx_wm_transactions_symbol_created ON wm_transactions (symbol, created_at);
CREATE INDEX IF NOT EXISTS idx_wm_alerts_active ON wm_price_alerts (active, symbol);
CREATE INDEX IF NOT EXISTS idx_wm_holdings_symbol ON wm_holdings (symbol);
CREATE INDEX IF NOT EXISTS idx_wm_history_symbol_period ON wm_asset_history (symbol, period_key, price_timestamp);
