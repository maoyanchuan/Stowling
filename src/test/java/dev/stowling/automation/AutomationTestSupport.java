package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.StorageRoute;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class AutomationTestSupport {
    static final String DIMENSION = "minecraft:overworld";
    static final String STONE = "minecraft:stone";
    static final String DIAMOND = "minecraft:diamond";
    static final Instant START = Instant.parse("2026-11-13T12:00:00Z");
    static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    final AutomationFakes.MutableClock clock = new AutomationFakes.MutableClock(START);
    final ContainerRef cache = new ContainerRef(
            "cache",
            DIMENSION,
            new BlockPosition(10, 64, 0),
            ContainerType.CHEST
    );
    final ContainerRef bulk = new ContainerRef(
            "bulk",
            DIMENSION,
            new BlockPosition(20, 64, 0),
            ContainerType.CHEST
    );
    final ContainerRef overflow = new ContainerRef(
            "overflow",
            DIMENSION,
            new BlockPosition(30, 64, 0),
            ContainerType.CHEST
    );
    final PickupSite site = new PickupSite(
            "quarry",
            DIMENSION,
            new BlockPosition(0, 64, 0),
            16,
            cache,
            Duration.ofSeconds(60)
    );
    final AutomationFakes.FakeWorldObserver world = new AutomationFakes.FakeWorldObserver(
            connectedAt(new BlockPosition(0, 64, 0))
    );
    final AutomationFakes.FakeInventory inventory = new AutomationFakes.FakeInventory(
            AutomationFakes.emptyInventory()
    );
    final AutomationFakes.FakeNavigation navigation = new AutomationFakes.FakeNavigation(
            new BlockPosition(0, 64, 0)
    );
    final AutomationFakes.FakeContainers containers =
            new AutomationFakes.FakeContainers(START);
    final AutomationFakes.FakePacking packing = new AutomationFakes.FakePacking();
    final InMemoryAutomationCheckpointStore checkpoints =
            new InMemoryAutomationCheckpointStore();

    PickupAutomationEngine engine(List<StorageRoute> routes, int retries) {
        PickupAutomationPlan plan = new PickupAutomationPlan(
                TASK_ID,
                site,
                routes,
                overflow,
                2,
                retries,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30)
        );
        return new PickupAutomationEngine(
                plan,
                world,
                inventory,
                navigation,
                containers,
                packing,
                clock,
                checkpoints
        );
    }

    void checkpoint(
            PickupStage stage,
            Optional<Instant> idleSince,
            Map<String, Integer> baseline
    ) {
        checkpoints.save(new AutomationCheckpoint(
                TASK_ID,
                stage,
                Set.of(),
                idleSince,
                baseline
        ));
    }

    static WorldObservation connectedAt(BlockPosition position) {
        return new WorldObservation(true, true, DIMENSION, Optional.of(position));
    }

    static StorageRoute looseRoute(String itemId) {
        return new StorageRoute(
                "stone-route",
                List.of(itemId),
                List.of(),
                10,
                new ContainerRef(
                        "bulk",
                        DIMENSION,
                        new BlockPosition(20, 64, 0),
                        ContainerType.CHEST
                ),
                null,
                null
        );
    }

    static StorageRoute packingRoute(String itemId) {
        return new StorageRoute(
                "pack-route",
                List.of(itemId),
                List.of(),
                10,
                new ContainerRef(
                        "bulk",
                        DIMENSION,
                        new BlockPosition(20, 64, 0),
                        ContainerType.CHEST
                ),
                64,
                "station-a"
        );
    }
}
