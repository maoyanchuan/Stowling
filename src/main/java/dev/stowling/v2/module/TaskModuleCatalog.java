package dev.stowling.v2.module;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ModuleDefinition;
import dev.stowling.v2.model.ModuleParameter;
import java.util.List;

public final class TaskModuleCatalog {
    private static final ModuleDefinition MOVE_TO_COORDINATE =
            new ModuleDefinition(
                    ConfigValidator.MOVE_TO_COORDINATE,
                    "移动到坐标",
                    "使用 Baritone 前往一个已保存的坐标点。",
                    List.of(new ModuleParameter(
                            ConfigValidator.COORDINATE_ID,
                            "目标坐标",
                            "coordinate-point",
                            true
                    ))
            );

    private static final List<ModuleDefinition> MODULES =
            List.of(MOVE_TO_COORDINATE);

    private TaskModuleCatalog() {
    }

    public static List<ModuleDefinition> modules() {
        return MODULES;
    }
}
