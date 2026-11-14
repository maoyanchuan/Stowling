package dev.stowling.schedule;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryTaskScheduleStore implements TaskScheduleStore {
    private final Map<TaskKey, TaskScheduleRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized List<TaskScheduleRecord> load() {
        return records.values().stream()
                .sorted(Comparator
                        .comparing((TaskScheduleRecord record) -> record.key().kind())
                        .thenComparing(record -> record.key().id()))
                .toList();
    }

    @Override
    public synchronized void save(TaskScheduleRecord record) {
        Objects.requireNonNull(record, "record");
        records.put(record.key(), record);
    }
}
