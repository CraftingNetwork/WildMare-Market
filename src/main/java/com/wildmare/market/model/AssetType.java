package com.wildmare.market.model;

import java.util.Locale;

/** Supported market instrument categories. */
public enum AssetType {
    STOCK, ETF, INDEX, CRYPTO, FOREX, COMMODITY, FICTIONAL;

    public static AssetType parse(String value) {
        return AssetType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
