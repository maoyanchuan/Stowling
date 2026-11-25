package dev.stowling.automation;

import java.util.Set;

public interface FoodConsumptionPort {
    FoodStatus observe();

    FoodConsumptionResult startEating(String itemId);

    default FoodConsumptionResult startEating(String itemId, Set<String> allowedKeys) {
        return FoodConsumptionResult.failed("无法确认食物来源");
    }

    default FoodConsumptionResult startEating(String itemId, Set<String> allowedKeys,
            boolean worldUseGuardEnabled) {
        return worldUseGuardEnabled
                ? FoodConsumptionResult.failed("当前环境不支持进食交互保护")
                : startEating(itemId, allowedKeys);
    }

    FoodConsumptionResult pollEating();

    void cancelEating();

    default FoodConsumptionResult startAcquiringFood(
            ContainerTarget target,
            String itemId
    ) {
        return FoodConsumptionResult.failed("食物获取组件不可用");
    }

    default FoodConsumptionResult startAcquiringFood(
            ContainerTarget target, String itemId, boolean replenish
    ) {
        return replenish ? FoodConsumptionResult.failed("食物补给组件不可用")
                : startAcquiringFood(target, itemId);
    }

    default FoodConsumptionResult pollAcquiringFood() {
        return FoodConsumptionResult.failed("食物获取组件不可用");
    }

    default FoodConsumptionResult startAcquiringFood(
            ContainerTarget target, String itemId, boolean replenish, int count
    ) {
        return FoodConsumptionResult.failed("食物补给组件不支持指定拿取数量");
    }

    default void cancelAcquiringFood() {
    }

    FoodConsumptionPort UNAVAILABLE = new FoodConsumptionPort() {
        @Override
        public FoodStatus observe() {
            return FoodStatus.disconnected();
        }

        @Override
        public FoodConsumptionResult startEating(String itemId) {
            return FoodConsumptionResult.failed("自动进食组件不可用");
        }

        @Override
        public FoodConsumptionResult pollEating() {
            return FoodConsumptionResult.failed("自动进食组件不可用");
        }

        @Override
        public void cancelEating() {
        }

        @Override
        public FoodConsumptionResult startAcquiringFood(
                ContainerTarget target,
                String itemId
        ) {
            return FoodConsumptionResult.failed("食物获取组件不可用");
        }

        @Override
        public FoodConsumptionResult pollAcquiringFood() {
            return FoodConsumptionResult.failed("食物获取组件不可用");
        }
    };
}
