package dev.stowling.client.automation;

import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.PlayerInventorySlot;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class MinecraftDroppedItemObserver
        implements DroppedItemObserverPort {
    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final PlayerInventorySlot[] presentation =
            new PlayerInventorySlot[Inventory.INVENTORY_SIZE];
    private LocalPlayer inventoryPlayer;
    private volatile Map<String, String> itemNames = Map.of();
    private volatile List<PlayerInventorySlot> inventorySlots = List.of();

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
        return observeArea(area, new Vec3(x, y, z));
    }

    @Override
    public List<DroppedItem> observe(RegionDefinition region) {
        if (!scheduler.isClientThread()) {
            return List.of();
        }
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return List.of();
        }
        int minX = Math.min(region.firstX(), region.secondX());
        int minY = Math.min(region.firstY(), region.secondY());
        int minZ = Math.min(region.firstZ(), region.secondZ());
        int maxX = Math.max(region.firstX(), region.secondX());
        int maxY = Math.max(region.firstY(), region.secondY());
        int maxZ = Math.max(region.firstZ(), region.secondZ());
        AABB area = new AABB(
                minX,
                minY,
                minZ,
                maxX + 1.0,
                maxY + 1.0,
                maxZ + 1.0
        );
        Vec3 center = new Vec3(
                (minX + maxX + 1.0) / 2.0,
                (minY + maxY + 1.0) / 2.0,
                (minZ + maxZ + 1.0) / 2.0
        );
        return observeArea(area, center);
    }

    private List<DroppedItem> observeArea(AABB area, Vec3 center) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return List.of();
        }
        return level.getEntities(
                        EntityTypeTest.forClass(ItemEntity.class),
                        area,
                        entity -> entity.isAlive() && !entity.isRemoved()
                )
                .stream()
                .filter(entity -> area.contains(entity.position()))
                .map(entity -> droppedItem(entity))
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparingDouble(item -> distanceSquared(
                        item,
                        center
                )))
                .toList();
    }

    @Override
    public boolean canPickUp(DroppedItem item) {
        if (!scheduler.isClientThread() || minecraft.player == null) {
            return false;
        }
        return canPickUp(minecraft.player.getInventory().getNonEquipmentItems(), item.itemId());
    }

    static boolean canPickUp(Iterable<ItemStack> slots, String itemId) {
        for (ItemStack stack : slots) {
            if (stack.isEmpty() || itemId(stack).equals(itemId)
                    && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Map<String, Integer>> playerItemCounts() {
        return readInventory(false).map(PlayerInventorySnapshot::itemCounts);
    }

    @Override
    public Optional<PlayerInventorySnapshot> playerInventorySnapshot() {
        return readInventory(true);
    }

    private Optional<PlayerInventorySnapshot> readInventory(boolean includeIdentities) {
        if (!scheduler.isClientThread()) {
            return Optional.empty();
        }
        LocalPlayer player = minecraft.player;
        if (player == null || player.isRemoved()) {
            if (inventoryPlayer != null) {
                inventoryPlayer = null;
                Arrays.fill(presentation, null);
                inventorySlots = List.of();
                itemNames = Map.of();
            }
            return Optional.empty();
        }
        if (player != inventoryPlayer) {
            // Avoid reconciling persisted locks against the empty inventory during login.
            if (player.inventoryMenu.getStateId() == 0) return Optional.empty();
            inventoryPlayer = player;
            Arrays.fill(presentation, null);
            inventorySlots = List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> pickaxes = new LinkedHashMap<>();
        Map<String, String> persistent = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        var registries = player.level().registryAccess();
        var stacks = player.getInventory().getNonEquipmentItems();
        boolean slotsChanged = false;
        for (int index = 0; index < stacks.size(); index++) {
            ItemStack stack = stacks.get(index);
            if (stack.isEmpty() || stack.getCount() <= 0) {
                if (includeIdentities && presentation[index] != null) {
                    presentation[index] = null;
                    slotsChanged = true;
                }
                continue;
            }
            String key = itemId(stack);
            counts.merge(key, stack.getCount(), Math::addExact);
            names.put(key, stack.getHoverName().getString());
            if (includeIdentities && updateSlot(index, key, stack, player)) {
                slotsChanged = true;
            }
            if (includeIdentities && key.indexOf('#') >= 0) {
                persistent.computeIfAbsent(key,
                        ignored -> MinecraftItemIdentity.persistentKey(stack, registries));
            }
            if (includeIdentities && stack.is(ItemTags.PICKAXES) && stack.isDamageableItem()) {
                ItemStack undamaged = stack.copyWithCount(1);
                undamaged.setDamageValue(0);
                pickaxes.put(key, MinecraftItemIdentity.persistentKey(undamaged, registries));
            }
        }
        if (slotsChanged) {
            List<PlayerInventorySlot> occupied = new ArrayList<>();
            for (PlayerInventorySlot slot : presentation) {
                if (slot != null) occupied.add(slot);
            }
            inventorySlots = List.copyOf(occupied);
        }
        itemNames = Map.copyOf(names);
        return Optional.of(new PlayerInventorySnapshot(counts, pickaxes, persistent));
    }

    private boolean updateSlot(int index, String key, ItemStack stack, LocalPlayer player) {
        PlayerInventorySlot before = presentation[index];
        if (before != null && before.key().equals(key) && before.count() == stack.getCount()) {
            return false;
        }
        var model = stack.get(DataComponents.ITEM_MODEL);
        int maximum = stack.isDamageableItem() ? stack.getMaxDamage() : 0;
        int remaining = maximum > 0 ? Math.max(0, maximum - stack.getDamageValue()) : 0;
        presentation[index] = new PlayerInventorySlot(index, key,
                model == null ? MinecraftItemIdentity.base(stack) : model.toString(),
                stack.getCount(), stack.getMaxStackSize(), tooltip(stack, player),
                remaining, maximum);
        return true;
    }

    private static List<String> tooltip(ItemStack stack, LocalPlayer player) {
        try {
            return stack.getTooltipLines(Item.TooltipContext.of(player.level()),
                    player, TooltipFlag.NORMAL).stream().skip(1).map(Component::getString).toList();
        } catch (RuntimeException exception) {
            // A modded tooltip must not prevent the inventory from being observed.
            return List.of();
        }
    }

    @Override
    public Map<String, String> playerItemNames() {
        return itemNames;
    }

    @Override
    public List<PlayerInventorySlot> playerInventorySlots() {
        return inventorySlots;
    }

    @Override
    public String playerItemIcon(String model) {
        return MinecraftBackpackIcon.read(minecraft.getResourceManager(), model);
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
            DroppedItem item,
            Vec3 center
    ) {
        return distanceSquared(
                item.position().x() + 0.5,
                item.position().y() + 0.5,
                item.position().z() + 0.5,
                center.x,
                center.y,
                center.z
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
        return MinecraftItemIdentity.key(stack);
    }
}
