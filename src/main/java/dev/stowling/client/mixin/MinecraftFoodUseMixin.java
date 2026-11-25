package dev.stowling.client.mixin;

import dev.stowling.automation.FoodUseGuard;
import dev.stowling.client.automation.MinecraftFoodUseAccess;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftFoodUseMixin implements MinecraftFoodUseAccess {
    @Unique
    private final FoodUseGuard stowling$foodUseGuard = new FoodUseGuard();

    @Override
    public FoodUseGuard stowling$foodUseGuard() {
        return stowling$foodUseGuard;
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void stowling$guardAutomaticMeal(CallbackInfo callback) {
        if (stowling$foodUseGuard.blocksWorldUse()) callback.cancel();
    }
}
