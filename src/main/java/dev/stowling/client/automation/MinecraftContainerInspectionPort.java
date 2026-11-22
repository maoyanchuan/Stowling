package dev.stowling.client.automation;

import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationPort;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;

public final class MinecraftContainerInspectionPort implements ContainerInspectionPort {
    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final NavigationPort navigation;
    private MinecraftContainerVisit active;

    public MinecraftContainerInspectionPort(Minecraft minecraft, ClientActionScheduler scheduler,
            NavigationPort navigation) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
    }

    @Override
    public ContainerInspectionResult startInspection(ContainerTarget target) {
        if (!scheduler.isClientThread()) {
            return ContainerInspectionResult.failed("容器检查必须在客户端线程执行");
        }
        if (active != null) {
            return ContainerInspectionResult.failed("已有容器检查正在进行");
        }
        active = new MinecraftContainerVisit(minecraft, navigation, target);
        return ContainerInspectionResult.pending("准备前往容器");
    }

    @Override
    public ContainerInspectionResult pollInspection() {
        if (!scheduler.isClientThread()) {
            return ContainerInspectionResult.failed("容器检查必须在客户端线程执行");
        }
        if (active == null) {
            return ContainerInspectionResult.failed("当前没有容器检查操作");
        }
        TaskModuleResult result = active.tick();
        if (result.state() == TaskModuleState.FAILED) {
            cancelInspection();
            return ContainerInspectionResult.failed(result.message());
        }
        if (result.state() != TaskModuleState.SUCCEEDED) {
            return ContainerInspectionResult.pending(result.message());
        }
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || MinecraftContainerInteractions.slots(screen.getMenu(), false).isEmpty()) {
            cancelInspection();
            return ContainerInspectionResult.failed("容器界面没有可读取的槽位");
        }
        Set<String> itemIds = new LinkedHashSet<>();
        var menu = screen.getMenu();
        for (int index : MinecraftContainerInteractions.slots(menu, false)) {
            var stack = menu.getSlot(index).getItem();
            if (!stack.isEmpty()) {
                itemIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        closeOpenedContainer();
        active = null;
        return ContainerInspectionResult.success("已读取容器", itemIds);
    }

    @Override
    public void cancelInspection() {
        if (scheduler.isClientThread() && active != null) {
            active.cancelNavigation();
            closeOpenedContainer();
            active = null;
        }
    }

    private void closeOpenedContainer() {
        if (active.requestedOpen() && minecraft.player != null
                && minecraft.gui.screen() instanceof AbstractContainerScreen<?>) {
            minecraft.player.closeContainer();
        }
    }
}
