package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.schedule.InterruptionKind;
import dev.stowling.schedule.TaskInterruption;
import dev.stowling.schedule.TaskSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModularActionConfigValidationTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void acceptsNamedCoordinateActionsWithDefaultAndExplicitContainerTypes() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS,
                AutomationStepType.TRANSFER_ITEMS, AutomationStepType.PACK_ITEMS
        )) {
            Map<String, String> parameters = parametersFor(type);
            assertValidInBothLocations(type, parameters);
            for (String key : containerTypeKeys(type)) {
                for (String containerType : List.of(
                        "CHEST", "TRAPPED_CHEST", "BARREL", "SHULKER_BOX", "HOPPER", "OTHER"
                )) {
                    assertValidInBothLocations(type, with(parameters, key, containerType));
                }
            }
        }
    }

    @Test
    void requiresExactCoordinateIdsRatherThanNamesCacheIdsOrCaseVariants() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS,
                AutomationStepType.TRANSFER_ITEMS, AutomationStepType.PACK_ITEMS
        )) {
            for (String key : coordinateKeys(type)) {
                for (String reference : List.of("missing", "source-name", "cache", "Source")) {
                    assertInvalidInBothLocations(type,
                            with(parametersFor(type), key, reference), "." + key, "");
                }
            }
        }
    }

    @Test
    void rejectsUnsupportedTypesForEveryTypedContainerParameter() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS,
                AutomationStepType.TRANSFER_ITEMS, AutomationStepType.PACK_ITEMS
        )) {
            for (String key : containerTypeKeys(type)) {
                assertInvalidInBothLocations(type,
                        with(parametersFor(type), key, "FURNACE"), "", key);
            }
        }
    }

    @Test
    void transferAndPackingRequireEveryCoordinateToShareOneDimension() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TRANSFER_ITEMS, AutomationStepType.PACK_ITEMS
        )) {
            for (String key : coordinateKeys(type)) {
                assertInvalidInBothLocations(type,
                        with(parametersFor(type), key, "nether"), "", "dimension");
            }
        }
    }

    @Test
    void acceptsPackingThresholdsAtTheirInclusiveLimits() {
        for (String trigger : List.of("ON_OCCUPANCY", "ON_ITEM_COUNT")) {
            String maximum = "ON_OCCUPANCY".equals(trigger) ? "100" : "1728";
            Map<String, String> parameters = with(parametersFor(AutomationStepType.PACK_ITEMS),
                    "completionTrigger", trigger);
            for (String threshold : List.of("1", maximum)) {
                assertValidInBothLocations(AutomationStepType.PACK_ITEMS,
                        with(parameters, "completionThreshold", threshold));
            }
        }
    }

    @Test
    void rejectsPackingThresholdsBeyondTheTriggerSpecificLimits() {
        Map<String, String> parameters = parametersFor(AutomationStepType.PACK_ITEMS);
        assertInvalidInBothLocations(AutomationStepType.PACK_ITEMS,
                with(parameters, "completionThreshold", "1729"),
                ".completionThreshold", "packing limit");
        assertInvalidInBothLocations(AutomationStepType.PACK_ITEMS,
                with(with(parameters, "completionTrigger", "ON_OCCUPANCY"),
                        "completionThreshold", "101"),
                ".completionThreshold", "packing limit");
        for (String threshold : List.of("0", "-1", "1.5", "invalid")) {
            assertInvalidInBothLocations(AutomationStepType.PACK_ITEMS,
                    with(parameters, "completionThreshold", threshold),
                    ".completionThreshold", "");
        }
        assertInvalidInBothLocations(AutomationStepType.PACK_ITEMS,
                with(parameters, "completionTrigger", "ON_FULL"),
                ".completionTrigger", "");
    }

    @Test
    void rejectsMissingRequiredPackingAndTransferCoordinates() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TRANSFER_ITEMS, AutomationStepType.PACK_ITEMS
        )) {
            for (String key : coordinateKeys(type)) {
                assertInvalidInBothLocations(type,
                        without(parametersFor(type), key), "." + key, "missing");
            }
        }
        for (String key : List.of("completionTrigger", "completionThreshold")) {
            assertInvalidInBothLocations(AutomationStepType.PACK_ITEMS,
                    without(parametersFor(AutomationStepType.PACK_ITEMS), key),
                    "." + key, "missing");
        }
    }

    @Test
    void rejectsMissingItemAndNonpositiveOrMalformedActionCounts() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS,
                AutomationStepType.TRANSFER_ITEMS, AutomationStepType.PACK_ITEMS
        )) {
            Map<String, String> parameters = parametersFor(type);
            assertInvalidInBothLocations(type, without(parameters, "itemId"), ".itemId", "");
            assertInvalidInBothLocations(type, without(parameters, "count"), ".count", "");
            for (String count : List.of("0", "-1", "1.5", "invalid")) {
                assertInvalidInBothLocations(type,
                        with(parameters, "count", count), ".count", "");
            }
        }
    }

    @Test
    void acceptsPureRegionPickupWithoutAnyCacheStorageOrPickupWorkflow() {
        for (boolean inline : List.of(false, true)) {
            for (Map<String, String> parameters : List.of(
                    Map.of("regionId", "farm"),
                    Map.of("regionId", "farm", "idleSeconds", "0", "waitForItems", "true")
            )) {
                StowlingConfig base = config(AutomationStepType.RUN_PICKUP, parameters, inline);
                StowlingConfig independent = new StowlingConfig(
                        StowlingConfig.CURRENT_SCHEMA_VERSION,
                        base.coordinatePoints(), base.pickupRegions(),
                        List.of(), List.of(), base.automationWorkflows(),
                        List.of(), List.of(), List.of(), base.overflowContainer()
                );
                assertValid(independent);
            }
        }
    }

    @Test
    void rejectsUnknownPureRegionIdsAndMixedOrInvalidPickupRules() {
        Map<String, String> parameters = Map.of("regionId", "farm", "idleSeconds", "0");
        for (String region : List.of("missing", "cache", "FARM")) {
            assertInvalidInBothLocations(AutomationStepType.RUN_PICKUP,
                    with(parameters, "regionId", region), "", "regionId");
        }
        for (String key : List.of(
                "standbyCoordinateId", "firstCornerCoordinateId", "secondCornerCoordinateId",
                "cacheIds", "classificationTiming", "workflowId"
        )) {
            assertInvalidInBothLocations(AutomationStepType.RUN_PICKUP,
                    with(parameters, key, "legacy"), "", "mix");
        }
        assertInvalidInBothLocations(AutomationStepType.RUN_PICKUP,
                with(parameters, "waitForItems", "TRUE"), ".waitForItems", "");
        for (String idle : List.of("-1", "1.5", "invalid")) {
            assertInvalidInBothLocations(AutomationStepType.RUN_PICKUP,
                    with(parameters, "idleSeconds", idle), "", "idleSeconds");
        }
    }

    @Test
    void acceptsPickupToContainerWithRangeAndPointOptions() {
        Map<String, String> parameters = pickupToContainerParameters();
        assertValidInBothLocations(AutomationStepType.PICKUP_TO_CONTAINER, parameters);
        for (String containerType : List.of(
                "CHEST", "TRAPPED_CHEST", "BARREL", "SHULKER_BOX", "HOPPER", "OTHER"
        )) {
            assertValidInBothLocations(
                    AutomationStepType.PICKUP_TO_CONTAINER,
                    with(parameters, "containerType", containerType)
            );
        }
        assertValidInBothLocations(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "idleSeconds", "0")
        );
        assertValidInBothLocations(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "waitForItems", "true")
        );
    }

    @Test
    void rejectsPickupToContainerTypeDimensionAndOptionErrors() {
        Map<String, String> parameters = pickupToContainerParameters();
        assertInvalidInBothLocations(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "coordinateId", "source"),
                ".coordinateId",
                "pickup range"
        );
        assertInvalidInBothLocations(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "destinationCoordinateId", "range"),
                ".destinationCoordinateId",
                "POINT"
        );
        assertInvalidInBothLocations(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "destinationCoordinateId", "nether"),
                "",
                "share one dimension"
        );
        assertInvalidInBothLocations(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "containerType", "FURNACE"),
                ".containerType",
                "supported"
        );
        assertInvalidInBothLocations(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "waitForItems", "TRUE"),
                ".waitForItems",
                "true or false"
        );
        for (String idleSeconds : List.of("-1", "1.5", "invalid")) {
            assertInvalidInBothLocations(
                    AutomationStepType.PICKUP_TO_CONTAINER,
                    with(parameters, "idleSeconds", idleSeconds),
                    ".idleSeconds",
                    "nonnegative integer"
            );
        }
    }

    @Test
    void preservesLegacyContainersWithoutRequiringNewCoordinateFields() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS
        )) {
            assertValidInBothLocations(type, Map.of(
                    "containerId", "cache", "itemId", "minecraft:stone", "count", "64"
            ));
        }
        for (String destination : List.of("PLAYER", "overflow")) {
            assertValidInBothLocations(AutomationStepType.TRANSFER_ITEMS, Map.of(
                    "sourceContainerId", "cache", "destination", destination,
                    "itemId", "minecraft:stone", "count", "64"
            ));
        }
        assertValidInBothLocations(AutomationStepType.RUN_PICKUP, Map.of(
                "workflowId", "farm-workflow", "regionId", "farm", "waitForItems", "true"
        ));
        assertValidInBothLocations(AutomationStepType.RUN_PICKUP, Map.of(
                "standbyCoordinateId", "source", "firstCornerCoordinateId", "source",
                "secondCornerCoordinateId", "destination", "cacheIds", "cache",
                "idleSeconds", "0", "classificationTiming", "AFTER_ROUND"
        ));
    }

    @Test
    void rejectsMixedLegacyAndCoordinateContainersAndOrphanTypeOptions() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS
        )) {
            assertInvalidInBothLocations(type,
                    with(parametersFor(type), "containerId", "cache"), "", "not both");
            assertInvalidInBothLocations(type, Map.of(
                    "containerId", "cache", "containerType", "CHEST",
                    "itemId", "minecraft:stone", "count", "64"
            ), "", "not both");
        }
        Map<String, String> transfer = parametersFor(AutomationStepType.TRANSFER_ITEMS);
        for (String key : List.of("sourceContainerId", "destination")) {
            assertInvalidInBothLocations(AutomationStepType.TRANSFER_ITEMS,
                    with(transfer, key, "cache"), "", "mix");
        }
        for (String key : List.of("sourceContainerType", "destinationContainerType")) {
            assertInvalidInBothLocations(AutomationStepType.TRANSFER_ITEMS, with(Map.of(
                    "sourceContainerId", "cache", "destination", "PLAYER",
                    "itemId", "minecraft:stone", "count", "64"
            ), key, "CHEST"), "", "mix");
        }
        assertInvalidInBothLocations(AutomationStepType.PACK_ITEMS,
                with(parametersFor(AutomationStepType.PACK_ITEMS), "sourceContainerId", "cache"),
                ".sourceContainerId", "unknown");
    }

    private static void assertValidInBothLocations(
            AutomationStepType type,
            Map<String, String> parameters
    ) {
        for (boolean inline : List.of(false, true)) {
            assertValid(config(type, parameters, inline));
        }
    }

    private static void assertValid(StowlingConfig config) {
        List<ConfigViolation> violations = ConfigValidator.validate(config);
        assertTrue(violations.isEmpty(), () -> "Unexpected violations: " + violations);
    }

    private static void assertInvalidInBothLocations(
            AutomationStepType type,
            Map<String, String> parameters,
            String parameterSuffix,
            String messagePart
    ) {
        for (boolean inline : List.of(false, true)) {
            List<ConfigViolation> violations = ConfigValidator.validate(
                    config(type, parameters, inline)
            );
            String stepPath = "automationWorkflows[0]"
                    + (inline ? ".settings.interruption.steps[0]" : ".steps[0]")
                    + ".parameters";
            assertTrue(violations.stream().anyMatch(violation ->
                    violation.path().startsWith(stepPath)
                            && violation.path().endsWith(parameterSuffix)
                            && violation.message().contains(messagePart)),
                    () -> type + " " + parameters + ": " + violations);
        }
    }

    private static StowlingConfig config(
            AutomationStepType type,
            Map<String, String> parameters,
            boolean inline
    ) {
        AutomationStep action = new AutomationStep("action", type, parameters);
        TaskSettings defaults = TaskSettings.defaults();
        TaskSettings settings = inline ? new TaskSettings(
                defaults.priority(), defaults.trigger(), defaults.autoStartOnRestart(),
                new TaskInterruption(InterruptionKind.INLINE, List.of(action), null)
        ) : defaults;
        List<AutomationStep> steps = inline
                ? List.of(new AutomationStep("wait", AutomationStepType.WAIT,
                        Map.of("seconds", "1")))
                : List.of(action);
        AutomationWorkflow workflow = new AutomationWorkflow(
                "modular-actions", "Modular actions", steps, settings
        );
        StowlingConfig base = ConfigTestFixtures.configWithAutomation(List.of(workflow));
        List<NamedCoordinate> coordinates = new ArrayList<>(base.coordinatePoints());
        coordinates.add(coordinate("source", OVERWORLD, 0));
        coordinates.add(coordinate("destination", OVERWORLD, 10));
        coordinates.add(coordinate("box", OVERWORLD, 20));
        coordinates.add(coordinate("output", OVERWORLD, 30));
        coordinates.add(coordinate("fallback", OVERWORLD, 40));
        coordinates.add(rangeCoordinate("range", OVERWORLD, 50));
        coordinates.add(coordinate("nether", NETHER, 0));
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                coordinates, base.pickupRegions(), base.cacheContainers(),
                base.pickupWorkflows(), base.automationWorkflows(), base.storageRoutes(),
                base.packingStations(), base.auditSchedules(), base.overflowContainer()
        );
    }

    private static NamedCoordinate coordinate(String id, String dimension, int x) {
        return new NamedCoordinate(id, id + "-name", dimension, new BlockPosition(x, 64, 0));
    }

    private static NamedCoordinate rangeCoordinate(String id, String dimension, int x) {
        return new NamedCoordinate(
                id,
                id + "-name",
                dimension,
                new BlockPosition(x, 64, 0),
                CoordinateType.SPHERE,
                8,
                null
        );
    }

    private static Map<String, String> pickupToContainerParameters() {
        return Map.of(
                "coordinateId", "range",
                "destinationCoordinateId", "destination",
                "containerType", "CHEST",
                "idleSeconds", "2",
                "waitForItems", "false"
        );
    }

    private static Map<String, String> parametersFor(AutomationStepType type) {
        return switch (type) {
            case TAKE_ITEMS, PUT_ITEMS -> Map.of(
                    "coordinateId", "source", "itemId", "minecraft:stone", "count", "64"
            );
            case TRANSFER_ITEMS -> Map.of(
                    "sourceCoordinateId", "source", "destinationCoordinateId", "destination",
                    "itemId", "minecraft:stone", "count", "64"
            );
            case PACK_ITEMS -> Map.of(
                    "sourceCoordinateId", "source", "boxCoordinateId", "box",
                    "outputCoordinateId", "output", "fallbackCoordinateId", "fallback",
                    "itemId", "minecraft:stone", "count", "64",
                    "completionTrigger", "ON_ITEM_COUNT", "completionThreshold", "1728"
            );
            default -> throw new IllegalArgumentException("Not a container action: " + type);
        };
    }

    private static List<String> coordinateKeys(AutomationStepType type) {
        return switch (type) {
            case TAKE_ITEMS, PUT_ITEMS -> List.of("coordinateId");
            case TRANSFER_ITEMS -> List.of("sourceCoordinateId", "destinationCoordinateId");
            case PACK_ITEMS -> List.of(
                    "sourceCoordinateId", "boxCoordinateId", "outputCoordinateId",
                    "fallbackCoordinateId"
            );
            default -> throw new IllegalArgumentException("Not a container action: " + type);
        };
    }

    private static List<String> containerTypeKeys(AutomationStepType type) {
        return switch (type) {
            case TAKE_ITEMS, PUT_ITEMS -> List.of("containerType");
            case TRANSFER_ITEMS -> List.of("sourceContainerType", "destinationContainerType");
            case PACK_ITEMS -> List.of(
                    "sourceContainerType", "outputContainerType", "fallbackContainerType"
            );
            default -> throw new IllegalArgumentException("Not a container action: " + type);
        };
    }

    private static Map<String, String> with(
            Map<String, String> parameters,
            String key,
            String value
    ) {
        Map<String, String> result = new LinkedHashMap<>(parameters);
        result.put(key, value);
        return Map.copyOf(result);
    }

    private static Map<String, String> without(Map<String, String> parameters, String key) {
        Map<String, String> result = new LinkedHashMap<>(parameters);
        result.remove(key);
        return Map.copyOf(result);
    }
}
