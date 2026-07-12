package com.wildmare.market.market;

import com.wildmare.market.config.ConfigManager;
import com.wildmare.market.model.AssetDefinition;
import com.wildmare.market.model.AssetType;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Evaluates configurable trading sessions without assuming the server time zone. */
public final class MarketHoursService {
    private final ConfigManager configManager;
    private volatile boolean enabled;
    private volatile ZoneId zoneId;
    private volatile LocalTime opening;
    private volatile LocalTime closing;
    private volatile Set<DayOfWeek> tradingDays = Set.of();
    private volatile Set<LocalDate> holidays = Set.of();
    private volatile Set<AssetType> alwaysOpenTypes = Set.of();
    private volatile boolean allowTradesWhileClosed;

    public MarketHoursService(ConfigManager configManager) {
        this.configManager = configManager;
        reload();
    }

    public synchronized void reload() {
        YamlConfiguration config = configManager.get("market-hours.yml");
        enabled = config.getBoolean("enabled", true);
        zoneId = ZoneId.of(config.getString("timezone", "America/New_York"));
        opening = LocalTime.parse(config.getString("opening-time", "09:30"));
        closing = LocalTime.parse(config.getString("closing-time", "16:00"));

        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String day : config.getStringList("trading-days")) {
            days.add(DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)));
        }
        if (config.getBoolean("weekend-trading", false)) {
            days.add(DayOfWeek.SATURDAY);
            days.add(DayOfWeek.SUNDAY);
        }
        tradingDays = Set.copyOf(days);

        Set<LocalDate> closedDates = new HashSet<>();
        for (String holiday : config.getStringList("holiday-closures")) {
            closedDates.add(LocalDate.parse(holiday));
        }
        holidays = Set.copyOf(closedDates);

        EnumSet<AssetType> openTypes = EnumSet.noneOf(AssetType.class);
        for (String type : config.getStringList("always-open-types")) {
            openTypes.add(AssetType.parse(type));
        }
        alwaysOpenTypes = Set.copyOf(openTypes);
        allowTradesWhileClosed = config.getBoolean("allow-trades-while-closed", false);
    }

    public boolean isOpen(AssetDefinition asset) {
        return !enabled || alwaysOpenTypes.contains(asset.type()) || isConfiguredSessionOpen();
    }

    /** Returns the status of the configured traditional-market session. */
    public String globalStatus() {
        YamlConfiguration config = configManager.get("market-hours.yml");
        return !enabled || isConfiguredSessionOpen()
                ? config.getString("status-open", "Market Open")
                : config.getString("status-closed", "Market Closed");
    }

    private boolean isConfiguredSessionOpen() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        if (!tradingDays.contains(now.getDayOfWeek())) return false;
        if (holidays.contains(now.toLocalDate())) return false;
        LocalTime time = now.toLocalTime();
        if (opening.equals(closing)) return true;
        if (opening.isBefore(closing)) {
            return !time.isBefore(opening) && time.isBefore(closing);
        }
        return !time.isBefore(opening) || time.isBefore(closing);
    }

    public boolean canTrade(AssetDefinition asset) {
        return allowTradesWhileClosed || isOpen(asset);
    }

    public boolean restrictionsEnabled() {
        return enabled;
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public String status(AssetDefinition asset) {
        return isOpen(asset) ? "OPEN" : "CLOSED";
    }
}
