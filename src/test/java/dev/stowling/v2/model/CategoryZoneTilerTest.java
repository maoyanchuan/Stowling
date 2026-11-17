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
    void expandsAlongPositiveXWithConfiguredStep() {
        CategoryBindingDefinition template = template();
        CategoryZoneExpansionDefinition expansion =
                new CategoryZoneExpansionDefinition(
                        new AreaBounds(0, 63, 17, 24, 65, 21),
                        CategoryZoneExpansionAxis.X_POSITIVE,
                        10
                );

        List<CategoryBindingDefinition> bindings =
                CategoryZoneTiler.expand(expansion, template);

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
                        10
                );
        CategoryBindingDefinition template = template();

        List<CategoryBindingDefinition> first =
                CategoryZoneTiler.expand(expansion, template);
        List<CategoryBindingDefinition> second =
                CategoryZoneTiler.expand(expansion, template);

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
                        10
                );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CategoryZoneTiler.expand(expansion, template)
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
                        10
                );

        List<CategoryBindingDefinition> bindings =
                CategoryZoneTiler.expand(expansion, template);

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

    private static CategoryBindingDefinition template() {
        return new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(0, 64, 20, 4, 64, 20),
                new AreaBounds(0, 64, 18, 4, 64, 18),
                "DIRECT_BLOCK_ID"
        );
    }
}
