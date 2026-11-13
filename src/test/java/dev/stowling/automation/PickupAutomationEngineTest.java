package dev.stowling.automation;

import static dev.stowling.automation.AutomationTestSupport.DIAMOND;
import static dev.stowling.automation.AutomationTestSupport.DIMENSION;
import static dev.stowling.automation.AutomationTestSupport.START;
import static dev.stowling.automation.AutomationTestSupport.STONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.PickupSite;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PickupAutomationEngineTest {
    @Test
    void runsPickupBufferAndIdleFlow() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupAutomationEngine engine = support.engine(List.of(), 1);

        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        support.world.setGroundItems(List.of(new GroundItem(
                "drop-1",
                STONE,
                1,
                new BlockPosition(0, 64, 0)
        )));
        assertEquals(PickupStage.COLLECT, engine.tick().stage());

        support.world.setGroundItems(List.of());
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.tick().stage());

        support.navigation.setPosition(support.cache.position());
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        support.containers.addResult(AutomationFakes.acknowledged(
                support.cache,
                2,
                List.of(new ContainerSlot(0, STONE, 1))
        ));

        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        assertEquals(1, support.containers.attempts().size());
    }

    @Test
    void depositsBeforeInventoryBecomesFull() {
        AutomationTestSupport support = new AutomationTestSupport();
        support.world.setGroundItems(List.of(new GroundItem(
                "drop-1",
                STONE,
                1,
                new BlockPosition(0, 64, 0)
        )));
        support.inventory.setSnapshot(nearlyFullInventory());
        PickupAutomationEngine engine = support.engine(List.of(), 1);

        assertEquals(PickupStage.COLLECT, engine.tick().stage());
        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.tick().stage());
    }

    @Test
    void defaultIdleDelayComesFromPickupSite() {
        AutomationTestSupport support = new AutomationTestSupport();
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        support.checkpoint(PickupStage.WAIT_FOR_IDLE, Optional.of(START), Map.of());
        PickupAutomationEngine engine = support.engine(List.of(), 1);
        engine.start();

        support.clock.advance(Duration.ofSeconds(59));
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());

        support.clock.advance(Duration.ofSeconds(1));
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
    }

    @Test
    void newlyObservedInputResetsIdleTimer() {
        AutomationTestSupport support = new AutomationTestSupport();
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        support.checkpoint(PickupStage.WAIT_FOR_IDLE, Optional.of(START), Map.of());
        PickupAutomationEngine engine = support.engine(List.of(), 1);
        engine.start();

        support.clock.advance(Duration.ofSeconds(30));
        support.world.setGroundItems(List.of(new GroundItem(
                "drop-new",
                STONE,
                1,
                new BlockPosition(1, 64, 0)
        )));
        assertEquals(PickupStage.COLLECT, engine.tick().stage());

        support.world.setGroundItems(List.of());
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        support.clock.advance(Duration.ofSeconds(59));
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        support.clock.advance(Duration.ofSeconds(1));
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
    }

    @Test
    void retriesPendingContainerTransactionWithinBudget() {
        AutomationTestSupport support = depositSupport();
        support.containers.addResult(pending());
        support.containers.addResult(pending());
        support.containers.addResult(AutomationFakes.acknowledged(
                support.cache,
                3,
                List.of(new ContainerSlot(0, STONE, 1))
        ));
        PickupAutomationEngine engine = support.engine(List.of(), 1);
        engine.start();

        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.tick().stage());
        support.clock.advance(Duration.ofSeconds(5));
        AutomationSnapshot retrySnapshot = engine.tick();
        assertEquals(
                PickupStage.DEPOSIT_BUFFER,
                retrySnapshot.stage(),
                retrySnapshot.toString()
        );
        assertEquals(PickupStage.WAIT_FOR_IDLE, engine.tick().stage());
        assertEquals(2, support.containers.attempts().size());
    }

    @Test
    void pausesAfterContainerAcknowledgementTimeoutBudget() {
        AutomationTestSupport support = depositSupport();
        PickupAutomationEngine engine = support.engine(List.of(), 0);
        engine.start();

        engine.tick();
        support.clock.advance(Duration.ofSeconds(5));
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.PAUSED, snapshot.stage());
        assertEquals("CONTAINER_ACK_TIMEOUT", snapshot.pauseReason().orElseThrow().code());
        assertEquals(PickupStage.DEPOSIT_BUFFER, snapshot.resumeStage().orElseThrow());
    }

    @Test
    void pausesWhenDisconnectedOrDead() {
        AutomationTestSupport disconnected = new AutomationTestSupport();
        disconnected.world.setWorld(WorldObservation.disconnected());
        AutomationSnapshot disconnectedSnapshot = disconnected.engine(List.of(), 1).tick();
        assertEquals("DISCONNECTED", disconnectedSnapshot.pauseReason().orElseThrow().code());

        AutomationTestSupport dead = new AutomationTestSupport();
        dead.world.setWorld(new WorldObservation(
                true,
                false,
                DIMENSION,
                Optional.of(new BlockPosition(0, 64, 0))
        ));
        AutomationSnapshot deadSnapshot = dead.engine(List.of(), 1).tick();
        assertEquals("DEAD", deadSnapshot.pauseReason().orElseThrow().code());
    }

    @Test
    void pausesWhenNavigationFails() {
        AutomationTestSupport support = new AutomationTestSupport();
        support.navigation.setPosition(new BlockPosition(-5, 64, 0));
        support.navigation.setStartStatus(NavigationStatus.FAILED);
        PickupAutomationEngine engine = support.engine(List.of(), 1);

        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.PAUSED, snapshot.stage());
        assertEquals("PATH_FAILED", snapshot.pauseReason().orElseThrow().code());
    }

    @Test
    void recoveryReconcilesBeforeStartingAnyNewTransaction() {
        AutomationTestSupport support = new AutomationTestSupport();
        support.navigation.setPosition(support.cache.position());
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        support.checkpoint(PickupStage.DEPOSIT_BUFFER, Optional.empty(), Map.of());
        PickupAutomationEngine engine = support.engine(List.of(), 1);

        engine.start();

        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.snapshot().stage());
        assertTrue(support.containers.attempts().isEmpty());
    }

    @Test
    void recoveryRebuildsClassificationWithoutReplayingClicks() {
        AutomationTestSupport support = new AutomationTestSupport();
        support.navigation.setPosition(support.cache.position());
        support.containers.putView(AutomationFakes.view(
                support.cache,
                4,
                List.of(new ContainerSlot(0, DIAMOND, 2))
        ));
        support.checkpoint(PickupStage.DELIVER_LOOSE, Optional.empty(), Map.of());
        PickupAutomationEngine engine = support.engine(
                List.of(AutomationTestSupport.looseRoute(DIAMOND)),
                1
        );

        engine.start();
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.DELIVER_LOOSE, snapshot.stage());
        assertTrue(support.containers.attempts().isEmpty());
    }

    @Test
    void unmatchedItemsRouteToOverflowInsteadOfBeingDropped() {
        AutomationTestSupport support = bufferSupport(DIAMOND);
        PickupAutomationEngine engine = support.engine(List.of(), 1);

        engine.start();
        AutomationSnapshot snapshot = engine.tick();

        DeliveryIntent intent = snapshot.activeDelivery().orElseThrow();
        assertEquals(PickupStage.DELIVER_LOOSE, snapshot.stage());
        assertEquals(support.overflow, intent.destination());
        assertTrue(intent.routeId().orElseThrow().contains("overflow"));
    }

    @Test
    void packingRouteEntersPackStage() {
        AutomationTestSupport support = bufferSupport(DIAMOND);
        PickupAutomationEngine engine = support.engine(
                List.of(AutomationTestSupport.packingRoute(DIAMOND)),
                1
        );

        engine.start();
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.PACK, snapshot.stage());
        assertEquals(DeliveryMode.PACK, snapshot.activeDelivery().orElseThrow().mode());
        assertEquals(
                "station-a",
                snapshot.activeDelivery().orElseThrow().packingStationId().orElseThrow()
        );
    }

    @Test
    void packingUnavailablePausesWithoutDiscardingItems() {
        AutomationTestSupport support = bufferSupport(DIAMOND);
        support.packing.setDisposition(PackingDisposition.UNAVAILABLE);
        PickupAutomationEngine engine = support.engine(
                List.of(AutomationTestSupport.packingRoute(DIAMOND)),
                1
        );

        engine.start();
        engine.tick();
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.PAUSED, snapshot.stage());
        assertEquals("PACKING_UNAVAILABLE", snapshot.pauseReason().orElseThrow().code());
    }

    @Test
    void pendingPackingRemainsInPackStage() {
        AutomationTestSupport support = bufferSupport(DIAMOND);
        support.packing.setDisposition(PackingDisposition.PENDING);
        PickupAutomationEngine engine = support.engine(
                List.of(AutomationTestSupport.packingRoute(DIAMOND)),
                1
        );

        engine.start();
        assertEquals(PickupStage.PACK, engine.tick().stage());
        AutomationSnapshot pending = engine.tick();

        assertEquals(PickupStage.PACK, pending.stage());
        assertTrue(pending.pauseReason().isEmpty());
        assertEquals(1, support.packing.requests().size());
    }

    @Test
    void depositSelectsNearestConfiguredCacheInitially() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupSite site = support.multiCacheSite();
        support.navigation.setPosition(support.secondaryCache.position());
        support.world.setWorld(AutomationTestSupport.connectedAt(
                support.secondaryCache.position()
        ));
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        support.containers.putView(AutomationFakes.view(
                support.secondaryCache,
                1,
                List.of()
        ));
        support.containers.addResult(AutomationFakes.acknowledged(
                support.secondaryCache,
                2,
                List.of(new ContainerSlot(0, STONE, 1))
        ));
        support.checkpoint(PickupStage.DEPOSIT_BUFFER, Optional.empty(), Map.of());
        PickupAutomationEngine engine = support.engineFor(site, List.of(), 1);

        engine.start();
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.WAIT_FOR_IDLE, snapshot.stage());
        assertEquals(
                support.secondaryCache,
                support.containers.attempts().getFirst().transfer().destination()
        );
    }

    @Test
    void depositFailsOverWhenFirstCacheRejectsTransfer() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupSite site = support.multiCacheSite();
        support.navigation.setPosition(support.cache.position());
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        support.containers.putView(AutomationFakes.view(
                support.secondaryCache,
                1,
                List.of()
        ));
        support.containers.addResult(new ContainerAttemptResult(
                ContainerAttemptStatus.REJECTED,
                1,
                Optional.empty(),
                Optional.empty(),
                "cache rejected the transfer"
        ));
        support.containers.addResult(AutomationFakes.acknowledged(
                support.secondaryCache,
                2,
                List.of(new ContainerSlot(0, STONE, 1))
        ));
        support.checkpoint(PickupStage.DEPOSIT_BUFFER, Optional.empty(), Map.of());
        PickupAutomationEngine engine = support.engineFor(site, List.of(), 1);

        engine.start();
        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.tick().stage());
        support.navigation.setPosition(support.secondaryCache.position());
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.WAIT_FOR_IDLE, snapshot.stage());
        assertEquals(2, support.containers.attempts().size());
        assertEquals(
                support.secondaryCache,
                support.containers.attempts().getLast().transfer().destination()
        );
    }

    @Test
    void depositFailsOverAfterCacheObservationTimeout() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupSite site = support.multiCacheSite();
        support.navigation.setPosition(support.cache.position());
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        support.containers.putView(AutomationFakes.view(
                support.secondaryCache,
                1,
                List.of()
        ));
        support.containers.addResult(AutomationFakes.acknowledged(
                support.secondaryCache,
                2,
                List.of(new ContainerSlot(0, STONE, 1))
        ));
        support.checkpoint(PickupStage.DEPOSIT_BUFFER, Optional.empty(), Map.of());
        PickupAutomationEngine engine = support.engineFor(site, List.of(), 1);

        engine.start();
        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.tick().stage());
        support.clock.advance(Duration.ofSeconds(5));
        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.tick().stage());
        support.navigation.setPosition(support.secondaryCache.position());
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.WAIT_FOR_IDLE, snapshot.stage());
        assertEquals(
                support.secondaryCache,
                support.containers.attempts().getFirst().transfer().destination()
        );
    }

    @Test
    void pausesOnlyAfterEveryConfiguredCacheFails() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupSite site = support.multiCacheSite();
        support.navigation.setPosition(new BlockPosition(-5, 64, 0));
        support.navigation.setStartStatus(NavigationStatus.FAILED);
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        support.checkpoint(PickupStage.DEPOSIT_BUFFER, Optional.empty(), Map.of());
        PickupAutomationEngine engine = support.engineFor(site, List.of(), 1);

        engine.start();
        assertEquals(PickupStage.DEPOSIT_BUFFER, engine.tick().stage());
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.PAUSED, snapshot.stage());
        assertEquals("PATH_FAILED", snapshot.pauseReason().orElseThrow().code());
        assertEquals(2, support.navigation.startedTargets().size());
    }

    @Test
    void classifiesEveryCacheInConfigurationOrderBeforeCompleting() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupSite site = support.multiCacheSite();
        support.navigation.setPosition(support.cache.position());
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        support.containers.putView(AutomationFakes.view(
                support.secondaryCache,
                2,
                List.of(new ContainerSlot(0, DIAMOND, 1))
        ));
        support.containers.putView(AutomationFakes.view(support.bulk, 1, List.of()));
        support.containers.addResult(AutomationFakes.acknowledged(
                support.bulk,
                2,
                List.of(new ContainerSlot(0, DIAMOND, 1))
        ));
        support.checkpoint(
                PickupStage.CLASSIFY_BUFFER,
                Optional.empty(),
                Map.of(),
                Optional.of(support.cache.id()),
                0
        );
        PickupAutomationEngine engine = support.engineFor(
                site,
                List.of(AutomationTestSupport.looseRoute(DIAMOND)),
                1
        );

        engine.start();
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
        support.navigation.setPosition(support.secondaryCache.position());
        AutomationSnapshot delivery = engine.tick();

        assertEquals(PickupStage.DELIVER_LOOSE, delivery.stage());
        assertEquals(support.secondaryCache, delivery.activeDelivery().orElseThrow().buffer());
        assertEquals(
                List.of(support.cache.id(), support.secondaryCache.id()),
                support.containers.observedContainers()
        );

        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
        support.containers.putView(AutomationFakes.view(
                support.secondaryCache,
                3,
                List.of()
        ));
        assertEquals(PickupStage.COMPLETE, engine.tick().stage());
    }

    @Test
    void pausesWhenACacheStaysUnavailableBeyondClassificationBudget() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupSite site = support.multiCacheSite();
        support.navigation.setPosition(support.cache.position());
        support.containers.putView(AutomationFakes.view(
                support.secondaryCache,
                1,
                List.of()
        ));
        support.checkpoint(
                PickupStage.CLASSIFY_BUFFER,
                Optional.empty(),
                Map.of(),
                Optional.of(support.cache.id()),
                0
        );
        PickupAutomationEngine engine = support.engineFor(site, List.of(), 1);

        engine.start();
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
        support.clock.advance(Duration.ofSeconds(5));
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.tick().stage());
        support.clock.advance(Duration.ofSeconds(5));
        AutomationSnapshot snapshot = engine.tick();

        assertEquals(PickupStage.PAUSED, snapshot.stage());
        assertEquals("BUFFER_UNAVAILABLE", snapshot.pauseReason().orElseThrow().code());
    }

    @Test
    void recoveryFromPackReturnsToClassificationWithoutContainerScreen() {
        AutomationTestSupport support = new AutomationTestSupport();
        PickupSite site = support.multiCacheSite();
        support.navigation.setPosition(support.secondaryCache.position());
        support.containers.putView(AutomationFakes.view(
                support.secondaryCache,
                2,
                List.of(new ContainerSlot(0, DIAMOND, 1))
        ));
        support.checkpoint(
                PickupStage.PACK,
                Optional.empty(),
                Map.of(),
                Optional.of(support.secondaryCache.id()),
                1
        );
        PickupAutomationEngine engine = support.engineFor(
                site,
                List.of(AutomationTestSupport.packingRoute(DIAMOND)),
                1
        );

        engine.start();

        assertEquals(PickupStage.CLASSIFY_BUFFER, engine.snapshot().stage());
        assertTrue(support.containers.observedContainers().isEmpty());
        assertTrue(support.containers.attempts().isEmpty());
        assertEquals(PickupStage.PACK, engine.tick().stage());
    }

    private AutomationTestSupport depositSupport() {
        AutomationTestSupport support = new AutomationTestSupport();
        support.navigation.setPosition(support.cache.position());
        support.inventory.setSnapshot(AutomationFakes.inventory(STONE, 1));
        support.containers.putView(AutomationFakes.view(support.cache, 1, List.of()));
        support.checkpoint(PickupStage.DEPOSIT_BUFFER, Optional.empty(), Map.of());
        return support;
    }

    private AutomationTestSupport bufferSupport(String itemId) {
        AutomationTestSupport support = new AutomationTestSupport();
        support.navigation.setPosition(support.cache.position());
        support.containers.putView(AutomationFakes.view(
                support.cache,
                1,
                List.of(new ContainerSlot(0, itemId, 1))
        ));
        support.checkpoint(PickupStage.CLASSIFY_BUFFER, Optional.empty(), Map.of());
        return support;
    }

    private ContainerAttemptResult pending() {
        return new ContainerAttemptResult(
                ContainerAttemptStatus.PENDING,
                1,
                Optional.empty(),
                Optional.empty(),
                "awaiting server acknowledgement"
        );
    }

    private PlayerInventorySnapshot nearlyFullInventory() {
        List<PlayerInventorySlot> slots = new ArrayList<>();
        for (int slot = 0; slot < 34; slot++) {
            slots.add(new PlayerInventorySlot(slot, STONE, 1));
        }
        return new PlayerInventorySnapshot(36, slots);
    }
}
