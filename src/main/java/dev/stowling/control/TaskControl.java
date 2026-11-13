package dev.stowling.control;

import java.util.List;
import java.util.UUID;

public interface TaskControl {
    List<TaskInfo> tasks();

    TaskInfo requestPickup(PickupRequest request);

    TaskInfo cancelTask(UUID taskId);
}
