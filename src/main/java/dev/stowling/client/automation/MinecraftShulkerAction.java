package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerRecoveryReceipt;
import dev.stowling.automation.ContainerRecoveryPathPlan;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Only operates on the one registered box; never asks pathfinding to mine storage blocks. */
final class MinecraftShulkerAction implements TaskModuleExecutor {
    private static final ClassValue<Boolean> HAS_BLOCK_INTERACTION = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> block = type; block != BlockBehaviour.class;
                    block = block.getSuperclass()) {
                if (declaresMethod(block, "useWithoutItem", BlockState.class, Level.class,
                        BlockPos.class, Player.class, BlockHitResult.class)
                        || declaresMethod(block, "useItemOn", ItemStack.class, BlockState.class,
                                Level.class, BlockPos.class, Player.class, InteractionHand.class,
                                BlockHitResult.class)) {
                    return true;
                }
            }
            return false;
        }
    };

    static final class Resource {
        final BlockPosition position;
        final ItemStack original;
        final ContainerRecoveryReceipt receipt = new ContainerRecoveryReceipt();
        ItemStack expected;
        boolean placed;
        boolean broken;
        int inventoryBefore;
        BlockPosition dropPosition;
        int dropEntityId = -1;

        Resource(BlockPosition position, ItemStack original) {
            this.position = position;
            this.original = original.copyWithCount(1);
            this.expected = this.original.copy();
        }
    }

    private final Minecraft minecraft;
    private final TaskExecutionContext context;
    private final Resource resource;
    private final boolean place;
    private int phase;
    private int ticks;
    private int swapSlot = -1;
    private int selected;
    private boolean swapping;
    private TaskModuleResult terminal;
    private BlockPosition placementFeet;
    private final Set<BlockPosition> rejectedPlacementStances = new java.util.HashSet<>();
    private BlockPosition pickupTarget;
    private int pickupRadius;
    private int pickupRetries;
    private int nextPickupRetry;
    private final Set<BlockPosition> rejectedPickupTargets = new java.util.HashSet<>();

    MinecraftShulkerAction(Minecraft minecraft, TaskExecutionContext context,
            Resource resource, boolean place) {
        this.minecraft = minecraft;
        this.context = context;
        this.resource = resource;
        this.place = place;
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (terminal != null) {
            return terminal;
        }
        if (!place && resource.receipt.confirmed()) {
            terminal = TaskModuleResult.succeeded("潜影盒回收已确认，无需重复入账");
            return terminal;
        }
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            return fail("潜影盒操作需要已加载世界");
        }
        BlockPos position = new BlockPos(resource.position.x(), resource.position.y(),
                resource.position.z());
        if (!place && phase == 0 && resource.placed && resource.receipt.begun()
                && minecraft.level.getBlockState(position).isAir()) {
            resource.broken = true;
        }
        if (++ticks > 600) {
            return fail(place ? "潜影盒放置未确认" : resource.broken
                    ? "潜影盒已挖掉，回收物尚未确认；请检查掉落物与背包空间"
                    : "潜影盒挖掘未确认");
        }
        if (place && phase < 2) {
            return approachPlacement(position);
        }
        if (phase == 0) {
            if (minecraft.gui.screen() != null) {
                minecraft.player.closeContainer();
                return waitFor("正在关闭界面");
            }
            NavigationStatus navigation = resource.broken ? NavigationStatus.ARRIVED
                    : context.navigation().startInteraction(resource.position, miningArea());
            if (navigation == NavigationStatus.FAILED) {
                return fail("无法到达潜影盒操作点");
            }
            if (navigation == NavigationStatus.ARRIVED) {
                return prepareHand(position);
            }
            phase = 1;
            return waitFor("正在前往潜影盒操作点");
        }
        if (phase == 1) {
            NavigationStatus navigation = context.navigation().status(resource.position);
            if (navigation == NavigationStatus.FAILED) {
                return fail("潜影盒操作点不可达");
            }
            if (navigation != NavigationStatus.ARRIVED) {
                return waitFor("正在靠近潜影盒");
            }
            return prepareHand(position);
        }
        if (phase == 2 && ticks < 4) {
            return waitFor("等待手持物品同步");
        }
        if (place) {
            return tickPlacement(position);
        }
        return tickRecovery(position);
    }

    private TaskModuleResult approachPlacement(BlockPos site) {
        if (!clearPlacementSite(minecraft, site)) return blockedPlacement();
        if (minecraft.gui.screen() != null) {
            minecraft.player.closeContainer();
            return waitFor("正在关闭界面");
        }
        if (canPlaceFromHere(site)) {
            if (placementFeet != null) context.navigation().cancel();
            return prepareHand(site);
        }
        if (placementFeet != null) {
            if (context.navigation().status(placementFeet, 0) == NavigationStatus.NAVIGATING) {
                return waitFor("正在前往潜影盒旁的操作站位");
            }
            rejectedPlacementStances.add(placementFeet);
        }
        BlockPos player = minecraft.player.blockPosition();
        List<BlockPosition> candidates = placementStances(resource.position).stream()
                .filter(feet -> !rejectedPlacementStances.contains(feet))
                .filter(feet -> canStandAt(blockPos(feet)))
                .sorted(Comparator.comparingDouble(feet -> blockPos(feet).distSqr(player)))
                .toList();
        for (BlockPosition candidate : candidates) {
            if (context.navigation().startWithinRadius(candidate, 0) != NavigationStatus.FAILED) {
                placementFeet = candidate;
                phase = 1;
                return waitFor("正在前往潜影盒旁的操作站位");
            }
            rejectedPlacementStances.add(candidate);
        }
        return fail("放置点旁没有可到达的站位；放盒点=" + resource.position);
    }

    static List<BlockPosition> placementStances(BlockPosition site) {
        List<BlockPosition> stances = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    stances.add(site.offset(dx, dy, dz));
                }
            }
        }
        return stances;
    }

    private boolean canPlaceFromHere(BlockPos site) {
        return validSite(minecraft, site)
                && minecraft.player.isWithinBlockInteractionRange(site.below(), 0.0D);
    }

    private TaskModuleResult blockedPlacement() {
        return fail("潜影盒放置点不可用。需要空置的放盒和开盖空间，且下方为不可右键交互的坚固方块。放盒点="
                + resource.position);
    }

    private static BlockPos blockPos(BlockPosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    static AreaBounds miningArea(BlockPosition box) {
        return new AreaBounds(box.x() - 1, box.y(), box.z() - 1,
                box.x() + 1, box.y(), box.z() + 1);
    }

    private AreaBounds miningArea() {
        return miningArea(resource.position);
    }

    private TaskModuleResult prepareHand(BlockPos position) {
        selected = minecraft.player.getInventory().getSelectedSlot();
        if (place) {
            int source = findSlot(MinecraftItemIdentity.key(resource.original), false);
            if (source < 0) {
                return fail("任务持有的潜影盒已不在背包中");
            }
            swap(source);
        } else if (!resource.broken) {
            if (!sameBlock(position)) {
                return fail("登记的潜影盒已变化，停止挖掘");
            }
            int tool = findSlot("", true);
            if (tool >= 0) {
                swap(tool);
            }
        }
        phase = 2;
        ticks = 0;
        return waitFor("等待手持物品同步");
    }

    private TaskModuleResult tickPlacement(BlockPos position) {
        String key = MinecraftItemIdentity.key(resource.original);
        if (phase == 2) {
            if (!clearPlacementSite(minecraft, position)) return blockedPlacement();
            if (!canPlaceFromHere(position)) {
                restore();
                phase = 0;
                return waitFor("正在重新选择潜影盒旁的操作站位");
            }
            ItemStack held = minecraft.player.getMainHandItem();
            if (held.isEmpty() || !MinecraftItemIdentity.key(held).equals(key)) {
                return fail("手持潜影盒与任务记录不一致");
            }
            resource.inventoryBefore = inventoryCount(key);
            BlockPos support = position.below();
            minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0),
                            Direction.UP, support, false));
            phase = 3;
            ticks = 0;
            return waitFor("等待服务器确认潜影盒放置");
        }
        if (sameBlock(position) && inventoryCount(key) == resource.inventoryBefore - 1
                && ticks >= 4) {
            context.workspace().consumeDeposit("managed", Map.of(key, 1), Map.of());
            resource.placed = true;
            restore();
            terminal = TaskModuleResult.succeeded("潜影盒已放置并登记");
            return terminal;
        }
        return waitFor("等待方块与背包同步确认放置");
    }

    private TaskModuleResult tickRecovery(BlockPos position) {
        if (!resource.broken) {
            if (phase == 2) {
                if (!sameBlock(position)) {
                    return fail("潜影盒在开始挖掘前已变化");
                }
                if (!context.navigation().isWithinInteractionRange(
                        resource.position, miningArea())) {
                    return fail("未贴近潜影盒，未开始挖掘");
                }
                if (!hasRecoveryCapacity(minecraft, resource.expected)) {
                    return fail("背包没有空位回收潜影盒，尚未开始挖掘");
                }
                resource.receipt.begin(inventoryCounts(), compatibleInventoryKeys(),
                        nearbyDrops(minecraft, resource).stream().map(ItemEntity::getUUID)
                                .collect(java.util.stream.Collectors.toSet()));
                minecraft.gameMode.startDestroyBlock(position, Direction.UP);
                phase = 3;
            }
            if (!sameBlock(position)) {
                if (!minecraft.level.getBlockState(position).isAir()) {
                    return fail("潜影盒位置出现其他方块，停止操作");
                }
                resource.broken = true;
                minecraft.gameMode.stopDestroyBlock();
                restore();
                ticks = 0;
                phase = 4;
            } else {
                minecraft.gameMode.continueDestroyBlock(position, Direction.UP);
                minecraft.player.swing(InteractionHand.MAIN_HAND);
                return waitFor("正在挖掘本次登记的潜影盒");
            }
        }
        String acquired = resource.receipt.acquiredKey(inventoryCounts(), compatibleInventoryKeys())
                .orElse(null);
        if (acquired != null) {
            context.navigation().cancel();
            resource.expected = minecraft.player.getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> !stack.isEmpty()
                            && MinecraftItemIdentity.key(stack).equals(acquired))
                    .findFirst().orElseThrow().copyWithCount(1);
            resource.receipt.confirm(() -> context.inventory().recordWithdrawn(
                    context.workspace().inventoryAccount(), Map.of(acquired, 1)));
            terminal = TaskModuleResult.succeeded("已回收并核对潜影盒");
            return terminal;
        }
        if (!resource.receipt.begun()) {
            return fail("缺少潜影盒挖掘前的回收快照，已保留收尾记录");
        }
        if (!hasRecoveryCapacity(minecraft, resource.expected)) {
            return fail("背包没有空位拾取已挖掉的潜影盒，请腾出一格后重试收尾");
        }
        ItemEntity tracked = minecraft.level.getEntity(resource.dropEntityId)
                instanceof ItemEntity entity && resource.receipt.tracksDrop(entity.getUUID())
                && entity.isAlive() && !entity.isRemoved() ? entity : null;
        ItemEntity drop = tracked != null ? tracked : nearbyDrops(minecraft, resource).stream()
                .filter(entity -> resource.receipt.acceptsDrop(entity.getUUID()))
                .filter(entity -> MinecraftItemIdentity.sameRecoveredBox(
                        resource.expected, entity.getItem()))
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(
                        resource.position.x() + 0.5, resource.position.y() + 0.5,
                        resource.position.z() + 0.5))).orElse(null);
        if (drop != null) {
            resource.receipt.trackDrop(drop.getUUID());
            resource.dropEntityId = drop.getId();
            resource.dropPosition = new BlockPosition(drop.blockPosition().getX(),
                    drop.blockPosition().getY(), drop.blockPosition().getZ());
        }
        BlockPosition observed = resource.dropPosition == null ? resource.position
                : resource.dropPosition;
        if (!ContainerRecoveryPathPlan.withinArea(resource.position, observed,
                minecraft.level.getMinY(), minecraft.level.getMaxY())) {
            return fail("潜影盒掉落物已离开安全回收范围，已停止追踪；放盒点="
                    + resource.position + "；掉落物=" + observed + "；保留回收登记");
        }
        BlockPosition target = pickupFeet(observed);
        if (target == null) {
            if (pickupTarget != null) context.navigation().cancel();
            pickupTarget = null;
            return waitFor("掉落物附近没有可站立的拾取位置，等待落稳；掉落物=" + observed);
        }
        if (pickupTarget == null || !pickupTarget.equals(target)) {
            if (ticks < nextPickupRetry) return waitFor("等待切换潜影盒安全拾取站位");
            pickupTarget = target;
            pickupRadius = 0;
            dev.stowling.v2.runtime.TaskTrace.event("DECISION", "shulker.pickupTarget",
                    "site=" + resource.position + "; drop=" + observed + "; feet=" + target,
                    0, false);
            context.navigation().startWithinRadius(pickupTarget, pickupRadius);
        }
        if (context.navigation().status(pickupTarget, pickupRadius) == NavigationStatus.FAILED
                && ticks >= nextPickupRetry) {
            if (pickupRetries >= 2) {
                return fail("潜影盒安全拾取站位不可达，已停止寻路；掉落物=" + observed
                        + "；保留回收登记");
            }
            context.navigation().cancel();
            rejectedPickupTargets.add(pickupTarget);
            pickupTarget = null;
            pickupRetries++;
            nextPickupRetry = ticks + 20;
        }
        phase = 5;
        return waitFor("等待拾取并核对潜影盒内容");
    }

    private BlockPosition pickupFeet(BlockPosition drop) {
        List<BlockPosition> supported = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPosition feet = drop.offset(dx, dy, dz);
                    BlockPos position = new BlockPos(feet.x(), feet.y(), feet.z());
                    if (!rejectedPickupTargets.contains(feet) && canStandAt(position)) {
                        supported.add(feet);
                    }
                }
            }
        }
        BlockPos player = minecraft.player.blockPosition();
        return ContainerRecoveryPathPlan.select(resource.position, drop,
                new BlockPosition(player.getX(), player.getY(), player.getZ()), supported,
                minecraft.level.getMinY(), minecraft.level.getMaxY()).orElse(null);
    }

    private boolean canStandAt(BlockPos position) {
        return minecraft.level.hasChunkAt(position)
                && minecraft.level.hasChunkAt(position.below())
                && minecraft.level.getBlockState(position).getCollisionShape(
                        minecraft.level, position).isEmpty()
                && minecraft.level.getBlockState(position.above()).getCollisionShape(
                        minecraft.level, position.above()).isEmpty()
                && minecraft.level.getBlockState(position.below()).isFaceSturdy(
                        minecraft.level, position.below(), Direction.UP);
    }

    private Map<String, Integer> inventoryCounts() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        minecraft.player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> !stack.isEmpty()).forEach(stack ->
                        counts.merge(MinecraftItemIdentity.key(stack), stack.getCount(),
                                Math::addExact));
        return counts;
    }

    private Set<String> compatibleInventoryKeys() {
        return minecraft.player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> MinecraftItemIdentity.sameRecoveredBox(resource.expected, stack))
                .map(MinecraftItemIdentity::key).collect(java.util.stream.Collectors.toSet());
    }

    static boolean hasRecoveryCapacity(Minecraft minecraft, ItemStack box) {
        if (minecraft.player == null) return false;
        return minecraft.player.getInventory().getNonEquipmentItems().stream()
                .anyMatch(stack -> stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, box)
                        && stack.getCount() < stack.getMaxStackSize());
    }

    static boolean canConfirmRecovery(Minecraft minecraft, Resource resource) {
        if (minecraft.player == null || !resource.receipt.begun()) return false;
        Map<String, Integer> matching = new java.util.LinkedHashMap<>();
        minecraft.player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> MinecraftItemIdentity.sameRecoveredBox(resource.expected, stack))
                .forEach(stack -> matching.merge(MinecraftItemIdentity.key(stack), stack.getCount(),
                        Math::addExact));
        return resource.receipt.acquiredKey(matching, matching.keySet()).isPresent();
    }

    static boolean isRecoveryDrop(Minecraft minecraft, Resource resource, DroppedItem item) {
        if (!resource.receipt.acceptsDrop(item.entityId()) || minecraft.level == null) return false;
        BlockPos site = new BlockPos(resource.position.x(), resource.position.y(),
                resource.position.z());
        if (!resource.broken && !minecraft.level.getBlockState(site).isAir()) return false;
        return nearbyDrops(minecraft, resource).stream()
                .filter(entity -> entity.getUUID().equals(item.entityId()))
                .anyMatch(entity -> {
                    if (!MinecraftItemIdentity.sameRecoveredBox(resource.expected, entity.getItem())) {
                        return false;
                    }
                    resource.receipt.trackDrop(entity.getUUID());
                    resource.dropEntityId = entity.getId();
                    resource.dropPosition = new BlockPosition(entity.blockPosition().getX(),
                            entity.blockPosition().getY(), entity.blockPosition().getZ());
                    return true;
                });
    }

    private static List<ItemEntity> nearbyDrops(Minecraft minecraft, Resource resource) {
        BlockPosition center = resource.position;
        return minecraft.level.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                new AABB(new BlockPos(center.x(), center.y(), center.z())).inflate(4, 8, 4),
                entity -> entity.isAlive() && !entity.isRemoved());
    }

    private boolean sameBlock(BlockPos position) {
        return resource.original.getItem() instanceof BlockItem item
                && minecraft.level.getBlockState(position).is(item.getBlock());
    }

    static boolean validSite(Minecraft minecraft, BlockPos position) {
        return minecraft.player != null && clearPlacementSite(minecraft, position)
                && !minecraft.player.getBoundingBox().intersects(new AABB(position));
    }

    private static boolean clearPlacementSite(Minecraft minecraft, BlockPos position) {
        BlockPos support = position.below();
        return minecraft.level != null && minecraft.level.hasChunkAt(position)
                && minecraft.level.getBlockState(position).isAir()
                && minecraft.level.getBlockState(position.above()).isAir()
                && minecraft.level.getBlockEntity(support) == null
                && isPlacementSupport(minecraft.level.getBlockState(support),
                        minecraft.level, support);
    }

    static boolean isPlacementSupport(BlockState support, BlockGetter level, BlockPos position) {
        // Check for right-click handlers without calling them on the live world.
        return support.isFaceSturdy(level, position, Direction.UP)
                && !HAS_BLOCK_INTERACTION.get(support.getBlock().getClass());
    }

    private static boolean declaresMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            type.getDeclaredMethod(name, parameters);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private int findSlot(String key, boolean tool) {
        for (int index : MinecraftStorageAccess.slots(minecraft.player.inventoryMenu, true)) {
            Slot slot = minecraft.player.inventoryMenu.getSlot(index);
            if (slot.getContainerSlot() >= 36 || slot.getItem().isEmpty()) {
                continue;
            }
            if (tool ? slot.getItem().is(ItemTags.PICKAXES)
                    : MinecraftItemIdentity.key(slot.getItem()).equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private void swap(int source) {
        if (minecraft.player.inventoryMenu.getSlot(source).getContainerSlot() == selected) {
            return;
        }
        swapSlot = source;
        minecraft.gameMode.handleContainerInput(minecraft.player.inventoryMenu.containerId,
                source, selected, ContainerInput.SWAP, minecraft.player);
        swapping = true;
    }

    private void restore() {
        if (swapping && minecraft.player != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleContainerInput(minecraft.player.inventoryMenu.containerId,
                    swapSlot, selected, ContainerInput.SWAP, minecraft.player);
            swapping = false;
        }
    }

    private int inventoryCount(String key) {
        return context.droppedItems().playerItemCounts().orElse(Map.of()).getOrDefault(key, 0);
    }

    private TaskModuleResult fail(String message) {
        context.navigation().cancel();
        if (minecraft.gameMode != null) {
            minecraft.gameMode.stopDestroyBlock();
        }
        restore();
        terminal = TaskModuleResult.failed(message);
        return terminal;
    }

    private static TaskModuleResult waitFor(String message) {
        return TaskModuleResult.waiting(message);
    }

    @Override
    public boolean safeToStop() {
        return terminal != null;
    }

    @Override
    public void requestStop() {
        // Finish the placement or recovery acknowledgement before handing off to global cleanup.
    }

    @Override
    public void cancel() {
        if (terminal != null) {
            restore();
        }
    }
}
