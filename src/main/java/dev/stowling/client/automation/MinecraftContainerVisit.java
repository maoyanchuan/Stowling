package dev.stowling.client.automation;

import dev.stowling.automation.ContainerApproach;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class MinecraftContainerVisit {
    private static final int OPEN_TIMEOUT_TICKS = 240;
    private static final int VISIT_TIMEOUT_TICKS = 2400;

    private final Minecraft minecraft;
    private final ContainerTarget target;
    private final NavigationPort navigation;
    private final List<BlockPosition> entrances;
    private ContainerApproach approach;
    private BlockPosition openRequested;
    private int openTicks;
    private int visitTicks;
    private final MinecraftContainerOpenHand hand;

    MinecraftContainerVisit(Minecraft minecraft, NavigationPort navigation, ContainerTarget target) {
        this.minecraft = minecraft;
        this.navigation = dev.stowling.v2.runtime.TaskTracePorts.wrap(NavigationPort.class,
                navigation);
        this.target = target;
        hand = new MinecraftContainerOpenHand(minecraft);
        entrances = new ArrayList<>(target.entrances());
        approach = new ContainerApproach(this.navigation, target.standingArea(), entrances);
    }

    TaskModuleResult tick() {
        if (minecraft.player == null || minecraft.gameMode == null || minecraft.level == null
                || ++visitTicks > VISIT_TIMEOUT_TICKS) {
            cancelNavigation();
            return TaskModuleResult.failed("容器访问中断或超时 " + target.position());
        }
        if (openRequested != null) {
            if (minecraft.gui.screen() instanceof AbstractContainerScreen<?>) {
                if (!MinecraftContainerInteractions.ready(minecraft.player.containerMenu)) {
                    return TaskModuleResult.waiting("等待容器槽位同步后还原手持槽位");
                }
                hand.restore();
                return TaskModuleResult.succeeded("已打开容器 " + target.position());
            }
            if (++openTicks <= OPEN_TIMEOUT_TICKS) {
                return TaskModuleResult.waiting("等待容器打开 " + openRequested);
            }
            entrances.remove(openRequested);
            if (entrances.isEmpty()) {
                hand.restore();
                return TaskModuleResult.failed("已到操作位置，但容器未打开 " + target.position());
            }
            openTicks = 0;
            approach = new ContainerApproach(navigation, target.standingArea(), entrances);
            openRequested = null;
        }
        if (minecraft.gui.screen() != null) {
            if (!minecraft.player.containerMenu.getCarried().isEmpty()) {
                return TaskModuleResult.waiting("等待鼠标上的物品归位");
            }
            dev.stowling.v2.runtime.TaskTrace.event("CALL", "game.closeContainer",
                    "reason=open-next-container", 0, false);
            minecraft.player.closeContainer();
            return TaskModuleResult.waiting("正在关闭上一容器");
        }
        NavigationStatus status = approach.tick();
        if (status == NavigationStatus.FAILED) {
            return TaskModuleResult.failed("无法到达容器操作位置 " + target.position());
        }
        if (status == NavigationStatus.NAVIGATING) {
            return TaskModuleResult.waiting("正在前往容器 " + target.position());
        }
        BlockPosition reached = approach.reached();
        BlockPos position = new BlockPos(reached.x(), reached.y(), reached.z());
        if (!minecraft.player.isWithinBlockInteractionRange(position, 0.0D)) {
            hand.restore();
            return TaskModuleResult.failed("最终站位够不到容器 " + reached);
        }
        if (!hand.prepare()) return TaskModuleResult.waiting("正在切换安全手持槽位以打开容器");
        openRequested = reached;
        dev.stowling.v2.runtime.TaskTrace.event("CALL", "game.useItemOn",
                "openContainer=" + openRequested, 0, false);
        var interaction = minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
        dev.stowling.v2.runtime.TaskTrace.event("RETURN", "game.useItemOn",
                dev.stowling.v2.runtime.TaskTraceValues.describe(interaction), 0, false);
        return TaskModuleResult.waiting("等待容器打开 " + openRequested);
    }

    boolean requestedOpen() {
        return openRequested != null;
    }

    void cancelNavigation() {
        approach.cancel();
        hand.restore();
    }
}
