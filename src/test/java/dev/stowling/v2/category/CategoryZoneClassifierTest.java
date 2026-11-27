package dev.stowling.v2.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryItemBinding;
import dev.stowling.v2.model.CategoryPositionDraft;
import dev.stowling.v2.model.CategoryZoneOptions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryZoneClassifierTest {
    @Test
    void excludesOnlyTheSupportPositionAndKeepsGrassElsewhereAndContainerBindings() {
        var support = new BlockPosition(0, 64, 0);
        var rule = rule("container", "", new BlockPosition(0, 60, 0), "grass_block");
        var options = options(List.of(support), false, List.of(rule));
        var flowers = scan(0, options, null, marker(0, "grass_block"),
                new CategoryMarker(new BlockPosition(0, 65, 0), "minecraft:pink_petals"));
        var grass = scan(1, options, null, marker(1, "grass_block"));
        var result = resolve(flowers, grass);
        assertTrue(result.succeeded(), result.message());
        assertEquals(List.of(flowers.containers().getFirst()),
                result.categories().get("minecraft:pink_petals"));
        assertEquals(List.of(grass.containers().getFirst(), flowers.containers().getFirst()),
                result.categories().get("minecraft:grass_block"));
    }

    @Test
    void infersCoralAcrossBindingsWithoutRemovingTheIndependentSlabOrDependingOnOrder() {
        var options = options(List.of(), true, List.of());
        var conflict = scan(0, options, null, marker(0, "brain_coral_fan"),
                marker(0, "smooth_quartz_slab"));
        var reference = scan(1, options, null, marker(1, "smooth_quartz_slab"));
        for (var scans : List.of(List.of(conflict, reference), List.of(reference, conflict))) {
            var result = CategoryZoneClassifier.resolve(scans, CategoryRuleRegistry.standard(), null);
            assertTrue(result.succeeded(), result.message());
            assertEquals(conflict.containers(),
                    result.categories().get("minecraft:brain_coral_fan"));
            assertEquals(reference.containers(),
                    result.categories().get("minecraft:smooth_quartz_slab"));
        }
    }

    @Test
    void unresolvedZeroMultipleAndChainedInferencesStillFail() {
        var options = options(List.of(), true, List.of());
        var reference = scan(0, options, null, marker(0, "stone"));
        var two = scan(1, options, null, marker(1, "stone"), marker(1, "dirt"));
        var chain = scan(2, options, null, marker(2, "dirt"), marker(2, "glass"));
        assertFalse(resolve(two).succeeded());
        assertFalse(resolve(reference, two, chain).succeeded());
        assertFalse(resolve(reference, scan(3, options, null, marker(3, "stone"),
                marker(3, "dirt"), marker(3, "glass"))).succeeded());
        assertFalse(resolve(reference, scan(4, options, null, marker(4, "dirt")), two).succeeded());
        // Explicit source selection must not silently accept an unresolved repeat conflict.
        assertFalse(CategoryZoneClassifier.resolve(List.of(two), CategoryRuleRegistry.standard(),
                java.util.Set.of(CategoryMarkerSource.PLACED_ITEM)).succeeded());
    }

    @Test
    void excludedReferencesAndItemFramesCannotSupplyPlacedBlockReferences() {
        var options = options(List.of(new BlockPosition(0, 64, 0)), true, List.of());
        var reference = scan(0, options, null, marker(0, "stone"));
        var conflict = scan(1, options, null, marker(1, "stone"), marker(1, "dirt"));
        assertFalse(resolve(reference, conflict).succeeded());
        var frame = new CategoryMarker(new BlockPosition(0, 64, 0), "minecraft:stone",
                CategoryMarkerSource.ITEM_FRAME, -1);
        assertFalse(resolve(scan(0, options(List.of(), true, List.of()), null, frame),
                conflict).succeeded());
        var ignored = new CategoryBindingDefinition(UUID.randomUUID(),
                reference.binding().markerArea(), reference.binding().storageArea(),
                DirectBlockIdCategoryRule.ID, List.of("minecraft:stone"));
        var ignoredReference = new CategoryZoneClassifier.Scan(ignored,
                options(List.of(), true, List.of()), null,
                List.of(marker(0, "stone")), reference.containers());
        assertFalse(resolve(ignoredReference, conflict).succeeded());
    }

    @Test
    void allThreeTargetsAppendRealIdsAndDoNotChainThroughBoundItems() {
        for (String type : List.of("marker-id", "marker-position", "container")) {
            var rule = rule(type, "minecraft:sculk", new BlockPosition(0,
                    "container".equals(type) ? 60 : 64, 0), "sculk_sensor");
            var options = options(List.of(), false, List.of(rule));
            var first = scan(0, options, null, marker(0, "sculk"));
            var second = scan(1, options, null, marker(1, "sculk"));
            var result = resolve(first, second);
            assertTrue(result.succeeded(), result.message());
            assertEquals(2, result.categories().get("minecraft:sculk").size());
            assertEquals("marker-id".equals(type) ? 2 : 1,
                    result.categories().get("minecraft:sculk_sensor").size());
        }
        var chained = options(List.of(), false, List.of(
                rule("marker-id", "minecraft:sculk", null, "sculk_sensor"),
                rule("marker-id", "minecraft:sculk_sensor", null, "sculk_catalyst")));
        assertFalse(resolve(scan(0, chained, null, marker(0, "sculk"))).succeeded());
    }

    @Test
    void missingAndExcludedMarkerTargetsFailButAnUnmarkedContainerRemainsValid() {
        var point = new BlockPosition(0, 64, 0);
        var excluded = options(List.of(point), false,
                List.of(rule("marker-position", "", point, "sculk")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                excluded::validateActiveRules);
        var missing = options(List.of(), false, List.of(rule("container", "",
                new BlockPosition(99, 60, 0), "sculk")));
        assertFalse(resolve(scan(0, missing, null, marker(0, "stone"))).succeeded());
        var valid = options(List.of(new BlockPosition(0, 60, 0)), false, List.of(rule("container", "",
                new BlockPosition(0, 60, 0), "sculk")));
        assertTrue(resolve(scan(0, valid, null, marker(0, "stone"))).succeeded());
    }

    @Test
    void independentScopesAndGlobalBindingsCoexistWithoutAGroupPriority() {
        UUID independent = UUID.randomUUID();
        var global = options(List.of(), false,
                List.of(rule("marker-id", "minecraft:stone", null, "dirt")));
        var local = options(List.of(), false,
                List.of(rule("marker-id", "minecraft:stone", null, "glass")));
        var inherited = scan(0, global, null, marker(0, "stone"));
        var own = scan(1, local, independent, marker(1, "stone"));
        var ownSlice = scan(2, local, independent, marker(2, "stone"));
        var result = resolve(inherited, own, ownSlice);
        assertTrue(result.succeeded(), result.message());
        assertEquals(inherited.containers(), result.categories().get("minecraft:dirt"));
        assertEquals(2, result.categories().get("minecraft:glass").size());
        assertEquals(3, result.categories().get("minecraft:stone").size());
    }

    private static CategoryRuleResult resolve(CategoryZoneClassifier.Scan... scans) {
        return CategoryZoneClassifier.resolve(List.of(scans), CategoryRuleRegistry.standard(), null);
    }

    private static CategoryMarker marker(int x, String id) {
        return new CategoryMarker(new BlockPosition(x, 64, 0), "minecraft:" + id);
    }

    private static CategoryItemBinding rule(String type, String id, BlockPosition point, String item) {
        return new CategoryItemBinding("Rule", type, id, point, "minecraft:" + item, false);
    }

    private static CategoryZoneOptions options(
            List<BlockPosition> excluded, boolean repeats, List<CategoryItemBinding> rules
    ) {
        return new CategoryZoneOptions("single", "marker", "bulk", "inside-box", "", false, "", 27, !excluded.isEmpty(),
                excluded.stream().map(CategoryPositionDraft::of).toList(),
                repeats, !rules.isEmpty(), rules);
    }

    private static CategoryZoneClassifier.Scan scan(
            int x, CategoryZoneOptions options, UUID scope, CategoryMarker... markers
    ) {
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(x, 64, 0, x, 65, 0), new AreaBounds(x, 60, 0, x, 60, 0),
                DirectBlockIdCategoryRule.ID);
        return new CategoryZoneClassifier.Scan(binding, options, scope, List.of(markers),
                List.of(new ContainerTarget(new BlockPosition(x, 60, 0), "minecraft:chest")));
    }
}
