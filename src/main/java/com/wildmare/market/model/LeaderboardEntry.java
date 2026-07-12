package com.wildmare.market.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Immutable ranked leaderboard row. */
public record LeaderboardEntry(UUID playerId, String playerName, BigDecimal value, int rank) {
}
