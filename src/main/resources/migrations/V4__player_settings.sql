CREATE TABLE IF NOT EXISTS wm_player_settings (
  player_uuid VARCHAR(36) PRIMARY KEY,
  alert_chat INTEGER NOT NULL DEFAULT 1,
  alert_actionbar INTEGER NOT NULL DEFAULT 1,
  alert_sound INTEGER NOT NULL DEFAULT 1,
  alert_title INTEGER NOT NULL DEFAULT 1,
  movement_notifications INTEGER NOT NULL DEFAULT 1,
  updated_at BIGINT NOT NULL
);
