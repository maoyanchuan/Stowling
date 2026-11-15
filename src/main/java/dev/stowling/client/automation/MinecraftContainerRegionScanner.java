package dev.stowling.client.automation;

import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class MinecraftContainerRegionScanner
        implements ContainerRegionPort {
    private static final Set<String> DEFAULT_TYPES = Set.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:barrel",
            "minecraft:shulker_box",
            "minecraft:hopper"
    );

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;

    public MinecraftContainerRegionScanner(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public ContainerScanResult scan(
            RegionDefinition region,
            Set<String> containerTypes,
            int maxVolume
    ) {
        if (!scheduler.isClientThread()) {
            return ContainerScanResult.failure("区域扫描必须在客户端线程执行");
        }
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return ContainerScanResult.failure("当前没有可扫描的游戏世界");
        }
        int minX = Math.min(region.firstX(), region.secondX());
        int minY = Math.min(region.firstY(), region.secondY());
        int minZ = Math.min(region.firstZ(), region.secondZ());
        int maxX = Math.max(region.firstX(), region.secondX());
        int maxY = Math.max(region.firstY(), region.secondY());
        int maxZ = Math.max(region.firstZ(), region.secondZ());
        long volume;
        try {
            volume = volume(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (ArithmeticException exception) {
            return ContainerScanResult.failure("扫描区域过大");
        }
        if (volume > maxVolume) {
            return ContainerScanResult.failure(
                    "区域体积 " + volume + " 超过上限 " + maxVolume
            );
        }
        Set<String> types = normalizedTypes(containerTypes);
        List<ContainerTarget> found = new ArrayList<>();
        try {
            for (long x = minX; x <= maxX; x++) {
                for (long y = minY; y <= maxY; y++) {
                    for (long z = minZ; z <= maxZ; z++) {
                        int blockX = (int) x;
                        int blockY = (int) y;
                        int blockZ = (int) z;
                        BlockPos position = new BlockPos(
                                blockX,
                                blockY,
                                blockZ
                        );
                        if (!level.isLoaded(position)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(position);
                        String id = BuiltInRegistries.BLOCK.getKey(
                                state.getBlock()
                        ).toString();
                        if (matchesType(id, types)) {
                            found.add(new ContainerTarget(
                                    new BlockPosition(blockX, blockY, blockZ),
                                    id
                            ));
                        }
                    }
                }
            }
        } catch (RuntimeException exception) {
            return ContainerScanResult.failure(
                    "扫描区域时发生错误: " + exception.getMessage()
            );
        }
        Vec3 playerPosition = player.position();
        found.sort(Comparator.comparingDouble(target -> distanceSquared(
                target.position(),
                playerPosition
        )));
        return ContainerScanResult.success(List.copyOf(found));
    }

    private static long volume(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        long width = (long) maxX - minX + 1;
        long height = (long) maxY - minY + 1;
        long depth = (long) maxZ - minZ + 1;
        return Math.multiplyExact(Math.multiplyExact(width, height), depth);
    }

    private static Set<String> normalizedTypes(Set<String> requested) {
        if (requested.isEmpty()) {
            return DEFAULT_TYPES;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String type : requested) {
            String normalized = type.trim();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return Set.copyOf(result);
    }

    private static boolean matchesType(String id, Set<String> types) {
        int separator = id.indexOf(':');
        String path = separator < 0 ? id : id.substring(separator + 1);
        return types.contains(id) || types.contains(path);
    }

    private static double distanceSquared(
            BlockPosition position,
            Vec3 playerPosition
    ) {
        double deltaX = position.x() + 0.5 - playerPosition.x;
        double deltaY = position.y() + 0.5 - playerPosition.y;
        double deltaZ = position.z() + 0.5 - playerPosition.z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }
}
