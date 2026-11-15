package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.stowling.automation.AutomationStepType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrictRequestParserModularActionsTest {
    private final StrictRequestParser parser = new StrictRequestParser();

    @Test
    void acceptsPureRegionPickupWithOptionalIdleAndWaitingParameters() throws Exception {
        for (Map<String, String> parameters : List.of(
                Map.of("regionId", "north"),
                Map.of("regionId", "north", "idleSeconds", "0", "waitForItems", "true"),
                Map.of("regionId", "north", "idleSeconds", "15", "waitForItems", "false")
        )) {
            assertAccepted(AutomationStepType.RUN_PICKUP, parameters);
        }
    }

    @Test
    void rejectsPureRegionPickupMixedWithDirectRulesOrInvalidOptions() {
        Map<String, String> pure = Map.of("regionId", "north", "idleSeconds", "1");
        for (String legacyKey : List.of(
                "standbyCoordinateId", "firstCornerCoordinateId", "secondCornerCoordinateId",
                "cacheIds", "classificationTiming"
        )) {
            assertRejected(AutomationStepType.RUN_PICKUP, with(pure, legacyKey, "legacy"));
        }
        for (String idle : List.of("-1", "1.5", "invalid")) {
            assertRejected(AutomationStepType.RUN_PICKUP, with(pure, "idleSeconds", idle));
        }
        assertRejected(AutomationStepType.RUN_PICKUP, with(pure, "waitForItems", "TRUE"));
        assertRejected(AutomationStepType.RUN_PICKUP, with(pure, "workflowId", "farm"));
        assertRejected(AutomationStepType.RUN_PICKUP, with(pure, "unexpected", "value"));
        assertRejected(AutomationStepType.RUN_PICKUP, Map.of("idleSeconds", "1"));
    }

    @Test
    void acceptsCoordinateTakeAndPutWithDefaultOrExplicitContainerTypes() throws Exception {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS
        )) {
            Map<String, String> parameters = inventoryParameters();
            assertAccepted(type, parameters);
            for (String containerType : List.of(
                    "CHEST", "TRAPPED_CHEST", "BARREL", "SHULKER_BOX", "HOPPER", "OTHER"
            )) {
                assertAccepted(type, with(parameters, "containerType", containerType));
            }
        }
    }

    @Test
    void normalizesContainerTypesForDescriptorExecution() throws Exception {
        Map<String, String> parameters = with(inventoryParameters(), "containerType", "barrel");
        var definition = parser.parseTaskDefinition(definition(
                AutomationStepType.TAKE_ITEMS, parameters, false
        ));
        assertEquals("BARREL", definition.steps().getFirst().parameter("containerType"));
    }

    @Test
    void rejectsMixedMissingOrInvalidTakeAndPutContainerParameters() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS
        )) {
            Map<String, String> parameters = inventoryParameters();
            assertRejected(type, with(parameters, "containerId", "cache"));
            assertRejected(type, without(parameters, "coordinateId"));
            assertRejected(type, with(parameters, "containerType", "FURNACE"));
            assertRejected(type, Map.of(
                    "containerId", "cache", "containerType", "CHEST",
                    "itemId", "minecraft:stone", "count", "1"
            ));
        }
    }

    @Test
    void acceptsTransferCoordinatesAndOptionalTypes() throws Exception {
        Map<String, String> parameters = transferParameters();
        assertAccepted(AutomationStepType.TRANSFER_ITEMS, parameters);
        assertAccepted(AutomationStepType.TRANSFER_ITEMS,
                with(parameters, "sourceContainerType", "BARREL"));
        assertAccepted(AutomationStepType.TRANSFER_ITEMS,
                with(parameters, "destinationContainerType", "SHULKER_BOX"));
        assertAccepted(AutomationStepType.TRANSFER_ITEMS,
                with(with(parameters, "sourceContainerType", "HOPPER"),
                        "destinationContainerType", "TRAPPED_CHEST"));
    }

    @Test
    void rejectsIncompleteMixedAndInvalidTransferContainers() {
        Map<String, String> parameters = transferParameters();
        for (String key : List.of("sourceCoordinateId", "destinationCoordinateId")) {
            assertRejected(AutomationStepType.TRANSFER_ITEMS, without(parameters, key));
        }
        for (String key : List.of("sourceContainerId", "destination")) {
            assertRejected(AutomationStepType.TRANSFER_ITEMS, with(parameters, key, "cache"));
        }
        for (String key : List.of("sourceContainerType", "destinationContainerType")) {
            assertRejected(AutomationStepType.TRANSFER_ITEMS,
                    with(parameters, key, "FURNACE"));
            assertRejected(AutomationStepType.TRANSFER_ITEMS, with(Map.of(
                    "sourceContainerId", "cache", "destination", "PLAYER",
                    "itemId", "minecraft:stone", "count", "1"
            ), key, "CHEST"));
        }
        assertRejected(AutomationStepType.TRANSFER_ITEMS, Map.of(
                "sourceCoordinateId", "source", "destination", "PLAYER",
                "itemId", "minecraft:stone", "count", "1"
        ));
    }

    @Test
    void acceptsPackingAtBothCompletionLimitsAndOptionalContainerTypes() throws Exception {
        for (String trigger : List.of("ON_OCCUPANCY", "ON_ITEM_COUNT")) {
            String maximum = "ON_OCCUPANCY".equals(trigger) ? "100" : "1728";
            Map<String, String> parameters = with(packingParameters(),
                    "completionTrigger", trigger);
            assertAccepted(AutomationStepType.PACK_ITEMS,
                    with(parameters, "completionThreshold", "1"));
            parameters = with(parameters, "completionThreshold", maximum);
            assertAccepted(AutomationStepType.PACK_ITEMS, parameters);
            for (String key : List.of(
                    "sourceContainerType", "outputContainerType", "fallbackContainerType"
            )) {
                parameters = with(parameters, key, "BARREL");
                assertAccepted(AutomationStepType.PACK_ITEMS, parameters);
            }
        }
    }

    @Test
    void rejectsPackingWithoutAnyRequiredParameter() {
        Map<String, String> parameters = packingParameters();
        for (String key : parameters.keySet()) {
            assertRejected(AutomationStepType.PACK_ITEMS, without(parameters, key));
        }
    }

    @Test
    void rejectsPackingInvalidThresholdsTriggersTypesAndLegacyFields() {
        Map<String, String> parameters = packingParameters();
        for (String threshold : List.of("0", "-1", "1.5", "1730", "invalid")) {
            assertRejected(AutomationStepType.PACK_ITEMS,
                    with(parameters, "completionThreshold", threshold));
        }
        assertRejected(AutomationStepType.PACK_ITEMS,
                with(parameters, "completionThreshold", "1729"));
        Map<String, String> occupancy = with(parameters, "completionTrigger", "ON_OCCUPANCY");
        assertRejected(AutomationStepType.PACK_ITEMS,
                with(occupancy, "completionThreshold", "101"));
        for (String trigger : List.of("ON_FULL", "on_item_count")) {
            assertRejected(AutomationStepType.PACK_ITEMS,
                    with(parameters, "completionTrigger", trigger));
        }
        for (String key : List.of(
                "sourceContainerType", "outputContainerType", "fallbackContainerType"
        )) {
            assertRejected(AutomationStepType.PACK_ITEMS, with(parameters, key, "FURNACE"));
        }
        for (String key : List.of("sourceContainerId", "destination", "boxContainerType")) {
            assertRejected(AutomationStepType.PACK_ITEMS, with(parameters, key, "cache"));
        }
    }

    @Test
    void rejectsMissingItemAndInvalidCountsForEveryContainerAction() {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS,
                AutomationStepType.TRANSFER_ITEMS, AutomationStepType.PACK_ITEMS
        )) {
            Map<String, String> parameters = switch (type) {
                case TRANSFER_ITEMS -> transferParameters();
                case PACK_ITEMS -> packingParameters();
                default -> inventoryParameters();
            };
            assertRejected(type, without(parameters, "itemId"));
            assertRejected(type, without(parameters, "count"));
            for (String count : List.of("0", "-1", "1.5", "invalid")) {
                assertRejected(type, with(parameters, "count", count));
            }
        }
        String body = definition(AutomationStepType.TAKE_ITEMS, inventoryParameters(), false);
        assertThrows(InvalidRequestException.class, () -> parser.parseTaskDefinition(
                body.replace("\"count\":\"64\"", "\"count\":64")));
    }

    @Test
    void retainsLegacyContainerWorkflowAndDirectPickupParameters() throws Exception {
        for (AutomationStepType type : List.of(
                AutomationStepType.TAKE_ITEMS, AutomationStepType.PUT_ITEMS
        )) {
            assertAccepted(type, Map.of(
                    "containerId", "cache", "itemId", "minecraft:stone", "count", "64"
            ));
        }
        for (String destination : List.of("PLAYER", "overflow")) {
            assertAccepted(AutomationStepType.TRANSFER_ITEMS, Map.of(
                    "sourceContainerId", "cache", "destination", destination,
                    "itemId", "minecraft:stone", "count", "64"
            ));
        }
        assertAccepted(AutomationStepType.RUN_PICKUP, Map.of(
                "workflowId", "farm-workflow", "regionId", "farm", "waitForItems", "true"
        ));
        assertAccepted(AutomationStepType.RUN_PICKUP, Map.of(
                "standbyCoordinateId", "standby", "firstCornerCoordinateId", "first",
                "secondCornerCoordinateId", "second", "cacheIds", "cache",
                "idleSeconds", "0", "classificationTiming", "AFTER_ROUND"
        ));
    }

    @Test
    void acceptsPickupToContainerAsAStrictCompositeAction() throws Exception {
        Map<String, String> parameters = Map.of(
                "coordinateId", "drops",
                "destinationCoordinateId", "chest",
                "containerType", "CHEST",
                "idleSeconds", "2",
                "waitForItems", "false"
        );
        assertAccepted(AutomationStepType.PICKUP_TO_CONTAINER, parameters);
        assertRejected(
                AutomationStepType.PICKUP_TO_CONTAINER,
                Map.of("coordinateId", "drops")
        );
        assertRejected(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "waitForItems", "TRUE")
        );
        assertRejected(
                AutomationStepType.PICKUP_TO_CONTAINER,
                with(parameters, "containerType", "FURNACE")
        );
        for (String idleSeconds : List.of("-1", "1.5", "invalid")) {
            assertRejected(
                    AutomationStepType.PICKUP_TO_CONTAINER,
                    with(parameters, "idleSeconds", idleSeconds)
            );
        }
    }

    @Test
    void appliesModularParameterRulesToInlineInterruptionSteps() throws Exception {
        for (AutomationStepType type : List.of(
                AutomationStepType.RUN_PICKUP, AutomationStepType.TAKE_ITEMS,
                AutomationStepType.PUT_ITEMS, AutomationStepType.TRANSFER_ITEMS,
                AutomationStepType.PACK_ITEMS
        )) {
            Map<String, String> parameters = switch (type) {
                case RUN_PICKUP -> Map.of("regionId", "north", "idleSeconds", "0");
                case TRANSFER_ITEMS -> transferParameters();
                case PACK_ITEMS -> packingParameters();
                default -> inventoryParameters();
            };
            var definition = parser.parseTaskDefinition(definition(type, parameters, true));
            assertEquals(parameters,
                    definition.settings().interruption().steps().getFirst().parameters());
            Map<String, String> invalid = with(parameters, "unexpected", "value");
            assertThrows(InvalidRequestException.class, () ->
                    parser.parseTaskDefinition(definition(type, invalid, true)));
        }
    }

    private void assertAccepted(
            AutomationStepType type,
            Map<String, String> parameters
    ) throws Exception {
        var definition = parser.parseTaskDefinition(definition(type, parameters, false));
        assertEquals(type, definition.steps().getFirst().type());
        assertEquals(parameters, definition.steps().getFirst().parameters());
    }

    private void assertRejected(AutomationStepType type, Map<String, String> parameters) {
        assertThrows(InvalidRequestException.class, () ->
                parser.parseTaskDefinition(definition(type, parameters, false)),
                type + ": " + parameters);
    }

    private static Map<String, String> inventoryParameters() {
        return Map.of("coordinateId", "source", "itemId", "minecraft:stone", "count", "64");
    }

    private static Map<String, String> transferParameters() {
        return Map.of(
                "sourceCoordinateId", "source", "destinationCoordinateId", "destination",
                "itemId", "minecraft:stone", "count", "64"
        );
    }

    private static Map<String, String> packingParameters() {
        return Map.of(
                "sourceCoordinateId", "source", "boxCoordinateId", "box",
                "outputCoordinateId", "output", "fallbackCoordinateId", "fallback",
                "itemId", "minecraft:stone", "count", "64",
                "completionTrigger", "ON_ITEM_COUNT", "completionThreshold", "1728"
        );
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

    private static String definition(
            AutomationStepType type,
            Map<String, String> parameters,
            boolean inline
    ) {
        JsonObject values = new JsonObject();
        parameters.forEach(values::addProperty);
        String action = "{\"id\":\"action\",\"type\":\"" + type
                + "\",\"parameters\":" + values + "}";
        String interruption = inline
                ? "{\"kind\":\"INLINE\",\"steps\":[" + action + "],\"taskId\":null}"
                : "{\"kind\":\"NONE\",\"steps\":[],\"taskId\":null}";
        String steps = inline
                ? "{\"id\":\"wait\",\"type\":\"WAIT\",\"parameters\":{\"seconds\":\"1\"}}"
                : action;
        return """
                {
                  "kind":"AUTOMATION","id":"modular-actions","name":"Modular actions",
                  "settings":{
                    "priority":"MEDIUM",
                    "trigger":{
                      "kind":"MANUAL","intervalSeconds":0,"times":[],
                      "zoneId":"Asia/Shanghai","regionIds":[]
                    },
                    "autoStartOnRestart":false,
                    "interruption":%s
                  },
                  "pickup":null,
                  "steps":[%s]
                }
                """.formatted(interruption, steps);
    }
}
