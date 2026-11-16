package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.FoodStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskStep;
import java.util.Map;
import java.util.UUID;

final class AutoEatAtCoordinateModule implements TaskModuleExecutor {
    private static final int DEFAULT_ARRIVAL_RADIUS = 2;

    private final TaskExecutionContext context;
    private final CoordinatePoint coordinate;
    private final int arrivalRadius;
    private final String foodItemId;
    private final int foodLevelThreshold;
    private final FoodStatusListener foodListener;
    private MoveToCoordinateModule movement;
    private FoodAcquisitionAction acquisition;
    private EatFoodModule eating;
    private boolean movementStarted;
    private boolean acquisitionStarted;
    private boolean arrived;

    AutoEatAtCoordinateModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        this.coordinate = TaskModuleSupport.coordinate(context, step);
        this.arrivalRadius = coordinate.radius() == null
                ? DEFAULT_ARRIVAL_RADIUS
                : coordinate.radius();
        this.foodItemId = step.parameters()
                .get(ConfigValidator.FOOD_ITEM_ID)
                .trim();
        this.foodLevelThreshold = TaskModuleSupport.integerParameter(
                step,
                ConfigValidator.FOOD_LEVEL_THRESHOLD,
                18
        );
        this.foodListener = new FoodStatusListener(context);
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        FoodStatus food = foodListener.observe();
        if (!food.connected()) {
            return TaskModuleResult.failed("无法读取当前饱食度");
        }
        if (food.foodLevel() >= foodLevelThreshold) {
            return complete("饱食度充足，无需进食");
        }
        TaskModuleResult movementResult = ensureArrived();
        if (movementResult != null) {
            return movementResult;
        }
        if (eating != null) {
            TaskModuleResult result = eating.tick();
            if (result.state() == TaskModuleState.SUCCEEDED) {
                eating = null;
                return TaskModuleResult.running(
                        "已完成一次进食，正在检查饱食度"
                );
            }
            return result;
        }
        return acquireFood();
    }

    @Override
    public void cancel() {
        if (acquisition != null) {
            acquisition.cancel();
        }
        if (eating != null) {
            eating.cancel();
        }
        if (movement != null) {
            movement.cancel();
        }
    }

    private TaskModuleResult ensureArrived() {
        if (arrived) {
            return null;
        }
        if (movement == null) {
            movement = new MoveToCoordinateModule(
                    context,
                    moveStep(),
                    arrivalRadius
            );
        }
        TaskModuleResult result = movementStarted
                ? movement.tick()
                : movement.start();
        movementStarted = true;
        if (result.state() == TaskModuleState.SUCCEEDED) {
            movement = null;
            movementStarted = false;
            arrived = true;
            return null;
        }
        return result;
    }

    private TaskModuleResult acquireFood() {
        if (acquisition == null) {
            acquisition = new FoodAcquisitionAction(
                    context,
                    new BlockPosition(
                            coordinate.x(),
                            coordinate.y(),
                            coordinate.z()
                    ),
                    foodItemId
            );
            acquisitionStarted = false;
        }
        TaskModuleResult result = acquisitionStarted
                ? acquisition.tick()
                : acquisition.start();
        acquisitionStarted = true;
        if (result.state() == TaskModuleState.SUCCEEDED) {
            acquisition = null;
            acquisitionStarted = false;
            return startEating();
        }
        return result;
    }

    private TaskModuleResult startEating() {
        eating = new EatFoodModule(context, eatStep());
        TaskModuleResult result = eating.start();
        if (result.state() == TaskModuleState.SUCCEEDED) {
            eating = null;
            return TaskModuleResult.running(
                    "已完成一次进食，正在检查饱食度"
            );
        }
        return result;
    }

    private TaskModuleResult complete(String message) {
        if (acquisition != null) {
            acquisition.cancel();
            acquisition = null;
        }
        if (eating != null) {
            eating.cancel();
            eating = null;
        }
        if (movement != null) {
            movement.cancel();
            movement = null;
        }
        return TaskModuleResult.succeeded(message);
    }

    private TaskStep moveStep() {
        return new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.COORDINATE_ID,
                        coordinate.id().toString()
                )
        );
    }

    private TaskStep eatStep() {
        return new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.EAT_FOOD,
                Map.of(ConfigValidator.FOOD_ITEM_ID, foodItemId)
        );
    }
}
