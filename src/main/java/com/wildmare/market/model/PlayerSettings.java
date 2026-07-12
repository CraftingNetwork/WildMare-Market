package com.wildmare.market.model;

import java.util.UUID;

/** Immutable player notification preferences. */
public record PlayerSettings(
        UUID playerId,
        boolean alertChat,
        boolean alertActionBar,
        boolean alertSound,
        boolean alertTitle,
        boolean movementNotifications
) {
    public PlayerSettings toggle(String key) {
        return switch (key) {
            case "alert_chat" -> new PlayerSettings(playerId, !alertChat, alertActionBar,
                    alertSound, alertTitle, movementNotifications);
            case "alert_actionbar" -> new PlayerSettings(playerId, alertChat, !alertActionBar,
                    alertSound, alertTitle, movementNotifications);
            case "alert_sound" -> new PlayerSettings(playerId, alertChat, alertActionBar,
                    !alertSound, alertTitle, movementNotifications);
            case "alert_title" -> new PlayerSettings(playerId, alertChat, alertActionBar,
                    alertSound, !alertTitle, movementNotifications);
            case "movement_notifications" -> new PlayerSettings(playerId, alertChat, alertActionBar,
                    alertSound, alertTitle, !movementNotifications);
            default -> this;
        };
    }

    public static PlayerSettings defaults(UUID playerId) {
        return new PlayerSettings(playerId, true, true, true, true, true);
    }
}
