package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StrictRequestParserCustomPickupTest {
    private final StrictRequestParser parser = new StrictRequestParser();

    @Test
    void acceptsIndependentPickupRuleParametersInSingleTaskSave() throws Exception {
        var definition = parser.parseTaskDefinition(definition(customParameters()));

        assertEquals("standby", definition.steps().getFirst().parameter("standbyCoordinateId"));
        assertEquals("true", definition.steps().getFirst().parameter("waitForItems"));
        assertEquals("0", definition.steps().getFirst().parameter("idleSeconds"));
    }

    @Test
    void acceptsNamedCoordinatePickupInSingleTaskSave() throws Exception {
        var definition = parser.parseTaskDefinition(definition(
                "{\"coordinateId\":\"point-1\",\"radius\":\"8\","
                        + "\"idleSeconds\":\"2\",\"waitForItems\":\"true\"}"
        ));

        var step = definition.steps().getFirst();
        assertEquals("point-1", step.parameter("coordinateId"));
        assertEquals("8", step.parameter("radius"));
        assertEquals("true", step.parameter("waitForItems"));
    }

    @Test
    void rejectsInvalidAndMixedCoordinatePickupParameters() {
        for (String parameters : new String[] {
            "{\"coordinateId\":\"point-1\",\"radius\":\"0\"}",
            "{\"coordinateId\":\"point-1\",\"radius\":\"129\"}",
            "{\"coordinateId\":\"point-1\",\"radius\":\"1.5\"}",
            "{\"coordinateId\":\"point-1\",\"regionId\":\"north\"}",
            "{\"coordinateId\":\"point-1\",\"workflowId\":\"farm\"}",
            "{\"radius\":\"8\"}",
            "{\"coordinateId\":\"point-1\",\"unexpected\":\"value\"}"
        }) {
            assertThrows(InvalidRequestException.class, () ->
                    parser.parseTaskDefinition(definition(parameters)));
        }
    }

    @Test
    void legacyPickupParametersStillAcceptItemWaiting() throws Exception {
        var definition = parser.parseTaskDefinition(definition(
                "{\"workflowId\":\"farm\",\"regionId\":\"north\","
                        + "\"waitForItems\":\"true\"}"
        ));

        assertEquals("farm", definition.steps().getFirst().parameter("workflowId"));
    }

    @Test
    void rejectsIncompleteMixedUnknownAndInvalidBooleanParameters() {
        for (String parameters : new String[] {
            "{\"cacheIds\":\"cache\"}",
            customParameters().replace("\"idleSeconds\":\"0\"",
                    "\"workflowId\":\"farm\""),
            customParameters().replace("\"waitForItems\":\"true\"",
                    "\"waitForItems\":\"TRUE\""),
            customParameters().replace("\"idleSeconds\":\"0\"",
                    "\"unexpected\":\"value\"")
        }) {
            assertThrows(InvalidRequestException.class, () ->
                    parser.parseTaskDefinition(definition(parameters)));
        }
    }

    @Test
    void emptyStepCannotPretendToBeAValidPickup() {
        var exception = assertThrows(InvalidRequestException.class, () ->
                parser.parseTaskDefinition(definition("{}")));

        assertTrue(exception.getMessage().contains("workflowId"));
    }

    private static String customParameters() {
        return """
                {
                  "standbyCoordinateId":"standby",
                  "firstCornerCoordinateId":"corner-a",
                  "secondCornerCoordinateId":"corner-b",
                  "cacheIds":"cache",
                  "idleSeconds":"0",
                  "waitForItems":"true"
                }
                """;
    }

    private static String definition(String parameters) {
        return """
                {
                  "kind":"AUTOMATION","id":"pickup","name":"Pickup",
                  "settings":{
                    "priority":"MEDIUM",
                    "trigger":{
                      "kind":"MANUAL","intervalSeconds":0,"times":[],
                      "zoneId":"Asia/Shanghai","regionIds":[]
                    },
                    "autoStartOnRestart":false,
                    "interruption":{"kind":"NONE","steps":[],"taskId":null}
                  },
                  "pickup":null,
                  "steps":[{"id":"collect","type":"RUN_PICKUP","parameters":%s}]
                }
                """.formatted(parameters);
    }
}
