package dev.stowling.client.interaction;

import dev.stowling.automation.GroundItem;
import dev.stowling.automation.PlayerInventoryAdapter;
import dev.stowling.automation.PlayerInventorySlot;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.PickupSite;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class MinecraftWorldObserver implements WorldObserver, PlayerInventoryAdapter {
    private static final int MAIN_INVENTORY_SLOTS = 36;

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;

    public MinecraftWorldObserver(Minecraft minecraft, ClientActionScheduler scheduler) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public WorldObservation observeWorld() {
        requireClientThread();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || player.isRemoved()) {
            return WorldObservation.disconnected();
        }
        BlockPosition position = new BlockPosition(
                player.blockPosition().getX(),
                player.blockPosition().getY(),
                player.blockPosition().getZ()
        );
        return new WorldObservation(
                true,
                player.isAlive(),
                level.dimension().identifier().toString(),
                Optional.of(position)
        );
    }

    @Override
    public List<GroundItem> observeGroundItems(PickupSite site) {
        requireClientThread();
        Objects.requireNonNull(site, "site");
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return List.of();
        }
        List<GroundItem> result = new ArrayList<>();
        double radiusSquared = (double) site.radius() * site.radius();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity itemEntity) || entity.isRemoved()) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty() || entity.distanceToSqr(player) > radiusSquared) {
                continue;
            }
            result.add(new GroundItem(
                    entity.getUUID().toString(),
                    itemId(stack),
                    stack.getCount(),
                    positionOf(entity)
            ));
        }
        return List.copyOf(result);
    }

    @Override
    public Set<String> tagsFor(String itemId) {
        requireClientThread();
        Identifier identifier = Identifier.tryParse(Objects.requireNonNull(itemId, "itemId"));
        if (identifier == null) {
            return Set.of();
        }
        Item item = BuiltInRegistries.ITEM.getValue(identifier);
        if (item == null) {
            return Set.of();
        }
        return item.builtInRegistryHolder().tags()
                .map(tag -> tag.location().toString())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public PlayerInventorySnapshot observe() {
        requireClientThread();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return new PlayerInventorySnapshot(MAIN_INVENTORY_SLOTS, List.of());
        }
        List<PlayerInventorySlot> slots = new ArrayList<>();
        List<ItemStack> items = player.getInventory().getNonEquipmentItems();
        int limit = Math.min(MAIN_INVENTORY_SLOTS, items.size());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty()) {
                slots.add(new PlayerInventorySlot(slot, itemId(stack), stack.getCount()));
            }
        }
        return new PlayerInventorySnapshot(MAIN_INVENTORY_SLOTS, slots);
    }

    private String itemId(ItemStack stack) {
        Identifier identifier = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return identifier.toString();
    }

    private BlockPosition positionOf(Entity entity) {
        return new BlockPosition(
                entity.blockPosition().getX(),
                entity.blockPosition().getY(),
                entity.blockPosition().getZ()
        );
    }

    private void requireClientThread() {
        if (!scheduler.isClientThread()) {
            throw new IllegalStateException("world observation must run on client thread");
        }
    }
}
