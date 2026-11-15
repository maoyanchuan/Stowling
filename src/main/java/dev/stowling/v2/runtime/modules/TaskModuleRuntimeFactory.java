package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.TaskStep;

public interface TaskModuleRuntimeFactory {
    String moduleId();

    TaskModuleExecutor create(TaskStep step, TaskExecutionContext context);
}
