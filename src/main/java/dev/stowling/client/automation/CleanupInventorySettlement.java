package dev.stowling.client.automation;

import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.v2.runtime.modules.PlayerInventoryAccount;
import dev.stowling.v2.runtime.modules.PlayerInventoryManager;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;

/** Captures one cleanup batch so continuous input cannot extend an interrupted run forever. */
final class CleanupInventorySettlement implements TaskModuleExecutor {
    private static final int STABLE_TICKS = 4;
    private static final int MAX_SETTLE_TICKS = 20;

    private final PlayerInventoryManager inventory;
    private final PlayerInventoryAccount account;
    private PlayerInventorySnapshot previous;
    private int stableTicks;
    private int ticks;
    private TaskModuleResult terminal;

    CleanupInventorySettlement(PlayerInventoryManager inventory, PlayerInventoryAccount account) {
        this.inventory = inventory;
        this.account = account;
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (terminal != null) return terminal;
        var observed = inventory.observe();
        if (observed.isEmpty()) {
            terminal = TaskModuleResult.failed("无法读取真实背包，收尾核对已停止");
            return terminal;
        }
        PlayerInventorySnapshot current = observed.orElseThrow().snapshot();
        stableTicks = current.equals(previous) ? stableTicks + 1 : 0;
        previous = current;
        ticks++;
        if (stableTicks < STABLE_TICKS && ticks < MAX_SETTLE_TICKS) {
            return TaskModuleResult.waiting("等待背包稳定，核对本批收尾物品");
        }
        // Recovery has finished; later pickups belong to the next intake batch.
        inventory.reconcileMissingItems(account);
        inventory.claimForCleanup(account);
        terminal = TaskModuleResult.succeeded("已核对本批收尾物品，后续投料交给下一批");
        return terminal;
    }

    @Override
    public void cancel() {
    }
}
