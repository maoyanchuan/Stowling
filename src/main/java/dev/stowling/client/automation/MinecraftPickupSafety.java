package dev.stowling.client.automation;

import dev.stowling.domain.BlockPosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/** Storage must stand outside pickup range, including briefly empty continuous input points. */
public final class MinecraftPickupSafety {
    private record Source(AABB bounds, long until) {
    }

    private static final SequencedMap<BlockPosition, Source> RECENT = new LinkedHashMap<>();
    private static ClientLevel recentLevel;

    private MinecraftPickupSafety() {
    }

    static void received(Minecraft minecraft, ItemEntity entity) {
        if (recentLevel != minecraft.level) {
            RECENT.clear();
            recentLevel = minecraft.level;
        }
        // Recovering our own box must not turn its operation site into an intake point.
        if (MinecraftItemIdentity.isBox(entity.getItem())) return;
        BlockPos position = entity.blockPosition();
        RECENT.put(new BlockPosition(position.getX(), position.getY(), position.getZ()),
                new Source(entity.getBoundingBox(), minecraft.level.getGameTime() + 100));
        while (RECENT.size() > 64) RECENT.pollFirstEntry();
    }

    public static List<AABB> hazards(LocalPlayer player, BlockPosition target) {
        double radius = Math.max(8, player.blockInteractionRange() + 4);
        AABB area = new AABB(new BlockPos(target.x(), target.y(), target.z())).inflate(radius);
        return hazards(player, area);
    }

    static List<AABB> hazards(LocalPlayer player, AABB area) {
        List<AABB> result = new ArrayList<>();
        player.level().getEntities(EntityTypeTest.forClass(ItemEntity.class), area,
                entity -> entity.isAlive() && !entity.isRemoved() && !entity.getItem().isEmpty())
                .forEach(entity -> result.add(entity.getBoundingBox()));
        if (player.level() == recentLevel) {
            long now = player.level().getGameTime();
            RECENT.values().removeIf(source -> source.until() < now);
            RECENT.values().stream().map(Source::bounds).filter(area::intersects)
                    .forEach(result::add);
        }
        return result;
    }

    public static boolean safe(AABB body, List<AABB> hazards) {
        AABB pickupRange = body.inflate(1.5, 1, 1.5);
        return hazards.stream().noneMatch(pickupRange::intersects);
    }

    public static AABB bodyAt(LocalPlayer player, BlockPosition feet) {
        return player.getBoundingBox().move(feet.x() + 0.5 - player.getX(),
                feet.y() - player.getY(), feet.z() + 0.5 - player.getZ());
    }

    static boolean safePackingSite(BlockPosition site, List<AABB> hazards) {
        AABB workingArea = new AABB(new BlockPos(site.x(), site.y(), site.z()))
                .inflate(1, 0, 1).expandTowards(0, 1, 0);
        return safe(workingArea, hazards);
    }
}
