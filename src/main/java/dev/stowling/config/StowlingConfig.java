package dev.stowling.config;

import dev.stowling.domain.AuditSchedule;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.StorageRoute;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record StowlingConfig(
        int schemaVersion,
        Duration defaultIdleDelay,
        List<PickupSite> pickupSites,
        List<StorageRoute> storageRoutes,
        List<PackingStation> packingStations,
        List<AuditSchedule> auditSchedules,
        ContainerRef overflowContainer
) {
    public StowlingConfig {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException(
                    "unsupported config schemaVersion: " + schemaVersion
            );
        }
        defaultIdleDelay = Objects.requireNonNull(defaultIdleDelay, "defaultIdleDelay");
        pickupSites = List.copyOf(Objects.requireNonNull(pickupSites, "pickupSites"));
        storageRoutes = List.copyOf(Objects.requireNonNull(storageRoutes, "storageRoutes"));
        packingStations = List.copyOf(Objects.requireNonNull(packingStations, "packingStations"));
        auditSchedules = List.copyOf(Objects.requireNonNull(auditSchedules, "auditSchedules"));
        overflowContainer = Objects.requireNonNull(overflowContainer, "overflowContainer");
        if (defaultIdleDelay.isNegative()) {
            throw new IllegalArgumentException("defaultIdleDelay must not be negative");
        }
    }
}
