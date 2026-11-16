package dev.stowling.v2.runtime.modules;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskStep;

final class WithdrawFoodModule implements TaskModuleExecutor {
    private final FoodAcquisitionAction acquisition;

    WithdrawFoodModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        CoordinatePoint coordinate = TaskModuleSupport.coordinate(
                context,
                step
        );
        String foodItemId = step.parameters()
                .get(ConfigValidator.FOOD_ITEM_ID)
                .trim();
        acquisition = new FoodAcquisitionAction(
                context,
                new BlockPosition(
                        coordinate.x(),
                        coordinate.y(),
                        coordinate.z()
                ),
                foodItemId
        );
    }

    @Override
    public TaskModuleResult start() {
        return acquisition.start();
    }

    @Override
    public TaskModuleResult tick() {
        return acquisition.tick();
    }

    @Override
    public void cancel() {
        acquisition.cancel();
    }
}
