package dev.stowling.schedule;

import java.util.List;
import java.util.Objects;

public record TaskTrigger(
        TriggerKind kind,
        long intervalSeconds,
        List<String> times,
        String zoneId,
        List<String> regionIds
) {
    public static final String DEFAULT_ZONE_ID = "Asia/Shanghai";

    public TaskTrigger {
        kind = kind == null ? TriggerKind.MANUAL : kind;
        times = List.copyOf(times == null ? List.of() : times);
        zoneId = normalizeZoneId(zoneId);
        regionIds = List.copyOf(regionIds == null ? List.of() : regionIds);
    }

    public TaskTrigger() {
        this(TriggerKind.MANUAL, 0L, List.of(), DEFAULT_ZONE_ID, List.of());
    }

    private static String normalizeZoneId(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_ZONE_ID;
        }
        return Objects.requireNonNull(value, "zoneId").trim();
    }
}
