package dev.stowling.v2.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarker;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.category.CategoryRuleRegistry;
import dev.stowling.v2.category.CategoryZoneClassifier;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CategoryZoneOptions;
import dev.stowling.v2.model.CategoryZoneTiler;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class CategoryScanEngineTest {
    public static final AreaBounds AREA = new AreaBounds(0, 64, 0, 9, 66, 5);
    public static final CategoryZoneOptions OPTIONS = new CategoryZoneOptions(
            "single", "marker", "bulk", "inside-box", "", false, "", 27);

    public static CategoryScanSnapshot world(int missing, int single) {
        var blocks = new ArrayList<CategoryScanBlock>();
        var markers = new ArrayList<CategoryMarker>();
        for (int i = 0; i < 10; i++) {
            if (i != missing) blocks.add(new CategoryScanBlock(new BlockPosition(i, 64, 1),
                    "minecraft:chest", i == single ? "single" : "chest", "east",
                    i == single ? null : new BlockPosition(i, 64, 2), 0, false));
            var position = new BlockPosition(i, i % 3 == 0 ? 64 : 65, 3);
            var source = switch (i % 3) {
                case 1 -> CategoryMarkerSource.ITEM_FRAME;
                case 2 -> CategoryMarkerSource.FLOWER_POT;
                default -> CategoryMarkerSource.PLACED_ITEM;
            };
            String id = "minecraft:" + (i % 3 == 0 ? "stone" : i % 3 == 1 ? "dirt" : "fern");
            markers.add(new CategoryMarker(position, id, source, -1));
            blocks.add(new CategoryScanBlock(position,
                    source == CategoryMarkerSource.FLOWER_POT ? "minecraft:potted_fern"
                            : source == CategoryMarkerSource.ITEM_FRAME ? "minecraft:item_frame" : id,
                    "quartz", "north", null, 0, false));
        }
        return new CategoryScanSnapshot(AREA, "minecraft:overworld", blocks, markers);
    }

    @Test
    void mixedDisplaysDoNotVoteOnLayoutAndEachSliceReadsItsOwnItem() {
        var world = world(-1, -1);
        var result = CategoryScanEngine.preview(world, OPTIONS);
        assertEquals("X", result.scan().axis());
        assertEquals(1, result.scan().step());
        assertEquals(1, result.scan().templates().size());
        assertEquals(10, result.bindings().size());
        assertTrue(result.scan().slices().stream().allMatch(slice -> slice.reason().isEmpty()));
        var scans = new ArrayList<CategoryZoneClassifier.Scan>();
        for (var binding : result.bindings()) {
            var target = new dev.stowling.automation.ContainerTarget(
                    new BlockPosition(binding.storageArea().minX(), 64, 1), "minecraft:chest",
                    null, binding.storagePolicy(),
                    new BlockPosition(binding.storageArea().minX(), 64, 2));
            scans.add(new CategoryZoneClassifier.Scan(binding, OPTIONS, null, world.markers().stream()
                    .filter(marker -> binding.markerArea().contains(marker.position())).toList(),
                    List.of(target)));
        }
        var categories = CategoryZoneClassifier.resolve(scans, CategoryRuleRegistry.standard(), null);
        assertTrue(categories.succeeded(), categories.message());
        assertEquals(4, categories.categories().get("minecraft:stone").size());
        assertEquals(3, categories.categories().get("minecraft:dirt").size());
        assertEquals(3, categories.categories().get("minecraft:fern").size());
    }

    @Test
    void majorityMotherCanComeAfterAnAbnormalFirstSlice() {
        var result = CategoryScanEngine.preview(world(-1, 0), OPTIONS);
        assertEquals(1, result.scan().templateIndex());
        assertEquals(9, result.bindings().size());
        assertFalse(result.scan().slices().getFirst().reason().isEmpty());
        assertEquals(1, result.scan().templates().getFirst().storageArea().minX());
        assertEquals(1, result.bindings().getFirst().storageArea().minX());
    }

    @Test
    void missingThirdSliceNeverRenumbersLaterCoordinatesAndReconstructionKeepsTheHole() {
        var result = CategoryScanEngine.preview(world(2, -1), OPTIONS);
        assertEquals(List.of(0, 1, 3, 4, 5, 6, 7, 8, 9), result.bindings().stream()
                .map(binding -> binding.storageArea().minX()).toList());
        var third = result.scan().slices().get(2);
        assertEquals(new BlockPosition(2, 64, 0), third.origin());
        assertTrue(third.reason().contains("双箱"));
        assertEquals(result.bindings(), CategoryScanEngine.savedBindings(result.scan()));
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Scanned", result.bindings(),
                null, OPTIONS, AREA, result.scan());
        assertEquals(result.scan().templates().getFirst(), CategoryZoneTiler.templateFor(zone, 2));
    }

    @Test
    void correctedCandidatePositionsAreReadAgainAndNoOldPreviewConclusionIsReused() {
        var world = world(-1, -1);
        var initial = CategoryScanEngine.preview(world, OPTIONS).scan();
        var template = initial.templates().getFirst();
        var changed = binding(template, new AreaBounds(0, 64, 4, 0, 65, 4), template.storageArea());
        var corrected = initial.withTemplates(List.of(changed));
        var empty = CategoryScanEngine.evaluate(world, corrected, OPTIONS);
        assertTrue(empty.bindings().isEmpty());
        var moved = world.markers().stream().map(marker -> new CategoryMarker(
                marker.position().offset(0, 0, 1), marker.itemId(), marker.source(), -1)).toList();
        var updated = new CategoryScanSnapshot(AREA, world.dimension(), world.blocks(), moved);
        assertEquals(10, CategoryScanEngine.evaluate(updated, corrected, OPTIONS).bindings().size());
    }

    @Test
    void ambiguityAcrossGroupsSkipsTheWholeSliceRatherThanKeepingTheFirstGroup() {
        var world = world(-1, -1);
        var initial = CategoryScanEngine.preview(world, OPTIONS).scan();
        var extraBlocks = new ArrayList<>(world.blocks());
        for (int i = 0; i < 10; i++) extraBlocks.add(new CategoryScanBlock(
                new BlockPosition(i, 66, 1), "minecraft:barrel", "barrel", "east", null, 0, false));
        var template = initial.templates().getFirst();
        var second = new CategoryBindingDefinition(UUID.randomUUID(), template.markerArea(),
                new AreaBounds(0, 66, 1, 0, 66, 1), "DIRECT_BLOCK_ID");
        var blocks = new ArrayList<>(initial.blocks());
        blocks.add(extraBlocks.get(extraBlocks.size() - 10));
        var edited = new CategoryScanDefinition(AREA, initial.dimension(), "X", 1, 0, 10,
                List.of(template, second), blocks, List.of());
        var result = CategoryScanEngine.evaluate(new CategoryScanSnapshot(AREA, initial.dimension(),
                extraBlocks, world.markers()), edited, OPTIONS);
        assertTrue(result.bindings().isEmpty());
        assertTrue(result.scan().slices().stream().allMatch(slice -> slice.reason().contains("多个组")));
    }

    @Test
    void geometryMismatchInOneGroupDoesNotLeaveHalfASlice() {
        var world = world(-1, -1);
        var initial = CategoryScanEngine.preview(world, OPTIONS).scan();
        var blocks = new ArrayList<>(world.blocks());
        var markers = new ArrayList<>(world.markers());
        for (int i = 0; i < 10; i++) {
            if (i != 2) blocks.add(new CategoryScanBlock(new BlockPosition(i, 66, 1),
                    "minecraft:barrel", "barrel", "east", null, 0, false));
            markers.add(new CategoryMarker(new BlockPosition(i, 66, 4), "minecraft:diamond"));
        }
        var first = initial.templates().getFirst();
        var second = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(0, 66, 4, 0, 66, 4),
                new AreaBounds(0, 66, 1, 0, 66, 1), "DIRECT_BLOCK_ID");
        var motherBlocks = new ArrayList<>(initial.blocks());
        motherBlocks.add(new CategoryScanBlock(new BlockPosition(0, 66, 1), "minecraft:barrel",
                "barrel", "east", null, 0, false));
        var edited = new CategoryScanDefinition(AREA, world.dimension(), "X", 1, 0, 10,
                List.of(first, second), motherBlocks, List.of());
        var result = CategoryScanEngine.evaluate(new CategoryScanSnapshot(AREA, world.dimension(),
                blocks, markers), edited, OPTIONS);
        assertEquals(18, result.bindings().size());
        assertTrue(result.bindings().stream().noneMatch(binding -> binding.storageArea().minX() == 2));
        assertEquals(result.bindings(), CategoryScanEngine.savedBindings(result.scan()));
    }

    @Test
    void reversedCornersAreEquivalentAndZMappingKeepsTheRightWorldAxis() {
        var original = world(2, -1);
        var area = new AreaBounds(5, 66, 9, 0, 64, 0);
        var blocks = original.blocks().stream().map(block -> new CategoryScanBlock(
                swap(block.position()), block.blockId(), block.kind(), "north",
                block.partner() == null ? null : swap(block.partner()), block.turns(), block.upper()))
                .toList();
        var markers = original.markers().stream().map(marker -> new CategoryMarker(
                swap(marker.position()), marker.itemId(), marker.source(), -1)).toList();
        var result = CategoryScanEngine.preview(new CategoryScanSnapshot(area, original.dimension(),
                blocks, markers), OPTIONS);
        assertEquals("Z", result.scan().axis());
        assertEquals(List.of(0, 1, 3, 4, 5, 6, 7, 8, 9), result.bindings().stream()
                .map(binding -> binding.storageArea().minZ()).toList());
    }

    @Test
    void changedSelectionDimensionAndNonIntegralOrOversizeInputsCannotBecomeSavedScans() {
        var world = world(-1, -1);
        var scan = CategoryScanEngine.preview(world, OPTIONS).scan();
        assertThrows(IllegalArgumentException.class, () -> CategoryScanEngine.evaluate(
                new CategoryScanSnapshot(AREA, "minecraft:the_nether", world.blocks(), world.markers()),
                scan, OPTIONS));
        assertThrows(IllegalArgumentException.class, () -> CategoryScanEngine.evaluate(
                new CategoryScanSnapshot(new AreaBounds(0, 64, 0, 10, 66, 5), world.dimension(),
                        world.blocks(), world.markers()), scan, OPTIONS));
        assertThrows(IllegalArgumentException.class, () -> CategoryScanDefinition.validateArea(
                new AreaBounds(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 1, 1)));
    }

    @Test
    void tiedLayoutsFailWithoutGuessingAndEmptyWorldsCannotGenerateAMother() {
        var base = world(-1, -1);
        var tied = base.blocks().stream().map(block -> block.container() && block.position().x() < 5
                ? new CategoryScanBlock(block.position(), block.blockId(), "single", block.facing(),
                        null, 0, false) : block).toList();
        assertThrows(IllegalArgumentException.class, () -> CategoryScanEngine.preview(
                new CategoryScanSnapshot(AREA, base.dimension(), tied, base.markers()), OPTIONS));
        assertThrows(IllegalArgumentException.class, () -> CategoryScanEngine.preview(
                new CategoryScanSnapshot(AREA, base.dimension(), List.of(), List.of()), OPTIONS));
    }

    private static CategoryBindingDefinition binding(CategoryBindingDefinition original,
            AreaBounds marker, AreaBounds storage) {
        return new CategoryBindingDefinition(original.id(), marker, storage, original.ruleId(),
                original.ignoredMarkerBlockIds(), List.of(), null, original.storagePolicy(),
                false, original.name(), false, null);
    }

    private static BlockPosition swap(BlockPosition p) {
        return new BlockPosition(p.z(), p.y(), p.x());
    }
}
