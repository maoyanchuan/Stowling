package dev.stowling.v2.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Independent-classification standing baseline; other consumers retain their own contracts. */
public final class CategoryStandingSubregions {
    private static final String UUID_NAMESPACE = "classification-standing-subregions";

    public record Segment(CategoryBindingDefinition binding, CategoryZoneOptions options,
            UUID scopeId) {
    }

    private CategoryStandingSubregions() {
    }

    public static List<Segment> segments(CategoryZoneDefinition zone, boolean classificationTask) {
        List<Segment> result = new ArrayList<>();
        for (int index = 0; index < zone.bindings().size(); index++) {
            var binding = zone.bindings().get(index);
            var options = binding.optionsEnabled()
                    ? binding.categoryOptions() : zone.categoryOptions();
            UUID scope = binding.optionsEnabled() ? CategoryZoneTiler.templateFor(zone, index).id()
                    : null;
            var plan = binding.standingPlan();
            if (classificationTask && zone.scan() != null && !plan.enabled()
                    && CategoryZoneTiler.templateFor(zone, index).standingPlan().enabled()) {
                // This physical container is outside the explicitly selected standing subregions.
                continue;
            }
            if (!classificationTask) {
                result.add(new Segment(binding, options, scope));
            } else if (!plan.enabled()) {
                result.add(new Segment(copy(binding, binding.id(), binding.markerArea(),
                        binding.storageArea(), null), options, scope));
            } else {
                plan.validate(binding.markerArea(), binding.storageArea());
                for (int pointIndex = 0; pointIndex < plan.points().size(); pointIndex++) {
                    var point = plan.points().get(pointIndex);
                    UUID id = UUID.nameUUIDFromBytes((binding.id() + ":" + UUID_NAMESPACE
                            + ":" + pointIndex)
                            .getBytes(StandardCharsets.UTF_8));
                    result.add(new Segment(copy(binding, id,
                            point.usesSubregions() ? point.markerArea() : binding.markerArea(),
                            point.usesSubregions() ? point.storageArea() : binding.storageArea(),
                            point.feetArea()), options, scope));
                }
            }
        }
        return List.copyOf(result);
    }

    private static CategoryBindingDefinition copy(CategoryBindingDefinition binding, UUID id,
            AreaBounds markers, AreaBounds storage, AreaBounds feet) {
        return new CategoryBindingDefinition(id, markers, storage, binding.ruleId(),
                binding.ignoredMarkerBlockIds(), binding.containerOverrides().stream()
                        .filter(value -> storage.contains(value.storagePosition())).toList(),
                feet, binding.storagePolicy(), binding.wholeStorage(), binding.name(),
                binding.optionsEnabled(), binding.categoryOptions());
    }
}
