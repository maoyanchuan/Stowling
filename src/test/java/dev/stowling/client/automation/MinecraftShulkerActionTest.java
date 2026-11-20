package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.LinkedHashMap;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftShulkerActionTest {
    @BeforeAll
    static void initializeRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aSturdyClosedWoodenTrapdoorCannotSupportPlacement() {
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.HALF, Half.TOP);
        EmptyBlockGetter level = EmptyBlockGetter.INSTANCE;
        assertTrue(trapdoor.isFaceSturdy(level, BlockPos.ZERO, Direction.UP));
        assertFalse(MinecraftShulkerAction.isPlacementSupport(trapdoor, level, BlockPos.ZERO));
    }

    @Test
    void placementAcceptsStoneAndSkipsBlocksWithItemInteraction() {
        EmptyBlockGetter level = EmptyBlockGetter.INSTANCE;
        assertTrue(MinecraftShulkerAction.isPlacementSupport(
                Blocks.STONE.defaultBlockState(), level, BlockPos.ZERO));
        assertFalse(MinecraftShulkerAction.isPlacementSupport(
                Blocks.TNT.defaultBlockState(), level, BlockPos.ZERO));
    }

    @Test
    void placingABoxUsesSideStancesWithoutStandingInItsColumn() {
        BlockPosition box = new BlockPosition(5, -43, 32);
        var stances = MinecraftShulkerAction.placementStances(box);

        assertTrue(stances.contains(box.offset(1, 0, 0)));
        assertTrue(stances.contains(box.offset(0, -1, -1)));
        assertFalse(stances.stream().anyMatch(feet -> feet.x() == box.x() && feet.z() == box.z()));
    }

    @Test
    void miningRequiresAStanceNextToTheBoxOnItsLevel() {
        BlockPosition box = new BlockPosition(5, -43, 32);
        AreaBounds area = MinecraftShulkerAction.miningArea(box);

        assertTrue(area.contains(box.offset(1, 0, 0)));
        assertTrue(area.contains(box.offset(0, 0, -1)));
        assertFalse(area.contains(box.offset(2, 0, 0)));
        assertFalse(area.contains(box.offset(1, 1, 0)));
    }

    @Test
    void miningPrefersTheLockedPickaxeEvenWhenAnotherVariantComesFirst() {
        var pickaxes = new LinkedHashMap<Integer, String>();
        pickaxes.put(9, "minecraft:diamond_pickaxe#stest-1");
        pickaxes.put(36, "minecraft:diamond_pickaxe#stest-2");

        assertEquals(36, MinecraftShulkerAction.selectMiningToolSlot(
                pickaxes, Set.of("minecraft:diamond_pickaxe#stest-2")));
    }

    @Test
    void miningFallsBackToInventoryOrderWhenNoLockedPickaxeIsPresent() {
        var pickaxes = new LinkedHashMap<Integer, String>();
        pickaxes.put(9, "minecraft:diamond_pickaxe");
        pickaxes.put(36, "minecraft:iron_pickaxe");

        assertEquals(9, MinecraftShulkerAction.selectMiningToolSlot(pickaxes, Set.of()));
    }

    @Test
    void miningLeavesTheCurrentHandAloneWhenNoPickaxeIsPresent() {
        assertEquals(-1, MinecraftShulkerAction.selectMiningToolSlot(
                new LinkedHashMap<>(), Set.of("minecraft:diamond_pickaxe")));
    }
}
