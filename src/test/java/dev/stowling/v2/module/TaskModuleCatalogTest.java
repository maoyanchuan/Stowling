package dev.stowling.v2.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ModuleDefinition;
import dev.stowling.v2.model.ModuleParameter;
import dev.stowling.v2.model.TaskModuleLevel;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TaskModuleCatalogTest {
    @Test
    void exposesAllSupportedModulesInBuildOrder() {
        List<ModuleDefinition> modules = TaskModuleCatalog.modules();

        assertEquals(
                List.of(
                        ConfigValidator.MOVE_TO_COORDINATE,
                        ConfigValidator.COLLECT_DROPPED_ITEMS,
                        ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                        ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                        ConfigValidator.AUTO_EAT_AT_COORDINATE,
                        ConfigValidator.SORT_CACHE_ITEMS_BY_CATEGORY_ZONE,
                        ConfigValidator.REFRESH_CATEGORY_MARKER_CACHE,
                        ConfigValidator.WAIT_FOR_DROPPED_ITEMS,
                        ConfigValidator.COLLECT_DROPPED_ITEMS_IN_REGION,
                        ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                        ConfigValidator.SORT_CACHE_ITEMS
                ),
                modules.stream().map(ModuleDefinition::id).toList()
        );
        assertEquals(
                7,
                modules.stream().filter(module -> !module.deprecated()).count()
        );
    }

    @Test
    void exposesCoordinateRegionCategoryAndCacheParameterContracts() {
        Map<String, ModuleDefinition> modules = TaskModuleCatalog.modules().stream()
                .collect(Collectors.toMap(ModuleDefinition::id, Function.identity()));

        ModuleParameter coordinate = moduleParameter(
                modules.get(ConfigValidator.COLLECT_DROPPED_ITEMS),
                ConfigValidator.AREA_REFERENCE
        );
        assertEquals("area-reference", coordinate.kind());
        assertTrue(coordinate.required());
        assertTrue(
                modules.get(ConfigValidator.WAIT_FOR_DROPPED_ITEMS).deprecated()
        );
        assertEquals(
                TaskModuleLevel.BASIC,
                modules.get(ConfigValidator.MOVE_TO_COORDINATE).level()
        );

        ModuleParameter region = moduleParameter(
                modules.get(ConfigValidator.SCAN_CONTAINERS_IN_REGION),
                ConfigValidator.REGION_ID
        );
        assertEquals("region-point", region.kind());
        assertTrue(region.required());

        ModuleParameter collectRegion = moduleParameter(
                modules.get(ConfigValidator.COLLECT_DROPPED_ITEMS_IN_REGION),
                ConfigValidator.REGION_ID
        );
        assertEquals("region-point", collectRegion.kind());
        assertTrue(collectRegion.required());

        ModuleDefinition category = modules.get(
                ConfigValidator.MATCH_CONTAINER_CATEGORIES
        );
        assertTrue(category.deprecated());
        assertEquals(
                List.of("previous-scan"),
                moduleParameter(
                        category,
                        ConfigValidator.SCAN_SOURCE
                ).options()
        );

        ModuleDefinition autoEat = modules.get(
                ConfigValidator.AUTO_EAT_AT_COORDINATE
        );
        ModuleParameter eatCoordinate = moduleParameter(
                autoEat,
                ConfigValidator.COORDINATE_ID
        );
        assertEquals("coordinate-point", eatCoordinate.kind());
        assertTrue(eatCoordinate.required());
        ModuleParameter threshold = moduleParameter(
                autoEat,
                ConfigValidator.FOOD_LEVEL_THRESHOLD
        );
        assertEquals("integer", threshold.kind());
        assertEquals("18", threshold.defaultValue());

        ModuleDefinition sort = modules.get(ConfigValidator.SORT_CACHE_ITEMS);
        assertEquals(TaskModuleLevel.COMPOSITE, sort.level());
        assertTrue(sort.deprecated());
        ModuleParameter cacheRegion = moduleParameter(
                sort,
                ConfigValidator.CACHE_REGION_ID
        );
        assertEquals("region-point", cacheRegion.kind());
        assertTrue(cacheRegion.required());
        assertEquals(
                List.of("previous-scan"),
                moduleParameter(sort, ConfigValidator.SCAN_SOURCE).options()
        );

        ModuleDefinition zoneSort = modules.get(
                ConfigValidator.SORT_CACHE_ITEMS_BY_CATEGORY_ZONE
        );
        ModuleParameter zone = moduleParameter(
                zoneSort,
                ConfigValidator.CATEGORY_ZONE_ID
        );
        assertEquals("category-zone-point", zone.kind());
        assertTrue(zone.required());

        ModuleDefinition refresh = modules.get(
                ConfigValidator.REFRESH_CATEGORY_MARKER_CACHE
        );
        ModuleParameter refreshZone = moduleParameter(
                refresh,
                ConfigValidator.CATEGORY_ZONE_ID
        );
        assertEquals("category-zone-point", refreshZone.kind());
        assertTrue(refreshZone.required());

        ModuleDefinition deposit = modules.get(
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS
        );
        assertTrue(deposit.parameters().isEmpty());
    }

    private static ModuleParameter moduleParameter(
            ModuleDefinition module,
            String key
    ) {
        return module.parameters().stream()
                .filter(parameter -> parameter.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
