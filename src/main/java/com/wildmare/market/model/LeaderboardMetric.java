package com.wildmare.market.model;

import java.util.Locale;

/** Supported portfolio and activity ranking metrics. */
public enum LeaderboardMetric {
    PORTFOLIO_VALUE,
    REALIZED_PROFIT,
    PERCENT_RETURN,
    MOST_ACTIVE,
    DAILY_PERFORMANCE,
    WEEKLY_PERFORMANCE,
    MONTHLY_PERFORMANCE;

    public static LeaderboardMetric parse(String raw) {
        if (raw == null || raw.isBlank()) return PORTFOLIO_VALUE;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "value", "portfolio", "portfolio_value" -> PORTFOLIO_VALUE;
            case "realized", "realized_profit" -> REALIZED_PROFIT;
            case "return", "percent", "percent_return" -> PERCENT_RETURN;
            case "active", "most_active" -> MOST_ACTIVE;
            case "daily", "day" -> DAILY_PERFORMANCE;
            case "weekly", "week" -> WEEKLY_PERFORMANCE;
            case "monthly", "month" -> MONTHLY_PERFORMANCE;
            default -> PORTFOLIO_VALUE;
        };
    }
}
