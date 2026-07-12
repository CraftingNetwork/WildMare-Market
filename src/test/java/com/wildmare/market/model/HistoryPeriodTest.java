package com.wildmare.market.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryPeriodTest {
    @Test
    void parsesSupportedPeriodKeysCaseInsensitively() {
        assertEquals(HistoryPeriod.ONE_HOUR, HistoryPeriod.parse("1H"));
        assertEquals(HistoryPeriod.ONE_YEAR, HistoryPeriod.parse("1y"));
    }

    @Test
    void invalidPeriodFallsBackToSevenDays() {
        assertEquals(HistoryPeriod.SEVEN_DAYS, HistoryPeriod.parse("unknown"));
        assertEquals(Duration.ofDays(7), HistoryPeriod.SEVEN_DAYS.duration());
    }
}
