package dev.stowling.v2.scan;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.Objects;
import java.util.Set;

/** A physical block (or a display entity) in the editable mother slice. */
public record CategoryScanBlock(BlockPosition position, String blockId, String kind,
        String facing, BlockPosition partner, int turns, boolean upper) {
    public CategoryScanBlock {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blockId, "blockId");
        if (!blockId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("扫描方块 ID 无效");
        }
        if (!Set.of("chest", "single", "barrel", "slab", "stairs", "checker", "glass", "quartz")
                .contains(kind)) throw new IllegalArgumentException("扫描方块类型无效");
        facing = facing == null ? "north" : facing;
        if (!Set.of("north", "south", "east", "west", "up", "down").contains(facing)) {
            throw new IllegalArgumentException("扫描方块朝向无效");
        }
        if (partner != null && (partner.y() != position.y()
                || Math.abs((long) partner.x() - position.x())
                        + Math.abs((long) partner.z() - position.z()) != 1)) {
            throw new IllegalArgumentException("双箱的两半必须相邻");
        }
        turns = Math.floorMod(turns, 4);
    }

    public boolean container() {
        return blockId.equals("minecraft:chest") || blockId.equals("minecraft:trapped_chest")
                || blockId.equals("minecraft:barrel") || blockId.equals("minecraft:hopper")
                || blockId.equals("minecraft:shulker_box");
    }

    public AreaBounds bounds() {
        BlockPosition end = partner == null ? position : partner;
        return new AreaBounds(position.x(), position.y(), position.z(), end.x(), end.y(), end.z());
    }

    public CategoryScanBlock shift(String axis, int delta) {
        return new CategoryScanBlock(shift(position, axis, delta), blockId, kind, facing,
                partner == null ? null : shift(partner, axis, delta), turns, upper);
    }

    private static BlockPosition shift(BlockPosition p, String axis, int delta) {
        return new BlockPosition(Math.addExact(p.x(), axis.equals("X") ? delta : 0), p.y(),
                Math.addExact(p.z(), axis.equals("Z") ? delta : 0));
    }
}
