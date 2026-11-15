package dev.stowling.v2.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ModuleDefinition;
import dev.stowling.v2.model.ModuleParameter;
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
                        ConfigValidator.WAIT_FOR_DROPPED_ITEMS,
                        ConfigValidator.COLLECT_DROPPED_ITEMS,
                        ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                        ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                        ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS
                ),
                modules.stream().map(ModuleDefinition::id).toList()
        );
    }

    @Test
    void exposesCoordinateRegionAndDepositParameterContracts() {
        Map<String, ModuleDefinition> modules = TaskModuleCatalog.modules().stream()
                .collect(Collectors.toMap(ModuleDefinition::id, Function.identity()));

        ModuleParameter coordinate = moduleParameter(
                modules.get(ConfigValidator.WAIT_FOR_DROPPED_ITEMS),
                ConfigValidator.COORDINATE_ID
        );
        assertEquals("coordinate-point", coordinate.kind());
        assertTrue(coordinate.required());

        ModuleParameter region = moduleParameter(
                modules.get(ConfigValidator.SCAN_CONTAINERS_IN_REGION),
                ConfigValidator.REGION_ID
        );
        assertEquals("region-point", region.kind());
        assertTrue(region.required());

        ModuleDefinition category = modules.get(
                ConfigValidator.MATCH_CONTAINER_CATEGORIES
        );
        assertEquals(
                List.of("previous-scan"),
                moduleParameter(
                        category,
                        ConfigValidator.SCAN_SOURCE
                ).options()
        );

        ModuleDefinition deposit = modules.get(
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS
        );
        assertEquals(
                List.of("previous-scan"),
                moduleParameter(deposit, ConfigValidator.SCAN_SOURCE).options()
        );
        assertEquals(
                List.of("collected-this-run"),
                moduleParameter(deposit, ConfigValidator.ITEM_SOURCE).options()
        );
        assertEquals(
                List.of("nearest-first"),
                moduleParameter(deposit, ConfigValidator.FILL_MODE).options()
        );
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
