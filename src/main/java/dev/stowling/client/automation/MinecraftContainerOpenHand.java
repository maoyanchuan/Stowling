package dev.stowling.client.automation;

import dev.stowling.automation.ContainerOpenHandPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Temporarily uses an empty slot or pickaxe, retaining the original hotbar selection. */
final class MinecraftContainerOpenHand {
    private final HandAccess access;
    private final boolean reusePrepared;
    private int original = -1;
    private int swapped = -1;
    private int settling;
    private boolean prepared;

    MinecraftContainerOpenHand(Minecraft minecraft) {
        this(minecraft, false);
    }

    MinecraftContainerOpenHand(Minecraft minecraft, boolean reusePrepared) {
        this(new ClientHand(minecraft, reusePrepared ? minecraft.player : null), reusePrepared);
    }

    MinecraftContainerOpenHand(HandAccess access, boolean reusePrepared) {
        this.access = access;
        this.reusePrepared = reusePrepared;
    }

    boolean prepare() {
        if (original >= 0) {
            if (!(reusePrepared && prepared) && ++settling < 4) return false;
            if (!access.selectedSafe()) {
                throw new IllegalStateException("安全手持槽位尚未同步，已停止打开容器");
            }
            prepared = true;
            return true;
        }
        original = access.selected();
        int chosen = ContainerOpenHandPlan.select(original, access.safeSlots());
        if (chosen < 0) {
            original = -1;
            throw new IllegalStateException("打开容器需要一个空背包槽位或镐，避免误放任务物品");
        }
        if (chosen == original) {
            prepared = true;
            return true;
        }
        if (chosen < 9) {
            access.select(chosen);
        } else {
            if (access.locked(original)) {
                int target = java.util.stream.IntStream.range(0, 9)
                        .filter(index -> !access.locked(index))
                        .findFirst().orElseThrow();
                access.select(target);
            }
            swapped = chosen;
            access.swap(chosen);
        }
        return false;
    }

    void restore() {
        if (original < 0 || !access.available()) return;
        if (swapped >= 0) access.swap(swapped);
        access.select(original);
        original = -1;
        swapped = -1;
        if (reusePrepared) {
            settling = 0;
            prepared = false;
        }
    }

    interface HandAccess {
        boolean available();

        int selected();

        boolean selectedSafe();

        boolean locked(int index);

        java.util.List<Boolean> safeSlots();

        void select(int index);

        void swap(int inventoryIndex);
    }

    private record ClientHand(Minecraft minecraft,
            net.minecraft.client.player.LocalPlayer owner) implements HandAccess {
        @Override
        public boolean available() {
            return minecraft.player != null && minecraft.gameMode != null
                    && (owner == null || minecraft.player == owner);
        }

        @Override
        public int selected() {
            return minecraft.player.getInventory().getSelectedSlot();
        }

        @Override
        public boolean selectedSafe() {
            return safe(minecraft.player.getMainHandItem());
        }

        @Override
        public boolean locked(int index) {
            return MinecraftContainerInteractions.locked(index);
        }

        @Override
        public java.util.List<Boolean> safeSlots() {
            var slots = minecraft.player.getInventory().getNonEquipmentItems();
            boolean canSwap = java.util.stream.IntStream.range(0, 9)
                    .anyMatch(index -> !locked(index));
            return java.util.stream.IntStream.range(0, slots.size())
                    .mapToObj(index -> safe(slots.get(index)) && (index < 9
                            || !locked(index) && canSwap)).toList();
        }

        @Override
        public void select(int index) {
            if (selected() == index) return;
            minecraft.player.getInventory().setSelectedSlot(index);
            minecraft.player.connection.send(new ServerboundSetCarriedItemPacket(index));
        }

        @Override
        public void swap(int inventoryIndex) {
            var menu = minecraft.player.containerMenu;
            int source = MinecraftContainerInteractions.slots(menu, true).stream()
                    .filter(index -> menu.getSlot(index).getContainerSlot() == inventoryIndex)
                    .findFirst().orElseThrow();
            MinecraftContainerInteractions.click(minecraft, menu, source, selected(),
                    ContainerInput.SWAP);
        }

        private static boolean safe(ItemStack stack) {
            return stack.isEmpty() || stack.is(ItemTags.PICKAXES);
        }
    }
}
