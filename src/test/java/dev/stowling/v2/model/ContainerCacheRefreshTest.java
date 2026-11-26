package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerCacheRefreshTest {
    @Test
    void nearbyRouteDefaultsOffAndNeverAppliesToInternalMarkerRefresh() {
        String target = "category-zone:" + java.util.UUID.randomUUID();
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", target)).nearbyRoute());
        assertTrue(ContainerCacheRefresh.from(Map.of("targetRef", target,
                "nearbyRouteEnabled", "true")).nearbyRoute());
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", target,
                "nearbyRouteEnabled", "false")).nearbyRoute());
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", target,
                "nearbyRouteEnabled", "true", "markerOnly", "true")).nearbyRoute());
        assertThrows(IllegalArgumentException.class, () -> ContainerCacheRefresh.from(
                Map.of("targetRef", target, "nearbyRouteEnabled", "invalid")));
    }

    @Test
    void readSessionDefaultsOffAndOnlyAppliesOutsideInternalMarkerRefresh() {
        String target = "category-zone:" + UUID.randomUUID();
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", target)).reuseSafeHand());
        assertTrue(ContainerCacheRefresh.from(Map.of("targetRef", target,
                "reuseSafeHandEnabled", "true")).reuseSafeHand());
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", target,
                "reuseSafeHandEnabled", "false")).reuseSafeHand());
        assertFalse(ContainerCacheRefresh.from(Map.of("targetRef", target,
                "reuseSafeHandEnabled", "true", "markerOnly", "true")).reuseSafeHand());
        assertThrows(IllegalArgumentException.class, () -> ContainerCacheRefresh.from(
                Map.of("targetRef", target, "reuseSafeHandEnabled", "invalid")));
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
