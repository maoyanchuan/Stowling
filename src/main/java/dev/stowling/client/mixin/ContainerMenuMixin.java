package dev.stowling.client.mixin;

import dev.stowling.client.automation.MinecraftContainerInteractions;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
    @Inject(method = "setItem", at = @At("TAIL"))
    private void stowling$slotUpdated(int slot, int state,
            net.minecraft.world.item.ItemStack stack, CallbackInfo callback) {
        MinecraftContainerInteractions.slotUpdated((AbstractContainerMenu) (Object) this, slot);
    }

    @Inject(method = "initializeContents", at = @At("TAIL"))
    private void stowling$contentsInitialized(CallbackInfo callback) {
        MinecraftContainerInteractions.contentsInitialized((AbstractContainerMenu) (Object) this);
    }
}
