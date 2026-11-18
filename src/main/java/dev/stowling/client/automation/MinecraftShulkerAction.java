package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerRecoveryReceipt;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Only operates on the one registered box; never asks pathfinding to mine storage blocks. */
final class MinecraftShulkerAction implements TaskModuleExecutor {
    static final class Resource {
        final BlockPosition position;
        final ItemStack original;
        final ContainerRecoveryReceipt receipt = new ContainerRecoveryReceipt();
        ItemStack expected;
        boolean placed;
        boolean broken;
        int inventoryBefore;

        Resource(BlockPosition position, ItemStack original) {
            this.position = position;
            this.original = original.copyWithCount(1);
            this.expected = this.original.copy();
        }
    }

    private final Minecraft minecraft;
    private final TaskExecutionContext context;
    private final Resource resource;
    private final boolean place;
    private int phase;
    private int ticks;
    private int swapSlot = -1;
    private int selected;
    private boolean swapping;
    private String toolBefore;
    private TaskModuleResult terminal;

    MinecraftShulkerAction(Minecraft minecraft, TaskExecutionContext context,
            Resource resource, boolean place) {
        this.minecraft = minecraft;
        this.context = context;
        this.resource = resource;
        this.place = place;
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (terminal != null) {
            return terminal;
        }
        if (!place && resource.receipt.confirmed()) {
            terminal = TaskModuleResult.succeeded("潜影盒回收已确认，无需重复入账");
            return terminal;
        }
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            return fail("潜影盒操作需要已加载世界");
        }
        if (++ticks > 600) {
            return fail(place ? "潜影盒放置未确认" : "潜影盒挖掘或拾取未确认");
        }
        BlockPos position = new BlockPos(resource.position.x(), resource.position.y(),
                resource.position.z());
        if (phase == 0) {
            if (minecraft.gui.screen() != null) {
                minecraft.player.closeContainer();
                return waitFor("正在关闭界面");
            }
            NavigationStatus navigation = place
                    ? context.navigation().startNear(resource.position, 2)
                    : resource.broken ? NavigationStatus.ARRIVED
                            : context.navigation().startInteraction(resource.position, miningArea());
            if (navigation == NavigationStatus.FAILED) {
                return fail("无法到达潜影盒操作点");
            }
            if (navigation == NavigationStatus.ARRIVED) {
                return prepareHand(position);
            }
            phase = 1;
            return waitFor("正在前往潜影盒操作点");
        }
        if (phase == 1) {
            NavigationStatus navigation = context.navigation().status(resource.position);
            if (navigation == NavigationStatus.FAILED) {
                return fail("潜影盒操作点不可达");
            }
            if (navigation != NavigationStatus.ARRIVED) {
                return waitFor("正在靠近潜影盒");
            }
            return prepareHand(position);
        }
        if (phase == 2 && ticks < 4) {
            return waitFor("等待手持物品同步");
        }
        if (place) {
            return tickPlacement(position);
        }
        return tickRecovery(position);
    }

    static AreaBounds miningArea(BlockPosition box) {
        return new AreaBounds(box.x() - 1, box.y(), box.z() - 1,
                box.x() + 1, box.y(), box.z() + 1);
    }

    private AreaBounds miningArea() {
        return miningArea(resource.position);
    }

    private TaskModuleResult prepareHand(BlockPos position) {
        selected = minecraft.player.getInventory().getSelectedSlot();
        if (place) {
            if (!validSite(minecraft, position)) {
                return fail("潜影盒放置点被占用或开盖空间不足");
            }
            int source = findSlot(MinecraftItemIdentity.key(resource.original), false);
            if (source < 0) {
                return fail("任务持有的潜影盒已不在背包中");
            }
            swap(source);
        } else if (!resource.broken) {
            if (!sameBlock(position)) {
                return fail("登记的潜影盒已变化，停止挖掘");
            }
            int tool = findSlot("", true);
            if (tool >= 0) {
                swap(tool);
            }
        }
        phase = 2;
        ticks = 0;
        return waitFor("等待手持物品同步");
    }

    private TaskModuleResult tickPlacement(BlockPos position) {
        String key = MinecraftItemIdentity.key(resource.original);
        if (phase == 2) {
            ItemStack held = minecraft.player.getMainHandItem();
            if (held.isEmpty() || !MinecraftItemIdentity.key(held).equals(key)) {
                return fail("手持潜影盒与任务记录不一致");
            }
            resource.inventoryBefore = inventoryCount(key);
            BlockPos support = position.below();
            minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0),
                            Direction.UP, support, false));
            phase = 3;
            ticks = 0;
            return waitFor("等待服务器确认潜影盒放置");
        }
        if (sameBlock(position) && inventoryCount(key) == resource.inventoryBefore - 1
                && ticks >= 4) {
            context.workspace().consumeDeposit("managed", Map.of(key, 1), Map.of());
            resource.placed = true;
            restore();
            terminal = TaskModuleResult.succeeded("潜影盒已放置并登记");
            return terminal;
        }
        return waitFor("等待方块与背包同步确认放置");
    }

    private TaskModuleResult tickRecovery(BlockPos position) {
        String key = MinecraftItemIdentity.key(resource.expected);
        if (!resource.broken) {
            if (phase == 2) {
                if (!sameBlock(position)) {
                    return fail("潜影盒在开始挖掘前已变化");
                }
                if (!context.navigation().isWithinInteractionRange(
                        resource.position, miningArea())) {
                    return fail("未贴近潜影盒，未开始挖掘");
                }
                resource.inventoryBefore = inventoryCount(key);
                toolBefore = heldKey();
                minecraft.gameMode.startDestroyBlock(position, Direction.UP);
                phase = 3;
            }
            if (!sameBlock(position)) {
                if (!minecraft.level.getBlockState(position).isAir()) {
                    return fail("潜影盒位置出现其他方块，停止操作");
                }
                resource.broken = true;
                minecraft.gameMode.stopDestroyBlock();
                context.inventory().replaceProtectedIdentity(toolBefore, heldKey());
                restore();
                ticks = 0;
                phase = 4;
            } else {
                minecraft.gameMode.continueDestroyBlock(position, Direction.UP);
                minecraft.player.swing(InteractionHand.MAIN_HAND);
                return waitFor("正在挖掘本次登记的潜影盒");
            }
        }
        if (inventoryCount(key) >= resource.inventoryBefore + 1) {
            if (phase == 5) {
                context.navigation().cancel();
            }
            resource.receipt.confirm(() -> context.inventory().recordWithdrawn(
                    context.workspace().inventoryAccount(), Map.of(key, 1)));
            terminal = TaskModuleResult.succeeded("已回收并核对潜影盒");
            return terminal;
        }
        if (phase != 5) {
            context.navigation().startWithinRadius(resource.position, 0);
            phase = 5;
        }
        if (context.navigation().status(resource.position, 0) == NavigationStatus.FAILED) {
            return fail("潜影盒已挖掉但尚未拾取，请检查掉落物");
        }
        return waitFor("等待拾取并核对潜影盒内容");
    }

    private boolean sameBlock(BlockPos position) {
        return resource.original.getItem() instanceof BlockItem item
                && minecraft.level.getBlockState(position).is(item.getBlock());
    }

    static boolean validSite(Minecraft minecraft, BlockPos position) {
        if (minecraft.level == null || minecraft.player == null) {
            return false;
        }
        return minecraft.level.hasChunkAt(position)
                && minecraft.level.getBlockState(position).isAir()
                && minecraft.level.getBlockState(position.above()).isAir()
                && minecraft.level.getBlockEntity(position.below()) == null
                && minecraft.level.getBlockState(position.below()).isFaceSturdy(
                        minecraft.level, position.below(), Direction.UP)
                && !minecraft.player.getBoundingBox().intersects(
                        new net.minecraft.world.phys.AABB(position));
    }

    private int findSlot(String key, boolean tool) {
        for (int index : MinecraftStorageAccess.slots(minecraft.player.inventoryMenu, true)) {
            Slot slot = minecraft.player.inventoryMenu.getSlot(index);
            if (slot.getContainerSlot() >= 36 || slot.getItem().isEmpty()) {
                continue;
            }
            if (tool ? slot.getItem().is(ItemTags.PICKAXES)
                    : MinecraftItemIdentity.key(slot.getItem()).equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private void swap(int source) {
        if (minecraft.player.inventoryMenu.getSlot(source).getContainerSlot() == selected) {
            return;
        }
        swapSlot = source;
        minecraft.gameMode.handleContainerInput(minecraft.player.inventoryMenu.containerId,
                source, selected, ContainerInput.SWAP, minecraft.player);
        swapping = true;
    }

    private void restore() {
        if (swapping && minecraft.player != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleContainerInput(minecraft.player.inventoryMenu.containerId,
                    swapSlot, selected, ContainerInput.SWAP, minecraft.player);
            swapping = false;
        }
    }

    private int inventoryCount(String key) {
        return context.droppedItems().playerItemCounts().orElse(Map.of()).getOrDefault(key, 0);
    }

    private String heldKey() {
        ItemStack held = minecraft.player == null ? ItemStack.EMPTY
                : minecraft.player.getMainHandItem();
        return held.isEmpty() ? "" : MinecraftItemIdentity.key(held);
    }

    private TaskModuleResult fail(String message) {
        if (minecraft.gameMode != null) {
            minecraft.gameMode.stopDestroyBlock();
        }
        context.inventory().replaceProtectedIdentity(toolBefore, heldKey());
        restore();
        terminal = TaskModuleResult.failed(message);
        return terminal;
    }

    private static TaskModuleResult waitFor(String message) {
        return TaskModuleResult.waiting(message);
    }

    @Override
    public boolean safeToStop() {
        return terminal != null;
    }

    @Override
    public void requestStop() {
        // Finish the placement or recovery acknowledgement before handing off to global cleanup.
    }

    @Override
    public void cancel() {
        if (terminal != null) {
            restore();
        }
    }
}
