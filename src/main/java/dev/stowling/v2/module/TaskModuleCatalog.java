package dev.stowling.v2.module;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ModuleDefinition;
import dev.stowling.v2.model.ModuleParameter;
import dev.stowling.v2.model.TaskModuleKind;
import dev.stowling.v2.model.TaskModuleLevel;
import dev.stowling.v2.model.TaskTriggerType;
import java.util.List;

public final class TaskModuleCatalog {
    private static final List<ModuleDefinition> MODULES = List.of(
            listener(
                    TaskTriggerType.DROPPED_ITEMS.name(),
                    "掉落物监听器",
                    "监听坐标范围内的掉落物并按优先级创建目标任务。",
                    List.of(
                            triggerTarget(),
                            triggerPriority(),
                            triggerInterval(),
                            triggerCooldown(),
                            reference(
                                    ConfigValidator.COORDINATE_ID,
                                    "检测坐标",
                                    "coordinate-reference",
                                    true
                            ),
                            text(ConfigValidator.ITEM_FILTER, "物品过滤", false)
                    )
            ),
            listener(
                    TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW.name(),
                    "饱食度监听器",
                    "饱食度达到阈值时按优先级创建目标任务。",
                    List.of(
                            triggerTarget(),
                            triggerPriority(),
                            triggerInterval(),
                            triggerCooldown(),
                            integer(
                                    ConfigValidator.FOOD_LEVEL_THRESHOLD,
                                    "饱食度阈值",
                                    true,
                                    0,
                                    20,
                                    "18"
                            )
                    )
            ),
            listener(
                    TaskTriggerType.CACHE_UPDATED.name(),
                    "缓存更新监听器",
                    "缓存区域收到物品后按优先级创建目标任务。",
                    List.of(
                            triggerTarget(),
                            triggerPriority(),
                            triggerInterval(),
                            triggerCooldown(),
                            reference(
                                    ConfigValidator.CACHE_REGION_ID,
                                    "缓存区域",
                                    "region-reference",
                                    true
                            )
                    )
            ),
            action(
                    ConfigValidator.MOVE_TO_COORDINATE,
                    "移动到坐标",
                    "移动到坐标或当前输出引用指向的位置。",
                    List.of(reference(
                            ConfigValidator.TARGET_REF,
                            "目标",
                            "coordinate-reference",
                            true
                    ))
            ),
            action(
                    ConfigValidator.COLLECT_DROPPED_ITEMS,
                    "收集掉落物",
                    "收集坐标半径或区域内的掉落物。",
                    List.of(
                            reference(
                                    ConfigValidator.AREA_REF,
                                    "收集范围",
                                    "area-reference",
                                    true
                            ),
                            text(ConfigValidator.ITEM_FILTER, "物品过滤", false),
                            integer(
                                    ConfigValidator.TIMEOUT_SECONDS,
                                    "超时秒数",
                                    false,
                                    0,
                                    86_400,
                                    null
                            )
                    )
            ),
            action(
                    ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                    "扫描区域容器",
                    "扫描区域内的容器并产生容器输出。",
                    List.of(
                            reference(
                                    ConfigValidator.REGION_REF,
                                    "扫描区域",
                                    "region-reference",
                                    true
                            ),
                            text(ConfigValidator.CONTAINER_TYPES, "容器类型", false),
                            integer(
                                    ConfigValidator.MAX_VOLUME,
                                    "最大扫描体积",
                                    false,
                                    1,
                                    1_048_576,
                                    null
                            )
                    )
            ),
            action(
                    ConfigValidator.RESOLVE_CONTAINER_CATEGORIES,
                    "解析容器分类",
                    "解析分类区并产生目标容器分类输出。",
                    List.of(reference(
                            ConfigValidator.CATEGORY_ZONE_REF,
                            "分类区",
                            "category-zone-reference",
                            true
                    ))
            ),
            action(
                    ConfigValidator.SELECT_CONTAINER,
                    "选择容器",
                    "从当前容器输出中选择一个容器。",
                    List.of(booleanParameter(
                            "resetCacheHasMore",
                            "重置剩余物品状态",
                            false
                    ))
            ),
            action(
                    ConfigValidator.WITHDRAW_ITEMS,
                    "取出物品",
                    "从目标容器取出符合来源条件的物品。",
                    List.of(
                            reference(
                                    ConfigValidator.TARGET_REF,
                                    "目标容器",
                                    "container-reference",
                                    true
                            ),
                            select(
                                    ConfigValidator.ITEM_SOURCE,
                                    "物品来源",
                                    true,
                                    List.of("classified")
                            )
                    )
            ),
            action(
                    ConfigValidator.SELECT_STORAGE_TARGET,
                    "选择储存目标",
                    "从分类输出中选择一个储存容器。",
                    List.of()
            ),
            action(
                    ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                    "存入物品",
                    "把指定来源的物品存入目标容器。",
                    List.of(
                            reference(
                                    ConfigValidator.TARGET_REF,
                                    "目标容器",
                                    "container-reference",
                                    true
                            ),
                            select(
                                    ConfigValidator.ITEM_SOURCE,
                                    "物品来源",
                                    true,
                                    List.of("collected", "carried", "managed")
                            )
                    )
            ),
            action(
                    ConfigValidator.WITHDRAW_FOOD,
                    "取出食物",
                    "从食物箱取出一个指定食物。",
                    List.of(
                            reference(
                                    ConfigValidator.TARGET_REF,
                                    "食物箱",
                                    "coordinate-reference",
                                    true
                            ),
                            text(ConfigValidator.FOOD_ITEM_ID, "食物 ID", true)
                    )
            ),
            action(
                    ConfigValidator.EAT_FOOD,
                    "进食",
                    "食用物品栏中的指定食物。",
                    List.of(text(
                            ConfigValidator.FOOD_ITEM_ID,
                            "食物 ID",
                            true
                    ))
            ),
            action(
                    ConfigValidator.SCAN_CATEGORY_MARKERS,
                    "扫描分类标记",
                    "扫描分类区中的标记容器并产生标记输出。",
                    List.of(
                            reference(
                                    ConfigValidator.CATEGORY_ZONE_REF,
                                    "分类区",
                                    "category-zone-reference",
                                    true
                            ),
                            integer(
                                    ConfigValidator.MAX_VOLUME,
                                    "最大扫描体积",
                                    false,
                                    1,
                                    1_048_576,
                                    null
                            )
                    )
            ),
            action(
                    ConfigValidator.SELECT_MARKER_CONTAINER,
                    "选择标记容器",
                    "从标记输出中选择一个容器。",
                    List.of()
            ),
            action(
                    ConfigValidator.READ_CONTAINER,
                    "读取容器",
                    "读取当前容器引用的内容。",
                    List.of(reference(
                            ConfigValidator.TARGET_REF,
                            "目标容器",
                            "container-reference",
                            true
                    ))
            ),
            action(
                    ConfigValidator.SAVE_CATEGORY_MARKER_CACHE,
                    "保存分类标记缓存",
                    "保存本次读取的分类标记内容。",
                    List.of(reference(
                            ConfigValidator.CATEGORY_ZONE_REF,
                            "分类区",
                            "category-zone-reference",
                            true
                    ))
            ),
            action(
                    ConfigValidator.ASSERT_NO_MANAGED_ITEMS,
                    "确认无托管物品",
                    "确认没有遗留的移动中物品。",
                    List.of()
            )
    );

