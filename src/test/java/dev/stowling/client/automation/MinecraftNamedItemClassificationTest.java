package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ClassificationRemainderPlan;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftNamedItemClassificationTest {
    @BeforeAll
    static void initializeRegistries() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void reportedTeamThreeBoxSeparatesOrdinaryStoneFromNamedContents() {
        ItemStack named = named(new ItemStack(Items.STONE), "Team 3");
        ItemStack box = box(List.of(new ItemStack(Items.STONE, 64),
                new ItemStack(Items.STONE, 32), named));

        assertEquals(ItemIdentity.MIXED_BOX_CATEGORY, MinecraftItemIdentity.boxCategory(box));
        assertTrue(ItemIdentity.isMixedBox(MinecraftItemIdentity.key(box)));
        assertEquals(ItemIdentity.NAMED_ITEM_CATEGORY, MinecraftItemIdentity.category(named));
        assertFalse(ItemIdentity.matches(MinecraftItemIdentity.key(named),
                Set.of("minecraft:stone")));
        assertEquals(ItemIdentity.NAMED_ITEM_CATEGORY,
                MinecraftItemIdentity.boxCategory(box(List.of(named))));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        assertEquals("minecraft:stone",
                ItemIdentity.category(MinecraftItemIdentity.persistentKey(box, registries)));
    }

    @Test
    void namedStoneUsesTheUnclassifiableRouteInBothPackingModes() {
        String stone = MinecraftItemIdentity.key(new ItemStack(Items.STONE));
        String named = MinecraftItemIdentity.key(named(new ItemStack(Items.STONE), "Team 3"));
        ContainerTarget cache = new ContainerTarget(new BlockPosition(0, 64, 0), "minecraft:chest");
        for (StorageMode mode : List.of(StorageMode.MIXED, StorageMode.BOX_ONLY)) {
            var policy = new StoragePolicy(mode, 50, true, null);
            var target = new ContainerTarget(new BlockPosition(1, 64, 0), "minecraft:chest",
                    null, policy);
            var workspace = new TaskRunWorkspace();
            workspace.setResolvedCategories(List.of(target), Map.of(target, Set.of(stone)),
                    List.of(cache));
            workspace.selectNextContainer().orElseThrow();

            assertEquals(Set.of(stone),
                    workspace.mixedBoxWithdrawalItems(cache, Set.of(stone, named)));
            assertEquals(Set.of(named), ClassificationRemainderPlan.select(
                    Map.of(stone, workspace.resolvedStorageTargets(stone),
                            named, workspace.resolvedStorageTargets(named)),
                    true, (destination, key) -> false));
            assertTrue(ClassificationRemainderPlan.select(
                    Map.of(named, workspace.resolvedStorageTargets(named)),
                    false, (destination, key) -> true).isEmpty());
        }
    }

    @Test
    void removingTheCustomNameRestoresOrdinaryClassification() {
        ItemStack stack = named(new ItemStack(Items.STONE), "Stone");
        assertEquals(ItemIdentity.NAMED_ITEM_CATEGORY,
                ItemIdentity.category(MinecraftItemIdentity.key(stack)));

        stack.remove(DataComponents.CUSTOM_NAME);
        assertEquals("minecraft:stone", MinecraftItemIdentity.key(stack));
        assertEquals("minecraft:stone", MinecraftItemIdentity.boxCategory(box(List.of(stack))));

        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.set(DataComponents.DAMAGE, 1);
        assertTrue(ItemIdentity.matches(MinecraftItemIdentity.key(tool),
                Set.of("minecraft:diamond_pickaxe")));
    }

    @Test
    void renamedBoxesAreUnclassifiableAndCannotBecomeEmptyBoxSupplies() {
        ItemStack box = named(box(List.of()), "Team 3");
        String key = MinecraftItemIdentity.key(box);

        assertEquals(ItemIdentity.NAMED_ITEM_CATEGORY, ItemIdentity.category(key));
        assertEquals(ItemIdentity.NAMED_ITEM_CATEGORY, MinecraftItemIdentity.boxCategory(box));
        assertFalse(ItemIdentity.isEmptyBox(key));
        assertFalse(ItemIdentity.matches(key, Set.of("minecraft:stone")));
    }

    private static ItemStack named(ItemStack stack, String name) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static ItemStack box(List<ItemStack> contents) {
        ItemStack stack = new ItemStack(Items.DYED_SHULKER_BOX.black());
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        return stack;
    }
}
