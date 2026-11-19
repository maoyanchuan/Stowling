package dev.stowling.client.mixin;

import dev.stowling.client.automation.MinecraftInventoryPickups;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void stowling$pickupReceived(ClientboundTakeItemEntityPacket packet,
            CallbackInfo callback) {
        MinecraftInventoryPickups.received(Minecraft.getInstance(), packet);
    }
}
