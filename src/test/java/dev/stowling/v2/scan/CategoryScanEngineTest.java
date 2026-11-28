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
    public static CategoryScanSnapshot displayedChestWorld() {
        var blocks = new ArrayList<CategoryScanBlock>();
        var markers = new ArrayList<CategoryMarker>();
        for (int z = -20; z < -10; z++) {
            var stored = new BlockPosition(216, -23, z);
            var partner = new BlockPosition(217, -23, z);
            blocks.add(new CategoryScanBlock(stored, "minecraft:chest", "chest", "north",
                    partner, 0, false));
            markers.add(new CategoryMarker(stored, "minecraft:chest"));
            markers.add(new CategoryMarker(partner, "minecraft:chest"));
            String id = z == -20 ? "minecraft:chest" : z == -19 ? "minecraft:barrel"
                    : "minecraft:stone";
            var p = new BlockPosition(216, -24, z);
            blocks.add(new CategoryScanBlock(p, id, z == -20 ? "single"
                    : z == -19 ? "barrel" : "quartz", "east", null, 0, false));
            markers.add(new CategoryMarker(p, id));
        }
        return new CategoryScanSnapshot(new AreaBounds(215, -25, -20, 219, -22, -11),
                "minecraft:overworld", blocks, markers);
    }

    public static CategoryScanSnapshot pressurePlateWorld() {
        var blocks = new ArrayList<CategoryScanBlock>();
        var markers = new ArrayList<CategoryMarker>();
        var anchors = new java.util.HashMap<BlockPosition, BlockPosition>();
        for (int z = -74; z < -64; z++) {
            var stored = new BlockPosition(223, -24, z);
            blocks.add(new CategoryScanBlock(stored, "minecraft:chest", "chest", "north",
                    stored.offset(1, 0, 0), 0, false));
            var base = new BlockPosition(222, -24, z);
            var position = base;
            var source = CategoryMarkerSource.PLACED_ITEM;
            String id = "minecraft:stripped_warped_hyphae";
            if (z == -74 || z == -72 || z == -71) {
                blocks.add(new CategoryScanBlock(base, "minecraft:quartz_block", "quartz",
                        "north", null, 0, false));
                position = z == -71 ? base.offset(-1, 0, 0) : base.offset(0, 1, 0);
                id = z == -74 ? "minecraft:oak_pressure_plate" : z == -72
                        ? "minecraft:fern" : "minecraft:diamond";
                source = z == -72 ? CategoryMarkerSource.FLOWER_POT : z == -71
                        ? CategoryMarkerSource.ITEM_FRAME : CategoryMarkerSource.PLACED_ITEM;
                anchors.put(position, base);
            }
            blocks.add(new CategoryScanBlock(position,
                    source == CategoryMarkerSource.FLOWER_POT ? "minecraft:potted_fern"
                            : source == CategoryMarkerSource.ITEM_FRAME ? "minecraft:item_frame" : id,
                    "quartz", "north", null, 0, false));
            markers.add(new CategoryMarker(position, id, source, -1));
        }
        return new CategoryScanSnapshot(new AreaBounds(220, -25, -74, 225, -22, -65),
                "minecraft:overworld", blocks, markers, anchors);
    }

    @Test
    void emptyChestAndBarrelDisplaysAreItemsRatherThanExtraStorageOrInventoryReads() {
        var world = displayedChestWorld();
        var result = CategoryScanEngine.preview(world, OPTIONS);
        assertEquals("Z", result.scan().axis());
        assertEquals(1, result.scan().templates().size());
        assertEquals(10, result.bindings().size());
        assertEquals(1, CategoryScanEngine.storageBlocks(result.scan()).size());
        var resolved = categories(world, result);
        assertTrue(resolved.succeeded(), resolved.message());
        assertEquals(new BlockPosition(216, -23, -20),
                resolved.categories().get("minecraft:chest").getFirst().position());
        assertEquals(1, resolved.categories().get("minecraft:chest").size());
        assertEquals(new BlockPosition(216, -23, -19),
                resolved.categories().get("minecraft:barrel").getFirst().position());
        assertEquals(result.bindings(), CategoryScanEngine.savedBindings(result.scan()));
    }

    @Test
    void differentDisplayFormsShareOneSlotWithoutCopyingTheMotherItemOrSupport() {
        var world = pressurePlateWorld();
        var result = CategoryScanEngine.preview(world, OPTIONS);
        assertEquals(10, result.bindings().size());
        var template = result.scan().templates().getFirst();
        assertEquals(List.of(new BlockPosition(222, -24, -74)),
                result.scan().markerAnchors().get(template.id()));
        var resolved = categories(world, result);
        assertTrue(resolved.succeeded(), resolved.message());
        assertEquals(new BlockPosition(223, -24, -74),
                resolved.categories().get("minecraft:oak_pressure_plate").getFirst().position());
        assertEquals(7, resolved.categories().get("minecraft:stripped_warped_hyphae").size());
        assertFalse(resolved.categories().containsKey("minecraft:quartz_block"));
        assertTrue(resolved.categories().containsKey("minecraft:fern"));
        assertTrue(resolved.categories().containsKey("minecraft:diamond"));
    }

    @Test
    void unrelatedExtraContainerAndMissingStorageRemainWholeSliceFailures() {
        var world = displayedChestWorld();
        var initial = CategoryScanEngine.preview(world, OPTIONS);
        var blocks = new ArrayList<>(world.blocks());
        blocks.add(new CategoryScanBlock(new BlockPosition(219, -22, -18), "minecraft:chest",
                "single", "east", null, 0, false));
        blocks.removeIf(block -> block.position().equals(new BlockPosition(216, -23, -17)));
        var result = CategoryScanEngine.evaluate(new CategoryScanSnapshot(world.area(),
                world.dimension(), blocks, world.markers()), initial.scan(), OPTIONS);
        assertEquals(8, result.bindings().size());
        assertTrue(result.scan().slices().get(2).reason().contains("母版之外"));
        assertTrue(result.scan().slices().get(3).reason().contains("双箱"));
        assertEquals(result.bindings(), CategoryScanEngine.savedBindings(result.scan()));
    }

    @Test
    void puttingADisplayContainerInTheMotherDoesNotTurnItIntoStorage() {
        var world = displayedChestWorld();
        var initial = CategoryScanEngine.preview(world, OPTIONS);
        var template = initial.scan().templates().getFirst();
        var display = new CategoryScanBlock(new BlockPosition(216, -24,
                initial.scan().mother().minZ()), "minecraft:chest", "single", "east", null, 0, false);
        var blocks = new ArrayList<>(initial.scan().blocks());
        blocks.removeIf(block -> block.bounds().contains(display.position()));
        blocks.add(display);
        var mother = new CategoryScanDefinition(initial.scan().area(), initial.scan().dimension(),
                initial.scan().axis(), initial.scan().step(), initial.scan().templateIndex(),
                initial.scan().sliceCount(), List.of(template), blocks, initial.scan().slices(),
                null, initial.scan().markerAnchors());
        assertEquals(initial.bindings(), CategoryScanEngine.savedBindings(mother));
        assertEquals(1, CategoryScanEngine.storageBlocks(mother).size());
        var contents = new CategoryZoneOptions("single", "container-item", "bulk", "inside-box",
                "", false, "", 27);
        assertThrows(IllegalArgumentException.class, () ->
                CategoryScanEngine.evaluate(world, mother, contents));
    }

    @Test
    void realPressurePlateCoordinateExclusionsAndRedrawingStillRecomputeResults() {
        var world = pressurePlateWorld();
        var initial = CategoryScanEngine.preview(world, OPTIONS);
        var options = new CategoryZoneOptions("single", "marker", "bulk", "inside-box", "",
                false, "", 27, true, List.of(new dev.stowling.v2.model.CategoryPositionDraft(
                        222, -23, -74)), false, false, List.of());
        var result = CategoryScanEngine.evaluate(world, initial.scan(), options);
        assertEquals(9, result.bindings().size());
        assertFalse(result.scan().slices().getFirst().reason().isEmpty());
        var prior = initial.scan().templates().getFirst();
        var edited = initial.scan().withTemplates(List.of(binding(prior,
                new AreaBounds(220, -24, -74, 220, -23, -74), prior.storageArea())));
        assertTrue(edited.markerAnchors().isEmpty());
        assertTrue(CategoryScanEngine.evaluate(world, edited, OPTIONS).bindings().isEmpty());
    }

    private static dev.stowling.v2.category.CategoryRuleResult categories(CategoryScanSnapshot world,
            CategoryScanResult result) {
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Scan", result.bindings(),
                null, OPTIONS, world.area(), result.scan());
        var scans = new ArrayList<CategoryZoneClassifier.Scan>();
        for (int i = 0; i < zone.bindings().size(); i++) {
            var binding = zone.bindings().get(i);
            var template = CategoryZoneTiler.templateFor(zone, i);
            int delta = result.scan().axis().equals("X")
                    ? binding.markerArea().minX() - template.markerArea().minX()
                    : binding.markerArea().minZ() - template.markerArea().minZ();
            var physical = world.blocks().stream().filter(CategoryScanBlock::container)
                    .filter(block -> binding.storageArea().contains(block.bounds()))
                    .findFirst().orElseThrow();
            var target = new dev.stowling.automation.ContainerTarget(physical.position(),
                    physical.blockId(), null, binding.storagePolicy(), physical.partner());
            scans.add(new CategoryZoneClassifier.Scan(binding, OPTIONS, null,
                    world.markers().stream().filter(marker -> CategoryScanEngine.matchesMarker(
                            result.scan(), template, delta, marker, world.anchor(marker))).toList(),
                    List.of(target)));
        }
        return CategoryZoneClassifier.resolve(scans, CategoryRuleRegistry.standard(), null);
    }

    @Test
    void reportedSameHeightMarkerDoesNotRequireAnExtraUpperRow() {
        var area = new AreaBounds(218, -18, -15, 221, -18, -6);
        var blocks = new ArrayList<CategoryScanBlock>();
        var markers = new ArrayList<CategoryMarker>();
        for (int z = -15; z <= -6; z++) {
            blocks.add(new CategoryScanBlock(new BlockPosition(219, -18, z),
                    "minecraft:chest", "chest", "north", new BlockPosition(220, -18, z),
                    0, false));
            var display = new BlockPosition(218, -18, z);
            blocks.add(new CategoryScanBlock(display, "minecraft:deepslate_gold_ore", "quartz",
                    "north", null, 0, false));
            markers.add(new CategoryMarker(display, "minecraft:deepslate_gold_ore"));
        }
        var result = CategoryScanEngine.preview(new CategoryScanSnapshot(area,
                "minecraft:overworld", blocks, markers), OPTIONS);
        assertEquals("Z", result.scan().axis());
        assertEquals(10, result.bindings().size());
        assertTrue(result.scan().templates().getFirst().markerArea()
                .contains(new BlockPosition(218, -18, -15)));
        assertEquals(-18, result.scan().templates().getFirst().markerArea().maxY());
    }

    @Test
    void displaysBelowEitherChestHalfWorkInBothHorizontalMappings() {
        for (boolean rotate : List.of(false, true)) {
            for (int half : List.of(1, 2)) {
                var blocks = new ArrayList<CategoryScanBlock>();
                var markers = new ArrayList<CategoryMarker>();
                for (int z = 0; z < 10; z++) {
                    var start = new BlockPosition(1, 65, z);
                    var end = new BlockPosition(2, 65, z);
                    var display = new BlockPosition(half, 64, z);
                    if (rotate) {
                        start = swap(start);
                        end = swap(end);
                        display = swap(display);
                    }
                    blocks.add(new CategoryScanBlock(start, "minecraft:chest", "chest",
                            rotate ? "east" : "north", end, 0, false));
                    blocks.add(new CategoryScanBlock(display, "minecraft:deepslate_gold_ore",
                            "quartz", "north", null, 0, false));
                    markers.add(new CategoryMarker(display, "minecraft:deepslate_gold_ore"));
                }
                var area = rotate ? new AreaBounds(0, 64, 0, 9, 66, 3)
                        : new AreaBounds(0, 64, 0, 3, 66, 9);
                var result = CategoryScanEngine.preview(new CategoryScanSnapshot(area,
                        "minecraft:overworld", blocks, markers), OPTIONS);
                assertEquals(10, result.bindings().size());
                assertEquals(64, result.scan().templates().getFirst().markerArea().maxY());
                assertEquals(result.bindings(), CategoryScanEngine.savedBindings(result.scan()));
            }
        }
    }

    @Test
    void correctedSteppedRingProducesAnEditableMotherWithoutExpandingTheSelection() {
        int[][] chests = {{5, 9}, {8, 9}, {11, 9}, {14, 9}, {2, 8}, {17, 8},
                {1, 6}, {18, 6}, {1, 4}, {18, 4}, {3, 2}, {6, 2}, {13, 2}, {16, 2}};
        int[][] displays = {{4, 9}, {7, 9}, {13, 9}, {16, 9}, {3, 7}, {17, 7},
                {2, 5}, {18, 5}, {2, 3}, {18, 3}, {5, 2}, {8, 3}, {12, 3}, {15, 2}};
        var blocks = new ArrayList<CategoryScanBlock>();
        var markers = new ArrayList<CategoryMarker>();
        for (int z = 0; z < 10; z++) {
            for (var chest : chests) {
                var start = new BlockPosition(chest[0], chest[1], z);
                var end = start.offset(1, 0, 0);
                blocks.add(new CategoryScanBlock(start, "minecraft:chest", "chest", "north",
                        end, 0, false));
                markers.add(new CategoryMarker(start, "minecraft:chest"));
                markers.add(new CategoryMarker(end, "minecraft:chest"));
            }
            for (var display : displays) {
                var p = new BlockPosition(display[0], display[1], z);
                blocks.add(new CategoryScanBlock(p, "minecraft:deepslate_gold_ore", "quartz",
                        "north", null, 0, false));
                markers.add(new CategoryMarker(p, "minecraft:deepslate_gold_ore"));
            }
        }
        var area = new AreaBounds(0, 1, 0, 20, 9, 9);
        var result = CategoryScanEngine.preview(new CategoryScanSnapshot(area,
                "minecraft:overworld", blocks, markers), OPTIONS);
        assertEquals("Z", result.scan().axis());
        assertEquals(chests.length, result.scan().templates().size());
        assertEquals(10 * chests.length, result.bindings().size(), result.scan().slices().toString());
        for (var template : result.scan().templates()) {
            assertTrue(result.scan().mother().contains(template.markerArea()));
            for (var block : result.scan().blocks()) {
                if (!block.container()) continue;
                assertFalse(template.markerArea().contains(block.position()));
                assertFalse(template.markerArea().contains(block.partner()));
            }
        }
    }

    @Test
    void steppedRingCanUseDifferentPhysicalAlternativesForTheSameGroupAcrossSlices() {
        int[][] chests = {{5, 9}, {8, 9}, {11, 9}, {14, 9}, {2, 8}, {17, 8},
                {1, 6}, {18, 6}, {1, 4}, {18, 4}, {3, 2}, {6, 2}, {13, 2}, {16, 2}};
        int[][] displays = {{4, 9}, {7, 9}, {13, 9}, {16, 9}, {3, 7}, {17, 7},
                {2, 5}, {18, 5}, {2, 3}, {18, 3}, {5, 2}, {8, 3}, {12, 3}, {15, 2}};
        var blocks = new ArrayList<CategoryScanBlock>();
        var markers = new ArrayList<CategoryMarker>();
        var anchors = new java.util.HashMap<BlockPosition, BlockPosition>();
        for (int z = 0; z < 10; z++) {
            var bodies = new java.util.HashSet<BlockPosition>();
            for (var chest : chests) {
                var p = new BlockPosition(chest[0], chest[1], z);
                bodies.add(p);
                bodies.add(p.offset(1, 0, 0));
                blocks.add(new CategoryScanBlock(p, "minecraft:chest", "chest", "north",
                        p.offset(1, 0, 0), 0, false));
            }
            for (int group = 0; group < displays.length; group++) {
                var base = new BlockPosition(displays[group][0], displays[group][1], z);
                var physical = base;
                String id = "minecraft:display_" + group;
                if (z % 3 == 1) {
                    if (bodies.contains(base.offset(0, 1, 0))) {
                        base = base.offset(chests[group][0] > 10 ? -1 : 1, 0, 0);
                    }
                    physical = base.offset(0, 1, 0);
                    blocks.add(new CategoryScanBlock(base, "minecraft:quartz_block", "quartz",
                            "north", null, 0, false));
                    anchors.put(physical, base);
                }
                assertFalse(bodies.contains(physical));
                blocks.add(new CategoryScanBlock(physical, id, "quartz", "north", null, 0, false));
                markers.add(new CategoryMarker(physical, id));
            }
        }
        var world = new CategoryScanSnapshot(new AreaBounds(0, 1, 0, 20, 10, 9),
                "minecraft:overworld", blocks, markers, anchors);
        var result = CategoryScanEngine.preview(world, OPTIONS);
        assertEquals(140, result.bindings().size(), result.scan().slices().toString());
        assertTrue(result.scan().markerAnchors().values().stream()
                .anyMatch(values -> values.size() > 1));
        var resolved = categories(world, result);
        assertTrue(resolved.succeeded(), resolved.message());
        for (int group = 0; group < chests.length; group++) {
            var targets = resolved.categories().get("minecraft:display_" + group);
            assertEquals(10, targets.size());
            int index = group;
            assertTrue(targets.stream().allMatch(target -> target.position().x() == chests[index][0]
                    && target.position().y() == chests[index][1]));
        }
    }

    public static final AreaBounds AREA = new AreaBounds(0, 64, 0, 9, 66, 5);
    public static final CategoryZoneOptions OPTIONS = new CategoryZoneOptions(
            "single", "marker", "bulk", "inside-box", "", false, "", 27);

    @Test
    void savedMaterialAndPositionExclusionsAlsoGuideCandidateScoring() {
        var base = world(-1, -1);
        var markers = new ArrayList<CategoryMarker>();
        var excluded = new ArrayList<dev.stowling.v2.model.CategoryPositionDraft>();
        for (int x = 0; x < 10; x++) {
            var left = new BlockPosition(x, 64, 0);
            markers.add(new CategoryMarker(left, "minecraft:grass_block"));
            excluded.add(dev.stowling.v2.model.CategoryPositionDraft.of(left));
            markers.add(new CategoryMarker(new BlockPosition(x, 64, 3),
                    "minecraft:deepslate_gold_ore"));
        }
        var snapshot = new CategoryScanSnapshot(AREA, base.dimension(), base.blocks(), markers);
        var disabled = new CategoryZoneOptions("single", "marker", "bulk", "inside-box", "",
                false, "minecraft:grass_block", 27, false, excluded, false, false, List.of());
        var material = new CategoryZoneOptions("single", "marker", "bulk", "inside-box", "",
                true, "minecraft:grass_block", 27);
        var positions = new CategoryZoneOptions("single", "marker", "bulk", "inside-box", "",
                false, "", 27, true, excluded, false, false, List.of());
        assertEquals(0, CategoryScanEngine.preview(snapshot, disabled).scan().templates()
                .getFirst().markerArea().minZ());
        for (var options : List.of(material, positions)) {
            var result = CategoryScanEngine.preview(snapshot, options);
            assertEquals(3, result.scan().templates().getFirst().markerArea().minZ());
            assertEquals(10, result.bindings().size());
        }
    }

    @Test
    void conflictingUpperAndLowerItemsStillSkipTheSliceInsteadOfSelectingOnlyOneCell() {
        var base = world(-1, -1);
        var markers = new ArrayList<CategoryMarker>();
        for (int x = 0; x < 10; x++) {
            markers.add(new CategoryMarker(new BlockPosition(x, 64, 3), "minecraft:stone"));
            markers.add(new CategoryMarker(new BlockPosition(x, 65, 3), "minecraft:dirt"));
        }
        var result = CategoryScanEngine.preview(new CategoryScanSnapshot(AREA, base.dimension(),
                base.blocks(), markers), OPTIONS);
        assertTrue(result.bindings().isEmpty());
        assertEquals(64, result.scan().templates().getFirst().markerArea().minY());
        assertEquals(65, result.scan().templates().getFirst().markerArea().maxY());
        assertTrue(result.scan().slices().stream()
                .allMatch(slice -> slice.reason().contains("多个物品")));
    }

    public static CategoryScanSnapshot world(int missing, int single) {
        var blocks = new ArrayList<CategoryScanBlock>();
        var markers = new ArrayList<CategoryMarker>();
        var anchors = new java.util.HashMap<BlockPosition, BlockPosition>();
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
            if (source != CategoryMarkerSource.PLACED_ITEM) {
                anchors.put(position, position.offset(0, -1, 0));
            }
            blocks.add(new CategoryScanBlock(position,
                    source == CategoryMarkerSource.FLOWER_POT ? "minecraft:potted_fern"
                            : source == CategoryMarkerSource.ITEM_FRAME ? "minecraft:item_frame" : id,
                    "quartz", "north", null, 0, false));
        }
        return new CategoryScanSnapshot(AREA, "minecraft:overworld", blocks, markers, anchors);
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
        var anchors = new java.util.HashMap<BlockPosition, BlockPosition>();
        original.markerAnchors().forEach((marker, anchor) -> anchors.put(swap(marker), swap(anchor)));
        var result = CategoryScanEngine.preview(new CategoryScanSnapshot(area, original.dimension(),
                blocks, markers, anchors), OPTIONS);
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
