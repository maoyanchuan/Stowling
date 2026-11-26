package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerCacheRefreshTest {
    @Test
    void orderedTargetsReadLegacySingleAndDeduplicateWithoutReordering() {
        String first = "region:" + UUID.randomUUID();
        String second = "category-zone:" + UUID.randomUUID();
        var legacy = ContainerCacheRefresh.from(Map.of("targetRef", first));
        assertEquals(List.of(ResourceReference.parse(first)), legacy.targets());
        assertFalse(legacy.multiAreaEnabled());
        assertFalse(legacy.foodPauseResume());
        var selected = ContainerCacheRefresh.from(Map.of("targetRef", second,
                "targetRefs", new com.google.gson.Gson().toJson(List.of(first, second, first)),
                "multiAreaEnabled", "true", "foodPauseResumeEnabled", "true"));
        assertEquals(List.of(ResourceReference.parse(first), ResourceReference.parse(second)),
                selected.targets());
        assertTrue(selected.multiAreaEnabled());
        assertTrue(selected.foodPauseResume());
    }

    @Test
    void rejectsInvalidTargetArraysAndKeepsInternalMarkerRefreshSingle() {
        for (String encoded : List.of("[]", "null", "{}", "[1]", "bad", "[\"bad\"]")) {
            assertThrows(IllegalArgumentException.class, () -> ContainerCacheRefresh.from(
                    Map.of("targetRefs", encoded)));
        }
        String zone = "category-zone:" + UUID.randomUUID();
        var marker = ContainerCacheRefresh.from(Map.of("targetRef", zone,
                "markerOnly", "true", "foodPauseResumeEnabled", "true"));
        assertFalse(marker.foodPauseResume());
        assertThrows(IllegalArgumentException.class, () -> ContainerCacheRefresh.from(Map.of(
                "targetRefs", new com.google.gson.Gson().toJson(List.of(zone,
                        "category-zone:" + UUID.randomUUID())), "markerOnly", "true")));
        for (String key : List.of("multiAreaEnabled", "foodPauseResumeEnabled")) {
            assertThrows(IllegalArgumentException.class, () -> ContainerCacheRefresh.from(
                    Map.of("targetRef", zone, key, "invalid")));
        }
    }

    @Test
    void retiredExecutionFlagsNoLongerChangeRefreshSettings() {
        String target = "region:" + UUID.randomUUID();
        var baseline = ContainerCacheRefresh.from(Map.of("targetRef", target));
        for (String hand : List.of("true", "false", "invalid", "")) {
            for (String route : List.of("true", "false", "invalid", "")) {
                assertEquals(baseline,
                        ContainerCacheRefresh.from(Map.of("targetRef", target,
                                "reuseSafeHandEnabled", hand, "nearbyRouteEnabled", route)));
            }
        }
    }

    @Test
    void categoryStorageRefreshDefaultsOffAndParsesPersistedSwitch() {
        String target = "category-zone:" + UUID.randomUUID();
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", target))
                .categoryStorageOnlyEnabled());
        assertTrue(ContainerCacheRefresh.from(Map.of("targetRef", target,
                "categoryStorageOnlyEnabled", "true")).categoryStorageOnly());
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", target,
                "categoryStorageOnlyEnabled", "false")).categoryStorageOnly());
        assertThrows(IllegalArgumentException.class, () -> ContainerCacheRefresh.from(
                Map.of("targetRef", target, "categoryStorageOnlyEnabled", "invalid")));
    }

    @Test
    void switchDoesNotChangeRegionsOrInternalMarkerRefresh() {
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", "region:" + UUID.randomUUID(),
                "categoryStorageOnlyEnabled", "true")).categoryStorageOnly());
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef",
                "category-zone:" + UUID.randomUUID(), "markerOnly", "true",
                "categoryStorageOnlyEnabled", "true")).categoryStorageOnly());
    }
}
