package dev.stowling.client.automation;

import dev.stowling.v2.model.MaterialRequirements;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceSpreadeableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

public final class LitematicMaterials {
    public static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private static final long MAX_BLOCKS = 32_000_000;
    private static final Set<String> GENERATED = Set.of(
            "piston_head", "moving_piston", "bubble_column", "nether_portal",
            "end_portal", "end_gateway", "fire", "soul_fire");
    private static final Set<String> ENTITY_ITEMS = Set.of(
            "minecart", "hopper_minecart", "chest_minecart", "furnace_minecart", "tnt_minecart",
            "command_block_minecart", "armor_stand", "item_frame", "glow_item_frame", "painting",
            "end_crystal");

    private LitematicMaterials() {
    }

    public static Preview read(InputStream input) throws IOException {
        var root = NbtIo.readCompressed(input, NbtAccounter.create(96L * 1024 * 1024));
        int version = root.getIntOr("Version", 0);
        if (version < 4 || version > 7) {
            throw new IOException("不支持的 Litematica 投影格式版本");
        }
        var regions = root.getCompound("Regions").orElseThrow(LitematicMaterials::invalid);
        if (regions.isEmpty()) {
            throw invalid();
        }
        Map<String, Integer> items = new TreeMap<>();
        Map<String, Integer> unsupported = new TreeMap<>();
        long budget = MAX_BLOCKS;
        for (String name : regions.keySet()) {
            var region = regions.getCompound(name).orElseThrow(LitematicMaterials::invalid);
            budget -= region(region, budget, items, unsupported);
            for (var tag : region.getListOrEmpty("Entities")) {
                if (tag instanceof CompoundTag entity) {
                    var data = entity.getCompound("EntityData").orElse(entity);
                    entity(data.getStringOr("id", ""), items, unsupported);
                }
            }
        }
        return new Preview(items, unsupported);
    }

    private static long region(CompoundTag region, long budget, Map<String, Integer> items,
            Map<String, Integer> unsupported) throws IOException {
        var size = region.getCompound("Size").orElseThrow(LitematicMaterials::invalid);
        long volume = 1;
        for (String axis : new String[] {"x", "y", "z"}) {
            long length = Math.abs((long) size.getIntOr(axis, 0));
            if (length == 0 || length > budget / volume) {
                throw new IOException("投影体积无效或超过 3200 万个方块位置");
            }
            volume *= length;
        }
        var palette = region.getList("BlockStatePalette").orElseThrow(LitematicMaterials::invalid);
        var words = region.getLongArray("BlockStates").orElseThrow(LitematicMaterials::invalid);
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        if (palette.isEmpty() || words.length != (volume * bits + 63) / 64) {
            throw invalid();
        }
        int[] counts = new int[palette.size()];
        for (long index = 0; index < volume; index++) {
            int entry = paletteIndex(words, bits, index);
            if (entry >= counts.length) {
                throw invalid();
            }
            counts[entry]++;
        }
        for (int entry = 0; entry < counts.length; entry++) {
            if (counts[entry] != 0) {
                var state = palette.getCompound(entry).orElseThrow(LitematicMaterials::invalid);
                block(state, counts[entry], items, unsupported);
            }
        }
        return volume;
    }

    static int paletteIndex(long[] words, int bits, long index) {
        long offset = index * bits;
        int word = (int) (offset / 64);
        int shift = (int) (offset % 64);
        long value = words[word] >>> shift;
        if (shift + bits > 64) {
            value |= words[word + 1] << (64 - shift);
        }
        return (int) (value & ((1L << bits) - 1));
    }

