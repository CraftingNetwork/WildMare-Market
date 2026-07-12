CREATE TABLE IF NOT EXISTS wm_asset_trade_counts (
  player_uuid VARCHAR(36) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  trade_count INTEGER NOT NULL DEFAULT 0,
  trade_volume DECIMAL(30,10) NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (player_uuid, symbol)
);
CREATE INDEX IF NOT EXISTS idx_wm_asset_trade_counts_rank
ON wm_asset_trade_counts (player_uuid, trade_count, trade_volume);
