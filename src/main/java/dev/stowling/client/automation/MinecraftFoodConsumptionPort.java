package dev.stowling.client.automation;

import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
import dev.stowling.client.interaction.ClientActionScheduler;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class MinecraftFoodConsumptionPort
        implements FoodConsumptionPort {
    private static final int EATING_TIMEOUT_TICKS = 100;

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private ActiveEating active;

    public MinecraftFoodConsumptionPort(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public FoodStatus observe() {
        if (!scheduler.isClientThread()) {
            return FoodStatus.disconnected();
        }
        LocalPlayer player = minecraft.player;
        if (player == null || player.isRemoved()) {
            return FoodStatus.disconnected();
        }
        return new FoodStatus(
                true,
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel()
        );
    }

    @Override
    public FoodConsumptionResult startEating(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        if (!scheduler.isClientThread()) {
            return FoodConsumptionResult.failed(
                    "自动进食必须在客户端线程执行"
            );
        }
        if (active != null) {
            return FoodConsumptionResult.failed("已有进食操作正在进行");
        }
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null || player.isRemoved()) {
            return FoodConsumptionResult.failed("当前没有可进食的玩家");
        }
        FoodSlot slot = findFoodSlot(player.getInventory(), itemId);
        if (slot == null) {
            return FoodConsumptionResult.failed(
                    "快捷栏或副手没有食物: " + itemId
            );
        }
        int previousSelected = player.getInventory().getSelectedSlot();
        if (slot.hotbar()) {
            player.getInventory().setSelectedSlot(slot.index());
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(
                        slot.index()
                ));
            }
        }
        InteractionResult result = gameMode.useItem(player, slot.hand());
        if (!result.consumesAction()) {
            restoreSelected(player, previousSelected, slot.hotbar());
            return FoodConsumptionResult.failed(
                    "无法使用指定食物: " + itemId
            );
        }
        active = new ActiveEating(
                itemId,
                slot.hotbar(),
                previousSelected,
                player.getFoodData().getFoodLevel(),
                itemCount(player, slot)
        );
        return FoodConsumptionResult.pending("正在进食 " + itemId);
    }

    @Override
    public FoodConsumptionResult pollEating() {
        if (!scheduler.isClientThread()) {
            return FoodConsumptionResult.failed(
                    "自动进食必须在客户端线程执行"
            );
        }
        if (active == null) {
            return FoodConsumptionResult.failed("当前没有进食操作");
        }
        LocalPlayer player = minecraft.player;
        if (player == null || player.isRemoved()) {
            active = null;
            return FoodConsumptionResult.failed("进食时玩家不可用");
        }
        active.ticks++;
        if (active.ticks >= EATING_TIMEOUT_TICKS) {
            player.stopUsingItem();
            restoreSelected(
                    player,
                    active.previousSelected,
                    active.hotbar
            );
            active = null;
            return FoodConsumptionResult.failed("进食超时");
        }
        if (player.isUsingItem()) {
            return FoodConsumptionResult.pending("正在进食 " + active.itemId);
        }
        if (active.ticks < 3) {
            return FoodConsumptionResult.pending(
                    "等待进食 " + active.itemId
            );
        }
        int foodAfter = player.getFoodData().getFoodLevel();
        int countAfter = itemCount(player, active);
        boolean consumed = foodAfter > active.foodLevelBefore
                || countAfter < active.itemCountBefore;
        restoreSelected(
                player,
                active.previousSelected,
                active.hotbar
        );
        active = null;
        if (consumed) {
            return FoodConsumptionResult.succeeded("已完成一次进食");
        }
        return FoodConsumptionResult.failed("食物没有成功消耗");
    }

    @Override
    public void cancelEating() {
        if (scheduler.isClientThread()) {
            LocalPlayer player = minecraft.player;
            if (player != null) {
                player.stopUsingItem();
                if (active != null) {
                    restoreSelected(
                            player,
                            active.previousSelected,
                            active.hotbar
                    );
                }
            }
        }
        active = null;
    }

    private static FoodSlot findFoodSlot(
            Inventory inventory,
            String itemId
    ) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (itemMatches(inventory.getItem(slot), itemId)) {
                return new FoodSlot(
                        slot,
                        true,
                        InteractionHand.MAIN_HAND
                );
            }
        }
        if (itemMatches(
                inventory.getItem(Inventory.SLOT_OFFHAND),
                itemId
        )) {
            return new FoodSlot(
                    Inventory.SLOT_OFFHAND,
                    false,
                    InteractionHand.OFF_HAND
            );
        }
        return null;
    }

    private static boolean itemMatches(ItemStack stack, String itemId) {
        return !stack.isEmpty()
                && itemId.equals(
                        BuiltInRegistries.ITEM.getKey(stack.getItem())
                                .toString()
                );
    }

    private static int itemCount(LocalPlayer player, FoodSlot slot) {
        return player.getInventory().getItem(slot.index()).getCount();
    }

    private static int itemCount(LocalPlayer player, ActiveEating eating) {
        int slot = eating.hotbar
                ? player.getInventory().getSelectedSlot()
                : Inventory.SLOT_OFFHAND;
        return player.getInventory().getItem(slot).getCount();
    }

    private static void restoreSelected(
            LocalPlayer player,
            int previousSelected,
            boolean hotbar
    ) {
        if (!hotbar) {
            return;
        }
        player.getInventory().setSelectedSlot(previousSelected);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(
                    previousSelected
            ));
        }
    }

    private record FoodSlot(
            int index,
            boolean hotbar,
            InteractionHand hand
    ) {
    }

    private static final class ActiveEating {
        private final String itemId;
        private final boolean hotbar;
        private final int previousSelected;
        private final int foodLevelBefore;
        private final int itemCountBefore;
        private int ticks;

        private ActiveEating(
                String itemId,
                boolean hotbar,
                int previousSelected,
                int foodLevelBefore,
                int itemCountBefore
        ) {
            this.itemId = itemId;
            this.hotbar = hotbar;
            this.previousSelected = previousSelected;
            this.foodLevelBefore = foodLevelBefore;
            this.itemCountBefore = itemCountBefore;
        }
    }
}
