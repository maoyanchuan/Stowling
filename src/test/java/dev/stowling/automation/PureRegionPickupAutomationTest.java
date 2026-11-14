package dev.stowling.automation;

import static dev.stowling.automation.AutomationTestSupport.DIMENSION;
import static dev.stowling.automation.AutomationTestSupport.STONE;
import static dev.stowling.automation.AutomationTestSupport.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.schedule.StopOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PureRegionPickupAutomationTest {
    private static final BlockPosition ORIGIN = new BlockPosition(0, 64, 0);

    @Test
    void navigatesOnlyToGroundItemsWithoutStandbyCacheOrPacking() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region(new BlockPosition(40, 64, 40));
        GroundItem item = new GroundItem(
                "drop",
                STONE,
                1,
                new BlockPosition(3, 64, 0)
        );
        support.world.setGroundItems(region.id(), List.of(item));
        PickupAutomationEngine engine = engine(support, region, Duration.ofSeconds(7));

        AutomationSnapshot collecting = engine.tick();

        assertEquals(PickupStage.COLLECT, collecting.stage());
        assertEquals(
                List.of(item.position()),
                support.navigation.startedTargets()
        );
        assertFalse(support.navigation.startedTargets().contains(
                region.standbyPosition()
        ));
        assertTrue(support.containers.observedContainers().isEmpty());
        assertEquals(0, support.packing.submits());

        support.world.setGroundItems(region.id(), List.of());
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        assertTrue(support.containers.observedContainers().isEmpty());
        assertEquals(0, support.packing.submits());
    }

    @Test
    void emptyRegionCompletesAfterConfiguredIdleWithoutInfrastructure() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region(new BlockPosition(40, 64, 40));
        PickupAutomationEngine engine = engine(support, region, Duration.ofSeconds(5));

        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        assertTrue(support.navigation.startedTargets().isEmpty());
        support.clock.advance(Duration.ofSeconds(5));

        AutomationSnapshot completed = engine.tick();

        assertEquals(PickupStage.COMPLETE, completed.stage());
        assertTrue(support.containers.observedContainers().isEmpty());
        assertEquals(0, support.packing.submits());
        assertEquals(StopOutcome.CLEAN, engine.requestStop());
    }

    @Test
    void itemAppearingDuringIdleStartsGroundItemNavigation() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region(new BlockPosition(40, 64, 40));
        GroundItem item = new GroundItem(
                "late-drop",
                STONE,
                1,
                new BlockPosition(5, 64, 0)
        );
        PickupAutomationEngine engine = engine(support, region, Duration.ofSeconds(5));

        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        support.world.setGroundItems(region.id(), List.of(item));

        AutomationSnapshot collecting = engine.tick();

        assertEquals(PickupStage.COLLECT, collecting.stage());
        assertEquals(List.of(item.position()), support.navigation.startedTargets());
        assertFalse(support.navigation.startedTargets().contains(
                region.standbyPosition()
        ));
    }

    @Test
    void reserveThresholdCompletesForLaterStorageStep() {
        AutomationTestSupport support = new AutomationTestSupport();
        support.inventory.setSnapshot(new PlayerInventorySnapshot(2, List.of()));
        PickupRegion region = region(new BlockPosition(40, 64, 40));
        support.world.setGroundItems(region.id(), List.of(new GroundItem(
                "waiting-drop",
                STONE,
                1,
                new BlockPosition(6, 64, 0)
        )));
        PickupAutomationEngine engine = engine(support, region, Duration.ofSeconds(5));

        AutomationSnapshot completed = engine.tick();

        assertEquals(PickupStage.COMPLETE, completed.stage());
        assertTrue(support.navigation.startedTargets().isEmpty());
        assertTrue(support.containers.observedContainers().isEmpty());
        assertEquals(0, support.packing.submits());
        assertEquals(StopOutcome.CLEAN, engine.requestStop());
    }

    @Test
    void legacyElevenArgumentPlanStillRequiresCacheContainer() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupRegion region = region(new BlockPosition(40, 64, 40));

        assertThrows(IllegalArgumentException.class, () ->
                new PickupAutomationPlan(
                        TASK_ID,
                        workflow(region, Duration.ofSeconds(5)),
                        Optional.empty(),
                        Map.of(region.id(), region),
                        Map.of(),
                        List.of(),
                        support.overflow,
                        2,
                        1,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30)
                ));
    }

    private PickupAutomationEngine engine(
            AutomationTestSupport support,
            PickupRegion region,
            Duration idleDelay
    ) {
        PickupAutomationPlan plan = new PickupAutomationPlan(
                TASK_ID,
                workflow(region, idleDelay),
                Optional.empty(),
                Map.of(region.id(), region),
                Map.of(),
                List.of(),
                null,
                2,
                1,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                true
        );
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

    private PickupWorkflow workflow(PickupRegion region, Duration idleDelay) {
        return new PickupWorkflow(
                "pickup-region",
                "Region pickup",
                DIMENSION,
                List.of(new PickupRegionBinding(region.id(), 100, idleDelay)),
                List.of(),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.ALL_IDLE,
                Duration.ZERO
        );
    }

    private PickupRegion region(BlockPosition standbyPosition) {
        return new PickupRegion(
                "north",
                "North",
                DIMENSION,
                standbyPosition,
                new SpherePickupShape(ORIGIN, 16)
        );
    }
}
