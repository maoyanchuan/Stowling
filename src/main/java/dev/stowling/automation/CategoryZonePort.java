package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.CategoryZoneDefinition;
import java.util.Map;
import java.util.Set;

public interface CategoryZonePort {
    CategoryZoneResult resolve(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume
    );

    default CategoryZoneResult resolve(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume,
            Map<BlockPosition, Set<String>> markerContainerContents
    ) {
        return resolve(zone, containerTypes, maxVolume);
    }

    default CategoryZoneResult resolve(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume,
            Map<BlockPosition, Set<String>> markerContainerContents,
            Set<CategoryMarkerSource> markerSources
    ) {
        return resolve(
                zone,
                containerTypes,
                maxVolume,
                markerContainerContents
        );
    }
}
