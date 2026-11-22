package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CacheLocations {
    private CacheLocations() {
    }

    public static ContainerScanResult scan(TaskExecutionContext context, ResourceReference ref,
            Set<String> types, int maxVolume, boolean complete) {
        var warehouse = context.warehouses().get(ref.uuid());
        if (ref.type() == ResourceType.WAREHOUSE && warehouse == null) {
            return ContainerScanResult.failure("任务引用的仓库不存在");
        }
        List<ResourceReference> locations = ref.type() == ResourceType.WAREHOUSE
                ? warehouse.members().stream().map(ResourceReference::parse).toList()
                : List.of(ref);
        var seen = new HashSet<BlockPosition>();
        var containers = new ArrayList<ContainerTarget>();
        for (var location : locations) {
            var region = bounds(context, location);
            if (region == null) continue;
            var result = complete ? context.containerRegions().scanComplete(region, types, maxVolume)
                    : context.containerRegions().scan(region, types, maxVolume);
            if (!result.succeeded()) {
                return ContainerScanResult.failure(region.name() + "：" + result.message());
            }
            for (var container : result.containers()) {
                boolean distinct = container.entrances().stream().noneMatch(seen::contains);
                seen.addAll(container.entrances());
                if (distinct) containers.add(container);
            }
        }
        if (ref.type() != ResourceType.REGION && containers.isEmpty()) {
            String name = warehouse == null ? "所选点位" : warehouse.name();
            return ContainerScanResult.failure(name + "在当前维度没有可用容器");
        }
        return ContainerScanResult.success(containers);
    }

    private static RegionDefinition bounds(TaskExecutionContext context, ResourceReference ref) {
        if (ref.type() == ResourceType.REGION) {
            return ResourceReferenceSupport.region(context, ref);
        }
        var point = ResourceReferenceSupport.coordinate(context, ref);
        if (!TaskModuleSupport.dimensionMatches(context, point)) return null;
        return new RegionDefinition(point.id(), point.name(), point.x(), point.y(), point.z(),
                point.x(), point.y(), point.z());
    }
}
