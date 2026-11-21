package dev.stowling.v2.control;

import java.util.List;
import java.util.UUID;

public record AutoEatTaskListResponse(String revision, UUID taskId, List<AutoEatTask> items) {
    public AutoEatTaskListResponse {
        items = List.copyOf(items);
    }
}
