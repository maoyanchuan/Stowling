package dev.stowling.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.GroundItem;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.schedule.RegionItemProbe.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScheduledRegionItemProbeTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockPosition STANDBY = new BlockPosition(0, 64, 0);

    @Test
    void customCuboidUsesProvidedRegionAndChecksInclusiveChunkBounds() {
        RecordingWorld world = new RecordingWorld(List.of());
        PickupRegion region = customRegion(DIMENSION);
        List<String> chunks = new ArrayList<>();

        Observation result = ScheduledRegionItemProbe.observeRegions(
                world, List.of(region), DIMENSION,
                (x, z) -> {
                    chunks.add(x + "," + z);
                    return true;
                }
        );

        assertEquals(Observation.EMPTY, result);
        assertSame(region, world.regions().getFirst());
        assertEquals(
                List.of(
                        "-2,-1", "-2,0", "-2,1", "-2,2",
                        "-1,-1", "-1,0", "-1,1", "-1,2",
                        "0,-1", "0,0", "0,1", "0,2",
                        "1,-1", "1,0", "1,1", "1,2",
                        "2,-1", "2,0", "2,1", "2,2"
                ),
                chunks
        );
    }

    @Test
    void visibleItemsArePresentEvenWhenOtherChunksMayBeMissing() {
        GroundItem item = new GroundItem(
                "drop", "minecraft:cobblestone", 1, STANDBY
        );
        RecordingWorld world = new RecordingWorld(List.of(item));
        PickupRegion region = customRegion(DIMENSION);

        Observation result = ScheduledRegionItemProbe.observeRegions(
                world, List.of(region), DIMENSION,
                (x, z) -> {
                    throw new AssertionError("present items must not require loading chunks");
                }
        );

        assertEquals(Observation.PRESENT, result);
        assertSame(region, world.regions().getFirst());
    }

    @Test
    void missingClientChunkReturnsUnobservableAndStopsChecking() {
        RecordingWorld world = new RecordingWorld(List.of());
        List<String> chunks = new ArrayList<>();

        Observation result = ScheduledRegionItemProbe.observeRegions(
                world, List.of(customRegion(DIMENSION)), DIMENSION,
                (x, z) -> {
                    chunks.add(x + "," + z);
                    return false;
                }
        );

        assertEquals(Observation.UNOBSERVABLE, result);
        assertEquals(List.of("-2,-1"), chunks);
    }

    @Test
    void otherDimensionIsNotObservedOrCheckedForChunks() {
        RecordingWorld world = new RecordingWorld(List.of());

        Observation result = ScheduledRegionItemProbe.observeRegions(
                world, List.of(customRegion("minecraft:the_nether")), DIMENSION,
                (x, z) -> {
                    throw new AssertionError("other dimensions must not query client chunks");
                }
        );

        assertEquals(Observation.UNOBSERVABLE, result);
        assertTrue(world.regions().isEmpty());
    }

    @Test
    void emptyRegionListIsUnobservableWithoutChunkQueries() {
        RecordingWorld world = new RecordingWorld(List.of());

        Observation result = ScheduledRegionItemProbe.observeRegions(
                world, List.of(), DIMENSION,
                (x, z) -> {
                    throw new AssertionError("no region must not query client chunks");
                }
        );

        assertEquals(Observation.UNOBSERVABLE, result);
        assertTrue(world.regions().isEmpty());
    }

    @Test
    void legacySphereUsesTheSameClientVisibilityObservation() {
        RecordingWorld world = new RecordingWorld(List.of());
        PickupRegion region = new PickupRegion(
                "named-region", "Named region", DIMENSION, STANDBY,
                new SpherePickupShape(STANDBY, 4)
        );
        List<String> chunks = new ArrayList<>();

        Observation result = ScheduledRegionItemProbe.observeRegions(
                world, List.of(region), DIMENSION,
                (x, z) -> {
                    chunks.add(x + "," + z);
                    return true;
                }
        );

        assertEquals(Observation.EMPTY, result);
        assertEquals(List.of("-1,-1", "-1,0", "0,-1", "0,0"), chunks);
        assertSame(region, world.regions().getFirst());
    }

    private static PickupRegion customRegion(String dimension) {
        return new PickupRegion(
                "process-local-region", "Custom region", dimension,
                "custom-standby", STANDBY,
                new CuboidPickupShape(
                        "custom-first", "custom-second",
                        new BlockPosition(-17, 60, -1), new BlockPosition(33, 70, 32)
                )
        );
    }

    private static final class RecordingWorld implements WorldObserver {
        private final List<GroundItem> items;
        private final List<PickupRegion> regions = new ArrayList<>();

        private RecordingWorld(List<GroundItem> items) {
            this.items = List.copyOf(items);
        }

        @Override
        public WorldObservation observeWorld() {
            return new WorldObservation(true, true, DIMENSION, Optional.of(STANDBY));
        }

        @Override
        public List<GroundItem> observeGroundItems(PickupSite site) {
            throw new AssertionError("observation must preserve the region's actual shape");
        }

        @Override
        public List<GroundItem> observeGroundItems(PickupRegion region) {
            regions.add(region);
            return items;
        }

        @Override
        public Set<String> tagsFor(String itemId) {
            return Set.of();
        }

        private List<PickupRegion> regions() {
            return List.copyOf(regions);
        }
    }
}
