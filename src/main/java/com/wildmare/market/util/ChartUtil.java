package com.wildmare.market.util;

import com.wildmare.market.model.HistoryPoint;

import java.math.BigDecimal;
import java.util.List;

/** Builds compact Unicode price sparklines for Minecraft text surfaces. */
public final class ChartUtil {
    private static final char[] BLOCKS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private ChartUtil() {
    }

    public static String sparkline(List<HistoryPoint> points, int maximumWidth) {
        if (points == null || points.isEmpty()) return "—";
        List<HistoryPoint> sampled = downsample(points, Math.max(2, maximumWidth));
        BigDecimal min = sampled.stream().map(HistoryPoint::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = sampled.stream().map(HistoryPoint::price).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal range = max.subtract(min);

        StringBuilder builder = new StringBuilder(sampled.size());
        for (HistoryPoint point : sampled) {
            int index;
            if (range.signum() == 0) {
                index = BLOCKS.length / 2;
            } else {
                double normalized = point.price().subtract(min).doubleValue() / range.doubleValue();
                index = Math.min(BLOCKS.length - 1, Math.max(0, (int) Math.round(normalized * (BLOCKS.length - 1))));
            }
            builder.append(BLOCKS[index]);
        }
        return builder.toString();
    }

    private static List<HistoryPoint> downsample(List<HistoryPoint> points, int width) {
        if (points.size() <= width) return points;
        java.util.ArrayList<HistoryPoint> result = new java.util.ArrayList<>(width);
        double step = (double) (points.size() - 1) / (width - 1);
        for (int i = 0; i < width; i++) {
            result.add(points.get((int) Math.round(i * step)));
        }
        return result;
    }
}
