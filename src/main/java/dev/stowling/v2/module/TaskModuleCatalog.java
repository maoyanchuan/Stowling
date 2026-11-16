package dev.stowling.v2.module;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ModuleDefinition;
import dev.stowling.v2.model.ModuleParameter;
import dev.stowling.v2.model.TaskModuleKind;
import dev.stowling.v2.model.TaskModuleLevel;
import dev.stowling.v2.model.TaskTriggerType;
import java.util.List;

public final class TaskModuleCatalog {
    private static final ModuleDefinition DROPPED_ITEMS_LISTENER =
            new ModuleDefinition(
                    "DROPPED_ITEMS_LISTENER",
                    "掉落物监听器",
                    "持续观察坐标半径内的掉落物，为掉落物触发器提供事件。",
                    TaskModuleKind.LISTENER,
                    TaskModuleLevel.BASIC,
                    List.of(),
                    false
            );

    private static final ModuleDefinition FOOD_STATUS_LISTENER =
            new ModuleDefinition(
                    "FOOD_STATUS_LISTENER",
                    "饱食度监听器",
                    "读取玩家当前饱食度，为饱食度触发器提供状态。",
                    TaskModuleKind.LISTENER,
                    TaskModuleLevel.BASIC,
                    List.of(),
                    false
            );

    private static final ModuleDefinition CACHE_UPDATE_LISTENER =
            new ModuleDefinition(
                    "CACHE_UPDATE_LISTENER",
                    "缓存更新监听器",
                    "接收本次任务运行中实际存入缓存区域的事件。",
                    TaskModuleKind.LISTENER,
                    TaskModuleLevel.BASIC,
                    List.of(),
                    false
            );

    private static final ModuleDefinition DROPPED_ITEMS_TRIGGER =
            new ModuleDefinition(
                    TaskTriggerType.DROPPED_ITEMS.name(),
                    "掉落物出现触发",
                    "使用掉落物监听器；命中后按优先级启动目标任务。",
                    TaskModuleKind.TRIGGER,
                    TaskModuleLevel.BASIC,
                    List.of(
                            triggerTargetParameter(),
                            triggerPriorityParameter(),
                            triggerIntervalParameter("检测间隔（tick）"),
                            triggerCooldownParameter(),
                            new ModuleParameter(
                                    ConfigValidator.COORDINATE_ID,
                                    "检测坐标",
                                    "coordinate-point",
                                    true
                            ),
                            new ModuleParameter(
                                    ConfigValidator.ITEM_FILTER,
                                    "物品过滤",
                                    "text",
                                    false
                            )
                    ),
                    false
            );

    private static final ModuleDefinition FOOD_LEVEL_TRIGGER =
            new ModuleDefinition(
                    TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW.name(),
                    "饱食度不足触发",
                    "使用饱食度监听器；达到阈值后按优先级启动目标任务。",
                    TaskModuleKind.TRIGGER,
                    TaskModuleLevel.BASIC,
                    List.of(
                            triggerTargetParameter(),
                            triggerPriorityParameter(),
                            triggerIntervalParameter("检测间隔（tick）"),
                            triggerCooldownParameter(),
                            new ModuleParameter(
                                    ConfigValidator.FOOD_LEVEL_THRESHOLD,
                                    "饱食度阈值",
                                    "integer",
                                    true,
                                    List.of(),
                                    0,
                                    20,
                                    1,
                                    "18"
                            )
                    ),
                    false
            );

    private static final ModuleDefinition CACHE_UPDATED_TRIGGER =
            new ModuleDefinition(
                    TaskTriggerType.CACHE_UPDATED.name(),
                    "缓存更新触发",
                    "使用缓存更新监听器；防抖后按优先级启动目标任务。",
                    TaskModuleKind.TRIGGER,
                    TaskModuleLevel.BASIC,
                    List.of(
                            triggerTargetParameter(),
                            triggerPriorityParameter(),
                            triggerIntervalParameter("触发防抖（tick）"),
                            triggerCooldownParameter(),
                            new ModuleParameter(
                                    ConfigValidator.CACHE_REGION_ID,
                                    "缓存区域",
                                    "region-point",
                                    true
                            )
                    ),
                    false
            );

    private static ModuleParameter triggerTargetParameter() {
        return new ModuleParameter(
                "targetTaskId",
                "目标任务",
                "task-point",
                true
        );
    }

    private static ModuleParameter triggerPriorityParameter() {
        return new ModuleParameter(
                "priority",
                "触发优先级",
                "select",
                true,
                List.of("BACKGROUND", "NORMAL", "URGENT")
        );
    }

    private static ModuleParameter triggerIntervalParameter(String label) {
        return new ModuleParameter(
                "intervalTicks",
                label,
                "integer",
                true,
                List.of(),
                1,
                1_200,
                1,
                "20"
        );
    }

    private static ModuleParameter triggerCooldownParameter() {
        return new ModuleParameter(
                "cooldownTicks",
                "完成冷却（tick）",
                "integer",
                false,
                List.of(),
                0,
                72_000,
                1,
                "100"
        );
    }

