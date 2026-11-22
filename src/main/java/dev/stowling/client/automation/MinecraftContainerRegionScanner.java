package dev.stowling.client.automation;

import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.model.ContainerCacheRefresh;
import dev.stowling.v2.model.RegionDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
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
    private static final Comparator<BlockPosition> POSITION_ORDER =
            Comparator.comparingInt(BlockPosition::x)
                    .thenComparingInt(BlockPosition::y)
                    .thenComparingInt(BlockPosition::z);

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
        return scan(region, containerTypes, maxVolume, false, null);
    }

    @Override
    public ContainerScanResult scanComplete(RegionDefinition region, Set<String> containerTypes,
            int maxVolume) {
        return scan(region, containerTypes, maxVolume, true, null);
    }

    ContainerScanResult scanRefresh(RegionDefinition region, ContainerCacheRefresh refresh) {
        return scan(region, Set.of(), ContainerCacheRefreshPlan.MAX_SCAN_VOLUME, true, refresh);
    }

    private ContainerScanResult scan(RegionDefinition region, Set<String> containerTypes,
            int maxVolume, boolean requireLoadedRegion, ContainerCacheRefresh refresh) {
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
        Map<BlockPosition, BlockPosition> chestPartners = new HashMap<>();
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
                            if (requireLoadedRegion) {
                                return ContainerScanResult.failure(
                                        "缓存区域“" + region.name() + "”未完整加载："
                                                + blockPosition(position) + "；无法完成库存盘点");
                            }
                            continue;
                        }
                        BlockState state = level.getBlockState(position);
                        String id = BuiltInRegistries.BLOCK.getKey(
                                state.getBlock()
                        ).toString();
                        if (refresh == null ? matchesType(id, types)
                                : refreshCandidate(level, position, state, id, refresh)) {
                            ContainerTarget target = new ContainerTarget(
                                    new BlockPosition(blockX, blockY, blockZ),
                                    id
                            );
                            found.add(target);
                            if (isChest(id)
                                    && state.getValue(ChestBlock.TYPE)
                                    != ChestType.SINGLE) {
                                BlockPos other = ChestBlock.getConnectedBlockPos(position, state);
                                if (level.isLoaded(other)) {
                                    BlockState otherState = level.getBlockState(other);
                                    if (otherState.is(state.getBlock())
                                            && otherState.getValue(ChestBlock.TYPE)
                                            != ChestType.SINGLE
                                            && ChestBlock.getConnectedBlockPos(other, otherState)
                                                    .equals(position)) {
                                        chestPartners.put(target.position(), blockPosition(other));
                                    }
                                } else if (refresh != null) {
                                    chestPartners.put(target.position(), blockPosition(other));
                                }
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException exception) {
            return ContainerScanResult.failure(
                    "扫描区域时发生错误: " + exception.getMessage()
            );
        }
        List<ContainerTarget> containers = collapseDoubleChestPairs(
                found,
                chestPartners
        );
        Vec3 playerPosition = player.position();
        containers.sort(Comparator.comparingDouble(target -> distanceSquared(
                target.position(),
                playerPosition
        )));
        return ContainerScanResult.success(List.copyOf(containers));
    }

    static List<ContainerTarget> collapseDoubleChestPairs(
            List<ContainerTarget> containers,
            Map<BlockPosition, BlockPosition> chestPartners
    ) {
        Set<BlockPosition> collapsed = new HashSet<>();
        List<ContainerTarget> result = new ArrayList<>();
        for (ContainerTarget container : containers) {
            if (!isChest(container.type())) {
                result.add(container);
                continue;
            }
            if (collapsed.contains(container.position())) {
                continue;
            }
            BlockPosition partnerPosition = chestPartners.get(
                    container.position()
            );
            if (partnerPosition == null) {
                result.add(container);
                continue;
            }
            BlockPosition representative = POSITION_ORDER.compare(
                    container.position(),
                    partnerPosition
            ) <= 0 ? container.position() : partnerPosition;
            collapsed.add(container.position());
            collapsed.add(partnerPosition);
            BlockPosition other = representative.equals(container.position())
                    ? partnerPosition : container.position();
            result.add(new ContainerTarget(representative, container.type(),
                    container.standingArea(), container.storagePolicy(), other));
        }
        return result;
    }

    private static BlockPosition blockPosition(BlockPos position) {
        return new BlockPosition(
                position.getX(),
                position.getY(),
                position.getZ()
        );
    }

    private static boolean refreshCandidate(ClientLevel level, BlockPos position,
            BlockState state, String id, ContainerCacheRefresh refresh) {
        if (ContainerContentsCategoryRule.CONTAINER_TYPES.contains(id)) {
            return refresh.scanMarkers() || refresh.markerOnly() || enabledRefreshType(id, refresh);
        }
        if (refresh.markerOnly() || "minecraft:ender_chest".equals(id)) {
            return false;
        }
        if (state.getBlock() instanceof ShulkerBoxBlock) {
            return refresh.shulkerBoxes();
        }
        return refresh.otherContainers() && level.getBlockEntity(position) instanceof Container
                && state.getMenuProvider(level, position) != null;
    }

    static boolean enabledRefreshType(String id, ContainerCacheRefresh refresh) {
        if (isChest(id)) {
            return refresh.chests();
        }
        if ("minecraft:barrel".equals(id)) {
            return refresh.barrels();
        }
        return true;
    }

    private static boolean isChest(String type) {
        return "minecraft:chest".equals(type)
                || "minecraft:trapped_chest".equals(type);
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
