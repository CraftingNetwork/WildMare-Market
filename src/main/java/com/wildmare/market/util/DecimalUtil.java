package com.wildmare.market.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact decimal helpers for quantities, percentages, and transaction fees. */
public final class DecimalUtil {
    public static final int INTERNAL_SCALE = 12;

    private DecimalUtil() {
    }

    public static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal quantity(String value) {
        return new BigDecimal(value).setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal fee(BigDecimal subtotal, BigDecimal percent, BigDecimal minimum) {
        BigDecimal calculated = subtotal.multiply(percent)
                .divide(BigDecimal.valueOf(100), INTERNAL_SCALE, RoundingMode.HALF_UP);
        return calculated.max(minimum).setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal percentage(BigDecimal amount, BigDecimal base) {
        if (base == null || base.signum() == 0) return BigDecimal.ZERO;
        return amount.multiply(BigDecimal.valueOf(100))
                .divide(base, INTERNAL_SCALE, RoundingMode.HALF_UP);
    }

    public static boolean hasFraction(BigDecimal value) {
        return value.stripTrailingZeros().scale() > 0;
    }

    public static BigDecimal maxZero(BigDecimal value) {
        return value.max(BigDecimal.ZERO);
    }
}
