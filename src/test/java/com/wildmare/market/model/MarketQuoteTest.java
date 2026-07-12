package com.wildmare.market.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketQuoteTest {
    @Test
    void normalizesSymbolAndNullFinancialFields() {
        MarketQuote quote = new MarketQuote("aapl", "test", BigDecimal.TEN,
                null, null, null, null, null, null, null, Instant.EPOCH, Instant.EPOCH);
        assertEquals("AAPL", quote.symbol());
        assertEquals(BigDecimal.ZERO, quote.change());
        assertTrue(quote.isTradable());
    }

    @Test
    void zeroPriceIsNotTradable() {
        MarketQuote quote = new MarketQuote("TEST", "test", BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.EPOCH, Instant.EPOCH);
        assertFalse(quote.isTradable());
    }
}
