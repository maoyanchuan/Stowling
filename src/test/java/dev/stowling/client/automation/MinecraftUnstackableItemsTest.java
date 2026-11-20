package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftUnstackableItemsTest {
    @BeforeAll
    static void initializeRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void excludesEmptyAndEveryShulkerBoxColor() {
        List<ItemStack> boxes = BuiltInRegistries.ITEM.stream().map(ItemStack::new)
                .filter(MinecraftItemIdentity::isBox).toList();

        assertEquals(17, boxes.size());
        assertTrue(MinecraftItemIdentity.unstackableItems(boxes).isEmpty());
        assertTrue(MinecraftItemIdentity.unstackableItems(List.of(ItemStack.EMPTY)).isEmpty());
    }

    @Test
    void selectsToolsAndPotionsWhileLeavingStackableItems() {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack potion = new ItemStack(Items.POTION);

        assertEquals(Set.of(MinecraftItemIdentity.key(tool), MinecraftItemIdentity.key(potion)),
                MinecraftItemIdentity.unstackableItems(List.of(tool, potion,
                        new ItemStack(Items.STONE), new ItemStack(Items.ENDER_PEARL),
                        new ItemStack(Items.BUCKET))));
    }

    @Test
    void usesActualStackLimitAndKeepsDifferentComponentsSeparate() {
        ItemStack singleStone = new ItemStack(Items.STONE);
        singleStone.set(DataComponents.MAX_STACK_SIZE, 1);
        ItemStack potion = new ItemStack(Items.POTION);
        ItemStack namedPotion = potion.copy();
        namedPotion.set(DataComponents.CUSTOM_NAME, Component.literal("特殊药水"));
        ItemStack stackablePotion = potion.copy();
        stackablePotion.set(DataComponents.MAX_STACK_SIZE, 16);

        assertEquals(Set.of(MinecraftItemIdentity.key(singleStone), MinecraftItemIdentity.key(potion),
                MinecraftItemIdentity.key(namedPotion)), MinecraftItemIdentity.unstackableItems(
                List.of(singleStone, new ItemStack(Items.STONE), potion, namedPotion,
                        stackablePotion)));
    }

    @Test
    void selectsEligibleContentsWithoutSelectingTheBoxItself() {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack box = new ItemStack(Items.BLUE_SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                List.of(tool, new ItemStack(Items.STONE))));

        assertEquals(Set.of(MinecraftItemIdentity.key(tool)),
                MinecraftItemIdentity.unstackableItems(List.of(box)));
    }
}
