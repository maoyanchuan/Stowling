package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryStandingSubregionsTest {
    private static final AreaBounds MARKERS = new AreaBounds(0, 66, 0, 0, 66, 9);
    private static final AreaBounds STORAGE = new AreaBounds(1, 64, 0, 1, 66, 9);
    private static final AreaBounds BASELINE = new AreaBounds(2, 65, 0, 2, 65, 0);

    @Test
    void disabledExperimentKeepsOriginalBindingsAndTheLegacyStance() {
        var zone = zone(plan(true), false);
        var result = CategoryStandingSubregions.segments(zone, false);
        assertEquals(1, result.size());
        assertSame(zone.bindings().getFirst(), result.getFirst().binding());
        assertEquals(BASELINE, result.getFirst().binding().standingArea());
    }

    @Test
    void enabledExperimentPairsEachStoragePartWithItsFiveBlockSeparatedStance() {
        var zone = zone(plan(true), false);
        var result = CategoryStandingSubregions.segments(zone, true);
        assertEquals(2, result.size());
        assertEquals(new AreaBounds(2, 65, 2, 2, 65, 2),
                result.getFirst().binding().standingArea());
        assertEquals(new AreaBounds(2, 65, 7, 2, 65, 7),
                result.getLast().binding().standingArea());
        assertEquals(4, result.getFirst().binding().storageArea().maxZ());
        assertEquals(5, result.getLast().binding().storageArea().minZ());
        assertEquals(result, CategoryStandingSubregions.segments(zone, true));
        assertEquals(1, zone.bindings().size());
        assertEquals(BASELINE, zone.bindings().getFirst().standingArea());
    }

    @Test
    void independentBusinessSettingsDoNotControlTheSpatialPlan() {
        var inherited = CategoryStandingSubregions.segments(zone(plan(true), false), true);
        var independent = CategoryStandingSubregions.segments(zone(plan(true), true), true);
        assertEquals(inherited.stream().map(value -> value.binding().standingArea()).toList(),
                independent.stream().map(value -> value.binding().standingArea()).toList());
        assertEquals(StorageMode.BOX_ONLY, independent.getFirst().binding().storagePolicy().mode());
    }

    @Test
    void sharedStandingServesMultipleGroupsWithoutCombiningTheirStoragePolicies() {
        var point = new CategoryStandingPoint(new CategoryPositionDraft(2, 64, 7),
                null, null, true, false, "shared-standing");
        var plan = new CategoryStandingPlan(true, List.of(point));
        var first = new CategoryBindingDefinition(UUID.randomUUID(), MARKERS, STORAGE,
                "MARKER_CONTAINER_CONTENTS", List.of(), List.of(), BASELINE,
                StoragePolicy.DEFAULT, true, "前区", false, null, plan);
        var boxes = new CategoryZoneOptions("selection", "container-item", "box", "inside-box",
                "", false, "");
        var second = new CategoryBindingDefinition(UUID.randomUUID(), MARKERS.shift(0, 0, 20),
                STORAGE.shift(0, 0, 20), "MARKER_CONTAINER_CONTENTS", List.of(), List.of(),
                BASELINE, boxes.storagePolicy(null), true, "后区", true, boxes, plan);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "仓库", List.of(first, second));
        var result = CategoryStandingSubregions.segments(zone, true);
        assertEquals(2, result.size());
        assertEquals(result.getFirst().binding().standingArea(),
                result.getLast().binding().standingArea());
        assertEquals(StorageMode.NORMAL, result.getFirst().binding().storagePolicy().mode());
        assertEquals(StorageMode.BOX_ONLY, result.getLast().binding().storagePolicy().mode());
        assertEquals(BASELINE, CategoryStandingSubregions.segments(zone, false)
                .getFirst().binding().standingArea());
        assertEquals("shared-standing", point.shift(5, 0).standingId());
        assertEquals(new CategoryPositionDraft(7, 64, 7), point.shift(5, 0).position());
    }

    @Test
    void disablingStandingRemovesItsConstraintOnlyWithinTheExperiment() {
        var zone = zone(plan(false), false);
        assertNull(CategoryStandingSubregions.segments(zone, true)
                .getFirst().binding().standingArea());
        assertEquals(BASELINE, CategoryStandingSubregions.segments(zone, false)
                .getFirst().binding().standingArea());
        assertEquals(2, zone.bindings().getFirst().standingPlan().points().size());
    }

    @Test
    void sharedMarkerAreasAreAllowedButOverlappingStorageAndOutOfBoundsAreRejected() {
        plan(true).validate(MARKERS, STORAGE);
        var point = plan(true).points().getFirst();
        assertThrows(IllegalArgumentException.class, () ->
                new CategoryStandingPlan(true, List.of(point, point)).validate(MARKERS, STORAGE));
        var outside = new CategoryStandingPoint(point.position(), MARKERS,
                STORAGE.shift(10, 0, 0), false);
        assertThrows(IllegalArgumentException.class, () ->
                new CategoryStandingPlan(true, List.of(outside)).validate(MARKERS, STORAGE));
        var incomplete = new CategoryStandingPoint(new CategoryPositionDraft(null, 64, 0),
                null, null, true);
        new CategoryStandingPlan(false, List.of(incomplete)).validate(MARKERS, STORAGE);
        assertThrows(IllegalArgumentException.class, () ->
                new CategoryStandingPlan(true, List.of(incomplete)).validate(MARKERS, STORAGE));
    }

    @Test
    void sliceExpansionMovesAllPositionsAndSubregionsTogether() {
        var binding = zone(plan(true), false).bindings().getFirst();
        var expansion = new CategoryZoneExpansionDefinition(
                new AreaBounds(0, 64, 0, 5, 66, 9), CategoryZoneExpansionAxis.X_POSITIVE, 2, 1);
        var slices = CategoryZoneTiler.expand(expansion, List.of(binding));
        assertEquals(3, slices.size());
        var moved = slices.getLast().standingPlan().points().getFirst();
        assertEquals(new CategoryPositionDraft(6, 64, 2), moved.position());
        assertEquals(MARKERS.shift(4, 0, 0), moved.markerArea());
        assertEquals(5, moved.storageArea().firstX());
    }

    @Test
    void ordinaryTasksCannotActivateTheExperimentThroughSharedWorkspaceState() {
        var workspace = new TaskRunWorkspace();
        workspace.configureStandingSubregions(true);
        assertFalse(workspace.standingSubregionsEnabled());
        workspace.configureClassificationTask(true);
        workspace.configureStandingSubregions(true);
        assertTrue(workspace.standingSubregionsEnabled());
        workspace.configureClassificationTask(false);
        assertFalse(workspace.standingSubregionsEnabled());
    }

    @Test
    void persistedTaskFlagDefaultsOffAndOnlyIndependentClassificationAcceptsIt() {
        var gson = new com.google.gson.Gson();
        var definition = new AutoClassifyDefinition(UUID.randomUUID(), "分类", false, true, true,
                "coordinate:" + UUID.randomUUID(), null, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                TaskPriority.NORMAL);
        assertFalse(definition.standingSubregionsEnabled());
        var json = gson.toJsonTree(definition).getAsJsonObject();
        json.addProperty("standingSubregionsEnabled", true);
        assertFalse(gson.fromJson(json, AutoClassifyDefinition.class).standingSubregionsEnabled());
        json.addProperty("kind", "CLASSIFICATION");
        assertTrue(gson.fromJson(json, AutoClassifyDefinition.class).standingSubregionsEnabled());
        json.remove("standingSubregionsEnabled");
        assertFalse(gson.fromJson(json, AutoClassifyDefinition.class).standingSubregionsEnabled());
    }

    private static CategoryStandingPlan plan(boolean enabled) {
        return new CategoryStandingPlan(enabled, List.of(
                new CategoryStandingPoint(new CategoryPositionDraft(2, 64, 2), MARKERS,
                        new AreaBounds(1, 64, 0, 1, 66, 4), true),
                new CategoryStandingPoint(new CategoryPositionDraft(2, 64, 7), MARKERS,
                        new AreaBounds(1, 64, 5, 1, 66, 9), false)));
    }

    private static CategoryZoneDefinition zone(CategoryStandingPlan plan, boolean independent) {
        var options = new CategoryZoneOptions("selection", "container-item", "box", "inside-box",
                "", false, "");
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), MARKERS, STORAGE,
                "MARKER_CONTAINER_CONTENTS", List.of(), List.of(), BASELINE,
                independent ? options.storagePolicy(null) : StoragePolicy.DEFAULT,
                true, "大单片", independent, options, plan);
        return new CategoryZoneDefinition(UUID.randomUUID(), "仓库", List.of(binding));
    }
}
