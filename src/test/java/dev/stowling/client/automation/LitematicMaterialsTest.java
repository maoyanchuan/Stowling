package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LitematicMaterialsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void countsActualBlocksAcrossRegionsAndConvertsStateToBuildItems() throws IOException {
        var first = region(-8, 0x76543211L,
                state("air"), state("glass"), state("redstone_wire"),
                state("redstone_wall_torch"), state("powder_snow"),
                state("water", Map.of("level", "1")),
                state("water", Map.of("level", "0")),
                state("quartz_slab", Map.of("type", "double")), state("diamond_block"));
        var second = region(2, 5, state("air"),
                state("oak_trapdoor", Map.of("waterlogged", "true")));
        var entities = new ListTag();
        var cart = new CompoundTag();
        cart.putString("id", "minecraft:hopper_minecart");
        entities.add(cart);
        second.put("Entities", entities);

        var preview = read(first, second);

        assertEquals(Map.of(
                "minecraft:glass", 2,
                "minecraft:redstone", 1,
                "minecraft:redstone_torch", 1,
                "minecraft:powder_snow_bucket", 1,
                "minecraft:water_bucket", 3,
                "minecraft:quartz_slab", 2,
                "minecraft:oak_trapdoor", 2,
                "minecraft:hopper_minecart", 1
        ), preview.items());
        assertEquals(Map.of(), preview.unsupported());
    }

    @Test
    void countsStillWaterAndLavaWithoutCountingFlowingOrFallingLiquid() throws IOException {
        var fluids = region(4, 0xe4,
                state("water", Map.of("level", "0")),
                state("water", Map.of("level", "2")),
                state("lava", Map.of("level", "0")),
                state("lava", Map.of("level", "8")));

        assertEquals(Map.of("minecraft:water_bucket", 1, "minecraft:lava_bucket", 1),
                read(fluids).items());
    }

    @Test
    void decodesPaletteEntriesThatSpanTwoWords() {
        long[] words = {0xcL << 60, 0x2L};
        assertEquals(44, LitematicMaterials.paletteIndex(words, 6, 10));
    }

    @Test
    void rejectsAnOutOfRangePaletteIndex() {
        assertThrows(IOException.class, () -> read(region(1, 3, state("air"))));
    }

    private static LitematicMaterials.Preview read(CompoundTag... areas) throws IOException {
        var root = new CompoundTag();
        root.putInt("Version", 7);
        var regions = new CompoundTag();
        for (int index = 0; index < areas.length; index++) {
            regions.put("region" + index, areas[index]);
        }
        root.put("Regions", regions);
        var compressed = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, compressed);
        return LitematicMaterials.read(new ByteArrayInputStream(compressed.toByteArray()));
    }

    private static CompoundTag region(int width, long word, CompoundTag... states) {
        var region = new CompoundTag();
        var size = new CompoundTag();
        size.putInt("x", width);
        size.putInt("y", 1);
        size.putInt("z", 1);
        region.put("Size", size);
        var palette = new ListTag();
        for (var state : states) {
            palette.add(state);
        }
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[] {word});
        return region;
    }

    private static CompoundTag state(String name) {
        return state(name, Map.of());
    }

    private static CompoundTag state(String name, Map<String, String> properties) {
        var state = new CompoundTag();
        state.putString("Name", "minecraft:" + name);
        var values = new CompoundTag();
        properties.forEach(values::putString);
        state.put("Properties", values);
        return state;
    }
}
