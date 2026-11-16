package dev.stowling.v2.control;

import java.util.List;

public record AutoClassifyTaskListResponse(
        String revision,
        List<AutoClassifyTask> items
) {
    public AutoClassifyTaskListResponse {
        items = List.copyOf(items);
    }
}
