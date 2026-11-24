package dev.stowling.v2.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CategoryZoneDefinition(
        UUID id,
        String name,
        List<CategoryBindingDefinition> bindings,
        CategoryZoneExpansionDefinition expansion,
        CategoryZoneOptions categoryOptions,
        AreaBounds overallArea
) {
    public CategoryZoneDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        bindings = List.copyOf(
                Objects.requireNonNull(bindings, "bindings")
        );
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "category zone must contain at least one binding"
            );
        }
        CategoryZoneOptions globalOptions = categoryOptions;
        bindings = bindings.stream()
                .map(binding -> withStandingHeight(binding, globalOptions))
                .toList();
    }

    public CategoryZoneDefinition(UUID id, String name, List<CategoryBindingDefinition> bindings,
            CategoryZoneExpansionDefinition expansion, CategoryZoneOptions categoryOptions) {
        this(id, name, bindings, expansion, categoryOptions, null);
    }

    public CategoryZoneDefinition(
            UUID id, String name, List<CategoryBindingDefinition> bindings,
            CategoryZoneExpansionDefinition expansion
    ) {
        this(id, name, bindings, expansion, null);
    }

    public CategoryZoneDefinition(
            UUID id,
            String name,
            List<CategoryBindingDefinition> bindings
    ) {
        this(id, name, bindings, null);
    }

    private static CategoryBindingDefinition withStandingHeight(
            CategoryBindingDefinition binding, CategoryZoneOptions globalOptions
    ) {
        CategoryZoneOptions effective = binding.optionsEnabled()
                ? binding.categoryOptions() : globalOptions;
        if (effective == null || !Boolean.TRUE.equals(effective.specifyStanding())) {
            return binding;
        }
        AreaBounds feet = effective.standingArea(null);
        AreaBounds previous = binding.standingArea();
        if (previous != null) {
            // Repair saved support-block heights without undoing horizontal slice expansion.
            feet = new AreaBounds(previous.firstX(), feet.firstY(), previous.firstZ(),
                    previous.secondX(), feet.secondY(), previous.secondZ());
        }
        if (feet.equals(previous)) return binding;
        return new CategoryBindingDefinition(binding.id(), binding.markerArea(),
                binding.storageArea(), binding.ruleId(), binding.ignoredMarkerBlockIds(),
                binding.containerOverrides(), feet, binding.storagePolicy(),
                binding.wholeStorage(), binding.name(), binding.optionsEnabled(),
                binding.categoryOptions());
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException(
                    "name must contain 1 to 80 characters"
            );
        }
        return trimmed;
    }
}
