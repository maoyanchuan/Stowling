package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.StorageAutomationSettings;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemporaryContainerResolverTest {
    @Test
    void overflowCombinesPointAndRegionFacilitiesWithoutDuplicatingDestinations() {
        RegionDefinition region = new RegionDefinition(UUID.randomUUID(), "Overflow",
                0, 64, 0, 8, 64, 8);
        BlockPosition point = new BlockPosition(1, 64, 1);
        ContainerTarget extra = new ContainerTarget(new BlockPosition(2, 64, 1), "barrel");
        assertEquals(List.of(new ContainerTarget(point, "container"), extra),
                TemporaryContainerResolver.resolve(List.of(point), List.of(region),
                        (selected, types, limit) -> ContainerScanResult.success(List.of(
                                new ContainerTarget(point, "chest"), extra)), Set.of(), "溢出"));
    }

    @Test
    void regionOnlyStagingAcceptsAndDeduplicatesScannedContainers() {
        UUID id = UUID.randomUUID();
        RegionDefinition region = new RegionDefinition(id, "Staging", 0, 64, 0, 8, 65, 8);
        var settings = new StorageAutomationSettings(true, List.of(), List.of(), null,
                List.of(), List.of(), null, List.of(id));
        ContainerTarget chest = new ContainerTarget(new BlockPosition(1, 64, 1), "chest");
        assertEquals(List.of(chest), TemporaryContainerResolver.resolve(settings,
                List.of(region, region), (selected, types, limit) -> {
                    assertEquals(TemporaryContainerResolver.TYPES, types);
                    return ContainerScanResult.success(List.of(chest));
                }));
    }

    @Test
    void scansPreserveFailuresAndDoNotUseTheEmptyBoxSupplyAsStaging() {
        UUID id = UUID.randomUUID();
        RegionDefinition region = new RegionDefinition(id, "Staging", 0, 64, 0, 8, 65, 8);
        BlockPosition supply = new BlockPosition(1, 64, 1);
        var settings = new StorageAutomationSettings(true, List.of(), List.of(supply), null,
                List.of(), List.of(), null, List.of(id));
        assertThrows(IllegalStateException.class, () -> TemporaryContainerResolver.resolve(settings,
                List.of(region), (selected, types, limit) -> ContainerScanResult.failure("unloaded")));
        assertThrows(IllegalStateException.class, () -> TemporaryContainerResolver.resolve(settings,
                List.of(region), (selected, types, limit) -> ContainerScanResult.success(
                        List.of(new ContainerTarget(supply, "chest")))));
    }
}
