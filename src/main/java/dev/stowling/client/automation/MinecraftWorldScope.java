package dev.stowling.client.automation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

final class MinecraftWorldScope {
    private MinecraftWorldScope() {
    }

    static String identify(Minecraft minecraft) {
        return minecraft.level == null ? null
                : identify(minecraft, minecraft.level.dimension().identifier().toString());
    }

    static String identify(Minecraft minecraft, String dimension) {
        if (minecraft.player == null) {
            return null;
        }
        var local = minecraft.getSingleplayerServer();
        var remote = minecraft.getCurrentServer();
        String world;
        if (local != null) {
            world = "world:" + local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } else if (remote != null && !remote.ip.isBlank()) {
            world = "server:" + remote.ip;
        } else {
            return null;
        }
        String scope = world + "\n" + minecraft.player.getUUID() + "\n"
                + dimension;
        return UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
