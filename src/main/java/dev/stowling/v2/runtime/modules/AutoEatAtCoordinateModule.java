package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.TaskStep;

final class AutoEatAtCoordinateModule implements TaskModuleExecutor {
    private static final int DEFAULT_ARRIVAL_RADIUS = 2;

    private final TaskExecutionContext context;
    private final CoordinatePoint coordinate;
    private final BlockPosition position;
    private final int arrivalRadius;
    private final String foodItemId;
    private final int foodLevelThreshold;
    private boolean navigationStarted;
    private boolean acquiringActive;
    private boolean foodReadyToEat;
    private boolean eatingActive;

    AutoEatAtCoordinateModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        this.coordinate = TaskModuleSupport.coordinate(context, step);
        this.position = new BlockPosition(
                coordinate.x(),
                coordinate.y(),
                coordinate.z()
        );
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
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        FoodStatus food = context.foodConsumption().observe();
        if (!food.connected()) {
            return TaskModuleResult.failed("无法读取当前饱食度");
        }
        if (food.foodLevel() >= foodLevelThreshold) {
            if (acquiringActive) {
                context.foodConsumption().cancelAcquiringFood();
                acquiringActive = false;
            }
            if (eatingActive) {
                context.foodConsumption().cancelEating();
                eatingActive = false;
            }
            if (navigationStarted) {
                context.navigation().cancel();
            }
            return TaskModuleResult.succeeded(
                    "饱食度充足，无需进食"
            );
        }
        if (!TaskModuleSupport.dimensionMatches(context, coordinate)) {
            return TaskModuleResult.failed("食物箱坐标维度不匹配");
        }
        NavigationStatus navigation = navigate();
        if (navigation == NavigationStatus.FAILED) {
            return TaskModuleResult.failed(
                    "无法前往食物箱 " + coordinate.name()
            );
        }
        if (navigation != NavigationStatus.ARRIVED) {
            return TaskModuleResult.waiting(
                    "正在前往食物箱 " + coordinate.name()
            );
        }
        if (acquiringActive) {
            FoodConsumptionResult result =
                    context.foodConsumption().pollAcquiringFood();
            if (result.state() == ContainerTransferState.PENDING) {
                return TaskModuleResult.waiting(result.message());
            }
            acquiringActive = false;
            if (result.state() == ContainerTransferState.FAILED) {
                return TaskModuleResult.failed(result.message());
            }
            foodReadyToEat = true;
            return TaskModuleResult.running(
                    "已从食物箱取出一个食物，正在进食"
            );
        }
        if (eatingActive) {
            FoodConsumptionResult result =
                    context.foodConsumption().pollEating();
            if (result.state() == ContainerTransferState.PENDING) {
                return TaskModuleResult.waiting(result.message());
            }
            eatingActive = false;
            if (result.state() == ContainerTransferState.FAILED) {
                return TaskModuleResult.failed(result.message());
            }
            return TaskModuleResult.running(
                    "已完成一次进食，正在检查饱食度"
            );
        }
        if (foodReadyToEat) {
            foodReadyToEat = false;
            eatingActive = true;
            FoodConsumptionResult result =
                    context.foodConsumption().startEating(foodItemId);
            return handleEatingResult(result);
        }
        acquiringActive = true;
        FoodConsumptionResult result = context.foodConsumption()
                .startAcquiringFood(
                        new ContainerTarget(position, "food-container"),
                        foodItemId
                );
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        acquiringActive = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        foodReadyToEat = true;
        return TaskModuleResult.running(
                "已从食物箱取出一个食物，正在进食"
        );
    }

    @Override
    public void cancel() {
        context.foodConsumption().cancelAcquiringFood();
        context.foodConsumption().cancelEating();
        context.navigation().cancel();
    }

    private NavigationStatus navigate() {
        if (!navigationStarted) {
            navigationStarted = true;
            return context.navigation().startNear(
                    position,
                    arrivalRadius
            );
        }
        return context.navigation().status(position, arrivalRadius);
    }

    private TaskModuleResult handleEatingResult(
            FoodConsumptionResult result
    ) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        eatingActive = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        return TaskModuleResult.running(
                "已完成一次进食，正在检查饱食度"
        );
    }
}
