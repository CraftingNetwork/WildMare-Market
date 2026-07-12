package com.wildmare.market.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecimalUtilTest {
    @Test
    void feeHonorsPercentageAndMinimum() {
        assertEquals(0, DecimalUtil.fee(
                new BigDecimal("1000"), new BigDecimal("0.5"), new BigDecimal("0.01"))
                .compareTo(new BigDecimal("5")));
        assertEquals(0, DecimalUtil.fee(
                new BigDecimal("1"), new BigDecimal("0.5"), new BigDecimal("0.01"))
                .compareTo(new BigDecimal("0.01")));
    }

    @Test
    void percentageHandlesZeroBase() {
        assertEquals(BigDecimal.ZERO, DecimalUtil.percentage(BigDecimal.TEN, BigDecimal.ZERO));
        assertEquals(0, DecimalUtil.percentage(
                new BigDecimal("25"), new BigDecimal("100")).compareTo(new BigDecimal("25")));
    }

    @Test
    void fractionDetectionIgnoresTrailingZeroes() {
        assertFalse(DecimalUtil.hasFraction(new BigDecimal("5.000")));
        assertTrue(DecimalUtil.hasFraction(new BigDecimal("5.125")));
    }
}
