package com.wildmare.market.model;

import java.time.Duration;
import java.util.Locale;

/** Supported historical chart windows. */
public enum HistoryPeriod {
    ONE_HOUR("1h", Duration.ofHours(1)),
    ONE_DAY("1d", Duration.ofDays(1)),
    SEVEN_DAYS("7d", Duration.ofDays(7)),
    THIRTY_DAYS("30d", Duration.ofDays(30)),
    NINETY_DAYS("90d", Duration.ofDays(90)),
    ONE_YEAR("1y", Duration.ofDays(365));

    private final String key;
    private final Duration duration;

    HistoryPeriod(String key, Duration duration) {
        this.key = key;
        this.duration = duration;
    }

    public String key() {
        return key;
    }

    public Duration duration() {
        return duration;
    }

    public static HistoryPeriod parse(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        for (HistoryPeriod period : values()) {
            if (period.key.equals(normalized)) return period;
        }
        return SEVEN_DAYS;
    }
}
