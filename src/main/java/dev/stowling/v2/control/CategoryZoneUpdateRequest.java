package dev.stowling.v2.control;

import java.util.List;

public record CategoryZoneUpdateRequest(
        String revision,
        String name,
        List<CategoryBindingRequest> bindings,
        CategoryZoneExpansionRequest expansion,
        dev.stowling.v2.model.CategoryZoneOptions categoryOptions,
        dev.stowling.v2.model.AreaBounds overallArea
) {
    public CategoryZoneUpdateRequest(String revision, String name,
            List<CategoryBindingRequest> bindings, CategoryZoneExpansionRequest expansion,
            dev.stowling.v2.model.CategoryZoneOptions categoryOptions) {
        this(revision, name, bindings, expansion, categoryOptions, null);
    }

    public CategoryZoneUpdateRequest(String revision, String name,
            List<CategoryBindingRequest> bindings, CategoryZoneExpansionRequest expansion) {
        this(revision, name, bindings, expansion, null);
    }

    public CategoryZoneUpdateRequest(
            String revision,
            String name,
            List<CategoryBindingRequest> bindings
    ) {
        this(revision, name, bindings, null);
    }
}
