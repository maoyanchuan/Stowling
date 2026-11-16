package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.domain.BlockPosition;

final class FoodAcquisitionAction {
    private final TaskExecutionContext context;
    private final String foodItemId;
    private final ContainerTarget target;
    private boolean active;

    FoodAcquisitionAction(
            TaskExecutionContext context,
            BlockPosition position,
            String foodItemId
    ) {
        this.context = context;
        this.foodItemId = foodItemId;
        this.target = new ContainerTarget(position, "food-container");
    }

    TaskModuleResult start() {
        active = true;
        return handleResult(
                context.foodConsumption().startAcquiringFood(
                        target,
                        foodItemId
                )
        );
    }

    TaskModuleResult tick() {
        if (!active) {
            return TaskModuleResult.failed("食物获取尚未开始");
        }
        return handleResult(
                context.foodConsumption().pollAcquiringFood()
        );
    }

    void cancel() {
        context.foodConsumption().cancelAcquiringFood();
    }

    private TaskModuleResult handleResult(FoodConsumptionResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        active = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        return TaskModuleResult.succeeded(result.message());
    }
}
