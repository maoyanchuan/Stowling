package dev.stowling.automation;

public interface FoodConsumptionPort {
    FoodStatus observe();

    FoodConsumptionResult startEating(String itemId);

    FoodConsumptionResult pollEating();

    void cancelEating();

    default FoodConsumptionResult startAcquiringFood(
            ContainerTarget target,
            String itemId
    ) {
        return FoodConsumptionResult.failed("食物获取组件不可用");
    }

    default FoodConsumptionResult pollAcquiringFood() {
        return FoodConsumptionResult.failed("食物获取组件不可用");
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
