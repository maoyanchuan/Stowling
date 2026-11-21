package dev.stowling.client.automation;

import dev.stowling.automation.InventoryPickupTracker;
import dev.stowling.automation.PickupReceipt;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.item.ItemEntity;

/** Client-thread pickup receipts shared by all container interaction modules. */
public final class MinecraftInventoryPickups {
    private static final InventoryPickupTracker PICKUPS = new InventoryPickupTracker();
    private static volatile Consumer<PickupReceipt> collectionListener;

    public static void setCollectionListener(Consumer<PickupReceipt> listener) {
        collectionListener = listener;
    }

    private MinecraftInventoryPickups() {
    }

    public static void received(Minecraft minecraft, ClientboundTakeItemEntityPacket packet) {
        if (!minecraft.isSameThread() || minecraft.level == null || minecraft.player == null
                || packet.getPlayerId() != minecraft.player.getId() || packet.getAmount() <= 0) {
            return;
        }
        if (minecraft.level.getEntity(packet.getItemId()) instanceof ItemEntity entity
                && !entity.getItem().isEmpty()) {
            String key = MinecraftItemIdentity.key(entity.getItem());
            MinecraftPickupSafety.received(minecraft, entity);
            PICKUPS.record(minecraft.level, key, packet.getAmount());
            Consumer<PickupReceipt> listener = collectionListener;
            if (listener != null) {
                listener.accept(new PickupReceipt(entity.getUUID(), key, packet.getAmount(),
                        minecraft.level.dimension().identifier().toString(),
                        entity.getX(), entity.getY(), entity.getZ()));
            }
        }
    }

    public static long count(Minecraft minecraft, String itemId) {
        return PICKUPS.count(minecraft.level, itemId);
    }
}
