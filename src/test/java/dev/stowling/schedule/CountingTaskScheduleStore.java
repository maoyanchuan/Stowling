package dev.stowling.schedule;

import java.util.List;

final class CountingTaskScheduleStore implements TaskScheduleStore {
    private final InMemoryTaskScheduleStore delegate = new InMemoryTaskScheduleStore();
    private int saveCount;

    int saveCount() {
        return saveCount;
    }

    @Override
    public List<TaskScheduleRecord> load() {
        return delegate.load();
    }

    @Override
    public void save(TaskScheduleRecord record) {
        saveCount++;
        delegate.save(record);
    }
}
