package dev.stowling.v2.control;

import java.util.List;

public record CategoryZoneUpdateRequest(
        String revision,
        String name,
        List<CategoryBindingRequest> bindings,
        CategoryZoneExpansionRequest expansion
) {
    public CategoryZoneUpdateRequest(
            String revision,
            String name,
            List<CategoryBindingRequest> bindings
    ) {
        this(revision, name, bindings, null);
    }
}
