package dev.stowling.v2.control;

import dev.stowling.v2.model.TaskFolderDefinition;
import java.util.List;
import java.util.Objects;

public record TaskFolderListResponse(
        String revision,
        List<TaskFolderDefinition> folders
) {
    public TaskFolderListResponse {
        revision = Objects.requireNonNull(revision, "revision");
        folders = List.copyOf(Objects.requireNonNull(folders, "folders"));
    }
}
