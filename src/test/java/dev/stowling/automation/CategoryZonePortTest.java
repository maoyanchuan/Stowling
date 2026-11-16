package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryZonePortTest {
    @Test
    void explicitSourcesDefaultToLegacyCacheResolution() {
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                UUID.randomUUID(),
                "分类区",
                List.of(new CategoryBindingDefinition(
                        UUID.randomUUID(),
                        new AreaBounds(0, 0, 0, 1, 1, 1),
                        new AreaBounds(0, 0, 2, 1, 1, 3),
                        "DIRECT_BLOCK_ID"
                ))
        );
        Map<BlockPosition, Set<String>> markerContents = Map.of(
                new BlockPosition(0, 0, 0),
                Set.of("minecraft:oak_log")
        );
        RecordingCategoryZonePort port = new RecordingCategoryZonePort();

        CategoryZoneResult result = port.resolve(
                zone,
                Set.of("minecraft:chest"),
                100,
                markerContents,
                Set.of(CategoryMarkerSource.CONTAINER_CONTENTS)
        );

        assertTrue(result.succeeded());
        assertSame(markerContents, port.lastMarkerContents);
    }

    private static final class RecordingCategoryZonePort
            implements CategoryZonePort {
        private Map<BlockPosition, Set<String>> lastMarkerContents;

        @Override
        public CategoryZoneResult resolve(
                CategoryZoneDefinition zone,
                Set<String> containerTypes,
                int maxVolume
        ) {
            throw new AssertionError("three-argument resolve was used");
        }

        @Override
        public CategoryZoneResult resolve(
                CategoryZoneDefinition zone,
                Set<String> containerTypes,
                int maxVolume,
                Map<BlockPosition, Set<String>> markerContainerContents
        ) {
            lastMarkerContents = markerContainerContents;
            return CategoryZoneResult.success(Map.of());
        }
    }
}
