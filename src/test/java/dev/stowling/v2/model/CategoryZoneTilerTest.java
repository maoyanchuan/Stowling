package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.UnstackableCategoryRule;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryZoneTilerTest {
    @Test
    void separateFrameAreaFollowsAllThirteenUnstackableSlices() {
        var marker = new AreaBounds(0, -34, 56, 0, -34, 56);
        var storage = new AreaBounds(0, -37, 57, 1, -35, 57);
        var template = new CategoryBindingDefinition(UUID.randomUUID(), marker, storage,
                UnstackableCategoryRule.ID);
        var expansion = new CategoryZoneExpansionDefinition(
                new AreaBounds(0, -37, 56, 25, -34, 57), CategoryZoneExpansionAxis.X_POSITIVE,
                2, 1);
        var slices = CategoryZoneTiler.expand(expansion, List.of(template));

        assertEquals(13, slices.size());
        for (int i = 0; i < slices.size(); i++) {
            assertEquals(marker.shift(i * 2, 0, 0), slices.get(i).markerArea());
            assertEquals(storage.shift(i * 2, 0, 0), slices.get(i).storageArea());
        }
    }

    @Test
    void wholeStoragePreservesRotatedTwoWideSlicesInEveryDirection() {
        for (CategoryZoneExpansionAxis direction : CategoryZoneExpansionAxis.values()) {
            boolean xAxis = direction == CategoryZoneExpansionAxis.X_POSITIVE
                    || direction == CategoryZoneExpansionAxis.X_NEGATIVE;
            boolean positive = direction == CategoryZoneExpansionAxis.X_POSITIVE
                    || direction == CategoryZoneExpansionAxis.Z_POSITIVE;
            int start = positive ? 0 : 4;
            AreaBounds marker = xAxis ? new AreaBounds(start, -40, 10, start, -40, 11)
                    : new AreaBounds(10, -40, start, 11, -40, start);
            AreaBounds storage = xAxis
                    ? new AreaBounds(start, -38, 10, start + 1, -36, 10)
                    : new AreaBounds(10, -38, start, 10, -36, start + 1);
            CategoryBindingDefinition template = new CategoryBindingDefinition(
                    UUID.randomUUID(), marker, storage, "MARKER_CONTAINER_CONTENTS",
                    List.of(), List.of(), null, StoragePolicy.DEFAULT, true);
            AreaBounds total = xAxis ? new AreaBounds(0, -40, 10, 5, -36, 11)
                    : new AreaBounds(10, -40, 0, 11, -36, 5);
            CategoryZoneExpansionDefinition expansion = new CategoryZoneExpansionDefinition(
                    total, direction, 2, 1);

            List<CategoryBindingDefinition> result = CategoryZoneTiler.expand(
                    expansion, List.of(template));

            assertEquals(3, result.size());
            int distance = positive ? 4 : -4;
            assertEquals(storage.shift(xAxis ? distance : 0, 0, xAxis ? 0 : distance),
                    result.get(2).storageArea());
            assertTrue(result.stream().allMatch(CategoryBindingDefinition::wholeStorage));
            assertThrows(IllegalArgumentException.class, () -> CategoryZoneTiler.expand(
                    new CategoryZoneExpansionDefinition(total, direction, 1, 1), List.of(template)));
        }
    }

    @Test
    void oldBindingsKeepColumnPairingWhenTheOptionIsAbsent() {
        CategoryBindingDefinition template = template();
        assertTrue(!template.wholeStorage());
        com.google.gson.Gson gson = new com.google.gson.Gson();
        String legacy = gson.toJson(template).replace(
                ",\"wholeStorage\":false", "");
        assertTrue(!gson.fromJson(legacy, CategoryBindingDefinition.class).wholeStorage());
        CategoryBindingDefinition enabled = new CategoryBindingDefinition(template.id(),
                template.markerArea(), template.storageArea(), "MARKER_CONTAINER_CONTENTS",
                List.of(), List.of(), null, StoragePolicy.DEFAULT, true);
        assertEquals(enabled, gson.fromJson(gson.toJson(enabled), CategoryBindingDefinition.class));
    }

    @Test
    void standingAreaOutsideTheSelectionMovesWithEverySlice() {
        CategoryBindingDefinition original = template();
        AreaBounds standing = new AreaBounds(0, 65, 15, 4, 65, 16);
        CategoryBindingDefinition template = new CategoryBindingDefinition(original.id(),
                original.markerArea(), original.storageArea(), original.ruleId(), List.of(),
                List.of(), standing);
        CategoryZoneExpansionDefinition expansion = new CategoryZoneExpansionDefinition(
                new AreaBounds(0, 63, 17, 24, 65, 21),
                CategoryZoneExpansionAxis.X_POSITIVE, 10, 1
        );

        List<CategoryBindingDefinition> result = CategoryZoneTiler.expand(expansion,
                List.of(template));

        assertEquals(3, result.size());
        assertEquals(standing, result.get(0).standingArea());
        assertEquals(standing.shift(10, 0, 0), result.get(1).standingArea());
        assertEquals(standing.shift(20, 0, 0), result.get(2).standingArea());
    }

    @Test
    void standingAreaFollowsNegativeExpansionInsteadOfRemainingAtTheMotherSlice() {
        CategoryBindingDefinition original = template();
        AreaBounds standing = new AreaBounds(0, 65, 15, 0, 65, 15);
        CategoryBindingDefinition template = new CategoryBindingDefinition(original.id(),
                original.markerArea(), original.storageArea(), original.ruleId(), List.of(),
                List.of(), standing);
        CategoryZoneExpansionDefinition expansion = new CategoryZoneExpansionDefinition(
                new AreaBounds(-20, 63, 17, 4, 65, 21),
                CategoryZoneExpansionAxis.X_NEGATIVE, 10, 1
        );

        List<CategoryBindingDefinition> result = CategoryZoneTiler.expand(expansion,
                List.of(template));

        assertEquals(standing.shift(-20, 0, 0), result.get(2).standingArea());
    }

    @Test
    void expandsAlongPositiveXWithConfiguredStep() {
        CategoryBindingDefinition template = template();
        CategoryZoneExpansionDefinition expansion =
                new CategoryZoneExpansionDefinition(
                        new AreaBounds(0, 63, 17, 24, 65, 21),
                        CategoryZoneExpansionAxis.X_POSITIVE,
                        10,
                        1
                );

        List<CategoryBindingDefinition> bindings =
                CategoryZoneTiler.expand(expansion, List.of(template));

        assertEquals(3, bindings.size());
        assertEquals(template.id(), bindings.getFirst().id());
        assertEquals(0, bindings.get(0).markerArea().minX());
        assertEquals(10, bindings.get(1).markerArea().minX());
        assertEquals(20, bindings.get(2).markerArea().minX());
    }

    @Test
    void expansionIdsRemainStableAcrossRepeatedCalculation() {
        CategoryZoneExpansionDefinition expansion =
                new CategoryZoneExpansionDefinition(
                        new AreaBounds(-20, 63, 17, 0, 65, 21),
                        CategoryZoneExpansionAxis.X_NEGATIVE,
                        10,
                        1
                );
        CategoryBindingDefinition template = template();

        List<CategoryBindingDefinition> first =
                CategoryZoneTiler.expand(expansion, List.of(template));
        List<CategoryBindingDefinition> second =
                CategoryZoneTiler.expand(expansion, List.of(template));

        assertEquals(first, second);
        assertEquals(3, first.size());
        assertEquals(-20, first.get(2).markerArea().minX());
    }

    @Test
    void rejectsTemplateWhenDirectionalLengthsDiffer() {
        CategoryBindingDefinition template = new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(0, 64, 20, 4, 64, 20),
                new AreaBounds(0, 64, 18, 2, 64, 18),
                "DIRECT_BLOCK_ID"
        );
        CategoryZoneExpansionDefinition expansion =
                new CategoryZoneExpansionDefinition(
                        new AreaBounds(0, 63, 17, 24, 65, 21),
                        CategoryZoneExpansionAxis.X_POSITIVE,
                        10,
                        1
                );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CategoryZoneTiler.expand(
                        expansion,
                        List.of(template)
                )
        );

        assertTrue(exception.getMessage().contains("长度必须一致"));
    }

    @Test
    void shiftsContainerOverridesWithEachGeneratedSlice() {
        UUID overrideId = UUID.randomUUID();
        CategoryBindingDefinition template = new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(0, 64, 20, 4, 64, 20),
                new AreaBounds(0, 64, 18, 4, 64, 18),
                "DIRECT_BLOCK_ID",
                List.of(),
                List.of(new CategoryContainerOverrideDefinition(
                        overrideId,
                        new BlockPosition(2, 64, 18),
                        CategoryContainerOverrideMode.ADD,
                        List.of("minecraft:stone")
                ))
        );
        CategoryZoneExpansionDefinition expansion =
                new CategoryZoneExpansionDefinition(
                        new AreaBounds(0, 63, 17, 14, 65, 21),
                        CategoryZoneExpansionAxis.X_POSITIVE,
                        10,
                        1
                );

        List<CategoryBindingDefinition> bindings =
                CategoryZoneTiler.expand(expansion, List.of(template));

        assertEquals(overrideId, bindings.getFirst()
                .containerOverrides().getFirst().id());
        assertEquals(
                new BlockPosition(12, 64, 18),
                bindings.get(1).containerOverrides()
                        .getFirst().storagePosition()
        );
        assertTrue(!bindings.get(1).containerOverrides()
                .getFirst().id().equals(overrideId));
    }

    @Test
    void repeatsAllMotherBindingsAsOneSlice() {
        CategoryBindingDefinition first = new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(0, 64, 20, 4, 64, 20),
                new AreaBounds(0, 64, 18, 4, 64, 18),
                "DIRECT_BLOCK_ID"
        );
        CategoryBindingDefinition second = new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(0, 64, 24, 4, 64, 24),
                new AreaBounds(0, 64, 22, 4, 64, 22),
                "DIRECT_BLOCK_ID"
        );
        CategoryZoneExpansionDefinition expansion =
                new CategoryZoneExpansionDefinition(
                        new AreaBounds(0, 63, 17, 14, 65, 25),
                        CategoryZoneExpansionAxis.X_POSITIVE,
                        10,
                        2
                );

        List<CategoryBindingDefinition> bindings =
                CategoryZoneTiler.expand(expansion, List.of(first, second));

        assertEquals(4, bindings.size());
        assertEquals(first.id(), bindings.get(0).id());
        assertEquals(second.id(), bindings.get(1).id());
        assertEquals(10, bindings.get(2).markerArea().minX());
        assertEquals(10, bindings.get(3).markerArea().minX());
        assertEquals(24, bindings.get(3).markerArea().minZ());
    }

    private static CategoryBindingDefinition template() {
        return new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(0, 64, 20, 4, 64, 20),
                new AreaBounds(0, 64, 18, 4, 64, 18),
                "DIRECT_BLOCK_ID"
        );
    }
}
