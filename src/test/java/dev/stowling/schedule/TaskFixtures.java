package dev.stowling.schedule;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class TaskFixtures {
    private TaskFixtures() {
    }

    static TaskDefinition task(
            String id,
            TaskPriority priority,
            TaskTrigger trigger,
            boolean autoStartOnRestart,
            TaskInterruption interruption
    ) {
        return task(
                id,
                priority,
                trigger,
                autoStartOnRestart,
                interruption,
                List.of()
        );
    }

    static TaskDefinition task(
            String id,
            TaskPriority priority,
            TaskTrigger trigger,
            boolean autoStartOnRestart,
            TaskInterruption interruption,
            List<AutomationStep> steps
    ) {
        TaskSettings settings = new TaskSettings(
                priority,
                trigger,
                autoStartOnRestart,
                interruption
        );
        return new TaskDefinition(
                TaskKey.AUTOMATION,
                id,
                id,
                settings,
                null,
                steps
        );
    }

    static TaskDefinition pickup(String id, TaskSettings settings) {
        PickupWorkflow workflow = new PickupWorkflow(
                id,
                id,
                "minecraft:overworld",
                List.of(new PickupRegionBinding("region-a", 100, Duration.ZERO)),
                List.of(),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.ALL_IDLE,
                Duration.ZERO,
                settings
        );
        return TaskDefinition.from(workflow);
    }

    static AutomationStep waitStep(String id) {
        return new AutomationStep(id, AutomationStepType.WAIT, Map.of());
    }
}