    private TaskModuleCatalog() {
    }

    public static List<ModuleDefinition> modules() {
        return MODULES;
    }

    private static ModuleDefinition listener(
            String id,
            String name,
            String description,
            List<ModuleParameter> parameters
    ) {
        return new ModuleDefinition(
                id,
                name,
                description,
                TaskModuleKind.LISTENER,
                TaskModuleLevel.BASIC,
                parameters,
                false,
                List.of()
        );
    }

    private static ModuleDefinition action(
            String id,
            String name,
            String description,
            List<ModuleParameter> parameters
    ) {
        return new ModuleDefinition(
                id,
                name,
                description,
                TaskModuleKind.ACTION,
                TaskModuleLevel.BASIC,
                parameters,
                false,
                List.of()
        );
    }

    private static ModuleParameter triggerTarget() {
        return reference(
                "targetTaskId",
                "目标任务",
                "task-reference",
                true
        );
    }

    private static ModuleParameter triggerPriority() {
        return select(
                ConfigValidator.PRIORITY,
                "创建优先级",
                true,
                List.of("BACKGROUND", "NORMAL", "URGENT")
        );
    }

    private static ModuleParameter triggerInterval() {
        return integer(
                "intervalTicks",
                "检测间隔",
                true,
                1,
                1_200,
                "20"
        );
    }

    private static ModuleParameter triggerCooldown() {
        return integer(
                "cooldownTicks",
                "冷却时间",
                true,
                0,
                86_400,
                "100"
        );
    }

    private static ModuleParameter reference(
            String key,
            String label,
            String kind,
            boolean required
    ) {
        return new ModuleParameter(key, label, kind, required);
    }

    private static ModuleParameter text(
            String key,
            String label,
            boolean required
    ) {
        return new ModuleParameter(key, label, "text", required);
    }

    private static ModuleParameter integer(
            String key,
            String label,
            boolean required,
            int minimum,
            int maximum,
            String defaultValue
    ) {
        return new ModuleParameter(
                key,
                label,
                "integer",
                required,
                List.of(),
                minimum,
                maximum,
                1,
                defaultValue
        );
    }

    private static ModuleParameter select(
            String key,
            String label,
            boolean required,
            List<String> options
    ) {
        return new ModuleParameter(key, label, "select", required, options);
    }

    private static ModuleParameter booleanParameter(
            String key,
            String label,
            boolean required
    ) {
        return new ModuleParameter(key, label, "boolean", required);
    }
}
