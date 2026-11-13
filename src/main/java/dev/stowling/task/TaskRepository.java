package dev.stowling.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository {
    void save(TaskSnapshot task);

    Optional<TaskSnapshot> findById(UUID id);

    List<TaskSnapshot> findAll();
}
