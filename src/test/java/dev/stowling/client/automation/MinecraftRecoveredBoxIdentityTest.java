package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ItemIdentity;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftRecoveredBoxIdentityTest {
    @BeforeAll
    static void initializeRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void emptyContainerEncodingDoesNotHideAnOtherwiseIdenticalRecoveredBox() {
        ItemStack expected = new ItemStack(Items.BLUE_SHULKER_BOX);
        expected.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        ItemStack actual = new ItemStack(Items.BLUE_SHULKER_BOX);

        assertTrue(MinecraftItemIdentity.sameRecoveredBox(expected, actual));
        assertFalse(MinecraftItemIdentity.sameRecoveredBox(expected,
                new ItemStack(Items.RED_SHULKER_BOX)));
        assertEquals(MinecraftItemIdentity.key(expected), MinecraftItemIdentity.key(actual));
    }

    @Test
    void itemKeysReuseEqualComponentsAndDistinguishBoxContents() {
        ItemStack expected = box(List.of(new ItemStack(Items.STONE, 16)));
        String key = MinecraftItemIdentity.key(expected);
        assertEquals(key, MinecraftItemIdentity.key(expected.copyWithCount(2)));
        assertEquals(key, MinecraftItemIdentity.key(box(List.of(new ItemStack(Items.STONE, 16)))));
        assertEquals("minecraft:stone", ItemIdentity.category(key));
        assertNotEquals(key, MinecraftItemIdentity.key(box(List.of(new ItemStack(Items.STONE, 17)))));
        assertNotEquals(key, MinecraftItemIdentity.key(box(List.of(new ItemStack(Items.DIRT, 16)))));
        assertEquals(ItemIdentity.MIXED_BOX_CATEGORY, ItemIdentity.category(MinecraftItemIdentity.key(
                box(List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIRT))))));
    }

    @Test
    void itemKeysPreserveEarlierVersionsOfEditedStacks() {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.set(DataComponents.DAMAGE, 1);
        ItemStack original = tool.copy();
        String originalKey = MinecraftItemIdentity.key(tool);
        tool.set(DataComponents.DAMAGE, 2);
        assertNotEquals(originalKey, MinecraftItemIdentity.key(tool));
        assertEquals(originalKey, MinecraftItemIdentity.key(original));
        assertEquals("minecraft:stone", MinecraftItemIdentity.key(new ItemStack(Items.STONE, 16)));
    }

    @Test
    void equivalentContentsCanUseDifferentSlotsWithoutChangingTheOwnedMaterials() {
        ItemStack expected = box(List.of(new ItemStack(Items.STONE, 32), ItemStack.EMPTY,
                new ItemStack(Items.STONE, 32)));
        ItemStack actual = box(List.of(new ItemStack(Items.STONE, 64)));

        assertTrue(MinecraftItemIdentity.sameRecoveredBox(expected, actual));
        assertFalse(MinecraftItemIdentity.sameRecoveredBox(expected,
                box(List.of(new ItemStack(Items.STONE, 63)))));
        assertFalse(MinecraftItemIdentity.sameRecoveredBox(expected,
                box(List.of(new ItemStack(Items.DIRT, 64)))));
        assertFalse(MinecraftItemIdentity.sameRecoveredBox(expected,
                new ItemStack(Items.STONE, 64)));
    }

    @Test
    void customBoxNamesAndDifferentItemComponentsAreStillDistinct() {
        ItemStack expected = box(List.of(new ItemStack(Items.STONE, 64)));
        ItemStack renamed = expected.copy();
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("另一个盒子"));
        assertFalse(MinecraftItemIdentity.sameRecoveredBox(expected, renamed));

        ItemStack firstTool = new ItemStack(Items.DIAMOND_PICKAXE);
        firstTool.set(DataComponents.DAMAGE, 1);
        ItemStack secondTool = firstTool.copy();
        secondTool.set(DataComponents.DAMAGE, 2);
        assertFalse(MinecraftItemIdentity.sameRecoveredBox(box(List.of(firstTool)),
                box(List.of(secondTool))));
    }

    private static ItemStack box(List<ItemStack> contents) {
        ItemStack box = new ItemStack(Items.BLUE_SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        return box;
    }
}
