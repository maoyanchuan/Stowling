package dev.stowling.v2.model;

import dev.stowling.automation.ContainerTarget;
import java.util.List;
import java.util.function.Predicate;

/** Independent classification baseline for zone-owned unstackable remainder routing. */
public final class UnstackableZoneRouting {
    public static final String MODULE = "ROUTE_UNSTACKABLE_ZONE_REMAINDERS";
    public static final String KIND = "UNSTACKABLE_ZONE";

    private UnstackableZoneRouting() {
    }

    public static boolean isDedicated(CategoryZoneDefinition zone) {
        return zone.categoryOptions() != null
                && "unstackable".equals(zone.categoryOptions().recognitionMode())
                || zone.bindings().stream()
                        .allMatch(binding -> "UNSTACKABLE_ITEMS".equals(binding.ruleId()));
    }

    public static CategoryZoneDefinition forExecution(CategoryZoneDefinition zone,
            boolean classificationTask) {
        if (!classificationTask || !isDedicated(zone)) return zone;
        var bindings = zone.bindings().stream().map(binding -> new CategoryBindingDefinition(
                binding.id(), binding.markerArea(), binding.storageArea(), "UNSTACKABLE_ITEMS",
                List.of(), binding.containerOverrides(), binding.standingArea(),
                binding.storagePolicy(), false, binding.name(), binding.optionsEnabled(),
                dedicatedOptions(binding.categoryOptions()), binding.standingPlan())).toList();
        return new CategoryZoneDefinition(zone.id(), zone.name(), bindings, zone.expansion(),
                dedicatedOptions(zone.categoryOptions()), zone.overallArea());
    }

    private static CategoryZoneOptions dedicatedOptions(CategoryZoneOptions options) {
        if (options == null) return null;
        return new CategoryZoneOptions(options.scopeMode(), "unstackable", options.storageMethod(),
                options.noCacheLocation(), options.cacheDestination(), false, "",
                options.specifyStanding(), options.standingPosition(), options.bulkStacks(),
                false, List.of(), false, false, List.of(), options.unstackableOverflowRef());
    }

    public static boolean eligible(List<ContainerTarget> targets,
            Predicate<ContainerTarget> confirmedFull) {
        return targets.isEmpty() || targets.stream().allMatch(confirmedFull);
    }
}
