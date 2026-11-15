package dev.stowling.v2.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ModuleDefinition;
import dev.stowling.v2.model.ModuleParameter;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskModuleCatalogTest {
    @Test
    void exposesOnlyMoveToCoordinateWithItsParameterContract() {
        List<ModuleDefinition> modules = TaskModuleCatalog.modules();

        assertEquals(1, modules.size());
        ModuleDefinition module = modules.getFirst();
        assertEquals(ConfigValidator.MOVE_TO_COORDINATE, module.id());
        assertEquals(1, module.parameters().size());
        ModuleParameter parameter = module.parameters().getFirst();
        assertEquals(ConfigValidator.COORDINATE_ID, parameter.key());
        assertEquals("coordinate-point", parameter.kind());
        assertTrue(parameter.required());
        assertTrue(parameter.options().isEmpty());
    }
}
