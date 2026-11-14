package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.automation.CustomPickupRules;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PickupShape;
import dev.stowling.domain.SpherePickupShape;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Schema6MigrationTest {
    private final ConfigLoader loader = new ConfigLoader();

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyRangesAndInterruptionStepsIdempotently() throws IOException {
        JsonObject root = legacyRoot();
        JsonObject pickupWorkflow = root.getAsJsonArray("pickupWorkflows")
                .get(0)
                .getAsJsonObject();
        pickupWorkflow.getAsJsonObject("settings").add(
                "interruption",
                inlineInterruption(
                        pickupStep("pickup-interrupt", "north-farm-center", "16")
                )
        );
        JsonArray automations = new JsonArray();
        automations.add(workflow(
                "ranged-pickup",
                pickupStep("pickup-8", "north-farm-center", "8"),
                inlineInterruption(
                        pickupStep("interrupt-12", "north-farm-center", "12")
                )
        ));
        automations.add(workflow(
                "region-pickup",
                regionPickupStep("pickup-region"),
                noneInterruption()
        ));
        automations.add(workflow(
                "default-radius",
                pickupStep("pickup-default", "north-cache-position", null),
                noneInterruption()
        ));
        automations.add(workflow(
                "legacy-workflow",
                workflowPickupStep("pickup-workflow"),
                noneInterruption()
        ));
        root.add("automationWorkflows", automations);

        StowlingConfig config = loader.parse(root.toString());

        assertEquals(6, config.schemaVersion());
        assertEquals(
                List.of(
                        "ranged-pickup",
                        "region-pickup",
                        "default-radius",
                        "legacy-workflow"
                ),
                config.automationWorkflows().stream()
                        .map(AutomationWorkflow::id)
                        .toList()
        );
        NamedCoordinate center = coordinate(config, "north-farm-center");
        assertEquals("North Farm Center", center.name());
        assertEquals(new BlockPosition(0, 64, 0), center.position());
        assertEquals(CoordinateType.SPHERE, center.type());
        assertEquals(8, center.radius());
        NamedCoordinate cachePosition = coordinate(
                config,
                "north-cache-position"
        );
        assertEquals(CoordinateType.SPHERE, cachePosition.type());
        assertEquals(8, cachePosition.radius());

        NamedCoordinate wide = coordinate(config, "north-farm-center-r12");
        assertEquals(12, wide.radius());
        NamedCoordinate pickupInterrupt = coordinate(
                config,
                "north-farm-center-r16"
        );
        assertEquals(16, pickupInterrupt.radius());
        NamedCoordinate regionRange = coordinate(config, "north-farm-pickup");
        assertEquals(32, regionRange.radius());
        assertNotEquals("north-farm", regionRange.id());
        assertTrue(config.pickupRegions().stream()
                .anyMatch(region -> region.id().equals("north-farm")));

        AutomationStep wideStep = step(config, "ranged-pickup", "pickup-8");
        AutomationStep interruptStep = step(config, "ranged-pickup", "interrupt-12");
        assertEquals("north-farm-center", wideStep.parameter("coordinateId"));
        assertEquals("north-farm-center-r12", interruptStep.parameter("coordinateId"));
        assertFalse(wideStep.parameters().containsKey("radius"));
        assertFalse(interruptStep.parameters().containsKey("radius"));

        AutomationStep regionStep = step(config, "region-pickup", "pickup-region");
        AutomationStep defaultStep = step(config, "default-radius", "pickup-default");
        AutomationStep legacyStep = step(
                config,
                "legacy-workflow",
                "pickup-workflow"
        );
        assertEquals("north-farm-pickup", regionStep.parameter("coordinateId"));
        assertFalse(regionStep.parameters().containsKey("regionId"));
        assertEquals(
                "north-cache-position",
                defaultStep.parameter("coordinateId")
        );
        assertEquals("farm-patrol", legacyStep.parameter("workflowId"));
        AutomationStep pickupInterruptStep = config.pickupWorkflows()
                .getFirst()
                .settings()
                .interruption()
                .steps()
                .getFirst();
        assertEquals(
                "north-farm-center-r16",
                pickupInterruptStep.parameter("coordinateId")
        );

        Path persisted = tempDir.resolve("schema6.json");
        loader.write(persisted, config);
        assertEquals(config, loader.load(persisted));
        assertEquals(6, loader.load(persisted).schemaVersion());
    }

    @Test
    void doesNotApplyLegacyDefaultsToSchemaSix() throws IOException {
        JsonObject root = legacyRoot();
        root.addProperty("schemaVersion", 6);
        JsonArray automations = new JsonArray();
        automations.add(workflow(
                "new-task",
                pickupStep("pickup-12", "north-farm-center", "12"),
                noneInterruption()
        ));
        root.add("automationWorkflows", automations);

        StowlingConfig config = loader.parse(root.toString());

        NamedCoordinate center = coordinate(config, "north-farm-center");
        assertEquals(CoordinateType.POINT, center.type());
        AutomationStep step = step(config, "new-task", "pickup-12");
        assertEquals("12", step.parameter("radius"));
        PickupShape shape = CustomPickupRules.fromParameters(step.parameters())
                .orElseThrow()
                .plan(
                        UUID.fromString("00000000-0000-0000-0000-000000000206"),
                        config
                )
                .regionsById()
                .values()
                .iterator()
                .next()
                .shape();
        assertTrue(shape instanceof SpherePickupShape);
        assertEquals(12, ((SpherePickupShape) shape).radius());
    }

    @Test
    void migratesLegacyCuboidRegionToAreaCoordinateWithNewId() throws IOException {
        JsonObject root = legacyRoot();
        JsonObject region = root.getAsJsonArray("pickupRegions")
                .get(0)
                .getAsJsonObject();
        JsonObject shape = new JsonObject();
        shape.addProperty("type", "CUBOID");
        shape.addProperty("firstCornerCoordinateId", "north-farm-center");
        shape.addProperty("secondCornerCoordinateId", "north-cache-position");
        region.add("shape", shape);
        JsonArray automations = new JsonArray();
        automations.add(workflow(
                "area-pickup",
                regionPickupStep("pickup-area"),
                noneInterruption()
        ));
        root.add("automationWorkflows", automations);

        StowlingConfig config = loader.parse(root.toString());

        NamedCoordinate area = coordinate(config, "north-farm-pickup");
        assertEquals(CoordinateType.AREA, area.type());
        assertEquals(new BlockPosition(0, 64, 0), area.position());
        assertEquals(new BlockPosition(8, 64, 0), area.secondCorner());
        assertNotEquals("north-farm", area.id());
        assertEquals(
                CoordinateType.POINT,
                coordinate(config, "north-farm-center").type()
        );
        AutomationStep pickup = step(config, "area-pickup", "pickup-area");
        CuboidPickupShape planned = (CuboidPickupShape) CustomPickupRules
                .fromParameters(pickup.parameters())
                .orElseThrow()
                .plan(
                        UUID.fromString("00000000-0000-0000-0000-000000000207"),
                        config
                )
                .regionsById()
                .values()
                .iterator()
                .next()
                .shape();
        assertEquals(new BlockPosition(0, 64, 0), planned.firstCorner());
        assertEquals(new BlockPosition(8, 64, 0), planned.secondCorner());
    }

    private JsonObject legacyRoot() throws IOException {
        return JsonParser.parseString(
                Files.readString(Path.of("src/test/resources/legacy-pickup-example.json"))
        ).getAsJsonObject();
    }

    private static JsonObject workflow(
            String id,
            JsonObject step,
            JsonObject interruption
    ) {
        JsonObject workflow = new JsonObject();
        workflow.addProperty("id", id);
        workflow.addProperty("name", id);
        JsonArray steps = new JsonArray();
        steps.add(step);
        workflow.add("steps", steps);
        workflow.add("settings", settings(interruption));
        return workflow;
    }

    private static JsonObject settings(JsonObject interruption) {
        JsonObject settings = new JsonObject();
        settings.addProperty("priority", "MEDIUM");
        JsonObject trigger = new JsonObject();
        trigger.addProperty("kind", "MANUAL");
        trigger.addProperty("intervalSeconds", 0);
        trigger.add("times", new JsonArray());
        trigger.addProperty("zoneId", "Asia/Shanghai");
        trigger.add("regionIds", new JsonArray());
        settings.add("trigger", trigger);
        settings.addProperty("autoStartOnRestart", false);
        settings.add("interruption", interruption);
        return settings;
    }

    private static JsonObject noneInterruption() {
        JsonObject interruption = new JsonObject();
        interruption.addProperty("kind", "NONE");
        interruption.add("steps", new JsonArray());
        return interruption;
    }

    private static JsonObject inlineInterruption(JsonObject step) {
        JsonObject interruption = new JsonObject();
        interruption.addProperty("kind", "INLINE");
        JsonArray steps = new JsonArray();
        steps.add(step);
        interruption.add("steps", steps);
        return interruption;
    }

    private static JsonObject pickupStep(String id, String coordinateId, String radius) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("coordinateId", coordinateId);
        if (radius != null) {
            parameters.addProperty("radius", radius);
        }
        return step(id, AutomationStepType.RUN_PICKUP, parameters);
    }

    private static JsonObject regionPickupStep(String id) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("regionId", "north-farm");
        return step(id, AutomationStepType.RUN_PICKUP, parameters);
    }

    private static JsonObject workflowPickupStep(String id) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("workflowId", "farm-patrol");
        return step(id, AutomationStepType.RUN_PICKUP, parameters);
    }

    private static JsonObject step(
            String id,
            AutomationStepType type,
            JsonObject parameters
    ) {
        JsonObject step = new JsonObject();
        step.addProperty("id", id);
        step.addProperty("type", type.name());
        step.add("parameters", parameters);
        return step;
    }

    private static NamedCoordinate coordinate(StowlingConfig config, String id) {
        return config.coordinatePoints().stream()
                .filter(coordinate -> coordinate.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static AutomationStep step(
            StowlingConfig config,
            String workflowId,
            String stepId
    ) {
        AutomationWorkflow workflow = config.automationWorkflows().stream()
                .filter(candidate -> candidate.id().equals(workflowId))
                .findFirst()
                .orElseThrow();
        List<AutomationStep> steps = workflow.steps();
        if (steps.stream().anyMatch(step -> step.id().equals(stepId))) {
            return steps.stream()
                    .filter(step -> step.id().equals(stepId))
                    .findFirst()
                    .orElseThrow();
        }
        return workflow.settings().interruption().steps().stream()
                .filter(step -> step.id().equals(stepId))
                .findFirst()
                .orElseThrow();
    }

}
