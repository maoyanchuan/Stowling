package dev.stowling.client.automation;

import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.scan.CategoryScanBlock;
import dev.stowling.v2.scan.CategoryScanDefinition;
import dev.stowling.v2.scan.CategoryScanSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Read-only world acquisition. Incomplete chunks fail the whole observation, never become air. */
public final class MinecraftCategoryScan {
    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;

    public MinecraftCategoryScan(Minecraft minecraft, ClientActionScheduler scheduler) {
        this.minecraft = minecraft;
        this.scheduler = scheduler;
    }

    public CategoryScanSnapshot read(AreaBounds area) {
        CategoryScanDefinition.validateArea(area);
        if (scheduler.isClientThread()) return readOnClient(area);
        try {
            return scheduler.submit(() -> readOnClient(area)).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("扫描已中断，请重试", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            if (exception.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("无法读取游戏选区", exception.getCause());
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalArgumentException("客户端未及时响应扫描，请返回游戏后重试", exception);
        }
    }

    private CategoryScanSnapshot readOnClient(AreaBounds area) {
        if (!scheduler.isClientThread()) throw new IllegalStateException("扫描必须在客户端线程");
        var level = minecraft.level;
        if (level == null || minecraft.player == null) {
            throw new IllegalArgumentException("当前没有可扫描的游戏世界");
        }
        if (area.minY() < level.getMinY() || area.maxY() > level.getMaxY()) {
            throw new IllegalArgumentException("总选区超出当前世界高度");
        }
        for (long x = area.minX(); x <= area.maxX(); x++) {
            for (long z = area.minZ(); z <= area.maxZ(); z++) {
                if (!level.isLoaded(new BlockPos((int) x, area.minY(), (int) z))) {
                    throw new IllegalArgumentException("总选区未完整加载，缺少区块：(" + x + ", "
                            + area.minY() + ", " + z + ")，请加载后重新扫描");
                }
            }
        }
        var consumed = new HashSet<BlockPosition>();
        var blocks = new ArrayList<CategoryScanBlock>();
        for (long x = area.minX(); x <= area.maxX(); x++) {
            for (long y = area.minY(); y <= area.maxY(); y++) {
                for (long z = area.minZ(); z <= area.maxZ(); z++) {
                    var pos = new BlockPos((int) x, (int) y, (int) z);
                    var p = position(pos);
                    if (consumed.contains(p)) continue;
                    var state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    String facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                            ? state.getValue(BlockStateProperties.HORIZONTAL_FACING).getName()
                            : state.hasProperty(BlockStateProperties.FACING)
                                    ? state.getValue(BlockStateProperties.FACING).getName() : "north";
                    BlockPosition partner = null;
                    String kind = kind(id);
                    if (state.getBlock() instanceof ChestBlock
                            && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                        var other = ChestBlock.getConnectedBlockPos(pos, state);
                        if (!area.contains(position(other))) {
                            throw new IllegalArgumentException("双箱跨出总选区：" + p
                                    + "，请包含完整箱体后重新扫描");
                        }
                        var otherState = level.getBlockState(other);
                        if (!otherState.is(state.getBlock())
                                || otherState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                                || !ChestBlock.getConnectedBlockPos(other, otherState).equals(pos)) {
                            throw new IllegalArgumentException("双箱状态尚未同步：" + p);
                        }
                        partner = position(other);
                        consumed.add(partner);
                        kind = "chest";
                    }
                    boolean upper = state.hasProperty(BlockStateProperties.SLAB_TYPE)
                            && state.getValue(BlockStateProperties.SLAB_TYPE)
                                    == net.minecraft.world.level.block.state.properties.SlabType.TOP;
                    int turns = switch (facing) {
                        case "east" -> 1;
                        case "south" -> 2;
                        case "west" -> 3;
                        default -> 0;
                    };
                    blocks.add(new CategoryScanBlock(p, id, kind, facing, partner, turns, upper));
                }
            }
        }
        blocks.sort(Comparator.comparingInt((CategoryScanBlock block) -> block.position().x())
                .thenComparingInt(block -> block.position().y())
                .thenComparingInt(block -> block.position().z()));
        var markers = MinecraftCategoryZoneResolver.scanMarkers(level, area);
        for (var marker : markers) {
            if (marker.source() == dev.stowling.v2.category.CategoryMarkerSource.ITEM_FRAME
                    && blocks.stream().noneMatch(block -> block.bounds().contains(marker.position()))) {
                blocks.add(new CategoryScanBlock(marker.position(), "minecraft:item_frame",
                        "checker", "north", null, 0, false));
            }
        }
        return new CategoryScanSnapshot(area, level.dimension().identifier().toString(), blocks,
                markers);
    }

    private static BlockPosition position(BlockPos p) {
        return new BlockPosition(p.getX(), p.getY(), p.getZ());
    }

    private static String kind(String id) {
        if (id.equals("minecraft:chest") || id.equals("minecraft:trapped_chest")) return "single";
        if (id.equals("minecraft:barrel")) return "barrel";
        if (id.endsWith("_slab")) return "slab";
        if (id.endsWith("_stairs")) return "stairs";
        if (id.endsWith("glass") || id.endsWith("glass_pane")) return "glass";
        return "quartz";
    }
}
