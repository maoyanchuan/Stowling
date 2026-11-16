package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.v2.model.TaskStep;
import java.util.Map;

final class EatFoodModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final String foodItemId;
    private boolean eatingActive;
    private boolean stopRequested;

    EatFoodModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.foodItemId = ResourceReferenceSupport.parameter(
                step,
                TaskModuleIds.FOOD_ITEM_ID
        );
    }

    @Override
    public TaskModuleResult start() {
        eatingActive = true;
        return handleResult(
                context.foodConsumption().startEating(foodItemId)
        );
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
        if (!eatingActive) {
            context.foodConsumption().cancelEating();
        }
    }

    private TaskModuleResult handleResult(FoodConsumptionResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        eatingActive = false;
        if (!result.items().isEmpty()) {
            for (Map.Entry<String, Integer> entry : result.items().entrySet()) {
                context.workspace().consumeManaged(
                        entry.getKey(),
                        entry.getValue()
                );
            }
        } else if (result.state() == ContainerTransferState.SUCCEEDED) {
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
