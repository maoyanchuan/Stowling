package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class MinecraftContainerVisit {
    private static final int OPEN_TIMEOUT_TICKS = 240;

    private final Minecraft minecraft;
    private final NavigationPort navigation;
    private final ContainerTarget target;
    private boolean navigating;
    private boolean openRequested;
    private int openTicks;

    MinecraftContainerVisit(Minecraft minecraft, NavigationPort navigation, ContainerTarget target) {
        this.minecraft = minecraft;
        this.navigation = navigation;
        this.target = target;
    }

    TaskModuleResult tick() {
        if (openRequested) {
            if (minecraft.gui.screen() instanceof AbstractContainerScreen<?>) {
                return TaskModuleResult.succeeded("已打开容器 " + target.position());
            }
            return ++openTicks > OPEN_TIMEOUT_TICKS
                    ? TaskModuleResult.failed("已到操作位置，但容器未打开 " + target.position())
                    : TaskModuleResult.waiting("等待容器打开 " + target.position());
        }
        if (minecraft.gui.screen() != null) {
            if (!minecraft.player.containerMenu.getCarried().isEmpty()) {
                return TaskModuleResult.waiting("等待鼠标上的物品归位");
            }
            minecraft.player.closeContainer();
            return TaskModuleResult.waiting("正在关闭上一容器");
        }
        NavigationStatus status = navigating ? navigation.status(target.position())
                : navigation.startInteraction(target.position(), target.standingArea());
        navigating = status == NavigationStatus.NAVIGATING;
        if (status == NavigationStatus.FAILED) {
            return TaskModuleResult.failed("无法到达容器操作位置 " + target.position());
        }
        if (navigating) {
            return TaskModuleResult.waiting("正在前往容器 " + target.position());
        }
        BlockPos position = new BlockPos(target.position().x(), target.position().y(),
                target.position().z());
        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
        openRequested = true;
        return TaskModuleResult.waiting("等待容器打开 " + target.position());
    }

    void cancelNavigation() {
        if (navigating) {
            navigation.cancel();
            navigating = false;
        }
    }
}
