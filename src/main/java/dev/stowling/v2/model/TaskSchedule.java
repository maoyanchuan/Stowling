package dev.stowling.v2.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

public record TaskSchedule(Mode mode, String time, String zoneId, Integer intervalMinutes,
        Long anchorEpochSecond) {
    public enum Mode { MANUAL, DAILY, INTERVAL }

    public TaskSchedule {
        Objects.requireNonNull(mode, "schedule mode");
        switch (mode) {
            case MANUAL -> {
                if (time != null || zoneId != null || intervalMinutes != null
                        || anchorEpochSecond != null) {
                    throw new IllegalArgumentException("仅手动模式无需填写定时时间");
                }
            }
            case DAILY -> {
                if (time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
                        || zoneId == null || intervalMinutes != null || anchorEpochSecond != null) {
                    throw new IllegalArgumentException("每日定时需要 HH:mm 和时区");
                }
                try {
                    ZoneId.of(zoneId);
                } catch (java.time.DateTimeException exception) {
                    throw new IllegalArgumentException("定时时区无效", exception);
                }
            }
            case INTERVAL -> {
                if (intervalMinutes == null || intervalMinutes < 1
                        || time != null || zoneId != null) {
                    throw new IllegalArgumentException("定时间隔必须为正整数分钟");
                }
                if (anchorEpochSecond != null) {
                    Instant.ofEpochSecond(anchorEpochSecond);
                }
            }
        }
    }

    public TaskSchedule activate(TaskSchedule previous, Instant now) {
        if (mode != Mode.INTERVAL) {
            return this;
        }
        if (previous != null && previous.mode == mode
                && intervalMinutes.equals(previous.intervalMinutes)) {
            return previous;
        }
        long anchor = now.getEpochSecond() + (now.getNano() == 0 ? 0 : 1);
        return new TaskSchedule(mode, null, null, intervalMinutes, anchor);
    }

    public Instant nextAfter(Instant instant) {
        if (mode == Mode.INTERVAL) {
            long interval = intervalMinutes * 60L;
            long elapsed = Math.subtractExact(instant.getEpochSecond(), anchorEpochSecond);
            long occurrences = Math.max(0L, Math.floorDiv(elapsed, interval)) + 1;
            return Instant.ofEpochSecond(Math.addExact(anchorEpochSecond,
                    Math.multiplyExact(occurrences, interval)));
        }
        if (mode != Mode.DAILY) {
            throw new IllegalStateException("手动任务没有定时时间");
        }
        ZoneId zone = ZoneId.of(zoneId);
        LocalTime localTime = LocalTime.parse(time);
        LocalDate date = instant.atZone(zone).toLocalDate();
        while (true) {
            var local = date.atTime(localTime);
            var offsets = zone.getRules().getValidOffsets(local);
            if (!offsets.isEmpty()) {
                Instant candidate = local.toInstant(offsets.getFirst());
                if (candidate.isAfter(instant)) {
                    return candidate;
                }
            }
            date = date.plusDays(1);
        }
    }
}
