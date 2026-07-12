CREATE TABLE IF NOT EXISTS wm_portfolio_snapshots (
  player_uuid VARCHAR(36) NOT NULL,
  snapshot_timestamp BIGINT NOT NULL,
  portfolio_value DECIMAL(30,10) NOT NULL,
  PRIMARY KEY (player_uuid, snapshot_timestamp)
);
CREATE INDEX IF NOT EXISTS idx_wm_snapshots_player_time
ON wm_portfolio_snapshots (player_uuid, snapshot_timestamp);
