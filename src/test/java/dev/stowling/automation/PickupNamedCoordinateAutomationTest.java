package dev.stowling.automation;

import static dev.stowling.automation.AutomationTestSupport.DIMENSION;
import static dev.stowling.automation.AutomationTestSupport.START;
import static dev.stowling.automation.AutomationTestSupport.STONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PickupNamedCoordinateAutomationTest {
    private static final BlockPosition FAR_PLAYER_POSITION =
            new BlockPosition(100, 64, 100);

    @Test
    void planKeepsResolvedV3CoordinatesAndStableResourceIds() {
        AutomationTestSupport support = new AutomationTestSupport();
        NamedCache namedCache = new NamedCache(
                "named-cache",
                "Named cache",
                "cache-coordinate",
                DIMENSION,
                new BlockPosition(20, 65, 4),
                ContainerType.CHEST
        );
        SpherePickupShape sphereShape = new SpherePickupShape(
                "sphere-center",
                new BlockPosition(1, 64, 2),
                8
        );
        PickupRegion sphere = new PickupRegion(
                "sphere-region",
                "Sphere region",
                DIMENSION,
                "sphere-standby",
                new BlockPosition(30, 70, 30),
                sphereShape
        );
        CuboidPickupShape cuboidShape = new CuboidPickupShape(
                "cuboid-first",
                "cuboid-second",
                new BlockPosition(-4, 60, -5),
                new BlockPosition(4, 68, 5)
        );
        PickupRegion cuboid = new PickupRegion(
                "cuboid-region",
                "Cuboid region",
                DIMENSION,
                "cuboid-standby",
                new BlockPosition(-30, 70, -30),
                cuboidShape
        );
        PickupWorkflow workflow = workflow(
                List.of(binding(sphere, 100), binding(cuboid, 50)),
                List.of(namedCache.id()),
                ClassificationTiming.AFTER_ROUND
        );

        PickupAutomationPlan plan = plan(
                support,
                workflow,
                List.of(sphere, cuboid),
                List.of(namedCache),
                Optional.empty()
        );

        assertEquals(
                List.of("sphere-region", "cuboid-region"),
                plan.orderedRegions().stream()
                .map(PickupRegionBinding::regionId)
                .toList()
        );
        assertEquals(sphere, plan.regionsById().get(sphere.id()));
        assertEquals(cuboid, plan.regionsById().get(cuboid.id()));
        assertEquals(
                "sphere-center",
                ((SpherePickupShape) sphere.shape()).centerCoordinateId()
        );
        assertEquals(
                "cuboid-first",
                ((CuboidPickupShape) cuboid.shape()).firstCornerCoordinateId()
        );
        assertEquals(namedCache.toContainerRef(), plan.caches().getFirst());
        assertEquals(
                Optional.of("cache-coordinate"),
                Optional.of(namedCache.coordinateId())
        );
    }

    @Test
    void sphereRegionUsesResolvedStandbyCenterAndConfiguredBoundary() {
        AutomationTestSupport support = new AutomationTestSupport();
        BlockPosition standby = new BlockPosition(30, 70, 30);
        SpherePickupShape shape = new SpherePickupShape(
                "sphere-center",
                new BlockPosition(0, 64, 0),
                4
        );
        PickupRegion region = new PickupRegion(
                "sphere-region",
                "Sphere region",
                DIMENSION,
                "sphere-standby",
                standby,
                shape
        );
        support.world.setWorld(AutomationTestSupport.connectedAt(FAR_PLAYER_POSITION));
        support.navigation.setPosition(standby);
        support.world.setGeometryCandidates(List.of(
                new GroundItem("inside", STONE, 1, new BlockPosition(4, 64, 0)),
                new GroundItem("outside", STONE, 1, new BlockPosition(5, 64, 0))
        ));
        PickupAutomationEngine engine = engine(
                support,
                workflow(
                List.of(binding(region, 100)),
                List.of(support.cache.id()),
                ClassificationTiming.AFTER_ROUND
        ),
                List.of(region),
                List.of(namedCache(support.cache)),
                Optional.empty()
        );

        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        assertEquals(PickupStage.COLLECT, engine.tick().stage());

        assertSame(region, support.world.lastObservedRegion());
        assertTrue(support.navigation.startedTargets().contains(standby));
        assertEquals(
                List.of(new BlockPosition(4, 64, 0)),
                support.navigation.startedTargets().stream()
                .filter(target -> target.equals(new BlockPosition(4, 64, 0)))
                .toList()
        );
    }

    @Test
    void cuboidRegionUsesResolvedCornersAndInclusiveConfiguredBoundary() {
        AutomationTestSupport support = new AutomationTestSupport();
        BlockPosition standby = new BlockPosition(-30, 70, -30);
        CuboidPickupShape shape = new CuboidPickupShape(
                "cuboid-first",
                "cuboid-second",
                new BlockPosition(10, 60, -1),
                new BlockPosition(12, 64, 1)
        );
        PickupRegion region = new PickupRegion(
                "cuboid-region",
                "Cuboid region",
                DIMENSION,
                "cuboid-standby",
                standby,
                shape
        );
        support.world.setWorld(AutomationTestSupport.connectedAt(FAR_PLAYER_POSITION));
        support.navigation.setPosition(standby);
        support.world.setGeometryCandidates(List.of(
                new GroundItem("edge-inside", STONE, 1, new BlockPosition(12, 64, 1)),
                new GroundItem("edge-outside", STONE, 1, new BlockPosition(13, 64, 1))
        ));
        PickupAutomationEngine engine = engine(
                support,
                workflow(
                List.of(binding(region, 100)),
                List.of(support.cache.id()),
                ClassificationTiming.AFTER_ROUND
        ),
                List.of(region),
                List.of(namedCache(support.cache)),
                Optional.empty()
        );

        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        assertEquals(PickupStage.COLLECT, engine.tick().stage());

        assertSame(region, support.world.lastObservedRegion());
        assertTrue(support.navigation.startedTargets().contains(standby));
        assertTrue(support.navigation.startedTargets().contains(
                new BlockPosition(12, 64, 1)
        ));
        assertTrue(!support.navigation.startedTargets().contains(
                new BlockPosition(13, 64, 1)
        ));
    }

    @Test
    void regionOverrideStartsAtItsResolvedV3StandbyPosition() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion high = region(
                "high",
                "high-standby",
                new BlockPosition(5, 64, 0)
        );
        PickupRegion low = region(
                "low",
                "low-standby",
                new BlockPosition(-5, 64, 0)
        );
        PickupAutomationEngine engine = engine(
                support,
                workflow(
                List.of(binding(high, 100), binding(low, 10)),
                List.of(support.cache.id()),
                ClassificationTiming.AFTER_ROUND
        ),
                List.of(high, low),
                List.of(namedCache(support.cache)),
                Optional.of(low.id())
        );

        AutomationSnapshot snapshot = engine.tick();

        assertEquals(Optional.of(low.id()), snapshot.currentRegionId());
        assertEquals(List.of(low.standbyPosition()), support.navigation.startedTargets());
    }

    @Test
    void multiRegionPatrolUsesEachResolvedStandbyPositionInPriorityOrder() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion high = region(
                "high",
                "high-standby",
                new BlockPosition(5, 64, 0)
        );
        PickupRegion low = region(
                "low",
                "low-standby",
                new BlockPosition(-5, 64, 0)
        );
        support.navigation.setPosition(high.standbyPosition());
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        PickupAutomationEngine engine = engine(
                support,
                workflow(
                List.of(binding(high, 100), binding(low, 10)),
                List.of(support.cache.id()),
                ClassificationTiming.AFTER_REGION
        ),
                List.of(high, low),
                List.of(namedCache(support.cache)),
                Optional.empty()
        );

        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
        support.navigation.setPosition(support.cache.position());
        AutomationSnapshot lowerPriority = engine.tick();

        assertEquals(PickupStage.NAVIGATE_PICKUP, lowerPriority.stage());
        assertEquals(Optional.of(low.id()), lowerPriority.currentRegionId());
        support.navigation.setPosition(low.standbyPosition());
        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        assertEquals(
                high.standbyPosition(),
                support.navigation.startedTargets().getFirst()
        );
        assertTrue(support.navigation.startedTargets().contains(
                low.standbyPosition()
        ));
    }

    @Test
    void namedCacheFailoverUsesResolvedDimensionsAndPositions() {
        AutomationTestSupport support = new AutomationTestSupport();
        NamedCache first = new NamedCache(
                "cache-first",
                "First cache",
                "first-coordinate",
                DIMENSION,
                new BlockPosition(10, 64, 0),
                ContainerType.CHEST
        );
        NamedCache second = new NamedCache(
                "cache-second",
                "Second cache",
                "second-coordinate",
                DIMENSION,
                new BlockPosition(14, 64, 0),
                ContainerType.CHEST
        );
        PickupRegion region = region(
                "region",
                "region-standby",
                new BlockPosition(0, 64, 0)
        );
        PickupWorkflow workflow = workflow(
                List.of(binding(region, 100)),
                List.of(first.id(), second.id()),
                ClassificationTiming.AFTER_ROUND
        );
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        support.navigation.setPosition(first.position());
        support.containers.putView(AutomationFakes.view(
                first.toContainerRef(),
                1,
                List.of()
        ));
        support.containers.putView(AutomationFakes.view(
                second.toContainerRef(),
                1,
                List.of()
        ));
        support.containers.addResult(new ContainerAttemptResult(
                ContainerAttemptStatus.REJECTED,
                1,
                Optional.empty(),
                Optional.empty(),
                "first cache rejected the transfer"
        ));
        support.containers.addResult(AutomationFakes.acknowledged(
                second.toContainerRef(),
                2,
                List.of(new ContainerSlot(0, STONE, 1))
        ));
        saveCheckpoint(
                support,
                workflow,
                PickupStage.DEPOSIT_BUFFER,
                region,
                first.id(),
                false
        );
        PickupAutomationEngine engine = createEngine(
                support,
                plan(
                support,
                workflow,
                List.of(region),
                List.of(first, second),
                Optional.empty()
        )
        );

        engine.start();
        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.tick().stage());
        support.navigation.setPosition(second.position());
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());

        assertEquals(
                List.of(first.id(), second.id()),
                support.containers.attempts().stream()
                .map(attempt -> attempt.transfer().destination().id())
                .toList()
        );
        assertEquals(
                DIMENSION,
                support.containers.attempts().getLast()
                .transfer()
                .destination()
                .dimension()
        );
    }

    @Test
    void recoveryReobservesConfirmedContainerStateWithoutReplayingAction() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region(
                "region",
                "region-standby",
                new BlockPosition(0, 64, 0)
        );
        PickupWorkflow workflow = workflow(
                List.of(binding(region, 100)),
                List.of(support.cache.id()),
                ClassificationTiming.AFTER_ROUND
        );
        support.navigation.setPosition(support.cache.position());
        support.containers.putView(AutomationFakes.view(
                support.cache,
                4,
                List.of()
        ));
        saveCheckpoint(
                support,
                workflow,
                PickupStage.DELIVER_LOOSE,
                region,
                support.cache.id(),
                true
        );
        PickupAutomationEngine engine = createEngine(
                support,
                plan(
                support,
                workflow,
                List.of(region),
                List.of(namedCache(support.cache)),
                Optional.empty()
        )
        );

        engine.start();
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.snapshot().stage());
        assertTrue(support.containers.attempts().isEmpty());
        assertEquals(PickupStage.NAVIGATE_PICKUP, engine.tick().stage());

        assertTrue(support.containers.attempts().isEmpty());
        assertEquals(List.of(support.cache.id()), support.containers.observedContainers());
    }

    @Test
    void legacyCheckpointRemainsRecoverableWithV3Plan() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region(
                "region",
                "region-standby",
                new BlockPosition(0, 64, 0)
        );
        support.checkpoint(PickupStage.WAIT_FOR_IDLE, Optional.of(START), Map.of());
        PickupAutomationEngine engine = engine(
                support,
                workflow(
                List.of(binding(region, 100)),
                List.of(support.cache.id()),
                ClassificationTiming.AFTER_ROUND
        ),
                List.of(region),
                List.of(namedCache(support.cache)),
                Optional.empty()
        );

        engine.start();

        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.snapshot().stage());
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
    }

    private PickupAutomationEngine engine(
            AutomationTestSupport support,
            PickupWorkflow workflow,
            List<PickupRegion> regions,
            List<NamedCache> caches,
            Optional<String> override
    ) {
        return createEngine(
                support,
                plan(support, workflow, regions, caches, override)
        );
    }

    private PickupAutomationEngine createEngine(
            AutomationTestSupport support,
            PickupAutomationPlan plan
    ) {
        return new PickupAutomationEngine(
                plan,
                support.world,
                support.inventory,
                support.navigation,
                support.containers,
                support.packing,
                support.clock,
                support.checkpoints
        );
    }

    private PickupAutomationPlan plan(
            AutomationTestSupport support,
            PickupWorkflow workflow,
            List<PickupRegion> regions,
            List<NamedCache> caches,
            Optional<String> override
    ) {
        Map<String, PickupRegion> regionsById = new LinkedHashMap<>();
        regions.forEach(region -> regionsById.put(region.id(), region));
        Map<String, ContainerRef> cachesById = new LinkedHashMap<>();
        caches.forEach(cache -> cachesById.put(cache.id(), cache.toContainerRef()));
        return new PickupAutomationPlan(
                AutomationTestSupport.TASK_ID,
                workflow,
                override,
                regionsById,
                cachesById,
                List.of(),
                support.overflow,
                2,
                1,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30)
        );
    }

    private PickupWorkflow workflow(
            List<PickupRegionBinding> bindings,
            List<String> cacheIds,
            ClassificationTiming classificationTiming
    ) {
        return new PickupWorkflow(
                "named-workflow",
                "Named workflow",
                DIMENSION,
                bindings,
                cacheIds,
                CachePolicy.NEAREST_AVAILABLE,
                classificationTiming,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
    }

    private PickupRegionBinding binding(PickupRegion region, int priority) {
        return new PickupRegionBinding(region.id(), priority, Duration.ZERO);
    }

    private PickupRegion region(
            String id,
            String standbyCoordinateId,
            BlockPosition standbyPosition
    ) {
        return new PickupRegion(
                id,
                id,
                DIMENSION,
                standbyCoordinateId,
                standbyPosition,
                new SpherePickupShape(
                        id + "-center",
                        standbyPosition,
                        16
                )
        );
    }

    private NamedCache namedCache(ContainerRef cache) {
        return new NamedCache(
                cache.id(),
                cache.id(),
                cache.id() + "-coordinate",
                cache.dimension(),
                cache.position(),
                cache.type()
        );
    }

    private void saveCheckpoint(
            AutomationTestSupport support,
            PickupWorkflow workflow,
            PickupStage stage,
            PickupRegion region,
            String cacheId,
            boolean classificationAfterRound
    ) {
        support.checkpoints.save(new AutomationCheckpoint(
                AutomationTestSupport.TASK_ID,
                stage,
                Optional.of(workflow.id()),
                Optional.empty(),
                Optional.of(region.id()),
                1,
                Set.of(),
                Optional.empty(),
                Map.of(),
                Set.of(),
                Map.of(),
                Optional.of(cacheId),
                0,
                classificationAfterRound
        ));
    }
}
