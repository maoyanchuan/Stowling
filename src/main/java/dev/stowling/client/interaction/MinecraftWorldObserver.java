package dev.stowling.client.interaction;

import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.domain.BlockPosition;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public final class MinecraftWorldObserver implements WorldObserver {
    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;

    public MinecraftWorldObserver(Minecraft minecraft, ClientActionScheduler scheduler) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public WorldObservation observeWorld() {
        if (!scheduler.isClientThread()) {
            return WorldObservation.disconnected();
        }
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || player.isRemoved()) {
            return WorldObservation.disconnected();
        }
        return new WorldObservation(
                true,
                player.isAlive(),
                Optional.of(new BlockPosition(
                        player.blockPosition().getX(),
                        player.blockPosition().getY(),
                        player.blockPosition().getZ()
                ))
        );
    }
}
