package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.TaskStep;

final class EatFoodModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final String foodItemId;
    private boolean eatingActive;

    EatFoodModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.foodItemId = step.parameters()
                .get(ConfigValidator.FOOD_ITEM_ID)
                .trim();
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
    public void cancel() {
        context.foodConsumption().cancelEating();
    }

    private TaskModuleResult handleResult(FoodConsumptionResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        eatingActive = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        return TaskModuleResult.succeeded(result.message());
    }
}
