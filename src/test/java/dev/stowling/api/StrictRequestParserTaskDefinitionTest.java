package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.TaskPriority;
import dev.stowling.schedule.TriggerKind;
import dev.stowling.schedule.VersionedTaskDefinition;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StrictRequestParserTaskDefinitionTest {
    private final StrictRequestParser parser = new StrictRequestParser();

    @Test
    void parsesPickupDefinitionWithNestedSettingsAndDurations() throws Exception {
        TaskDefinition definition = parser.parseTaskDefinition(pickupDefinition());

        assertEquals(new TaskKey(TaskKey.PICKUP, "north"), definition.key());
        assertEquals(TaskPriority.HIGH, definition.settings().priority());
        assertEquals(TriggerKind.FIXED_INTERVAL, definition.settings().trigger().kind());
        assertEquals(120, definition.settings().trigger().intervalSeconds());
        assertEquals(Duration.ofSeconds(60), definition.pickup().regions().getFirst().idleDelay());
        assertEquals(Duration.ofSeconds(300), definition.pickup().completionIdleDelay());
        assertEquals(definition.settings(), definition.pickup().settings());
    }

    @Test
    void parsesVersionedUpdateAndDeleteRevisionStrictly() throws Exception {
        String update = "{\"revision\":\"revision-1\",\"definition\":"
                + automationDefinition() + "}";

        VersionedTaskDefinition versioned = parser.parseVersionedTaskDefinition(update);

        assertEquals("revision-1", versioned.revision());
        assertEquals(TaskKey.AUTOMATION, versioned.definition().kind());
        assertEquals("revision-2", parser.parseTaskDefinitionRevision(
                "{\"revision\":\"revision-2\"}"
        ));
    }

    @Test
    void rejectsUnknownFieldsAtEveryDefinitionLevel() {
        String definition = automationDefinition().stripTrailing();
        String unknownTopLevel = definition.substring(0, definition.length() - 1)
                + ",\"unexpected\":true}";
        String unknownTrigger = automationDefinition().replace(
                "\"regionIds\":[]",
                "\"regionIds\":[],\"unexpected\":true"
        );
        String unknownInterruption = automationDefinition().replace(
                "\"taskId\":null",
                "\"taskId\":null,\"unexpected\":true"
        );

        assertInvalidContains("Unknown JSON field: unexpected", unknownTopLevel);
        assertInvalidContains("Unknown JSON field: unexpected", unknownTrigger);
        assertInvalidContains("Unknown JSON field: unexpected", unknownInterruption);
    }

    @Test
    void rejectsDuplicateFieldsAtEveryDefinitionLevel() {
        String duplicateTopLevel = automationDefinition().replace(
                "\"kind\":\"AUTOMATION\"",
                "\"kind\":\"AUTOMATION\",\"kind\":\"AUTOMATION\""
        );
        String duplicatePriority = automationDefinition().replace(
                "\"priority\":\"MEDIUM\"",
                "\"priority\":\"MEDIUM\",\"priority\":\"LOW\""
        );
        String duplicateTaskId = automationDefinition().replace(
                "\"taskId\":null",
                "\"taskId\":null,\"taskId\":null"
        );

        assertInvalidContains("Duplicate JSON field: kind", duplicateTopLevel);
        assertInvalidContains("Duplicate JSON field: priority", duplicatePriority);
        assertInvalidContains("Duplicate JSON field: taskId", duplicateTaskId);
    }

    @Test
    void rejectsMismatchedNestedPickupSettings() {
        String mismatched = replaceNestedPriority(pickupDefinition(), "LOW");

        assertInvalidContains(
                "pickup.settings must match the task definition settings",
                mismatched
        );
    }

    @Test
    void pickupConfigStillParsesPolymorphicShapeAndDurations() throws Exception {
        String body = """
                {
                  "revision": "revision-1",
                  "pickupRegions": [{
                    "id": "north",
                    "name": "North",
                    "dimension": "minecraft:overworld",
                    "standbyCoordinateId": "north-position",
                    "standbyPosition": {"x": 1, "y": -44, "z": 4},
                    "shape": {
                      "type": "SPHERE",
                      "centerCoordinateId": "north-position",
                      "center": {"x": 1, "y": -44, "z": 4},
                      "radius": 16
                    }
                  }],
                  "cacheContainers": [],
                  "pickupWorkflows": [{
                    "id": "north",
                    "name": "North",
                    "dimension": "minecraft:overworld",
                    "regions": [{
                      "regionId": "north",
                      "priority": 100,
                      "idleDelay": 60
                    }],
                    "cacheIds": [],
                    "cachePolicy": "NEAREST_AVAILABLE",
                    "classificationTiming": "AFTER_ROUND",
                    "completionMode": "MANUAL",
                    "completionIdleDelay": 300
                  }]
                }
                """;

        var request = parser.parsePickupConfigUpdate(body);
        PickupRegion region = request.pickupRegions().getFirst();

        assertInstanceOf(SpherePickupShape.class, region.shape());
        assertEquals(Duration.ofSeconds(60),
                request.pickupWorkflows().getFirst().regions().getFirst().idleDelay());
        assertEquals(Duration.ofSeconds(300),
                request.pickupWorkflows().getFirst().completionIdleDelay());
    }

    @Test
    void taskResourceUpdateDefaultsWorkflowsAndRejectsWorkflowField() {
        String body = """
                {
                  "revision":"revision-1",
                  "pickupRegions":[],
                  "cacheContainers":[]
                }
                """;

        var request = assertDoesNotThrow(
                () -> parser.parseTaskResourcesUpdate(body)
        );
        assertEquals("revision-1", request.revision());
        assertTrue(request.pickupWorkflows().isEmpty());

        String withWorkflows = body.stripTrailing().replace(
                "}",
                ",\"pickupWorkflows\":[]}"
        );
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> parser.parseTaskResourcesUpdate(withWorkflows)
        );
        assertTrue(exception.getMessage().contains(
                "Unknown JSON field: pickupWorkflows"
        ));
    }

    private void assertInvalidContains(String expected, String body) {
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> parser.parseTaskDefinition(body)
        );
        assertTrue(exception.getMessage().contains(expected));
    }

    private static String replaceNestedPriority(String definition, String priority) {
        String target = "\"priority\": \"HIGH\"";
        int first = definition.indexOf(target);
        int nested = definition.indexOf(target, first + target.length());
        String replacement = "\"priority\": \"" + priority + "\"";
        return definition.substring(0, nested)
                + replacement
                + definition.substring(nested + target.length());
    }

    private static String pickupDefinition() {
        return """
                {
                  "kind": "PICKUP",
                  "id": "north",
                  "name": "North",
                  "settings": {
                    "priority": "HIGH",
                    "trigger": {
                      "kind": "FIXED_INTERVAL",
                      "intervalSeconds": 120,
                      "times": [],
                      "zoneId": "Asia/Shanghai",
                      "regionIds": []
                    },
                    "autoStartOnRestart": true,
                    "interruption": {
                      "kind": "NONE",
                      "steps": [],
                      "taskId": null
                    }
                  },
                  "pickup": {
                    "id": "north",
                    "name": "North",
                    "dimension": "minecraft:overworld",
                    "regions": [{
                      "regionId": "north",
                      "priority": 100,
                      "idleDelay": 60
                    }],
                    "cacheIds": ["north-cache"],
                    "cachePolicy": "NEAREST_AVAILABLE",
                    "classificationTiming": "AFTER_ROUND",
                    "completionMode": "MANUAL",
                    "completionIdleDelay": 300,
                    "settings": {
                      "priority": "HIGH",
                      "trigger": {
                        "kind": "FIXED_INTERVAL",
                        "intervalSeconds": 120,
                        "times": [],
                        "zoneId": "Asia/Shanghai",
                        "regionIds": []
                      },
                      "autoStartOnRestart": true,
                      "interruption": {
                        "kind": "NONE",
                        "steps": [],
                        "taskId": null
                      }
                    }
                  },
                  "steps": []
                }
                """;
    }

    private static String automationDefinition() {
        return """
                {
                  "kind":"AUTOMATION",
                  "id":"daily",
                  "name":"Daily",
                  "settings":{
                    "priority":"MEDIUM",
                    "trigger":{
                      "kind":"MANUAL",
                      "intervalSeconds":0,
                      "times":[],
                      "zoneId":"Asia/Shanghai",
                      "regionIds":[]
                    },
                    "autoStartOnRestart":false,
                    "interruption":{
                      "kind":"NONE",
                      "steps":[],
                      "taskId":null
                    }
                  },
                  "pickup":null,
                  "steps":[]
                }
                """;
    }
}
