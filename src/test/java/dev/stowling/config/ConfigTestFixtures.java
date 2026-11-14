package dev.stowling.config;

import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.AuditMode;
import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.StorageRoute;
import java.time.Duration;
import java.util.List;

final class ConfigTestFixtures {
    private ConfigTestFixtures() {
    }

    static ContainerRef container(String id, int x) {
        return new ContainerRef(
                id,
                "minecraft:overworld",
                new BlockPosition(x, 64, 0),
                ContainerType.CHEST
        );
    }

    static StorageRoute route(String id, List<String> items, int priority) {
        return new StorageRoute(
                id,
                items,
                List.of(),
                priority,
                container("bulk-" + id, 20),
                null,
                null
        );
    }

    static StowlingConfig config(List<StorageRoute> routes) {
        ContainerRef cache = container("cache", 8);
        ContainerRef overflow = container("overflow", 24);
        PickupSite site = new PickupSite(
                "farm",
                "minecraft:overworld",
                new BlockPosition(0, 64, 0),
                32,
                cache,
                Duration.ofSeconds(60)
        );
        AuditSchedule audit = new AuditSchedule(
                "manual",
                AuditMode.MANUAL,
                Duration.ZERO,
                List.of(cache.id())
        );
        return new StowlingConfig(
                1,
                Duration.ofSeconds(60),
                List.of(site),
                routes,
                List.of(),
                List.of(audit),
                overflow
        );
    }

    static StowlingConfig configWithAutomation(List<AutomationWorkflow> workflows) {
        StowlingConfig base = config(List.of());
        return new StowlingConfig(
                5,
                base.coordinatePoints(),
                base.pickupRegions(),
                base.cacheContainers(),
                base.pickupWorkflows(),
                workflows,
                base.storageRoutes(),
                base.packingStations(),
                base.auditSchedules(),
                base.overflowContainer()
        );
    }
}
