package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryZoneTilerTest {
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
