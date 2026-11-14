package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.SpherePickupShape;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomPickupRulesTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void absentCustomParametersPreserveLegacyRequests() {
        assertTrue(CustomPickupRules.fromParameters(Map.of("workflowId", "farm")).isEmpty());
    }

    @Test
    void resolvesIndependentCoordinatesWithoutAStoredWorkflow() {
        CustomPickupRules rules = rules();
        StowlingConfig config = config(DIMENSION);
        UUID attemptId = UUID.randomUUID();

        PickupAutomationPlan plan = rules.plan(attemptId, config);
        CuboidPickupShape shape = (CuboidPickupShape) plan.regionsById()
                .get(plan.orderedRegions().getFirst().regionId()).shape();

        assertEquals(attemptId, plan.taskId());
        assertEquals(new BlockPosition(-8, 60, -2), shape.firstCorner());
        assertEquals(new BlockPosition(8, 72, 12), shape.secondCorner());
        assertTrue(shape.contains(0, 64, 0));
        assertFalse(shape.contains(0, 73, 0));
        assertEquals(CompletionMode.ALL_IDLE, plan.workflow().completionMode());
        assertEquals(Duration.ZERO, plan.workflow().completionIdleDelay());
        assertEquals(Duration.ofSeconds(2), plan.orderedRegions().getFirst().idleDelay());
        assertEquals(List.of("cache"), plan.workflow().cacheIds());
        assertFalse(plan.collectOnly());
        assertNull(rules.selectedRegionId());
        assertTrue(config.pickupRegions().isEmpty());
        assertTrue(config.pickupWorkflows().isEmpty());
        assertTrue(PickupWorkflowPort.Request.custom(rules, true).customRules().isPresent());
    }

    @Test
    void rejectsPartialMixedAndInvalidRules() {
        assertThrows(IllegalArgumentException.class, () ->
                CustomPickupRules.fromParameters(Map.of("idleSeconds", "2")));
        for (Map.Entry<String, String> invalid : Map.of(
                "workflowId", "farm", "regionId", "north", "idleSeconds", "-1",
                "cacheIds", "cache,cache", "classificationTiming", "invalid"
        ).entrySet()) {
            Map<String, String> values = new HashMap<>(parameters());
            values.put(invalid.getKey(), invalid.getValue());
            assertThrows(IllegalArgumentException.class, () ->
                    CustomPickupRules.fromParameters(values));
        }
    }

    @Test
    void rejectsUnknownAndCrossDimensionCoordinatesOrCaches() {
        assertThrows(IllegalArgumentException.class, () ->
                rules().plan(UUID.randomUUID(), config("minecraft:the_nether")));
        Map<String, String> unknown = new HashMap<>(parameters());
        unknown.put("firstCornerCoordinateId", "missing");
        assertThrows(IllegalArgumentException.class, () ->
                CustomPickupRules.fromParameters(unknown).orElseThrow()
                        .plan(UUID.randomUUID(), config(DIMENSION)));
        unknown.put("firstCornerCoordinateId", "corner-a");
        unknown.put("cacheIds", "missing");
        assertThrows(IllegalArgumentException.class, () ->
                CustomPickupRules.fromParameters(unknown).orElseThrow()
                        .plan(UUID.randomUUID(), config(DIMENSION)));
    }

    @Test
    void supportsZeroIdleAndCustomClassificationTiming() {
        Map<String, String> values = new HashMap<>(parameters());
        values.put("idleSeconds", "0");
        values.put("classificationTiming", "AFTER_REGION");
        CustomPickupRules rules = CustomPickupRules.fromParameters(values).orElseThrow();

        assertEquals(Duration.ZERO, rules.idleDelay());
        assertEquals(ClassificationTiming.AFTER_REGION, rules.classificationTiming());
    }

    @Test
    void pureRegionParametersResolveSavedRegionWithoutPickupInfrastructure() {
        Map<String, String> parameters = Map.of(
                "regionId", "north",
                "idleSeconds", "7",
                "waitForItems", "true"
        );
        CustomPickupRules rules = CustomPickupRules.fromParameters(parameters).orElseThrow();
        StowlingConfig config = pureConfig();

        PickupAutomationPlan plan = rules.plan(UUID.randomUUID(), config);

        assertEquals("north", rules.selectedRegionId());
        assertTrue(rules.cacheIds().isEmpty());
        assertTrue(plan.collectOnly());
        assertTrue(plan.workflow().cacheIds().isEmpty());
        assertTrue(plan.storageRoutes().isEmpty());
        assertNull(plan.overflowContainer());
        assertEquals(CompletionMode.ALL_IDLE, plan.workflow().completionMode());
        assertEquals(Duration.ZERO, plan.workflow().completionIdleDelay());
        assertEquals(config.pickupRegions().getFirst(), plan.regionsById().get("north"));
        assertEquals(Duration.ofSeconds(7), plan.orderedRegions().getFirst().idleDelay());
    }

    @Test
    void pureRegionRulesRejectUnknownRegionsAndLegacyMixing() {
        CustomPickupRules unknown = CustomPickupRules.fromParameters(
                Map.of("regionId", "missing")
        ).orElseThrow();
        assertThrows(IllegalArgumentException.class, () ->
                unknown.plan(UUID.randomUUID(), pureConfig()));
        assertThrows(IllegalArgumentException.class, () ->
                CustomPickupRules.fromParameters(Map.of(
                        "regionId", "north",
                        "standbyCoordinateId", "standby"
                )));
    }

    private static CustomPickupRules rules() {
        return CustomPickupRules.fromParameters(parameters()).orElseThrow();
    }

    private static Map<String, String> parameters() {
        return Map.of(
                "standbyCoordinateId", "standby",
                "firstCornerCoordinateId", "corner-a",
                "secondCornerCoordinateId", "corner-b",
                "cacheIds", "cache"
        );
    }

    private static StowlingConfig config(String cornerDimension) {
        NamedCoordinate standby = new NamedCoordinate(
                "standby", "Standby", DIMENSION, new BlockPosition(0, 64, 0)
        );
        NamedCoordinate first = new NamedCoordinate(
                "corner-a", "First", cornerDimension, new BlockPosition(8, 60, 12)
        );
        NamedCoordinate second = new NamedCoordinate(
                "corner-b", "Second", DIMENSION, new BlockPosition(-8, 72, -2)
        );
        NamedCoordinate cachePoint = new NamedCoordinate(
                "cache-point", "Cache", DIMENSION, new BlockPosition(10, 64, 0)
        );
        NamedCache cache = new NamedCache(
                "cache", "Cache", "cache-point", DIMENSION,
                cachePoint.position(), ContainerType.CHEST
        );
        ContainerRef overflow = new ContainerRef(
                "overflow", DIMENSION, new BlockPosition(20, 64, 0), ContainerType.CHEST
        );
        return new StowlingConfig(
                5, List.of(standby, first, second, cachePoint), List.of(), List.of(cache),
                List.of(), List.of(), List.of(), List.of(), List.of(), overflow
        );
    }

    private static StowlingConfig pureConfig() {
        BlockPosition center = new BlockPosition(0, 64, 0);
        PickupRegion region = new PickupRegion(
                "north",
                "North",
                DIMENSION,
                center,
                new SpherePickupShape(center, 12)
        );
        ContainerRef overflow = new ContainerRef(
                "overflow",
                DIMENSION,
                new BlockPosition(20, 64, 0),
                ContainerType.CHEST
        );
        return new StowlingConfig(
                5,
                List.of(),
                List.of(region),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                overflow
        );
    }
}
