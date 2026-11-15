package dev.stowling.v2.control;

import dev.stowling.v2.model.CategoryZoneDefinition;
import java.util.List;
import java.util.Objects;

public record CategoryZoneListResponse(
        String revision,
        List<CategoryZoneDefinition> categoryZones
) {
    public CategoryZoneListResponse {
        revision = Objects.requireNonNull(revision, "revision");
        categoryZones = List.copyOf(
                Objects.requireNonNull(categoryZones, "categoryZones")
        );
    }
}
