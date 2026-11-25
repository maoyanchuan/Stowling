package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.client.mixin.MinecraftFoodUseMixin;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

class MinecraftFoodUseMixinTest {
    @Test
    void blocksTheExtraVanillaUseAfterConsumptionUntilTheUseKeyIsReleased() throws Exception {
        var hook = new MinecraftFoodUseMixin() {};
        var guard = hook.stowling$foodUseGuard();
        assertFalse(vanillaUseCancelled(hook));
        guard.begin();
        // Vanilla observes isUsingItem=false before the end-of-tick eating poll runs.
        assertTrue(vanillaUseCancelled(hook));
        assertTrue(vanillaUseCancelled(hook));
        guard.end();
        assertFalse(vanillaUseCancelled(hook));
    }

    @Test
    void manualUseAndOtherClientsKeepVanillaInteraction() throws Exception {
        var first = new MinecraftFoodUseMixin() {};
        var second = new MinecraftFoodUseMixin() {};
        first.stowling$foodUseGuard().begin();
        assertTrue(vanillaUseCancelled(first));
        assertFalse(vanillaUseCancelled(second));
        first.stowling$foodUseGuard().end();
        assertFalse(vanillaUseCancelled(first));
    }

    private static boolean vanillaUseCancelled(MinecraftFoodUseMixin hook) throws Exception {
        Method handler = MinecraftFoodUseMixin.class.getDeclaredMethod(
                "stowling$guardAutomaticMeal", CallbackInfo.class);
        handler.setAccessible(true);
        var callback = new CallbackInfo("startUseItem", true);
        handler.invoke(hook, callback);
        return callback.isCancelled();
    }
}
