package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.StorageAutomationSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Expands saved staging regions using the same scan contract as cache regions. */
public final class TemporaryContainerResolver {
    public static final Set<String> TYPES = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel");
    private static final int MAX_VOLUME = 1_000_000;

    private TemporaryContainerResolver() {
    }

    public static List<ContainerTarget> resolve(StorageAutomationSettings settings,
            List<RegionDefinition> regions, ContainerRegionPort scanner) {
        Map<BlockPosition, ContainerTarget> targets = new LinkedHashMap<>();
        for (BlockPosition point : settings.temporaryContainers()) {
            targets.put(point, new ContainerTarget(point, "container"));
        }
        for (RegionDefinition region : regions) {
            ContainerScanResult scan = scanner.scan(region, TYPES, MAX_VOLUME);
            if (!scan.succeeded()) {
                throw new IllegalStateException("暂存区域“" + region.name() + "”扫描失败："
                        + scan.message());
            }
            for (ContainerTarget target : scan.containers()) {
                if (settings.emptyBoxContainers().contains(target.position())) {
                    throw new IllegalStateException("暂存区域包含空盒供应箱，请调整区域范围");
                }
                targets.putIfAbsent(target.position(), target);
            }
        }
        return List.copyOf(targets.values());
    }
}
