package dev.stowling.client;

import dev.stowling.automation.WorldObserver;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupShape;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.schedule.RegionItemProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Observes client-known items only. It never navigates or requests missing chunks. */
public final class ScheduledRegionItemProbe implements RegionItemProbe {
    private final Minecraft minecraft;
    private final WorldObserver observer;
    private final Supplier<StowlingConfig> config;

    public ScheduledRegionItemProbe(
            Minecraft minecraft, WorldObserver observer, Supplier<StowlingConfig> config
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Observation observe(List<String> regionIds) {
        List<PickupRegion> configured = config.get().pickupRegions();
        List<PickupRegion> regions = new ArrayList<>();
        for (String id : regionIds) {
            PickupRegion region = configured.stream().filter(value -> value.id().equals(id))
                    .findFirst().orElse(null);
            if (region == null) {
                return Observation.UNOBSERVABLE;
            }
            regions.add(region);
        }
        return observeRegions(regions);
    }

    @Override
    public Observation observeRegions(List<PickupRegion> regions) {
        if (minecraft.level == null || minecraft.player == null
                || !observer.observeWorld().connected()) {
            return Observation.UNOBSERVABLE;
        }
        ClientLevel level = minecraft.level;
        String dimension = level.dimension().identifier().toString();
        return observeRegions(observer, regions, dimension, level::hasChunk);
    }

    static Observation observeRegions(
            WorldObserver observer,
            List<PickupRegion> regions,
            String dimension,
            BiPredicate<Integer, Integer> chunkVisible
    ) {
        boolean observable = !regions.isEmpty();
        for (PickupRegion region : regions) {
            if (!region.dimension().equals(dimension)) {
                observable = false;
                continue;
            }
            if (!observer.observeGroundItems(region).isEmpty()) {
                return Observation.PRESENT;
            }
            if (!allChunksVisible(region.shape(), chunkVisible)) {
                observable = false;
            }
        }
        return observable ? Observation.EMPTY : Observation.UNOBSERVABLE;
    }

    private static boolean allChunksVisible(
            PickupShape shape,
            BiPredicate<Integer, Integer> chunkVisible
    ) {
        int minX;
        int maxX;
        int minZ;
        int maxZ;
        if (shape instanceof SpherePickupShape sphere) {
            minX = sphere.center().x() - sphere.radius();
            maxX = sphere.center().x() + sphere.radius();
            minZ = sphere.center().z() - sphere.radius();
            maxZ = sphere.center().z() + sphere.radius();
        } else if (shape instanceof CuboidPickupShape cuboid) {
            minX = cuboid.firstCorner().x();
            maxX = cuboid.secondCorner().x();
            minZ = cuboid.firstCorner().z();
            maxZ = cuboid.secondCorner().z();
        } else {
            return false;
        }
        // A region may be huge. Stop at the first missing client chunk, without loading it.
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (!chunkVisible.test(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }
}
