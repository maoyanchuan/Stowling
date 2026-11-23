package dev.stowling.v2.control;

import java.util.List;

public record CategoryZoneRequest(
        String name,
        List<CategoryBindingRequest> bindings,
        CategoryZoneExpansionRequest expansion,
        dev.stowling.v2.model.CategoryZoneOptions categoryOptions,
        dev.stowling.v2.model.AreaBounds overallArea
) {
    public CategoryZoneRequest(String name, List<CategoryBindingRequest> bindings,
            CategoryZoneExpansionRequest expansion,
            dev.stowling.v2.model.CategoryZoneOptions categoryOptions) {
        this(name, bindings, expansion, categoryOptions, null);
    }

    public CategoryZoneRequest(String name, List<CategoryBindingRequest> bindings,
            CategoryZoneExpansionRequest expansion) {
        this(name, bindings, expansion, null);
    }

    public CategoryZoneRequest(
            String name,
            List<CategoryBindingRequest> bindings
    ) {
        this(name, bindings, null);
    }
}
