package com.wildmare.market.model;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable configuration-backed definition of a tradable virtual asset. */
public record AssetDefinition(
        String symbol,
        String name,
        AssetType type,
        String provider,
        String apiSymbol,
        boolean enabled,
        boolean fractional,
        Material material,
        int customModelData,
        BigDecimal initialPrice
) {
    public AssetDefinition {
        symbol = Objects.requireNonNull(symbol, "symbol").toUpperCase();
        name = Objects.requireNonNull(name, "name");
        type = Objects.requireNonNull(type, "type");
        provider = Objects.requireNonNull(provider, "provider").toLowerCase();
        apiSymbol = Objects.requireNonNull(apiSymbol, "apiSymbol");
        material = material == null ? Material.PAPER : material;
        initialPrice = initialPrice == null ? BigDecimal.ZERO : initialPrice;
    }
}
