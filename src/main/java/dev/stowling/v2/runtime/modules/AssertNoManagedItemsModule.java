package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.TaskStep;
import java.util.Map;

final class AssertNoManagedItemsModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final String itemSource;

    AssertNoManagedItemsModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.itemSource = itemSource(step);
    }

    @Override
    public TaskModuleResult start() {
        Map<String, Integer> items = context.workspace().itemsForSource(
                itemSource
        );
        if (items.isEmpty()) {
            return TaskModuleResult.succeeded("任务物品已全部处理");
        }
        return TaskModuleResult.failed(
                "任务结束仍有" + itemLabel() + "物品: " + items
        );
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("assertion already completed");
    }

    @Override
    public void cancel() {
    }

    private String itemLabel() {
        return switch (itemSource) {
            case "collected" -> "已收集";
            case "carried" -> "已取出";
            default -> "受管";
        };
    }

    private static String itemSource(TaskStep step) {
        String source = ResourceReferenceSupport.optionalParameter(
                step,
                TaskModuleIds.ITEM_SOURCE
        );
        if (source == null) {
            return "managed";
        }
        return switch (source) {
            case "collected", "carried", "managed" -> source;
            default -> throw new IllegalArgumentException(
                    "unsupported item source: " + source
            );
        };
    }
}
