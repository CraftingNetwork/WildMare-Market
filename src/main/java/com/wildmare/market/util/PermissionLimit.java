package com.wildmare.market.util;

import org.bukkit.entity.Player;

/** Resolves numbered permission limits within configured bounds. */
public final class PermissionLimit {
    private PermissionLimit() {
    }

    public static int resolve(Player player, String prefix, int defaultLimit, int maximumLimit) {
        int resolved = defaultLimit;
        for (org.bukkit.permissions.PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String permission = info.getPermission();
            if (!info.getValue() || !permission.startsWith(prefix)) continue;
            String suffix = permission.substring(prefix.length());
            try {
                resolved = Math.max(resolved, Integer.parseInt(suffix));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.min(maximumLimit, resolved);
    }
}
