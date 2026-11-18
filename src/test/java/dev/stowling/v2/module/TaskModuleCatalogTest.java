package dev.stowling.v2.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ModuleDefinition;
import dev.stowling.v2.model.ModuleParameter;
import dev.stowling.v2.model.TaskModuleKind;
import dev.stowling.v2.model.TaskTriggerType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TaskModuleCatalogTest {
    @Test
    void exposesOnlyRealActionsAndListeners() {
        List<ModuleDefinition> modules = TaskModuleCatalog.modules();

        assertEquals(23, modules.size());
        assertEquals(
                3,
                modules.stream()
                        .filter(module -> module.kind() == TaskModuleKind.LISTENER)
                        .count()
        );
        assertEquals(
                20,
                modules.stream()
                        .filter(module -> module.kind() == TaskModuleKind.ACTION)
                        .count()
        );
        assertTrue(modules.stream().allMatch(module ->
                module.composedOf().isEmpty()
        ));
        assertTrue(modules.stream().noneMatch(module ->
                module.kind() == TaskModuleKind.PRESET
                        || module.kind() == TaskModuleKind.INTERNAL
        ));
    }

    @Test
    void exposesUnifiedReferenceParameters() {
        Map<String, ModuleDefinition> modules = TaskModuleCatalog.modules().stream()
                .collect(Collectors.toMap(
                        ModuleDefinition::id,
                        Function.identity()
                ));

        assertEquals(
                List.of(
                        "COLLECT",
                        "FIND",
                        "READ",
                        "WITHDRAW",
                        "DEPOSIT"
                ),
                parameter(
                        modules.get(ConfigValidator.CONTAINER_INTERACTION),
                        "operation"
                ).options()
        );
        assertTrue(parameter(
                modules.get(ConfigValidator.CONTAINER_INTERACTION),
                "operation"
        ).required());
        assertEquals(
                "coordinate-reference",
                parameter(
                        modules.get(ConfigValidator.MOVE_TO_COORDINATE),
                        ConfigValidator.TARGET_REF
                ).kind()
        );
        ModuleParameter moveTarget = parameter(
                modules.get(ConfigValidator.MOVE_TO_COORDINATE),
                ConfigValidator.TARGET_REF
        );
        assertFalse(moveTarget.required());
        for (String key : List.of(
                ConfigValidator.X,
                ConfigValidator.Y,
                ConfigValidator.Z
        )) {
            ModuleParameter coordinate = parameter(
                    modules.get(ConfigValidator.MOVE_TO_COORDINATE),
                    key
            );
            assertEquals("integer", coordinate.kind());
            assertFalse(coordinate.required());
            assertEquals(Integer.MIN_VALUE, coordinate.min());
            assertEquals(Integer.MAX_VALUE, coordinate.max());
        }
        assertEquals(
                "area-reference",
                parameter(
                        modules.get(ConfigValidator.COLLECT_DROPPED_ITEMS),
                        ConfigValidator.AREA_REF
                ).kind()
        );
        assertEquals(
                "region-reference",
                parameter(
                        modules.get(ConfigValidator.SCAN_CONTAINERS_IN_REGION),
                        ConfigValidator.REGION_REF
                ).kind()
        );
        assertEquals(
                "category-zone-reference",
                parameter(
                        modules.get(
                                ConfigValidator.RESOLVE_CONTAINER_CATEGORIES
                        ),
                        ConfigValidator.CATEGORY_ZONE_REF
                ).kind()
        );
        assertEquals(
                List.of("collected", "carried", "managed"),
                parameter(
                        modules.get(
                                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS
                        ),
                        ConfigValidator.ITEM_SOURCE
                ).options()
        );
        assertEquals(
                List.of("classified"),
                parameter(
                        modules.get(ConfigValidator.WITHDRAW_ITEMS),
                        ConfigValidator.ITEM_SOURCE
                ).options()
        );
        ModuleParameter assertionSource = parameter(
                modules.get(ConfigValidator.ASSERT_NO_MANAGED_ITEMS),
                ConfigValidator.ITEM_SOURCE
        );
        assertEquals(
                List.of("collected", "carried", "managed"),
                assertionSource.options()
        );
        assertFalse(assertionSource.required());
        assertEquals("managed", assertionSource.defaultValue());
    }

    @Test
    void keepsExistingListeners() {
        List<String> listenerIds = TaskModuleCatalog.modules().stream()
                .filter(module -> module.kind() == TaskModuleKind.LISTENER)
                .map(ModuleDefinition::id)
                .toList();

        assertEquals(
                List.of(
                        TaskTriggerType.DROPPED_ITEMS.name(),
                        TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW.name(),
                        TaskTriggerType.CACHE_UPDATED.name()
                ),
                listenerIds
        );
    }

    private static ModuleParameter parameter(
            ModuleDefinition module,
            String key
    ) {
        return module.parameters().stream()
                .filter(parameter -> parameter.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