    private static final ModuleDefinition MOVE_TO_COORDINATE =
            new ModuleDefinition(
                    ConfigValidator.MOVE_TO_COORDINATE,
                    "移动到坐标",
                    "使用 Baritone 前往一个已保存的坐标点。",
                    TaskModuleLevel.BASIC,
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
                    TaskModuleLevel.BASIC,
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
                    ),
                    true
            );

    private static final ModuleDefinition COLLECT_DROPPED_ITEMS =
            new ModuleDefinition(
                    ConfigValidator.COLLECT_DROPPED_ITEMS,
                    "收集掉落物",
                    "在坐标半径或指定区域内拾取掉落物。",
                    TaskModuleLevel.COMPOSITE,
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.AREA_REFERENCE,
                                    "收集范围",
                                    "area-reference",
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

    private static final ModuleDefinition COLLECT_DROPPED_ITEMS_IN_REGION =
            new ModuleDefinition(
                    ConfigValidator.COLLECT_DROPPED_ITEMS_IN_REGION,
                    "收集区域掉落物",
                    "收集指定区域内的掉落物，并记录到当前任务运行中。",
                    TaskModuleLevel.COMPOSITE,
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.REGION_ID,
                                    "掉落物区域",
                                    "region-point",
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
                    ),
                    true
            );

    private static final ModuleDefinition SCAN_CONTAINERS_IN_REGION =
            new ModuleDefinition(
                    ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                    "扫描区域容器",
                    "扫描指定区域内的容器并生成容器列表。",
                    TaskModuleLevel.BASIC,
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
                    "把当前任务拾取的物品存入上一次扫描到的容器。",
                    TaskModuleLevel.BASIC,
                    List.of()
            );

    private static final ModuleDefinition MATCH_CONTAINER_CATEGORIES =
            new ModuleDefinition(
                    ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                    "识别容器分类",
                    "读取上一次扫描结果，并按附近展示框中的物品标记箱子的分类。",
                    TaskModuleLevel.BASIC,
                    List.of(new ModuleParameter(
                            ConfigValidator.SCAN_SOURCE,
                            "扫描结果",
                            "select",
                            true,
                            List.of("previous-scan")
                    )),
                    true
            );

    private static final ModuleDefinition AUTO_EAT_AT_COORDINATE =
            new ModuleDefinition(
                    ConfigValidator.AUTO_EAT_AT_COORDINATE,
                    "自动进食",
                    "饱食度低于阈值时前往食物箱，每次取出一个食物并吃完。",
                    TaskModuleLevel.COMPOSITE,
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.COORDINATE_ID,
                                    "食物箱子坐标",
                                    "coordinate-point",
                                    true
                            ),
                            new ModuleParameter(
                                    ConfigValidator.FOOD_ITEM_ID,
                                    "食物 ID",
                                    "text",
                                    true
                            ),
                            new ModuleParameter(
                                    ConfigValidator.FOOD_LEVEL_THRESHOLD,
                                    "饱食度低于此值时进食",
                                    "integer",
                                    false,
                                    List.of(),
                                    1,
                                    20,
                                    1,
                                    "18"
                            )
                    )
            );

    private static final ModuleDefinition SORT_CACHE_ITEMS =
            new ModuleDefinition(
                    ConfigValidator.SORT_CACHE_ITEMS,
                    "缓存物品分类入库",
                    "从缓存区域取出有目标分类的物品，并精确存入对应容器。",
                    TaskModuleLevel.COMPOSITE,
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.CACHE_REGION_ID,
                                    "缓存区域",
                                    "region-point",
                                    true
                            ),
                            new ModuleParameter(
                                    ConfigValidator.SCAN_SOURCE,
                                    "目标分类扫描",
                                    "select",
                                    true,
                                    List.of("previous-scan")
                            ),
                            new ModuleParameter(
                                    ConfigValidator.CONTAINER_TYPES,
                                    "缓存容器类型（逗号分隔）",
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
                    ),
                    true
            );

    private static final ModuleDefinition SORT_CACHE_ITEMS_BY_CATEGORY_ZONE =
            new ModuleDefinition(
                    ConfigValidator.SORT_CACHE_ITEMS_BY_CATEGORY_ZONE,
                    "按分类区入库",
                    "使用分类区自动识别、容器缓存和例外规则，把缓存物品存入"
                            + "对应储存区。",
                    TaskModuleLevel.COMPOSITE,
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.CACHE_REGION_ID,
                                    "缓存区域",
                                    "region-point",
                                    true
                            ),
                            new ModuleParameter(
                                    ConfigValidator.CATEGORY_ZONE_ID,
                                    "分类区",
                                    "category-zone-point",
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

    private static final ModuleDefinition REFRESH_CATEGORY_MARKER_CACHE =
            new ModuleDefinition(
                    ConfigValidator.REFRESH_CATEGORY_MARKER_CACHE,
                    "刷新分类容器缓存",
                    "读取分类区识别箱或木桶的物品槽位并保存缓存，不移动样本物品。",
                    TaskModuleLevel.COMPOSITE,
                    List.of(
                            new ModuleParameter(
                                    ConfigValidator.CATEGORY_ZONE_ID,
                                    "分类区",
                                    "category-zone-point",
                                    true
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

    private static final List<ModuleDefinition> MODULES =
            List.of(
                    DROPPED_ITEMS_LISTENER,
                    FOOD_STATUS_LISTENER,
                    CACHE_UPDATE_LISTENER,
                    DROPPED_ITEMS_TRIGGER,
                    FOOD_LEVEL_TRIGGER,
                    CACHE_UPDATED_TRIGGER,
                    MOVE_TO_COORDINATE,
                    COLLECT_DROPPED_ITEMS,
                    SCAN_CONTAINERS_IN_REGION,
                    DEPOSIT_ITEMS_TO_CONTAINERS,
                    AUTO_EAT_AT_COORDINATE,
                    SORT_CACHE_ITEMS_BY_CATEGORY_ZONE,
                    REFRESH_CATEGORY_MARKER_CACHE,
                    WAIT_FOR_DROPPED_ITEMS,
                    COLLECT_DROPPED_ITEMS_IN_REGION,
                    MATCH_CONTAINER_CATEGORIES,
                    SORT_CACHE_ITEMS
            );

    private TaskModuleCatalog() {
    }

    public static List<ModuleDefinition> modules() {
        return MODULES;
    }
}
