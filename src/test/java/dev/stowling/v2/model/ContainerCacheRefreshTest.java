package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerCacheRefreshTest {
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
