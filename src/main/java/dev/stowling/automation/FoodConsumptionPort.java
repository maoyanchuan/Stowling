package dev.stowling.automation;

public interface FoodConsumptionPort {
    FoodStatus observe();

    FoodConsumptionResult startEating(String itemId);

    FoodConsumptionResult pollEating();

    void cancelEating();

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
    };
}
