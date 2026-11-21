package dev.stowling.v2.control;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskFlowTemplates;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.TaskStepKind;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.model.TaskTriggerType;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record AutoEatTask(UUID id, Settings settings) {
    public record Settings(String name, String foodItemId, UUID coordinateId,
            int foodLevelThreshold, int intervalSeconds, boolean enabled) {
        public Settings {
            foodItemId = Objects.requireNonNull(foodItemId, "foodItemId").trim();
            if (foodLevelThreshold < 0 || foodLevelThreshold >= 20) {
                throw new IllegalArgumentException("饱食度阈值必须在 0 到 19 之间");
            }
            if (intervalSeconds < 1
                    || intervalSeconds > TaskTriggerDefinition.MAX_INTERVAL_TICKS / 20) {
                throw new IllegalArgumentException("检查间隔必须在 1 到 60 秒之间");
            }
        }

        TaskDefinition task(UUID id) {
            String target = coordinateId == null ? null
                    : ResourceReference.of(ResourceType.COORDINATE, coordinateId).encode();
            return new TaskDefinition(id, name, TaskPriority.URGENT,
                    TaskFlowTemplates.scheduledEating(target, foodItemId, foodLevelThreshold));
        }

        TaskTriggerDefinition trigger(TaskDefinition task) {
            int ticks = intervalSeconds * 20;
            return new TaskTriggerDefinition(listenerId(task.id()), task.name(),
                    TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW, task.id(), task.priority(), ticks,
                    Math.max(ticks, TaskTriggerDefinition.DEFAULT_COOLDOWN_TICKS), null, null,
                    foodLevelThreshold, null, null, enabled ? null : true,
                    coordinateId == null ? foodItemId : null);
        }
    }

    static UUID listenerId(UUID taskId) {
        return UUID.nameUUIDFromBytes(("auto-eat:" + taskId).getBytes(StandardCharsets.UTF_8));
    }

    static AutoEatTask from(TaskDefinition task, TaskTriggerDefinition trigger) {
        if (trigger.type() != TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW
                || !trigger.id().equals(listenerId(task.id()))
                || trigger.foodLevelThreshold() >= 20 || trigger.intervalTicks() % 20 != 0) {
            return null;
        }
        if (task.steps().isEmpty()) {
            return null;
        }
        TaskStep hunger = task.steps().getFirst();
        if (hunger.kind() != TaskStepKind.IF || hunger.steps().isEmpty()) {
            return null;
        }
        TaskStep meals = hunger.steps().getLast();
        if (meals.kind() != TaskStepKind.REPEAT || meals.steps().isEmpty()) {
            return null;
        }
        TaskStep eat = meals.steps().getLast();
        if (!ConfigValidator.EAT_FOOD.equals(eat.moduleId())) {
            return null;
        }
        UUID coordinate = null;
        if (meals.steps().size() == 2) {
            TaskStep refill = meals.steps().getFirst();
            TaskStep withdraw = refill.kind() == TaskStepKind.IF && !refill.steps().isEmpty()
                    ? refill.steps().getLast() : refill;
            String reference = withdraw.parameters().get(ConfigValidator.TARGET_REF);
            if (reference == null) {
                return null;
            }
            var parsed = ResourceReference.parse(reference);
            if (parsed.type() != ResourceType.COORDINATE) {
                return null;
            }
            coordinate = parsed.uuid();
        }
        return new AutoEatTask(task.id(), new Settings(task.name(),
                eat.parameters().get(ConfigValidator.FOOD_ITEM_ID), coordinate,
                trigger.foodLevelThreshold(), trigger.intervalTicks() / 20, trigger.enabled()));
    }
}
