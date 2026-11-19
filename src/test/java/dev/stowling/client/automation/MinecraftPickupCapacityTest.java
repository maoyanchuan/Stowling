package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftPickupCapacityTest {
    @BeforeAll
    static void initializeRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void discsNeedAnEmptySlotEvenWhenTheInventoryContainsTheSameDisc() {
        ItemStack disc = new ItemStack(Items.MUSIC_DISC_OTHERSIDE);
        String key = MinecraftItemIdentity.key(disc);

        assertFalse(MinecraftDroppedItemObserver.canPickUp(List.of(disc), key));
        assertTrue(MinecraftDroppedItemObserver.canPickUp(List.of(disc, ItemStack.EMPTY), key));
    }

    @Test
    void matchingPartialStacksCanReceiveItemsWithoutAnEmptySlot() {
        ItemStack stone = new ItemStack(Items.STONE, 63);
        String key = MinecraftItemIdentity.key(stone);
        assertTrue(MinecraftDroppedItemObserver.canPickUp(List.of(stone), key));
        stone.setCount(64);
        assertFalse(MinecraftDroppedItemObserver.canPickUp(List.of(stone), key));
    }

    @Test
    void differentlyNamedItemsCannotUseEachOthersStackSpace() {
        ItemStack stone = new ItemStack(Items.STONE, 1);
        ItemStack named = stone.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("保留物品"));

        assertFalse(MinecraftDroppedItemObserver.canPickUp(List.of(named),
                MinecraftItemIdentity.key(stone)));
    }
}