    private static void block(CompoundTag entry, int count, Map<String, Integer> items,
            Map<String, Integer> unsupported) throws IOException {
        String name = entry.getString("Name").orElseThrow(LitematicMaterials::invalid);
        Identifier id = Identifier.tryParse(name);
        var block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            add(unsupported, id == null ? "未知方块" : id.toString(), count);
            return;
        }
        BlockState state = block.defaultBlockState();
        var properties = entry.getCompoundOrEmpty("Properties");
        for (String key : properties.keySet()) {
            var property = block.getStateDefinition().getProperty(key);
            if (property != null) {
                state = withProperty(state, property, properties.getStringOr(key, ""));
            }
        }
        if (state.isAir() || ("minecraft".equals(id.getNamespace())
                && GENERATED.contains(id.getPath()))) {
            return;
        }
        if ((block instanceof DoorBlock || block instanceof DoublePlantBlock)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        == DoubleBlockHalf.UPPER) {
            return;
        }
        if (state.getOptionalValue(BlockStateProperties.BED_PART).orElse(null) == BedPart.HEAD) {
            return;
        }
        if (state.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false)) {
            add(items, "minecraft:water_bucket", count);
        }
        if (block instanceof FlowerPotBlock pot && block != Blocks.FLOWER_POT) {
            add(items, "minecraft:flower_pot", count);
            addBlockItem(items, unsupported, pot.getPotted().asItem(), name, count);
            return;
        }
        if ("minecraft".equals(id.getNamespace())) {
            switch (id.getPath()) {
                case "water", "lava" -> {
                    if (state.getValue(LiquidBlock.LEVEL) == 0) {
                        add(items, name + "_bucket", count);
                    }
                    return;
                }
                case "lava_cauldron", "powder_snow_cauldron", "water_cauldron" -> {
                    add(items, "minecraft:cauldron", count);
                    int level = state.getOptionalValue(BlockStateProperties.LEVEL_CAULDRON)
                            .orElse(3);
                    if (block == Blocks.WATER_CAULDRON && level < 3) {
                        add(items, MaterialRequirements.WATER_BOTTLE, count * level);
                    } else {
                        add(items, name.replace("_cauldron", "_bucket"), count);
                    }
                    return;
                }
                case "powder_snow" -> {
                    add(items, "minecraft:powder_snow_bucket", count);
                    return;
                }
                case "redstone_wire", "redstone_wall_torch", "tripwire",
                        "wall_torch", "soul_wall_torch", "farmland", "dirt_path" -> {
                    String item = switch (id.getPath()) {
                        case "redstone_wire" -> "redstone";
                        case "redstone_wall_torch" -> "redstone_torch";
                        case "wall_torch" -> "torch";
                        case "soul_wall_torch" -> "soul_torch";
                        case "tripwire" -> "string";
                        default -> "dirt";
                    };
                    add(items, "minecraft:" + item, count);
                    return;
                }
                default -> {
                    // Other blocks use the vanilla block-to-item mapping.
                }
            }
        }
        addBlockItem(items, unsupported, block.asItem(), name, count * itemsPerBlock(state));
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state,
            Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed))
                .orElse(state);
    }

    private static int itemsPerBlock(BlockState state) {
        if (state.getOptionalValue(BlockStateProperties.SLAB_TYPE).orElse(null) == SlabType.DOUBLE) {
            return 2;
        }
        if (state.getBlock() instanceof MultifaceSpreadeableBlock) {
            return Math.max(1, MultifaceSpreadeableBlock.availableFaces(state).size());
        }
        for (var property : java.util.List.of(BlockStateProperties.LAYERS, BlockStateProperties.EGGS,
                BlockStateProperties.PICKLES, BlockStateProperties.CANDLES,
                BlockStateProperties.FLOWER_AMOUNT)) {
            var value = state.getOptionalValue(property);
            if (value.isPresent()) {
                return value.get();
            }
        }
        return 1;
    }

    private static void addBlockItem(Map<String, Integer> items, Map<String, Integer> unsupported,
            net.minecraft.world.item.Item item, String block, int count) {
        if (item == net.minecraft.world.item.Items.AIR) {
            add(unsupported, block, count);
        } else {
            add(items, BuiltInRegistries.ITEM.getKey(item).toString(), count);
        }
    }

    private static void entity(String name, Map<String, Integer> items,
            Map<String, Integer> unsupported) {
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            add(unsupported, "未知实体", 1);
            return;
        }
        String path = id.getPath();
        if ("minecraft".equals(id.getNamespace()) && "item".equals(path)) {
            return;
        }
        if ("minecraft".equals(id.getNamespace()) && BuiltInRegistries.ITEM.containsKey(id)
                && (ENTITY_ITEMS.contains(path) || path.endsWith("_boat")
                        || path.endsWith("_raft"))) {
            add(items, name, 1);
        } else {
            add(unsupported, name, 1);
        }
    }

    private static void add(Map<String, Integer> items, String name, int count) {
        items.merge(name, count, Math::addExact);
    }

    private static IOException invalid() {
        return new IOException("Litematica 投影损坏或缺少方块数据");
    }

    public record Preview(Map<String, Integer> items, Map<String, Integer> unsupported) {
    }
}
