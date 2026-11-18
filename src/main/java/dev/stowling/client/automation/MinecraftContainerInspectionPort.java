package dev.stowling.client.automation;

import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.automation.NavigationPort;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftContainerInspectionPort
        implements ContainerInspectionPort {
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final NavigationPort navigation;
    private ActiveInspection active;

    public MinecraftContainerInspectionPort(
            Minecraft minecraft,
            ClientActionScheduler scheduler,
            NavigationPort navigation
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
    }

    @Override
    public ContainerInspectionResult startInspection(ContainerTarget target) {
        Objects.requireNonNull(target, "target");
        if (!scheduler.isClientThread()) {
            return ContainerInspectionResult.failed(
                    "容器检查必须在客户端线程执行"
            );
        }
        if (active != null) {
            return ContainerInspectionResult.failed(
                    "已有容器检查正在进行"
            );
        }
        active = new ActiveInspection(target);
        if (target.partner() != null) {
            active.visit = new MinecraftContainerVisit(minecraft, navigation, target);
        }
        active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        return ContainerInspectionResult.pending("正在打开分类标记容器");
    }

    @Override
    public ContainerInspectionResult pollInspection() {
        if (!scheduler.isClientThread()) {
            return ContainerInspectionResult.failed(
                    "容器检查必须在客户端线程执行"
            );
        }
        if (active == null) {
            return ContainerInspectionResult.failed(
                    "当前没有容器检查操作"
            );
        }
        if (active.visit == null && Instant.now().isAfter(active.deadline)) {
            return fail("分类标记容器打开超时");
        }
        if (active.visit != null) {
            TaskModuleResult visit = active.visit.tick();
            if (visit.state() == TaskModuleState.FAILED) {
                return fail(visit.message());
            }
            if (visit.state() != TaskModuleState.SUCCEEDED) {
                return ContainerInspectionResult.pending(visit.message());
            }
            active.visit = null;
            active.openSent = true;
            active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        }
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen != null) {
            if (!active.openSent) {
                closeContainer();
                return ContainerInspectionResult.pending(
                        "正在关闭当前界面"
                );
            }
            AbstractContainerMenu menu = screen.getMenu();
            if (containerSlots(menu).isEmpty()) {
                return ContainerInspectionResult.pending(
                        "等待分类标记容器槽位加载"
                );
            }
            Set<String> itemIds = readItemIds(menu);
            closeContainer();
            active = null;
            return ContainerInspectionResult.success(
                    "已读取分类标记容器",
                    itemIds
            );
        }
        if (!active.openSent) {
            openBlock(active.target);
            active.openSent = true;
            active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        }
        return ContainerInspectionResult.pending("等待分类标记容器界面");
    }

    @Override
    public void cancelInspection() {
        if (scheduler.isClientThread()) {
            if (active != null && active.visit != null) {
                active.visit.cancelNavigation();
            }
            closeContainer();
        }
        active = null;
    }

    private ContainerInspectionResult fail(String message) {
        closeContainer();
        active = null;
        return ContainerInspectionResult.failed(message);
    }

    private static Set<String> readItemIds(AbstractContainerMenu menu) {
        Set<String> result = new LinkedHashSet<>();
        for (int slotIndex : containerSlots(menu)) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (!stack.isEmpty()) {
                result.add(
                        BuiltInRegistries.ITEM.getKey(stack.getItem())
                                .toString()
                );
            }
        }
        return Set.copyOf(result);
    }

    private static java.util.List<Integer> containerSlots(
            AbstractContainerMenu menu
    ) {
        return menu.slots.stream()
                .filter(slot -> !(slot.container instanceof Inventory))
                .map(menu.slots::indexOf)
                .toList();
    }

    private void openBlock(ContainerTarget target) {
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null || !ContainerStandingAccess.canOpen(player, target)) {
            return;
        }
        BlockPos position = new BlockPos(
                target.position().x(),
                target.position().y(),
                target.position().z()
        );
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(position),
                Direction.UP,
                position,
                false
        );
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    private void closeContainer() {
        LocalPlayer player = minecraft.player;
        if (player != null) {
            player.closeContainer();
        }
    }

    private AbstractContainerScreen<?> currentContainerScreen() {
        return minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen
                ? screen
                : null;
    }

    private static final class ActiveInspection {
        private MinecraftContainerVisit visit;
        private final ContainerTarget target;
        private Instant deadline;
        private boolean openSent;

        private ActiveInspection(ContainerTarget target) {
            this.target = target;
        }
    }
}
