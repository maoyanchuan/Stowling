package dev.stowling.client.automation;

import dev.stowling.automation.ContainerOpenHandPlan;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Temporarily uses an empty slot or pickaxe, retaining the original hotbar selection. */
final class MinecraftContainerOpenHand {
    private final Minecraft minecraft;
    private int original = -1;
    private int swapped = -1;
    private int settling;

    MinecraftContainerOpenHand(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    boolean prepare() {
        if (original >= 0) {
            if (++settling < 4) return false;
            if (!safe(minecraft.player.getMainHandItem())) {
                throw new IllegalStateException("安全手持槽位尚未同步，已停止打开容器");
            }
            return true;
        }
        var inventory = minecraft.player.getInventory();
        original = inventory.getSelectedSlot();
        List<ItemStack> slots = inventory.getNonEquipmentItems();
        int chosen = ContainerOpenHandPlan.select(original,
                slots.stream().map(MinecraftContainerOpenHand::safe).toList());
        if (chosen < 0) {
            original = -1;
            throw new IllegalStateException("打开容器需要一个空背包槽位或镐，避免误放任务物品");
        }
        if (chosen == original) return true;
        if (chosen < 9) {
            select(chosen);
        } else {
            swapped = chosen;
            swap(chosen);
        }
        return false;
    }

    void restore() {
        if (original < 0 || minecraft.player == null || minecraft.gameMode == null) return;
        if (swapped >= 0) swap(swapped);
        select(original);
        original = -1;
        swapped = -1;
    }

    private void select(int index) {
        if (minecraft.player.getInventory().getSelectedSlot() == index) return;
        minecraft.player.getInventory().setSelectedSlot(index);
        minecraft.player.connection.send(new ServerboundSetCarriedItemPacket(index));
    }

    private void swap(int inventoryIndex) {
        var menu = minecraft.player.containerMenu;
        int source = MinecraftContainerInteractions.slots(menu, true).stream()
                .filter(index -> menu.getSlot(index).getContainerSlot() == inventoryIndex)
                .findFirst().orElseThrow();
        MinecraftContainerInteractions.click(minecraft, menu, source,
                minecraft.player.getInventory().getSelectedSlot(), ContainerInput.SWAP);
    }

    private static boolean safe(ItemStack stack) {
        return stack.isEmpty() || stack.is(ItemTags.PICKAXES);
    }
}
