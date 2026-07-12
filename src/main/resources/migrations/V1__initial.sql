CREATE TABLE IF NOT EXISTS wm_schema_history (
  version INTEGER PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  installed_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS wm_players (
  player_uuid VARCHAR(36) PRIMARY KEY,
  last_known_name VARCHAR(32) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS wm_portfolios (
  player_uuid VARCHAR(36) PRIMARY KEY,
  realized_profit DECIMAL(30,10) NOT NULL DEFAULT 0,
  total_invested DECIMAL(30,10) NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS wm_holdings (
  player_uuid VARCHAR(36) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  quantity DECIMAL(30,10) NOT NULL,
  average_price DECIMAL(30,10) NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (player_uuid, symbol)
);
CREATE TABLE IF NOT EXISTS wm_transactions (
  transaction_id VARCHAR(36) PRIMARY KEY,
  player_uuid VARCHAR(36) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  transaction_type VARCHAR(16) NOT NULL,
  quantity DECIMAL(30,10) NOT NULL,
  unit_price DECIMAL(30,10) NOT NULL,
  subtotal DECIMAL(30,10) NOT NULL,
  fee DECIMAL(30,10) NOT NULL,
  total DECIMAL(30,10) NOT NULL,
  realized_profit DECIMAL(30,10) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  failure_reason VARCHAR(255),
  price_timestamp BIGINT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS wm_watchlists (
  player_uuid VARCHAR(36) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (player_uuid, symbol)
);
CREATE TABLE IF NOT EXISTS wm_price_alerts (
  alert_id VARCHAR(36) PRIMARY KEY,
  player_uuid VARCHAR(36) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  condition_type VARCHAR(32) NOT NULL,
  target_value DECIMAL(30,10) NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  triggered INTEGER NOT NULL DEFAULT 0,
  last_triggered_at BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS wm_market_cache (
  symbol VARCHAR(32) PRIMARY KEY,
  provider VARCHAR(32) NOT NULL,
  price DECIMAL(30,10) NOT NULL,
  change_amount DECIMAL(30,10) NOT NULL,
  change_percent DECIMAL(30,10) NOT NULL,
  open_price DECIMAL(30,10) NOT NULL,
  high_price DECIMAL(30,10) NOT NULL,
  low_price DECIMAL(30,10) NOT NULL,
  previous_close DECIMAL(30,10) NOT NULL,
  volume DECIMAL(30,10) NOT NULL,
  source_timestamp BIGINT NOT NULL,
  cached_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS wm_asset_history (
  symbol VARCHAR(32) NOT NULL,
  period_key VARCHAR(16) NOT NULL,
  price_timestamp BIGINT NOT NULL,
  price DECIMAL(30,10) NOT NULL,
  PRIMARY KEY (symbol, period_key, price_timestamp)
);
CREATE TABLE IF NOT EXISTS wm_player_statistics (
  player_uuid VARCHAR(36) PRIMARY KEY,
  total_trades INTEGER NOT NULL DEFAULT 0,
  successful_purchases INTEGER NOT NULL DEFAULT 0,
  successful_sales INTEGER NOT NULL DEFAULT 0,
  total_trading_volume DECIMAL(30,10) NOT NULL DEFAULT 0,
  total_fees_paid DECIMAL(30,10) NOT NULL DEFAULT 0,
  total_realized_profit DECIMAL(30,10) NOT NULL DEFAULT 0,
  total_realized_loss DECIMAL(30,10) NOT NULL DEFAULT 0,
  largest_profitable_trade DECIMAL(30,10) NOT NULL DEFAULT 0,
  largest_losing_trade DECIMAL(30,10) NOT NULL DEFAULT 0,
  most_traded_asset VARCHAR(32),
  first_trade_at BIGINT NOT NULL DEFAULT 0,
  last_trade_at BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS wm_audit_logs (
  audit_id VARCHAR(36) PRIMARY KEY,
  player_uuid VARCHAR(36),
  action VARCHAR(64) NOT NULL,
  details VARCHAR(2048) NOT NULL,
  created_at BIGINT NOT NULL
);
