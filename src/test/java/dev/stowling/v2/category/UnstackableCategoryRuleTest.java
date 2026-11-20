package dev.stowling.v2.category;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnstackableCategoryRuleTest {
    @Test
    void usesTheWholeStorageAreaWithoutClassificationMarkers() {
        var rule = CategoryRuleRegistry.standard().require(UnstackableCategoryRule.ID);
        var first = new ContainerTarget(new BlockPosition(0, 64, 0), "chest");
        var second = new ContainerTarget(new BlockPosition(2, 64, 0), "barrel");
        var result = rule.resolve(List.of(), List.of(first, second));

        assertTrue(result.succeeded());
        assertEquals(List.of(first, second),
                result.categories().get(UnstackableCategoryRule.CATEGORY));
        assertFalse(rule.resolve(List.of(), List.of()).succeeded());
    }

    @Test
    void theLayoutOnlyNeedsStorageBoundsAndAlwaysStoresLooseItems() {
        AreaBounds area = new AreaBounds(10, 64, 0, 12, 65, 1);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(0, 0, 0, 0, 0, 0), area, UnstackableCategoryRule.ID,
                List.of(), List.of(), null,
                new StoragePolicy(StorageMode.BOX_ONLY, 50, true, null));
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "不可堆叠", List.of(binding));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), List.of(),
                List.of(), List.of(zone));

        assertEquals(area, binding.markerArea());
        assertEquals(StorageMode.NORMAL, binding.storagePolicy().mode());
        assertDoesNotThrow(() -> ConfigValidator.requireValid(config));
    }
}
