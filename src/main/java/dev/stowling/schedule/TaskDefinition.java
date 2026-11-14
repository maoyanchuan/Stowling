package dev.stowling.schedule;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.PickupWorkflow;
import java.util.List;
import java.util.Objects;

public record TaskDefinition(
        String kind,
        String id,
        String name,
        TaskSettings settings,
        PickupWorkflow pickup,
        List<AutomationStep> steps
) {
    public TaskDefinition {
        kind = validateTaskKind(kind);
        id = requireText(id, "task id");
        name = requireText(name, "task name");
        settings = Objects.requireNonNull(settings, "task settings");
        steps = List.copyOf(Objects.requireNonNull(steps, "automation steps"));
        validateShape(kind, pickup, steps);
        validatePickupIdentity(kind, id, name, pickup);
        if (TaskKey.PICKUP.equals(kind)) {
            pickup = withSettings(pickup, settings);
        }
    }

    public TaskKey key() {
        return new TaskKey(kind, id);
    }

    public static TaskDefinition from(PickupWorkflow workflow) {
        Objects.requireNonNull(workflow, "pickup workflow");
        return new TaskDefinition(
                TaskKey.PICKUP,
                workflow.id(),
                workflow.name(),
                workflow.settings(),
                workflow,
                List.of()
        );
    }

    public static TaskDefinition from(AutomationWorkflow workflow) {
        Objects.requireNonNull(workflow, "automation workflow");
        return new TaskDefinition(
                TaskKey.AUTOMATION,
                workflow.id(),
                workflow.name(),
                workflow.settings(),
                null,
                workflow.steps()
        );
    }

    public PickupWorkflow toPickup() {
        if (!TaskKey.PICKUP.equals(kind)) {
            throw new IllegalStateException("task is not a pickup task: " + key());
        }
        return pickup;
    }

    public AutomationWorkflow toAutomation() {
        if (!TaskKey.AUTOMATION.equals(kind)) {
            throw new IllegalStateException("task is not an automation task: " + key());
        }
        return new AutomationWorkflow(id, name, steps, settings);
    }

    private static void validateShape(
            String kind,
            PickupWorkflow pickup,
            List<AutomationStep> steps
    ) {
        if (TaskKey.PICKUP.equals(kind)) {
            Objects.requireNonNull(pickup, "pickup workflow");
            if (!steps.isEmpty()) {
                throw new IllegalArgumentException("pickup task must not contain steps");
            }
            return;
        }
        if (pickup != null) {
            throw new IllegalArgumentException("automation task must not contain a pickup");
        }
    }

    private static void validatePickupIdentity(
            String kind,
            String id,
            String name,
            PickupWorkflow pickup
    ) {
        if (!TaskKey.PICKUP.equals(kind)) {
            return;
        }
        if (!id.equals(pickup.id()) || !name.equals(pickup.name())) {
            throw new IllegalArgumentException("pickup task identity must match its workflow");
        }
    }

    private static PickupWorkflow withSettings(
            PickupWorkflow workflow,
            TaskSettings settings
    ) {
        return new PickupWorkflow(
                workflow.id(),
                workflow.name(),
                workflow.dimension(),
                workflow.regions(),
                workflow.cacheIds(),
                workflow.cachePolicy(),
                workflow.classificationTiming(),
                workflow.completionMode(),
                workflow.completionIdleDelay(),
                settings
        );
    }

    private static String validateTaskKind(String kind) {
        String value = requireText(kind, "task kind");
        if (!TaskKey.PICKUP.equals(value) && !TaskKey.AUTOMATION.equals(value)) {
            throw new IllegalArgumentException("unsupported task kind: " + value);
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
