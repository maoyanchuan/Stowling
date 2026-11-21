package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.Map;

final class WithdrawFoodModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final ContainerTarget target;
    private final String foodItemId;
    private final boolean reserveFood;
    private boolean started;
    private boolean inFlight;

    WithdrawFoodModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        ResourceReference reference = ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.TARGET_REF,
                ResourceType.COORDINATE,
                TaskModuleIds.COORDINATE_ID
        );
        CoordinatePoint coordinate = ResourceReferenceSupport.coordinate(
                context,
                reference
        );
        this.target = new ContainerTarget(
                new BlockPosition(
                        coordinate.x(),
                        coordinate.y(),
                        coordinate.z()
                ),
                "food-container"
        );
        this.foodItemId = ResourceReferenceSupport.parameter(
                step,
                TaskModuleIds.FOOD_ITEM_ID
        );
        reserveFood = Boolean.parseBoolean(step.parameters().get(TaskModuleIds.RESERVE_FOOD));
    }

    @Override
    public TaskModuleResult start() {
        if (started) {
            return tick();
        }
        started = true;
        inFlight = true;
        return handle(context.foodConsumption().startAcquiringFood(
                target,
                foodItemId,
                reserveFood
        ));
    }

    @Override
    public TaskModuleResult tick() {
        if (!started) {
            return start();
        }
        if (!inFlight) {
            return TaskModuleResult.succeeded("食物获取已完成");
        }
        return handle(context.foodConsumption().pollAcquiringFood());
    }

    @Override
    public void requestStop() {
    }

    @Override
    public boolean safeToStop() {
        return !inFlight;
    }

    @Override
    public void cancel() {
        if (inFlight) {
            context.foodConsumption().cancelAcquiringFood();
        }
        inFlight = false;
    }

    private TaskModuleResult handle(FoodConsumptionResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        inFlight = false;
        if (reserveFood) {
            context.inventory().reserveAcquiredFood(result.items());
            return result.state() == ContainerTransferState.FAILED
                    ? TaskModuleResult.failed(result.message())
                    : TaskModuleResult.succeeded(result.message());
        }
        for (Map.Entry<String, Integer> entry : result.items().entrySet()) {
            context.workspace().recordManagedAcquired(
                    entry.getKey(),
                    entry.getValue()
            );
        }
        return result.state() == ContainerTransferState.FAILED
                ? TaskModuleResult.failed(result.message())
                : TaskModuleResult.succeeded(result.message());
    }
}
