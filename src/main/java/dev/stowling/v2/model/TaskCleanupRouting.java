package dev.stowling.v2.model;

import dev.stowling.v2.config.ConfigValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Storage fallbacks copied from the interrupted task's frozen execution plan. */
public record TaskCleanupRouting(boolean legacyOverflowEnabled,
        ResourceReference overflowDestination, ResourceReference robotWarehouse,
        boolean mixedBoxes, boolean overflowPackingEnabled) {
    public static final String OVERFLOW_DESTINATION = "overflowDestinationRef";
    public static final String ROBOT_WAREHOUSE = "robotWarehouseRef";

    public TaskCleanupRouting(boolean legacyOverflowEnabled, ResourceReference overflowDestination,
            ResourceReference robotWarehouse, boolean mixedBoxes) {
        this(legacyOverflowEnabled, overflowDestination, robotWarehouse, mixedBoxes, false);
    }

    public static TaskCleanupRouting fromSteps(List<TaskStep> steps) {
        Map<String, String> parameters = new LinkedHashMap<>();
        collect(steps, parameters);
        // Explicit task destinations supersede the old shared overflow facilities.
        if (parameters.containsKey(OVERFLOW_DESTINATION)
                || parameters.containsKey(ROBOT_WAREHOUSE)) {
            parameters.put("overflowEnabled", "false");
        }
        return fromParameters(parameters);
    }

    private static void collect(List<TaskStep> steps, Map<String, String> parameters) {
        for (TaskStep step : steps) {
            if (ConfigValidator.PACK_OVERFLOW.equals(step.moduleId())) {
                parameters.put("overflowEnabled", "true");
            } else if (ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId())) {
                String destination = step.parameters().get(ConfigValidator.TARGET_REF);
                if (destination != null && !destination.isBlank()) {
                    parameters.put(ROBOT_WAREHOUSE, destination);
                    parameters.put("mixedBoxes",
                            step.parameters().getOrDefault("mixedBoxes", "true"));
                }
            } else if (ClassificationRemainderPolicy.MODULE.equals(step.moduleId())
                    && ClassificationRemainderPolicy.OVERFLOW.equals(
                            step.parameters().get(ClassificationRemainderPolicy.KIND))) {
                parameters.put(OVERFLOW_DESTINATION,
                        step.parameters().get(ConfigValidator.TARGET_REF));
                parameters.put(ClassificationRemainderPolicy.OVERFLOW_PACKING_ENABLED,
                        step.parameters().getOrDefault(
                                ClassificationRemainderPolicy.OVERFLOW_PACKING_ENABLED, "false"));
            }
            collect(step.steps(), parameters);
            collect(step.otherwise(), parameters);
        }
    }

    public static TaskCleanupRouting fromParameters(Map<String, String> parameters) {
        return new TaskCleanupRouting(Boolean.parseBoolean(parameters.get("overflowEnabled")),
                reference(parameters.get(OVERFLOW_DESTINATION)),
                reference(parameters.get(ROBOT_WAREHOUSE)),
                Boolean.parseBoolean(parameters.getOrDefault("mixedBoxes", "true")),
                Boolean.parseBoolean(parameters.get(
                        ClassificationRemainderPolicy.OVERFLOW_PACKING_ENABLED)));
    }

    private static ResourceReference reference(String value) {
        return value == null || value.isBlank() ? null : ResourceReference.parse(value);
    }

    public Map<String, String> parameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("overflowEnabled", Boolean.toString(legacyOverflowEnabled));
        parameters.put("mixedBoxes", Boolean.toString(mixedBoxes));
        if (overflowPackingEnabled) {
            parameters.put(ClassificationRemainderPolicy.OVERFLOW_PACKING_ENABLED, "true");
        }
        if (overflowDestination != null) {
            parameters.put(OVERFLOW_DESTINATION, overflowDestination.encode());
        }
        if (robotWarehouse != null) parameters.put(ROBOT_WAREHOUSE, robotWarehouse.encode());
        return Map.copyOf(parameters);
    }

    public boolean enabled() {
        return legacyOverflowEnabled || overflowDestination != null || robotWarehouse != null;
    }
}
