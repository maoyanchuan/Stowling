package dev.stowling.client.interaction;

import dev.stowling.automation.ContainerAttempt;
import dev.stowling.automation.ContainerAttemptResult;
import dev.stowling.automation.ContainerAttemptStatus;
import dev.stowling.automation.ContainerTransfer;
import dev.stowling.automation.ContainerView;
import dev.stowling.automation.ItemAmount;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.packing.PackingBoxContents;
import dev.stowling.packing.PackingStationException;
import dev.stowling.packing.PackingStationPort;
import dev.stowling.packing.PackingStationTimeoutException;
import dev.stowling.packing.PackingWorkcell;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Minecraft primitives for a fixed shulker-box workcell.
 *
 * <p>Live orchestration only consumes confirmed menu states. A call that would
 * need another client tick reports a timeout so callers retry with a new attempt.
 */
public final class MinecraftPackingStationPort implements PackingStationPort {
    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final MinecraftContainerInteraction containers;
    private final Map<String, PendingInsert> pendingInserts = new HashMap<>();

    public MinecraftPackingStationPort(
            Minecraft minecraft,
            ClientActionScheduler scheduler,
            MinecraftContainerInteraction containers
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.containers = Objects.requireNonNull(containers, "containers");
    }

    @Override
    public PackingBoxContents inspect(PackingWorkcell workcell)
            throws PackingStationException {
        requireClientThread();
        ContainerRef box = boxRef(workcell);
        containers.prepare(box);
        ContainerView view = containers.observe(box).orElseThrow(
                () -> timeout("waiting for the shulker-box screen")
        );
        Map<String, Integer> counts = new HashMap<>();
        for (var slot : view.slots()) {
            counts.merge(slot.itemId(), slot.count(), Math::addExact);
        }
        return new PackingBoxContents(counts, view.slots().size(), view.slotCapacity());
    }

    @Override
    public int insert(
            PackingWorkcell workcell,
            String itemId,
            int maximumQuantity
    ) throws PackingStationException {
        requireClientThread();
        Objects.requireNonNull(itemId, "itemId");
        if (maximumQuantity < 1) {
            throw new IllegalArgumentException("maximumQuantity must be positive");
        }
        ContainerRef box = boxRef(workcell);
        String stationId = workcell.station().id();
        PendingInsert pending = pendingInserts.get(stationId);
        if (pending == null) {
            ContainerView view = containers.observe(box).orElseThrow(
                    () -> timeout("shulker box must be open before insertion")
            );
            int quantity = Math.min(maximumQuantity, capacity(view, itemId));
            if (quantity == 0) {
                return 0;
            }
            ContainerTransfer transfer = new ContainerTransfer(
                    stationId + ":pack:" + itemId,
                    Optional.empty(),
                    box,
                    List.of(new ItemAmount(itemId, quantity))
            );
            ContainerAttempt attempt = containers.begin(transfer, view.revision());
            pending = new PendingInsert(attempt, quantity);
            pendingInserts.put(stationId, pending);
            containers.poll(attempt);
            return 0;
        }
        ContainerAttemptResult result = containers.poll(pending.attempt());
        if (result.status() == ContainerAttemptStatus.ACKNOWLEDGED) {
            pendingInserts.remove(stationId);
            return pending.quantity();
        }
        if (result.status() == ContainerAttemptStatus.REJECTED
                || result.status() == ContainerAttemptStatus.UNAVAILABLE) {
            pendingInserts.remove(stationId);
            throw new PackingStationException(result.detail());
        }
        throw timeout("waiting for slot confirmation");
    }

    @Override
    public void close(PackingWorkcell workcell) {
        requireClientThread();
        ContainerRef box = boxRef(workcell);
        if (containers.observe(box).isPresent() && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
        pendingInserts.remove(workcell.station().id());
    }

    @Override
    public void triggerBreak(PackingWorkcell workcell) throws PackingStationException {
        requireClientThread();
        ContainerRef box = boxRef(workcell);
        BlockPos position = new BlockPos(
                box.position().x(),
                box.position().y(),
                box.position().z()
        );
        if (minecraft.level == null || minecraft.gameMode == null
                || minecraft.level.getBlockState(position).isAir()) {
            throw new PackingStationException("shulker box is not present");
        }
        if (minecraft.player != null) {
            minecraft.player.closeContainer();
        }
        boolean destroyed = minecraft.gameMode.destroyBlock(position);
        if (!destroyed) {
            throw timeout("shulker-box break was not confirmed");
        }
    }

    @Override
    public boolean awaitPackedBoxInProductContainer(
            PackingWorkcell workcell,
            String itemId
    ) throws PackingStationException {
        requireClientThread();
        Objects.requireNonNull(itemId, "itemId");
        ContainerRef product = workcell.station().productContainer();
        containers.prepare(product);
        Optional<ContainerView> view = containers.observe(product);
        if (view.isEmpty()) {
            return false;
        }
        return view.orElseThrow().itemCounts().getOrDefault(itemId, 0) > 0;
    }

    private static int capacity(ContainerView view, String itemId) {
        int occupied = view.itemCounts().getOrDefault(itemId, 0);
        int emptySlots = view.freeSlots();
        int remainingInStacks = Math.max(0, 64 - (occupied % 64));
        return Math.addExact(emptySlots * 64, remainingInStacks);
    }

    private static ContainerRef boxRef(PackingWorkcell workcell) {
        BlockPosition position = workcell.station().shulkerBoxPosition();
        return new ContainerRef(
                workcell.station().id() + "-box",
                workcell.station().dimension(),
                position,
                ContainerType.SHULKER_BOX
        );
    }

    private static PackingStationTimeoutException timeout(String message) {
        return new PackingStationTimeoutException(message);
    }

    private void requireClientThread() {
        if (!scheduler.isClientThread()) {
            throw new IllegalStateException("packing station access must run on client thread");
        }
    }

    private record PendingInsert(ContainerAttempt attempt, int quantity) {
    }
}
