package dev.stowling.client.automation;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

public final class MinecraftDroppedItemObserver
        implements DroppedItemObserverPort {
    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;

    public MinecraftDroppedItemObserver(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public List<DroppedItem> observe(BlockPosition center, int radius) {
        if (!scheduler.isClientThread()) {
            return List.of();
        }
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return List.of();
        }
        double checkedRadius = Math.max(0, radius);
        double x = center.x() + 0.5;
        double y = center.y() + 0.5;
        double z = center.z() + 0.5;
        AABB area = new AABB(
                x - checkedRadius,
                y - checkedRadius,
                z - checkedRadius,
                x + checkedRadius,
                y + checkedRadius,
                z + checkedRadius
        );
        return level.getEntities(
                        EntityTypeTest.forClass(ItemEntity.class),
                        area,
                        entity -> entity.isAlive() && !entity.isRemoved()
                )
                .stream()
                .filter(entity -> distanceSquared(entity, x, y, z)
                        <= checkedRadius * checkedRadius)
                .map(entity -> droppedItem(entity))
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparingDouble(item -> distanceSquared(
                        item,
                        x,
                        y,
                        z
                )))
                .toList();
    }

    @Override
    public Optional<Map<String, Integer>> playerItemCounts() {
        if (!scheduler.isClientThread()) {
            return Optional.empty();
        }
        LocalPlayer player = minecraft.player;
        if (player == null || player.isRemoved()) {
            return Optional.empty();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || stack.getCount() <= 0) {
                continue;
            }
            counts.merge(itemId(stack), stack.getCount(), Math::addExact);
        }
        return Optional.of(Map.copyOf(counts));
    }

    private static Optional<DroppedItem> droppedItem(
            ItemEntity entity
    ) {
        ItemStack stack = entity.getItem();
        if (stack.isEmpty() || stack.getCount() <= 0) {
            return java.util.Optional.empty();
        }
        return Optional.of(new DroppedItem(
                entity.getUUID(),
                itemId(stack),
                stack.getCount(),
                new BlockPosition(
                        floor(entity.getX()),
                        floor(entity.getY()),
                        floor(entity.getZ())
                )
        ));
    }

    private static double distanceSquared(
            ItemEntity entity,
            double x,
            double y,
            double z
    ) {
        return distanceSquared(entity.getX(), entity.getY(), entity.getZ(), x, y, z);
    }

    private static double distanceSquared(
            DroppedItem item,
            double x,
            double y,
            double z
    ) {
        return distanceSquared(
                item.position().x() + 0.5,
                item.position().y() + 0.5,
                item.position().z() + 0.5,
                x,
                y,
                z
        );
    }

    private static double distanceSquared(
            double firstX,
            double firstY,
            double firstZ,
            double secondX,
            double secondY,
            double secondZ
    ) {
        double deltaX = firstX - secondX;
        double deltaY = firstY - secondY;
        double deltaZ = firstZ - secondZ;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
