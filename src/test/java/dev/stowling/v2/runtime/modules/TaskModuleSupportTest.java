package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskModuleSupportTest {
    @Test
    void selectsOneBindingAndAppliesTheConfiguredRuleOverride() {
        UUID zoneId = UUID.randomUUID();
        UUID selectedId = UUID.randomUUID();
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                zoneId,
                "Zone",
                List.of(
                        binding(selectedId, "DIRECT_BLOCK_ID"),
                        binding(UUID.randomUUID(), "CONTAINER_CONTENTS")
                )
        );
        TaskStep step = TaskStep.action(
                UUID.randomUUID(),
                TaskModuleIds.RESOLVE_CONTAINER_CATEGORIES,
                Map.of(
                        TaskModuleIds.CATEGORY_BINDING_ID,
                        selectedId.toString(),
                        TaskModuleIds.CATEGORY_RULE_ID,
                        "CONTAINER_CONTENTS"
                )
        );

        CategoryZoneDefinition selected = TaskModuleSupport.categoryZoneSelection(
                step,
                zone
        );

        assertEquals(1, selected.bindings().size());
        assertEquals(selectedId, selected.bindings().getFirst().id());
        assertEquals(
                "CONTAINER_CONTENTS",
                selected.bindings().getFirst().ruleId()
        );
    }

    @Test
    void appliesRuleOverrideToTheWholeSelectedZone() {
        UUID zoneId = UUID.randomUUID();
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                zoneId,
                "Zone",
                List.of(
                        binding(UUID.randomUUID(), "DIRECT_BLOCK_ID"),
                        binding(UUID.randomUUID(), "DIRECT_BLOCK_ID")
                )
        );
        TaskStep step = TaskStep.action(
                UUID.randomUUID(),
                TaskModuleIds.RESOLVE_CONTAINER_CATEGORIES,
                Map.of(
                        TaskModuleIds.CATEGORY_RULE_ID,
                        "CONTAINER_CONTENTS"
                )
        );

        CategoryZoneDefinition selected = TaskModuleSupport.categoryZoneSelection(
                step,
                zone
        );

        assertEquals(2, selected.bindings().size());
        assertEquals(
                List.of("CONTAINER_CONTENTS", "CONTAINER_CONTENTS"),
                selected.bindings().stream()
                        .map(CategoryBindingDefinition::ruleId)
                        .toList()
        );
    }

    private static CategoryBindingDefinition binding(
            UUID id,
            String ruleId
    ) {
        return new CategoryBindingDefinition(
                id,
                new AreaBounds(0, 64, 0, 4, 64, 4),
                new AreaBounds(0, 64, 6, 4, 64, 6),
                ruleId
        );
    }
}
