package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarker;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.category.CategoryRuleResult;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.category.DirectBlockIdCategoryRule;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftCategoryZoneResolverTest {
    @Test
    void frameSupportFollowsTheAttachmentDirectionRatherThanAlwaysRemovingTheLowerCell() {
        var upper = new BlockPos(2, 65, 3);
        assertEquals(upper.below(), MinecraftCategoryZoneResolver.itemFrameSupport(
                upper, net.minecraft.core.Direction.UP));
        assertEquals(upper.west(), MinecraftCategoryZoneResolver.itemFrameSupport(
                upper, net.minecraft.core.Direction.EAST));
        assertEquals(upper.north(), MinecraftCategoryZoneResolver.itemFrameSupport(
                upper, net.minecraft.core.Direction.SOUTH));
    }

    @Test
    void pottedSupportRemovalIsLocalAndLeavesTheSameMaterialAsAStandaloneMarker() {
        var pot = new BlockPosition(0, 65, 3);
        var support = pot.offset(0, -1, 0);
        var separate = support.offset(1, 0, 0);
        LevelReader level = (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) {
                        return ((BlockPos) args[0]).equals(new BlockPos(0, 65, 3))
                                ? Blocks.POTTED_FERN.defaultBlockState()
                                : Blocks.STONE.defaultBlockState();
                    }
                    if (method.getName().equals("getBlockEntity")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
        var markers = new ArrayList<>(List.of(new CategoryMarker(pot, "minecraft:fern",
                CategoryMarkerSource.FLOWER_POT, -1), new CategoryMarker(support, STONE),
                new CategoryMarker(separate, STONE)));
        MinecraftCategoryZoneResolver.removePottedSupports(level, markers);
        assertEquals(List.of(new CategoryMarker(pot, "minecraft:fern",
                CategoryMarkerSource.FLOWER_POT, -1), new CategoryMarker(separate, STONE)), markers);
    }

    private static final String CHEST = "minecraft:chest";
    private static final String TRAPPED_CHEST = "minecraft:trapped_chest";
    private static final String BARREL = "minecraft:barrel";
    private static final String STONE = "minecraft:stone";
    private static final BlockPosition MARKER = new BlockPosition(0, 65, 0);

    @BeforeAll
    static void initializeRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void smartRecognitionRoutesPlacedContainersToTheirStorageColumns() {
        Set<CategoryMarkerSource> sources = MinecraftCategoryZoneResolver.markerSourcesFor(
                DirectBlockIdCategoryRule.ID, null);
        BlockPosition trappedChest = MARKER.offset(1, 0, 0);
        BlockPosition barrel = MARKER.offset(2, 0, 0);
        List<CategoryMarker> markers = new ArrayList<>();
        markers.addAll(readBlock(Blocks.CHEST, MARKER, Set.of(), Map.of(), sources));
        markers.addAll(readBlock(Blocks.TRAPPED_CHEST, trappedChest, Set.of(), Map.of(), sources));
        markers.addAll(readBlock(Blocks.BARREL, barrel, Set.of(), Map.of(), sources));

        ContainerTarget chestStorage = new ContainerTarget(MARKER.offset(0, -1, 0), CHEST);
        ContainerTarget trappedStorage = new ContainerTarget(trappedChest.offset(0, -1, 0), CHEST);
        ContainerTarget barrelStorage = new ContainerTarget(barrel.offset(0, -1, 0), CHEST);
        CategoryRuleResult result = new DirectBlockIdCategoryRule().resolve(
                markers, List.of(chestStorage, trappedStorage, barrelStorage));

        assertTrue(result.succeeded());
        assertEquals(Map.of(CHEST, List.of(chestStorage), TRAPPED_CHEST, List.of(trappedStorage),
                BARREL, List.of(barrelStorage)), result.categories());
    }

    @Test
    void smartRecognitionUsesThePlacedBlockEvenWithCachedContentsAtTheSamePosition() {
        Set<CategoryMarkerSource> sources = MinecraftCategoryZoneResolver.markerSourcesFor(
                DirectBlockIdCategoryRule.ID, null);
        Map<BlockPosition, Set<String>> cache = Map.of(MARKER, Set.of(STONE));

        assertEquals(List.of(new CategoryMarker(MARKER, CHEST)),
                readBlock(Blocks.CHEST, MARKER, Set.of(), cache, sources));
        assertTrue(readBlock(Blocks.CHEST, MARKER, Set.of(CHEST), cache, sources).isEmpty());
    }

    @Test
    void contentsRecognitionReadsTheCachedItemsAndSkipsUnreadContainers() {
        Set<CategoryMarkerSource> sources = MinecraftCategoryZoneResolver.markerSourcesFor(
                ContainerContentsCategoryRule.ID, null);

        assertEquals(List.of(new CategoryMarker(
                MARKER, STONE, CategoryMarkerSource.CONTAINER_CONTENTS, 0)),
                readBlock(Blocks.BARREL, MARKER, Set.of(), Map.of(MARKER, Set.of(STONE)), sources));
        assertTrue(readBlock(Blocks.BARREL, MARKER, Set.of(), Map.of(), sources).isEmpty());
    }

    @Test
    void explicitSourcesKeepContainerContentsAheadOfPlacedItems() {
        Set<CategoryMarkerSource> sources = MinecraftCategoryZoneResolver.markerSourcesFor(
                DirectBlockIdCategoryRule.ID,
                Set.of(CategoryMarkerSource.PLACED_ITEM, CategoryMarkerSource.CONTAINER_CONTENTS));

        assertEquals(List.of(new CategoryMarker(
                MARKER, STONE, CategoryMarkerSource.CONTAINER_CONTENTS, 0)),
                readBlock(Blocks.CHEST, MARKER, Set.of(), Map.of(MARKER, Set.of(STONE)), sources));
        assertTrue(readBlock(Blocks.CHEST, MARKER, Set.of(), Map.of(), sources).isEmpty());
    }

    private static List<CategoryMarker> readBlock(
            Block block,
            BlockPosition position,
            Set<String> ignoredBlockIds,
            Map<BlockPosition, Set<String>> contents,
            Set<CategoryMarkerSource> sources
    ) {
        LevelReader level = (LevelReader) Proxy.newProxyInstance(
                LevelReader.class.getClassLoader(),
                new Class<?>[]{LevelReader.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" -> block.defaultBlockState();
                    case "getBlockEntity" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        List<CategoryMarker> markers = new ArrayList<>();
        BlockPos blockPos = new BlockPos(position.x(), position.y(), position.z());
        MinecraftCategoryZoneResolver.appendBlockMarkers(
                level, blockPos, ignoredBlockIds, contents, sources, markers);
        return markers;
    }
}
