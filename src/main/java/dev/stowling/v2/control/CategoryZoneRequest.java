package dev.stowling.v2.control;

import java.util.List;

public record CategoryZoneRequest(
        String name,
        List<CategoryBindingRequest> bindings,
        CategoryZoneExpansionRequest expansion
) {
    public CategoryZoneRequest(
            String name,
            List<CategoryBindingRequest> bindings
    ) {
        this(name, bindings, null);
    }
}
