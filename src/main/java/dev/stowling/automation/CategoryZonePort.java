package dev.stowling.automation;

import dev.stowling.v2.model.CategoryZoneDefinition;
import java.util.Set;

public interface CategoryZonePort {
    CategoryZoneResult resolve(
            CategoryZoneDefinition zone,
            Set<String> containerTypes,
            int maxVolume
    );
}
