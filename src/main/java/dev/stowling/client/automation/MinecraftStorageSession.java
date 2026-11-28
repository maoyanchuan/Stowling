package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** Owns only menus opened by one opted-in storage subflow. */
final class MinecraftStorageSession {
    private final Access access;
    private final MinecraftContainerOpenHand hand;
    private ContainerTarget retainedTarget;
    private Object retainedMenu;

    MinecraftStorageSession(Minecraft minecraft) {
        this(new ClientAccess(minecraft, minecraft.player, minecraft.level),
                new MinecraftContainerOpenHand(minecraft, true));
    }

    MinecraftStorageSession(Access access, MinecraftContainerOpenHand hand) {
        this.access = access;
        this.hand = hand;
    }

    MinecraftContainerOpenHand hand() {
        return hand;
    }

    boolean begin(ContainerTarget target, boolean inspect) {
        boolean reuse = target.equals(retainedTarget) && access.valid(target, retainedMenu);
        if (!reuse) closeMenu();
        retainedTarget = null;
        retainedMenu = null;
        if (!inspect) hand.restore();
        return reuse;
    }

    boolean retain(ContainerTarget target) {
        Object menu = access.menu();
        if (access.valid(target, menu)) {
            retainedTarget = target;
            retainedMenu = menu;
            return true;
        }
        return false;
    }

    void close() {
        closeMenu();
        hand.restore();
    }

    private void closeMenu() {
        if (retainedMenu != null) access.close(retainedMenu);
        retainedTarget = null;
        retainedMenu = null;
    }

    interface Access {
        Object menu();

        boolean valid(ContainerTarget target, Object menu);

        void close(Object menu);
    }

    private record ClientAccess(Minecraft minecraft, Object player, Object level) implements Access {
        @Override
        public Object menu() {
            return minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen
                    ? screen.getMenu() : null;
        }

        @Override
        public boolean valid(ContainerTarget target, Object menu) {
            return menu != null && menu == menu() && minecraft.player == player
                    && minecraft.level == level && minecraft.player != null
                    && MinecraftContainerInteractions.ready(minecraft.player.containerMenu)
                    && minecraft.player.containerMenu.getCarried().isEmpty()
                    && target.entrances().stream().anyMatch(entrance ->
                            minecraft.player.isWithinBlockInteractionRange(
                                    new net.minecraft.core.BlockPos(entrance.x(), entrance.y(),
                                            entrance.z()), 0.0D))
                    && ContainerStandingAccess.canOpen(minecraft.player, target);
        }

        @Override
        public void close(Object menu) {
            if (menu == menu() && minecraft.player == player && minecraft.level == level
                    && minecraft.player != null
                    && minecraft.player.containerMenu.getCarried().isEmpty()) {
                dev.stowling.v2.runtime.TaskTrace.event("CALL", "game.closeContainer",
                        "reason=classification-session-release", 0, false);
                minecraft.player.closeContainer();
            }
        }
    }
}
