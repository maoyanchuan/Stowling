package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.module.RunPickupAutomationModule;
import dev.stowling.config.ConfigValidator;
import dev.stowling.config.LegacyPickupDefaults;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.schedule.StopOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoordinatePickupRulesTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final UUID TASK_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Test
    void plansNamedCoordinateAsInMemoryPurePickupRegion() {
        NamedCoordinate point = new NamedCoordinate(
                "point-1",
                "Pickup center",
                DIMENSION,
                new BlockPosition(1, -43, 4)
        );
        Map<String, String> parameters = Map.of(
                "coordinateId", "point-1",
                "radius", "9",
                "idleSeconds", "4",
                "waitForItems", "true"
        );
        StowlingConfig config = config(point, parameters);
        CustomPickupRules rules = CustomPickupRules.fromParameters(parameters).orElseThrow();

        PickupAutomationPlan plan = rules.plan(TASK_ID, config);
        PickupRegion region = plan.regionsById().get(
                plan.orderedRegions().getFirst().regionId()
        );
        SpherePickupShape shape = (SpherePickupShape) region.shape();

        assertEquals("point-1", rules.selectedCoordinateId());
        assertEquals(9, rules.radius());
        assertEquals(DIMENSION, region.dimension());
        assertEquals(new BlockPosition(1, -43, 4), shape.center());
        assertEquals("point-1", shape.centerCoordinateId());
        assertTrue(shape.contains(10, -43, 4));
        assertFalse(shape.contains(11, -43, 4));
        assertEquals(Duration.ofSeconds(4), plan.orderedRegions().getFirst().idleDelay());
        assertTrue(plan.collectOnly());
        assertTrue(plan.cachesById().isEmpty());
        assertTrue(plan.workflow().cacheIds().isEmpty());
        assertTrue(plan.storageRoutes().isEmpty());
        assertNull(plan.overflowContainer());
        assertTrue(config.pickupRegions().isEmpty());
        assertTrue(config.pickupWorkflows().isEmpty());
    }

    @Test
    void resolvesUpdatedCoordinateOnEveryPlan() {
        Map<String, String> parameters = Map.of("coordinateId", "point-1");
        CustomPickupRules rules = CustomPickupRules.fromParameters(parameters).orElseThrow();
        StowlingConfig firstConfig = config(
                new NamedCoordinate(
                        "point-1",
                        "First center",
                        DIMENSION,
                        new BlockPosition(1, -43, 4),
                        CoordinateType.SPHERE,
                        5,
                        null
                ),
                parameters
        );
        StowlingConfig updatedConfig = config(
                new NamedCoordinate(
                        "point-1",
                        "Updated center",
                        "minecraft:the_nether",
                        new BlockPosition(21, 70, -9),
                        CoordinateType.SPHERE,
                        11,
                        null
                ),
                parameters
        );

        SpherePickupShape first = plannedShape(rules, firstConfig);
        SpherePickupShape second = plannedShape(rules, updatedConfig);

        assertEquals(new BlockPosition(1, -43, 4), first.center());
        assertEquals(DIMENSION, plannedRegion(rules, firstConfig).dimension());
        assertEquals(new BlockPosition(21, 70, -9), second.center());
        assertEquals("minecraft:the_nether", plannedRegion(rules, updatedConfig).dimension());
        assertEquals(5, first.radius());
        assertEquals(11, second.radius());
    }

    @Test
    void validatorAcceptsCoordinatePickupAndRejectsInvalidReferencesAndMixing() {
        NamedCoordinate range = new NamedCoordinate(
                "point-1",
                "Pickup range",
                DIMENSION,
                new BlockPosition(1, -43, 4),
                CoordinateType.SPHERE,
                8,
                null
        );
        NamedCoordinate point = new NamedCoordinate(
                "point-1",
                "Pickup center",
                DIMENSION,
                new BlockPosition(1, -43, 4)
        );
        Map<String, String> validParameters = Map.of("coordinateId", "point-1");
        StowlingConfig valid = config(range, validParameters);

        assertEquals(List.of(), ConfigValidator.validate(valid));
        assertValidationViolation(
                valid,
                List.of(),
                "unknown coordinateId: point-1"
        );
        for (String radius : List.of("0", "129", "1.5", "invalid")) {
            assertValidationViolation(
                    config(point, validParameters),
                    Map.of("coordinateId", "point-1", "radius", radius),
                    "radius must be an integer between 1 and 128"
            );
        }
        assertValidationViolation(
                config(point, validParameters),
                Map.of("regionId", "saved-region", "coordinateId", "point-1", "radius", "8"),
                "both regionId and coordinateId"
        );
        assertValidationViolation(
                config(point, validParameters),
                Map.of("coordinateId", "point-1", "radius", "8", "workflowId", "legacy"),
                "legacy workflows cannot mix"
        );
        assertValidationViolation(
                config(point, validParameters),
                Map.of("coordinateId", "point-1", "radius", "8", "cacheIds", "cache"),
                "pure coordinate pickup cannot mix"
        );
        assertValidationViolation(
                config(point, validParameters),
                Map.of("radius", "8"),
                "radius requires coordinateId"
        );
        assertValidationViolation(
                config(point, validParameters),
                Map.of("coordinateId", "point-1"),
                "has no pickup range"
        );
    }

    @Test
    void rejectsInvalidRadiusAndMixedRulesBeforePlanning() {
        for (String radius : List.of("0", "129", "1.5", "invalid")) {
            assertThrows(IllegalArgumentException.class, () ->
                    CustomPickupRules.fromParameters(Map.of(
                            "coordinateId", "point-1",
                            "radius", radius
                    ))
            );
        }
        assertThrows(IllegalArgumentException.class, () ->
                CustomPickupRules.fromParameters(Map.of(
                        "regionId", "saved-region",
                        "coordinateId", "point-1",
                        "radius", "8"
                ))
        );
        assertThrows(IllegalArgumentException.class, () ->
                CustomPickupRules.fromParameters(Map.of("radius", "8"))
        );
        assertThrows(IllegalArgumentException.class, () ->
                CustomPickupRules.fromParameters(Map.of("coordinateId", ""))
        );
    }

    @Test
    void preservesBlankRegionCompatibilityForLegacyAndCoordinateModes() {
        assertTrue(CustomPickupRules.fromParameters(Map.of(
                "workflowId", "legacy",
                "regionId", ""
        )).isEmpty());

        CustomPickupRules direct = CustomPickupRules.fromParameters(Map.of(
                "regionId", "",
                "standbyCoordinateId", "standby",
                "firstCornerCoordinateId", "corner-a",
                "secondCornerCoordinateId", "corner-b",
                "cacheIds", "cache"
        )).orElseThrow();
        assertNull(direct.selectedRegionId());

        CustomPickupRules coordinate = CustomPickupRules.fromParameters(Map.of(
                "regionId", "",
                "coordinateId", "point-1",
                "radius", "8"
        )).orElseThrow();
        assertEquals("point-1", coordinate.selectedCoordinateId());
    }

    @Test
    void legacyDefaultsProtectRunPickupCoordinateReference() {
        NamedCoordinate standby = new NamedCoordinate(
                "north-farm-standby",
                "Standby",
                DIMENSION,
                new BlockPosition(0, 64, 0)
        );
        NamedCoordinate center = new NamedCoordinate(
                "north-farm-center",
                "Center",
                DIMENSION,
                new BlockPosition(1, -43, 4)
        );
        NamedCoordinate cachePoint = new NamedCoordinate(
                "north-cache-position",
                "Cache",
                DIMENSION,
                new BlockPosition(8, 64, 0)
        );
        PickupRegion region = new PickupRegion(
                "north-farm",
                "North farm",
                DIMENSION,
                standby.id(),
                standby.position(),
                new SpherePickupShape(center.id(), center.position(), 8)
        );
        NamedCache cache = new NamedCache(
                "north-cache",
                "North cache",
                cachePoint.id(),
                DIMENSION,
                cachePoint.position(),
                ContainerType.CHEST
        );
        PickupWorkflow pickup = new PickupWorkflow(
                "farm-patrol",
                "Farm patrol",
                DIMENSION,
                List.of(new PickupRegionBinding(region.id(), 100, Duration.ZERO)),
                List.of(cache.id()),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
        AutomationWorkflow task = new AutomationWorkflow(
                "user-task",
                "User task",
                List.of(new AutomationStep(
                        "pickup",
                        AutomationStepType.RUN_PICKUP,
                        Map.of("coordinateId", center.id(), "radius", "8")
                ))
        );
        StowlingConfig source = new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                List.of(standby, center, cachePoint),
                List.of(region),
                List.of(cache),
                List.of(pickup),
                List.of(task),
                List.of(),
                List.of(),
                List.of(),
                new ContainerRef(
                        "overflow",
                        DIMENSION,
                        new BlockPosition(20, 64, 0),
                        ContainerType.CHEST
                )
        );

        assertSame(source, LegacyPickupDefaults.remove(source));
    }

    @Test
    void moduleSubmitsCoordinateRequestAndDelegatesSafeStop() {
        RecordingPickupPort pickup = new RecordingPickupPort();
        RunPickupAutomationModule module = new RunPickupAutomationModule(pickup);
        Map<String, String> parameters = Map.of(
                "coordinateId", "point-1",
                "radius", "12",
                "idleSeconds", "3",
                "waitForItems", "true"
        );
        AutomationStep step = new AutomationStep(
                "coordinate-pickup",
                AutomationStepType.RUN_PICKUP,
                parameters
        );

        AutomationModuleResult started = module.execute(context(step, Map.of()));

        assertEquals(AutomationModuleStatus.RUNNING, started.status());
        assertEquals(
                PickupWorkflowPort.Request.custom(
                        CustomPickupRules.fromParameters(parameters).orElseThrow(),
                        true
                ),
                pickup.requests.getFirst()
        );
        assertEquals(12, pickup.requests.getFirst()
                .customRules().orElseThrow().radius());
        assertEquals(Duration.ofSeconds(3), pickup.requests.getFirst()
                .customRules().orElseThrow().idleDelay());
        assertEquals(
                StopOutcome.PENDING,
                module.stop(context(step, started.moduleState()))
        );
        assertEquals(
                StopOutcome.CLEAN,
                module.pollStop(context(step, started.moduleState()))
        );
        assertEquals(1, pickup.requestStopCount);
        assertEquals(1, pickup.pollStopCount);

        AutomationModuleDescriptor.Parameter coordinate = module.descriptor().parameters()
                .stream()
                .filter(parameter -> parameter.key().equals("coordinateId"))
                .findFirst()
                .orElseThrow();
        AutomationModuleDescriptor.Parameter radius = module.descriptor().parameters()
                .stream()
                .filter(parameter -> parameter.key().equals("radius"))
                .findFirst()
                .orElseThrow();
        assertEquals("拾取坐标", coordinate.label());
        assertEquals("coordinate", coordinate.kind());
        assertEquals("拾取半径（格）", radius.label());
        assertEquals("positive-integer", radius.kind());
        assertTrue(module.descriptor().description().contains("命名坐标"));
        assertTrue(module.descriptor().description().contains("范围"));
    }

    private static SpherePickupShape plannedShape(
            CustomPickupRules rules,
            StowlingConfig config
    ) {
        return (SpherePickupShape) plannedRegion(rules, config).shape();
    }

    private static PickupRegion plannedRegion(
            CustomPickupRules rules,
            StowlingConfig config
    ) {
        PickupAutomationPlan plan = rules.plan(TASK_ID, config);
        return plan.regionsById().get(plan.orderedRegions().getFirst().regionId());
    }

    private static void assertValidationViolation(
            StowlingConfig valid,
            Map<String, String> parameters,
            String expectedMessage
    ) {
        StowlingConfig invalid = new StowlingConfig(
                valid.schemaVersion(),
                valid.coordinatePoints(),
                valid.pickupRegions(),
                valid.cacheContainers(),
                valid.pickupWorkflows(),
                List.of(new AutomationWorkflow(
                        "coordinate-pickup",
                        "Coordinate pickup",
                        List.of(new AutomationStep(
                                "pickup",
                                AutomationStepType.RUN_PICKUP,
                                parameters
                        ))
                )),
                valid.storageRoutes(),
                valid.packingStations(),
                valid.auditSchedules(),
                valid.overflowContainer()
        );
        assertTrue(
                ConfigValidator.validate(invalid).stream().anyMatch(violation ->
                        violation.message().contains(expectedMessage)
                ),
                "missing validation message: " + expectedMessage
        );
    }

    private static void assertValidationViolation(
            StowlingConfig valid,
            List<NamedCoordinate> coordinates,
            String expectedMessage
    ) {
        StowlingConfig invalid = new StowlingConfig(
                valid.schemaVersion(),
                coordinates,
                valid.pickupRegions(),
                valid.cacheContainers(),
                valid.pickupWorkflows(),
                valid.automationWorkflows(),
                valid.storageRoutes(),
                valid.packingStations(),
                valid.auditSchedules(),
                valid.overflowContainer()
        );
        assertTrue(
                ConfigValidator.validate(invalid).stream().anyMatch(violation ->
                        violation.message().contains(expectedMessage)
                ),
                "missing validation message: " + expectedMessage
        );
    }

    private static StowlingConfig config(
            NamedCoordinate point,
            Map<String, String> parameters
    ) {
        AutomationWorkflow pickupWorkflow = new AutomationWorkflow(
                "coordinate-pickup",
                "Coordinate pickup",
                List.of(new AutomationStep(
                        "pickup",
                        AutomationStepType.RUN_PICKUP,
                        parameters
                ))
        );
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                List.of(point),
                List.of(),
                List.of(),
                List.of(),
                List.of(pickupWorkflow),
                List.of(),
                List.of(),
                List.of(),
                new ContainerRef(
                        "overflow",
                        DIMENSION,
                        new BlockPosition(20, 64, 0),
                        ContainerType.CHEST
                )
        );
    }

    private static AutomationModuleContext context(
            AutomationStep step,
            Map<String, String> state
    ) {
        return new AutomationModuleContext(
                TASK_ID,
                step,
                state,
                false,
                Optional.empty()
        );
    }

    private static final class RecordingPickupPort implements PickupWorkflowPort {
        private final List<Request> requests = new ArrayList<>();
        private int requestStopCount;
        private int pollStopCount;

        @Override
        public AutomationOperationResult start(String operationKey, Request request) {
            requests.add(request);
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            return AutomationOperationResult.succeeded("confirmed");
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            return AutomationOperationResult.running();
        }

        @Override
        public StopOutcome requestStop(UUID attemptId) {
            requestStopCount++;
            return StopOutcome.PENDING;
        }

        @Override
        public StopOutcome pollStop(UUID attemptId) {
            pollStopCount++;
            return StopOutcome.CLEAN;
        }
    }
}
