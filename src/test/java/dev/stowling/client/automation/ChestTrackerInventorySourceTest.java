package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.inventory.WarehouseInventory;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.category.DirectBlockIdCategoryRule;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChestTrackerInventorySourceTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String DRIPLEAF = "minecraft:big_dripleaf";
    private static final BlockPosition MARKER = new BlockPosition(10, 65, 20);
    private static final BlockPosition STORAGE = new BlockPosition(10, 64, 20);
    private static final BlockPosition PLACED_MARKER = new BlockPosition(11, 65, 20);

    @Test
    void excludesTheMarkerBarrelFromTheDripleafTotal() {
        var marker = stock(OVERWORLD, List.of(MARKER), 1);
        var storage = stock(OVERWORLD, List.of(STORAGE), 276);
        List<AreaBounds> areas = markerAreas();

        long total = List.of(marker, storage).stream()
                .filter(stock -> !ChestTrackerInventorySource.isMarkerContainer(
                        stock, "minecraft:barrel", OVERWORLD, areas))
                .flatMap(stock -> stock.items().stream())
                .mapToLong(WarehouseInventory.Entry::loose)
                .sum();

        assertEquals(276, total);
    }

    @Test
    void matchesEitherChestHalfAndKeepsOtherDimensionsAndRecognitionRules() {
        List<AreaBounds> areas = markerAreas();
        var doubleChest = stock(OVERWORLD, List.of(PLACED_MARKER, MARKER), 1);
        var placedChest = stock(OVERWORLD, List.of(PLACED_MARKER), 1);
        var hopper = stock(OVERWORLD, List.of(MARKER), 1);
        var netherBarrel = stock("minecraft:the_nether", List.of(MARKER), 1);

        assertTrue(ChestTrackerInventorySource.isMarkerContainer(
                doubleChest, "minecraft:trapped_chest", OVERWORLD, areas));
        assertFalse(ChestTrackerInventorySource.isMarkerContainer(
                placedChest, "minecraft:chest", OVERWORLD, areas));
        assertFalse(ChestTrackerInventorySource.isMarkerContainer(
                hopper, "minecraft:hopper", OVERWORLD, areas));
        assertFalse(ChestTrackerInventorySource.isMarkerContainer(
                netherBarrel, "minecraft:barrel", OVERWORLD, areas));
    }

    @Test
    void markerInventoryOptInOnlyChangesDisplay() {
        var marker = stock(OVERWORLD, List.of(MARKER), 1);
        var markerBounds = List.of(at(MARKER));
        var storageBounds = List.of(at(STORAGE));

        assertFalse(ChestTrackerInventorySource.visible(marker, "minecraft:barrel", OVERWORLD,
                markerBounds, storageBounds, List.of(), Set.of(MARKER), Set.of()));
        assertTrue(ChestTrackerInventorySource.visible(marker, "minecraft:barrel", OVERWORLD,
                markerBounds, storageBounds, List.of(), Set.of(MARKER), Set.of(MARKER)));
        assertFalse(ChestTrackerInventorySource.visible(marker, "minecraft:barrel", OVERWORLD,
                markerBounds, storageBounds, markerBounds, Set.of(MARKER), Set.of()));

        var doubles = stock(OVERWORLD, List.of(MARKER, STORAGE), 2);
        assertTrue(ChestTrackerInventorySource.visible(doubles, "minecraft:chest", OVERWORLD,
                markerBounds, storageBounds, List.of(), Set.of(MARKER), Set.of()));
        assertFalse(ChestTrackerInventorySource.visible(doubles, "minecraft:chest", OVERWORLD,
                markerBounds, storageBounds, markerBounds, Set.of(MARKER), Set.of()));
    }

    private static List<AreaBounds> markerAreas() {
        var contents = new CategoryBindingDefinition(UUID.randomUUID(), at(MARKER), at(STORAGE),
                ContainerContentsCategoryRule.ID);
        var placed = new CategoryBindingDefinition(UUID.randomUUID(), at(PLACED_MARKER),
                at(new BlockPosition(11, 64, 20)), DirectBlockIdCategoryRule.ID);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Warehouse",
                List.of(contents, placed));
        return ChestTrackerInventorySource.markerAreas(List.of(zone));
    }

    private static AreaBounds at(BlockPosition point) {
        return new AreaBounds(point.x(), point.y(), point.z(), point.x(), point.y(), point.z());
    }

    private static WarehouseInventory.Container stock(String dimension,
            List<BlockPosition> positions, long count) {
        var entry = new WarehouseInventory.Entry(DRIPLEAF, DRIPLEAF, "Big Dripleaf", count, 0, 64);
        return new WarehouseInventory.Container(dimension + ":" + positions.getFirst(),
                "Container", dimension, positions, null, 27, 1, 0, 0, 0, List.of(entry));
    }
}
