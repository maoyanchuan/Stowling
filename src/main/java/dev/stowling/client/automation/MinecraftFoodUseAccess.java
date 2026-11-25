package dev.stowling.client.automation;

import dev.stowling.automation.FoodUseGuard;

/** Per-client access to the guard shared by the eating port and vanilla input hook. */
public interface MinecraftFoodUseAccess {
    FoodUseGuard stowling$foodUseGuard();
}
