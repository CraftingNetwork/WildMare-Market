package com.wildmare.market.util;

import com.wildmare.market.model.HistoryPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartUtilTest {
    @Test
    void emptyHistoryUsesNeutralMarker() {
        assertEquals("—", ChartUtil.sparkline(List.of(), 20));
    }

    @Test
    void chartIsDownsampledToRequestedWidth() {
        List<HistoryPoint> points = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> new HistoryPoint(
                        Instant.EPOCH.plusSeconds(index), BigDecimal.valueOf(index + 1L)))
                .toList();
        String chart = ChartUtil.sparkline(points, 16);
        assertEquals(16, chart.codePointCount(0, chart.length()));
        assertTrue(chart.startsWith("▁"));
        assertTrue(chart.endsWith("█"));
    }

    @Test
    void flatHistoryProducesAStableLine() {
        List<HistoryPoint> points = List.of(
                new HistoryPoint(Instant.EPOCH, BigDecimal.TEN),
                new HistoryPoint(Instant.EPOCH.plusSeconds(60), BigDecimal.TEN),
                new HistoryPoint(Instant.EPOCH.plusSeconds(120), BigDecimal.TEN));
        assertEquals("▅▅▅", ChartUtil.sparkline(points, 8));
    }
}
