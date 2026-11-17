package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

final class ContainerStandingAccess {
    private ContainerStandingAccess() {
    }

    static boolean canOpen(LocalPlayer player, ContainerTarget target) {
        if (target.standingArea() == null) {
            return true;
        }
        BlockPos feet = player.blockPosition();
        return target.standingArea().contains(new BlockPosition(
                feet.getX(), feet.getY(), feet.getZ()
        )) && player.isWithinBlockInteractionRange(new BlockPos(
                target.position().x(), target.position().y(), target.position().z()
        ), 0.0D);
    }
}
