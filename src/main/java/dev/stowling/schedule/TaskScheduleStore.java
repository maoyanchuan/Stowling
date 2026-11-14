package dev.stowling.schedule;

import java.util.List;

public interface TaskScheduleStore {
    List<TaskScheduleRecord> load();

    void save(TaskScheduleRecord record);
}
