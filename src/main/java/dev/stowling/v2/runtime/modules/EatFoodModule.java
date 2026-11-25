package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.v2.model.TaskStep;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class EatFoodModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final String foodItemId;
    private final String itemSource;
    private final boolean worldUseGuardEnabled;
    private boolean eatingActive;
    private boolean stopRequested;

    EatFoodModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.foodItemId = ResourceReferenceSupport.parameter(
                step,
                TaskModuleIds.FOOD_ITEM_ID
        );
        itemSource = step.parameters().get(TaskModuleIds.ITEM_SOURCE);
        worldUseGuardEnabled = Boolean.parseBoolean(step.parameters().get(
                dev.stowling.v2.model.AutoEatOptions.WORLD_USE_GUARD));
    }

    @Override
    public TaskModuleResult start() {
        eatingActive = true;
        if (itemSource == null) {
            return handleResult(context.foodConsumption().startEating(foodItemId));
        }
        boolean protectedFood = "protected".equals(itemSource);
        Set<String> allowed = protectedFood
                ? context.inventory().availableProtectedFoodKeys(foodItemId)
                : context.workspace().managedItems().keySet().stream()
                        .filter(key -> foodItemId.equals(ItemIdentity.base(key)))
                        .collect(Collectors.toUnmodifiableSet());
        if (allowed.isEmpty()) {
            eatingActive = false;
            return TaskModuleResult.failed(protectedFood ? "背包没有锁定的指定食物: " + foodItemId
                    : "本次任务没有从食物箱取到指定食物: " + foodItemId);
        }
        return handleResult(worldUseGuardEnabled
                ? context.foodConsumption().startEating(foodItemId, allowed, true)
                : context.foodConsumption().startEating(foodItemId, allowed));
    }

    @Override
    public TaskModuleResult tick() {
        if (!eatingActive) {
            return TaskModuleResult.failed("进食尚未开始");
        }
        return handleResult(context.foodConsumption().pollEating());
    }

    @Override
    public void requestStop() {
        stopRequested = true;
    }

    @Override
    public boolean safeToStop() {
        return !eatingActive;
    }

    @Override
    public void cancel() {
        if (eatingActive) {
            context.foodConsumption().cancelEating();
            eatingActive = false;
        }
    }

    private TaskModuleResult handleResult(FoodConsumptionResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        eatingActive = false;
        if (!result.items().isEmpty()) {
            for (Map.Entry<String, Integer> entry : result.items().entrySet()) {
                if ("protected".equals(itemSource)) {
                    context.inventory().consumeProtectedFood(entry.getKey(), entry.getValue());
                } else {
                    context.workspace().consumeManaged(entry.getKey(), entry.getValue());
                }
            }
        } else if (itemSource == null && result.state() == ContainerTransferState.SUCCEEDED) {
            context.workspace().consumeManaged(foodItemId, 1);
        }
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        return TaskModuleResult.succeeded(
                stopRequested ? "进食已确认，可安全停止" : result.message()
        );
    }
}
