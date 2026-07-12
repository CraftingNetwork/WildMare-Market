package com.wildmare.market.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Formatting utilities for money, numbers, time, locale, and MiniMessage values. */
public final class TextUtil {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private TextUtil() {
    }

    public static String replace(String input, Map<String, ?> placeholders) {
        String output = input == null ? "" : input;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            output = output.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return output;
    }

    public static Component component(String miniMessage, Map<String, ?> placeholders) {
        return MINI_MESSAGE.deserialize(replace(miniMessage, placeholders));
    }

    public static Component component(String miniMessage) {
        return MINI_MESSAGE.deserialize(miniMessage == null ? "" : miniMessage);
    }

    public static List<Component> components(List<String> lines, Map<String, ?> placeholders) {
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(component(line, placeholders));
        }
        return result;
    }

    public static String currency(BigDecimal value, String symbol, int decimals, Locale locale) {
        NumberFormat formatter = NumberFormat.getNumberInstance(locale);
        formatter.setMinimumFractionDigits(decimals);
        formatter.setMaximumFractionDigits(decimals);
        return symbol + formatter.format(value == null ? BigDecimal.ZERO : value);
    }

    public static String decimal(BigDecimal value, int decimals) {
        if (value == null) return "0";
        return value.setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public static String age(Instant instant) {
        if (instant == null) return "unavailable";
        long seconds = Math.max(0, Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60) return seconds + " seconds ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " minutes ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hours ago";
        return (hours / 24) + " days ago";
    }

    public static String timestamp(Instant instant) {
        return instant == null ? "unavailable" : DATE_FORMAT.format(instant);
    }

    public static String colorName(BigDecimal value) {
        if (value == null || value.signum() == 0) return "gray";
        return value.signum() > 0 ? "green" : "red";
    }

    public static Locale parseLocale(String raw) {
        if (raw == null || raw.isBlank()) return Locale.US;
        Locale locale = Locale.forLanguageTag(raw);
        return locale.getLanguage().isBlank() ? Locale.US : locale;
    }
}
