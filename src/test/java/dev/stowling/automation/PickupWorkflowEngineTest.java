package dev.stowling.automation;

import static dev.stowling.automation.AutomationTestSupport.DIMENSION;
import static dev.stowling.automation.AutomationTestSupport.START;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
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

class PickupWorkflowEngineTest {
    private static final BlockPosition ORIGIN = new BlockPosition(0, 64, 0);

    @Test
    void ordersRegionsByPriorityAndKeepsEqualPriorityOrder() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion low = region("low");
        PickupRegion firstEqual = region("first-equal");
        PickupRegion secondEqual = region("second-equal");
        PickupWorkflow workflow = workflow(
                List.of(
                binding(low, 10),
                binding(firstEqual, 100),
                binding(secondEqual, 100)
        ),
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ZERO
        );

        PickupAutomationPlan plan = plan(
                support,
                workflow,
                List.of(low, firstEqual, secondEqual),
                Optional.empty()
        );

        assertEquals(
                List.of("first-equal", "second-equal", "low"),
                plan.orderedRegions().stream()
                .map(PickupRegionBinding::regionId)
                .toList()
        );
    }

    @Test
    void afterRegionClassifiesBeforeMovingToLowerPriorityRegion() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion high = region("high");
        PickupRegion low = region("low");
        PickupWorkflow workflow = workflow(
                List.of(binding(low, 10), binding(high, 100)),
                ClassificationTiming.AFTER_REGION,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        PickupAutomationEngine engine = engine(
                support,
                workflow,
                List.of(high, low),
                Optional.empty()
        );

        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
        support.navigation.setPosition(support.cache.position());

        AutomationSnapshot nextRegion = engine.tick();

        assertEquals(PickupStage.NAVIGATE_PICKUP, nextRegion.stage());
        assertEquals(Optional.of("low"), nextRegion.currentRegionId());
    }

    @Test
    void afterRoundReturnsToHighestPriorityForManualWorkflow() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion high = region("high");
        PickupRegion low = region("low");
        PickupWorkflow workflow = workflow(
                List.of(binding(low, 10), binding(high, 100)),
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        PickupAutomationEngine engine = engine(
                support,
                workflow,
                List.of(high, low),
                Optional.empty()
        );

        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        AutomationSnapshot lowerPriority = engine.tick();
        assertEquals(Optional.of("low"), lowerPriority.currentRegionId());
        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
        support.navigation.setPosition(support.cache.position());

        AutomationSnapshot nextRound = engine.tick();

        assertEquals(PickupStage.NAVIGATE_PICKUP, nextRound.stage());
        assertEquals(Optional.of("high"), nextRound.currentRegionId());
        assertEquals(2, nextRound.round());
    }

    @Test
    void allIdleCompletesOnlyAfterWorkflowDelay() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region("only");
        PickupWorkflow workflow = workflow(
                List.of(binding(region, 100)),
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.ALL_IDLE,
                Duration.ofSeconds(10)
        );
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        PickupAutomationEngine engine = engine(
                support,
                workflow,
                List.of(region),
                Optional.empty()
        );

        engine.tick();
        engine.tick();
        engine.tick();
        support.navigation.setPosition(support.cache.position());
        assertEquals(PickupStage.NAVIGATE_PICKUP, engine.tick().stage());
        support.clock.advance(Duration.ofSeconds(10));
        support.navigation.setPosition(ORIGIN);
        engine.tick();
        engine.tick();
        engine.tick();
        support.navigation.setPosition(support.cache.position());

        assertEquals(PickupStage.COMPLETE, engine.tick().stage());
    }

    @Test
    void newlyObservedItemResetsWorkflowIdleTimer() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region("only");
        PickupWorkflow workflow = workflow(
                List.of(binding(region, 100)),
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.ALL_IDLE,
                Duration.ofSeconds(30)
        );
        support.checkpoints.save(new AutomationCheckpoint(
                AutomationTestSupport.TASK_ID,
                PickupStage.WAIT_FOR_IDLE,
                Optional.of("workflow"),
                Optional.empty(),
                Optional.of("only"),
                2,
                Set.of(),
                Optional.of(START),
                Map.of("only", START),
                Set.of(),
                Map.of(),
                Optional.of(support.cache.id()),
                0,
                false
        ));
        support.world.setGroundItems("only", List.of(new GroundItem(
                "drop",
                AutomationTestSupport.STONE,
                1,
                ORIGIN
        )));
        PickupAutomationEngine engine = engine(
                support,
                workflow,
                List.of(region),
                Optional.empty()
        );

        assertEquals(PickupStage.COLLECT, engine.tick().stage());

        AutomationCheckpoint saved = support.checkpoints
                .load(AutomationTestSupport.TASK_ID)
                .orElseThrow();
        assertTrue(saved.workflowIdleSince().isEmpty());
        assertTrue(saved.regionIdleSince().isEmpty());
    }

    @Test
    void choosesNearestCacheUsingThreeDimensionalDistance() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region("only");
        ContainerRef highCache = new ContainerRef(
                "high-cache",
                DIMENSION,
                new BlockPosition(1, 164, 0),
                support.cache.type()
        );
        ContainerRef levelCache = new ContainerRef(
                "level-cache",
                DIMENSION,
                new BlockPosition(3, 64, 0),
                support.cache.type()
        );
        PickupWorkflow workflow = new PickupWorkflow(
                "workflow",
                "Workflow",
                DIMENSION,
                List.of(binding(region, 100)),
                List.of(highCache.id(), levelCache.id()),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
        support.inventory.setSnapshot(AutomationFakes.inventory(
                AutomationTestSupport.STONE,
                1
        ));
        support.navigation.setPosition(levelCache.position());
        support.containers.putView(AutomationFakes.view(levelCache, 1, List.of()));
        support.containers.addResult(AutomationFakes.acknowledged(
                levelCache,
                2,
                List.of(new ContainerSlot(0, AutomationTestSupport.STONE, 1))
        ));
        support.checkpoints.save(new AutomationCheckpoint(
                AutomationTestSupport.TASK_ID,
                PickupStage.DEPOSIT_BUFFER,
                Set.of(),
                Optional.empty(),
                Map.of()
        ));
        PickupAutomationPlan plan = plan(
                support,
                workflow,
                List.of(region),
                Optional.empty(),
                List.of(highCache, levelCache)
        );
        PickupAutomationEngine engine = createEngine(support, plan);

        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        assertEquals(
                levelCache,
                support.containers.attempts().getFirst().transfer().destination()
        );
    }

    @Test
    void regionOverrideRunsOnlySelectedRegionWithWorkflowPolicies() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion high = region("high");
        PickupRegion low = region("low");
        PickupWorkflow workflow = workflow(
                List.of(binding(high, 100), binding(low, 10)),
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
        PickupAutomationPlan plan = plan(
                support,
                workflow,
                List.of(high, low),
                Optional.of("low")
        );
        PickupAutomationEngine engine = createEngine(support, plan);

        AutomationSnapshot snapshot = engine.tick();

        assertEquals(List.of("low"), plan.orderedRegions().stream()
                .map(PickupRegionBinding::regionId)
                .toList());
        assertEquals(Optional.of("low"), snapshot.currentRegionId());
        assertEquals("workflow", snapshot.workflowId());
    }

    @Test
    void restoresWorkflowRegionRoundAndFailedCachesWithoutReplayingClicks() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion high = region("high");
        PickupRegion low = region("low");
        PickupWorkflow workflow = workflow(
                List.of(binding(high, 100), binding(low, 10)),
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ZERO
        );
        support.checkpoints.save(new AutomationCheckpoint(
                AutomationTestSupport.TASK_ID,
                PickupStage.WAIT_FOR_IDLE,
                Optional.of("workflow"),
                Optional.empty(),
                Optional.of("low"),
                3,
                Set.of(support.cache.id()),
                Optional.of(START),
                Map.of("low", START),
                Set.of(),
                Map.of(),
                Optional.of(support.secondaryCache.id()),
                0,
                false
        ));
        PickupAutomationPlan plan = plan(
                support,
                workflow,
                List.of(high, low),
                Optional.empty(),
                List.of(support.cache, support.secondaryCache)
        );
        PickupAutomationEngine engine = createEngine(support, plan);

        engine.start();
        AutomationSnapshot snapshot = engine.snapshot();

        assertEquals(Optional.of("low"), snapshot.currentRegionId());
        assertEquals(3, snapshot.round());
        assertTrue(support.containers.attempts().isEmpty());
    }

    private PickupAutomationEngine engine(
            AutomationTestSupport support,
            PickupWorkflow workflow,
            List<PickupRegion> regions,
            Optional<String> override
    ) {
        return createEngine(support, plan(support, workflow, regions, override));
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
            Optional<String> override
    ) {
        return plan(support, workflow, regions, override, List.of(support.cache));
    }

    private PickupAutomationPlan plan(
            AutomationTestSupport support,
            PickupWorkflow workflow,
            List<PickupRegion> regions,
            Optional<String> override,
            List<ContainerRef> caches
    ) {
        Map<String, PickupRegion> regionsById = new LinkedHashMap<>();
        regions.forEach(region -> regionsById.put(region.id(), region));
        Map<String, ContainerRef> cachesById = new LinkedHashMap<>();
        caches.forEach(cache -> cachesById.put(cache.id(), cache));
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
            ClassificationTiming timing,
            CompletionMode completionMode,
            Duration completionIdleDelay
    ) {
        return new PickupWorkflow(
                "workflow",
                "Workflow",
                DIMENSION,
                bindings,
                List.of("cache"),
                CachePolicy.NEAREST_AVAILABLE,
                timing,
                completionMode,
                completionIdleDelay
        );
    }

    private PickupRegionBinding binding(PickupRegion region, int priority) {
        return new PickupRegionBinding(region.id(), priority, Duration.ZERO);
    }

    private PickupRegion region(String id) {
        return new PickupRegion(
                id,
                id,
                DIMENSION,
                ORIGIN,
                new SpherePickupShape(ORIGIN, 16)
        );
    }
}
