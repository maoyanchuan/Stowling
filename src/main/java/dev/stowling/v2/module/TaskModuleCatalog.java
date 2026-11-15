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

    private static final ModuleDefinition WAIT_FOR_DROPPED_ITEMS =
            new ModuleDefinition(
                    ConfigValidator.WAIT_FOR_DROPPED_ITEMS,
                    "等待掉落物",
                    "等待坐标搜索半径内出现掉落物。",
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.COORDINATE_ID,
                                    "监测坐标",
                                    "coordinate-point",
                                    true
                            ),
                            new ModuleParameter(
                                    ConfigValidator.ITEM_FILTER,
                                    "物品过滤（逗号分隔）",
                                    "text",
                                    false
                            ),
                            new ModuleParameter(
                                    ConfigValidator.TIMEOUT_SECONDS,
                                    "超时秒数",
                                    "integer",
                                    false,
                                    List.of(),
                                    0,
                                    86_400,
                                    1,
                                    null
                            )
                    )
            );

    private static final ModuleDefinition COLLECT_DROPPED_ITEMS =
            new ModuleDefinition(
                    ConfigValidator.COLLECT_DROPPED_ITEMS,
                    "拾取掉落物",
                    "前往坐标半径内的掉落物并等待其被拾取。",
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.COORDINATE_ID,
                                    "拾取坐标",
                                    "coordinate-point",
                                    true
                            ),
                            new ModuleParameter(
                                    ConfigValidator.ITEM_FILTER,
                                    "物品过滤（逗号分隔）",
                                    "text",
                                    false
                            ),
                            new ModuleParameter(
                                    ConfigValidator.TIMEOUT_SECONDS,
                                    "超时秒数",
                                    "integer",
                                    false,
                                    List.of(),
                                    0,
                                    86_400,
                                    1,
                                    null
                            )
                    )
            );

    private static final ModuleDefinition SCAN_CONTAINERS_IN_REGION =
            new ModuleDefinition(
                    ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                    "扫描区域容器",
                    "扫描指定区域内的容器并生成容器列表。",
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.REGION_ID,
                                    "扫描区域",
                                    "region-point",
                                    true
                            ),
                            new ModuleParameter(
                                    ConfigValidator.CONTAINER_TYPES,
                                    "容器类型（逗号分隔）",
                                    "text",
                                    false
                            ),
                            new ModuleParameter(
                                    ConfigValidator.MAX_VOLUME,
                                    "最大扫描体积",
                                    "integer",
                                    false,
                                    List.of(),
                                    1,
                                    1_048_576,
                                    1,
                                    null
                            )
                    )
            );

    private static final ModuleDefinition DEPOSIT_ITEMS_TO_CONTAINERS =
            new ModuleDefinition(
                    ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                    "存入区域容器",
                    "使用上一次容器扫描结果存入本次拾取的物品。",
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.SCAN_SOURCE,
                                    "扫描结果",
                                    "select",
                                    true,
                                    List.of("previous-scan")
                            ),
                            new ModuleParameter(
                                    ConfigValidator.ITEM_SOURCE,
                                    "物品来源",
                                    "select",
                                    true,
                                    List.of("collected-this-run")
                            ),
                            new ModuleParameter(
                                    ConfigValidator.FILL_MODE,
                                    "容器顺序",
                                    "select",
                                    true,
                                    List.of("nearest-first")
                            )
                    )
            );

    private static final List<ModuleDefinition> MODULES =
            List.of(
                    MOVE_TO_COORDINATE,
                    WAIT_FOR_DROPPED_ITEMS,
                    COLLECT_DROPPED_ITEMS,
                    SCAN_CONTAINERS_IN_REGION,
                    DEPOSIT_ITEMS_TO_CONTAINERS
            );

    private TaskModuleCatalog() {
    }

    public static List<ModuleDefinition> modules() {
        return MODULES;
    }
}
