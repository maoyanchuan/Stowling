package dev.stowling.automation;

public record FoodStatus(
        boolean connected,
        int foodLevel,
        float saturation
) {
    public FoodStatus {
        if (connected && (foodLevel < 0 || foodLevel > 20)) {
            throw new IllegalArgumentException(
                    "foodLevel must contain 0 to 20"
            );
        }
    }

    public static FoodStatus disconnected() {
        return new FoodStatus(false, -1, 0.0F);
    }
}
