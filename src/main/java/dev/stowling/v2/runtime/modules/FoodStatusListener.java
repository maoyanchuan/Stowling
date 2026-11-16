package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodStatus;
import java.util.Objects;

final class FoodStatusListener {
    private final FoodConsumptionPort foodConsumption;

    FoodStatusListener(TaskExecutionContext context) {
        foodConsumption = Objects.requireNonNull(
                context,
                "context"
        ).foodConsumption();
    }

    FoodStatus observe() {
        return foodConsumption.observe();
    }

    boolean atOrAbove(int threshold) {
        FoodStatus status = observe();
        return status.connected() && status.foodLevel() >= threshold;
    }
}
