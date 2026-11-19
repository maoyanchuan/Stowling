package dev.stowling.client.automation;

import dev.stowling.automation.InventoryPickupTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.item.ItemEntity;

/** Client-thread pickup receipts shared by all container interaction modules. */
public final class MinecraftInventoryPickups {
    private static final InventoryPickupTracker PICKUPS = new InventoryPickupTracker();

    private MinecraftInventoryPickups() {
    }

    public static void received(Minecraft minecraft, ClientboundTakeItemEntityPacket packet) {
        if (!minecraft.isSameThread() || minecraft.level == null || minecraft.player == null
                || packet.getPlayerId() != minecraft.player.getId() || packet.getAmount() <= 0) {
            return;
        }
        if (minecraft.level.getEntity(packet.getItemId()) instanceof ItemEntity entity
                && !entity.getItem().isEmpty()) {
            PICKUPS.record(minecraft.level, MinecraftItemIdentity.key(entity.getItem()),
                    packet.getAmount());
        }
    }

    public static long count(Minecraft minecraft, String itemId) {
        return PICKUPS.count(minecraft.level, itemId);
    }
}
