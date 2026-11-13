package dev.stowling.client.interaction;

import dev.stowling.automation.ContainerAttempt;
import dev.stowling.automation.ContainerAttemptResult;
import dev.stowling.automation.ContainerAttemptStatus;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.ContainerSlot;
import dev.stowling.automation.ContainerTransfer;
import dev.stowling.automation.ContainerView;
import dev.stowling.automation.ItemAmount;
import dev.stowling.domain.ContainerRef;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryItemKind;
import dev.stowling.inventory.MenuSlotClassification;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Confirms each container action from a newer menu state before issuing another click.
 */
public final class MinecraftContainerInteraction implements ContainerInteraction {
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final Map<String, Transaction> transactions = new HashMap<>();

    private ContainerRef openedContainer;
    private ContainerRef pendingOpen;
    private Instant pendingOpenedAt;

    public MinecraftContainerInteraction(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void prepare(ContainerRef container) {
        requireClientThread();
        Objects.requireNonNull(container, "container");
        if (container.equals(openedContainer)) {
            return;
        }
        Instant now = Instant.now();
        if (container.equals(pendingOpen)
                && pendingOpenedAt != null
                && Duration.between(pendingOpenedAt, now).compareTo(OPEN_TIMEOUT) < 0) {
            return;
        }
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen != null) {
            closeCurrentContainer();
            return;
        }
        openBlock(container);
        pendingOpen = container;
        pendingOpenedAt = now;
    }

    @Override
    public Optional<ContainerView> observe(ContainerRef container) {
        requireClientThread();
        Objects.requireNonNull(container, "container");
        prepare(container);
        AbstractContainerMenu menu = currentContainerMenu();
        if (menu == null) {
            return Optional.empty();
        }
        if (pendingOpen != null) {
            openedContainer = pendingOpen;
            pendingOpen = null;
            pendingOpenedAt = null;
        }
        if (!container.equals(openedContainer)) {
            return Optional.empty();
        }
        return Optional.of(view(menu, container));
    }

    Optional<List<InventoryCapture.SlotCapture>> captureInventory(
            ContainerRef container
    ) {
        requireClientThread();
        Objects.requireNonNull(container, "container");
        prepare(container);
        AbstractContainerMenu menu = currentContainerMenu();
        if (menu == null) {
            return Optional.empty();
        }
        if (pendingOpen != null) {
            openedContainer = pendingOpen;
            pendingOpen = null;
            pendingOpenedAt = null;
        }
        if (!container.equals(openedContainer)) {
            return Optional.empty();
        }
        return Optional.of(captureSlots(menu));
    }

    @Override
    public ContainerAttempt begin(ContainerTransfer transfer, long baselineRevision) {
        requireClientThread();
        Objects.requireNonNull(transfer, "transfer");
        if (baselineRevision < 0) {
            throw new IllegalArgumentException("baselineRevision must not be negative");
        }
        String attemptId = UUID.randomUUID().toString();
        Transaction transaction = new Transaction(
                transfer,
                transfer.source().isPresent(),
                baselineRevision
        );
        transactions.put(attemptId, transaction);
        ContainerRef initialTarget = transfer.source().orElse(transfer.destination());
        prepare(initialTarget);
        return new ContainerAttempt(
                attemptId,
                transfer,
                baselineRevision,
                Instant.now()
        );
    }

    @Override
    public ContainerAttemptResult poll(ContainerAttempt attempt) {
        requireClientThread();
        Objects.requireNonNull(attempt, "attempt");
        Transaction transaction = transactions.get(attempt.attemptId());
        if (transaction == null) {
            return unavailable(attempt, "attempt is no longer active");
        }
        AbstractContainerMenu menu = currentContainerMenu();
        if (menu == null) {
            ContainerRef target = transaction.phase == Phase.EXTRACT
                    ? transaction.transfer.source().orElseThrow()
                    : transaction.transfer.destination();
            prepare(target);
            return pending(attempt, transaction.baselineRevision, "waiting for container");
        }
        if (pendingOpen != null) {
            openedContainer = pendingOpen;
            pendingOpen = null;
            pendingOpenedAt = null;
        }
        if (transaction.pendingRevision >= 0) {
            long revision = revision(menu);
            if (revision <= transaction.pendingRevision) {
                return pending(attempt, revision, "waiting for server slot confirmation");
            }
            applyConfirmedClick(transaction, menu);
            transaction.pendingRevision = -1;
        }
        return switch (transaction.phase) {
            case OPEN_SOURCE -> openSource(attempt, transaction);
            case EXTRACT -> extract(attempt, transaction, menu);
            case OPEN_DESTINATION -> openDestination(attempt, transaction);
            case PLACE -> place(attempt, transaction, menu);
        };
    }

    @Override
    public void cancel(ContainerAttempt attempt) {
        requireClientThread();
        Objects.requireNonNull(attempt, "attempt");
        Transaction transaction = transactions.remove(attempt.attemptId());
        if (transaction == null) {
            return;
        }
        ContainerRef activeTarget = openedContainer;
        if (activeTarget != null
                && (activeTarget.equals(transaction.transfer.destination())
                || transaction.transfer.source().filter(activeTarget::equals).isPresent())) {
            closeCurrentContainer();
        }
    }

    private ContainerAttemptResult openSource(
            ContainerAttempt attempt,
            Transaction transaction
    ) {
        ContainerRef source = transaction.transfer.source().orElseThrow();
        prepare(source);
        AbstractContainerMenu menu = currentContainerMenu();
        if (menu == null || !source.equals(openedContainer)) {
            return pending(attempt, transaction.baselineRevision, "opening source container");
        }
        transaction.sourceView = view(menu, source);
        transaction.phase = Phase.EXTRACT;
        return pending(attempt, revision(menu), "source container is ready");
    }

    private ContainerAttemptResult extract(
            ContainerAttempt attempt,
            Transaction transaction,
            AbstractContainerMenu menu
    ) {
        ContainerRef source = transaction.transfer.source().orElseThrow();
        if (!source.equals(openedContainer)) {
            prepare(source);
            return pending(attempt, revision(menu), "source container is no longer open");
        }
        if (transaction.remaining.isEmpty()) {
            closeCurrentContainer();
            transaction.phase = Phase.OPEN_DESTINATION;
            prepare(transaction.transfer.destination());
            return pending(attempt, revision(menu), "opening destination container");
        }
        for (int slotIndex : slotClassification(menu).containerSlots()) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = itemId(stack);
            int wanted = transaction.remaining.getOrDefault(itemId, 0);
            if (wanted <= 0) {
                continue;
            }
            click(menu, slotIndex, 0, ContainerInput.QUICK_MOVE);
            transaction.pendingRevision = revision(menu);
            transaction.pendingClick = PendingClick.EXTRACT;
            transaction.pendingSlot = slotIndex;
            transaction.pendingItemId = itemId;
            transaction.pendingCount = stack.getCount();
            return pending(attempt, transaction.pendingRevision, "source transfer sent");
        }
        return rejected(attempt, menu, "requested items are absent from the source");
    }

    private ContainerAttemptResult openDestination(
            ContainerAttempt attempt,
            Transaction transaction
    ) {
        ContainerRef destination = transaction.transfer.destination();
        prepare(destination);
        AbstractContainerMenu menu = currentContainerMenu();
        if (menu == null || !destination.equals(openedContainer)) {
            return pending(attempt, revisionOrBaseline(menu, transaction), "opening destination");
        }
        transaction.destinationView = view(menu, destination);
        transaction.phase = Phase.PLACE;
        return pending(attempt, revision(menu), "destination container is ready");
    }

    private ContainerAttemptResult place(
            ContainerAttempt attempt,
            Transaction transaction,
            AbstractContainerMenu menu
    ) {
        ContainerRef destination = transaction.transfer.destination();
        if (!destination.equals(openedContainer)) {
            prepare(destination);
            return pending(attempt, revision(menu), "destination container is no longer open");
        }
        if (transaction.remaining.isEmpty()) {
            ContainerView destinationView = view(menu, destination);
            transactions.remove(attempt.attemptId());
            return new ContainerAttemptResult(
                    ContainerAttemptStatus.ACKNOWLEDGED,
                    revision(menu),
                    Optional.ofNullable(transaction.sourceView),
                    Optional.of(destinationView),
                    "all requested slots were confirmed by the server"
            );
        }
        MenuSlotClassification slots = slotClassification(menu);
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            int picked = pickNextPlayerStack(menu, slots.playerSlots(), transaction.remaining);
            if (picked < 0) {
                return rejected(attempt, menu, "player inventory lacks the requested quantity");
            }
            ItemStack pickedStack = menu.getSlot(picked).getItem();
            click(menu, picked, 0, ContainerInput.PICKUP);
            transaction.pendingRevision = revision(menu);
            transaction.pendingClick = PendingClick.PICK_UP;
            transaction.pendingSlot = picked;
            transaction.pendingItemId = itemId(pickedStack);
            transaction.pendingCount = pickedStack.getCount();
            return pending(attempt, transaction.pendingRevision, "player stack pickup sent");
        }
        String carriedItem = itemId(carried);
        int wanted = transaction.remaining.getOrDefault(carriedItem, 0);
        if (wanted <= 0) {
            return rejected(attempt, menu, "cursor contains an unexpected item");
        }
        int destinationSlot = findDestinationSlot(menu, slots.containerSlots(), carried);
        if (destinationSlot < 0) {
            return rejected(attempt, menu, "destination container has no compatible slot");
        }
        int button = carried.getCount() <= wanted ? 0 : 1;
        click(menu, destinationSlot, button, ContainerInput.PICKUP);
        transaction.pendingRevision = revision(menu);
        transaction.pendingClick = PendingClick.PLACE;
        transaction.pendingSlot = destinationSlot;
        transaction.pendingItemId = carriedItem;
        transaction.pendingCount = carried.getCount();
        return pending(attempt, transaction.pendingRevision, "destination slot click sent");
    }

    private void applyConfirmedClick(Transaction transaction, AbstractContainerMenu menu) {
        if (transaction.pendingClick == PendingClick.NONE) {
            return;
        }
        String pendingItem = transaction.pendingItemId;
        int before = transaction.pendingCount;
        int after;
        if (transaction.pendingClick == PendingClick.EXTRACT) {
            after = menu.getSlot(transaction.pendingSlot).getItem().getCount();
        } else if (transaction.pendingClick == PendingClick.PICK_UP) {
            ItemStack carried = menu.getCarried();
            after = carried.isEmpty() || !pendingItem.equals(itemId(carried))
                    ? 0
                    : carried.getCount();
        } else if (transaction.pendingClick == PendingClick.PLACE) {
            ItemStack carried = menu.getCarried();
            after = carried.isEmpty() || !pendingItem.equals(itemId(carried))
                    ? 0
                    : carried.getCount();
        } else {
            after = before;
        }
        runChanges(transaction, pendingItem, before, after);
        transaction.pendingClick = PendingClick.NONE;
    }

    private void runChanges(Transaction transaction, String itemId, int before, int after) {
        if (transaction.pendingClick == PendingClick.EXTRACT) {
            int moved = Math.max(0, before - after);
            decrement(transaction.remaining, itemId, moved);
        } else if (transaction.pendingClick == PendingClick.PLACE) {
            int moved = Math.max(0, before - after);
            decrement(transaction.remaining, itemId, moved);
        }
    }

    private static void decrement(Map<String, Integer> remaining, String itemId, int amount) {
        if (amount <= 0) {
            return;
        }
        int next = Math.max(0, remaining.getOrDefault(itemId, 0) - amount);
        if (next == 0) {
            remaining.remove(itemId);
        } else {
            remaining.put(itemId, next);
        }
    }

    private static Map<String, Integer> remaining(List<ItemAmount> items) {
        Map<String, Integer> result = new HashMap<>();
        for (ItemAmount item : items) {
            result.merge(item.itemId(), item.count(), Math::addExact);
        }
        return result;
    }

    private static int pickNextPlayerStack(
            AbstractContainerMenu menu,
            List<Integer> playerSlots,
            Map<String, Integer> remaining
    ) {
        for (int slot : playerSlots) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && remaining.getOrDefault(itemId(stack), 0) > 0) {
                return slot;
            }
        }
        return -1;
    }

    private static int findDestinationSlot(
            AbstractContainerMenu menu,
            List<Integer> containerSlots,
            ItemStack carried
    ) {
        String carriedId = itemId(carried);
        for (int slot : containerSlots) {
            Slot candidate = menu.getSlot(slot);
            ItemStack existing = candidate.getItem();
            if (existing.isEmpty()) {
                return slot;
            }
            if (carriedId.equals(itemId(existing))
                    && existing.getCount() < candidate.getMaxStackSize(existing)) {
                return slot;
            }
        }
        return -1;
    }

    private void openBlock(ContainerRef container) {
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null) {
            return;
        }
        BlockPos position = new BlockPos(
                container.position().x(),
                container.position().y(),
                container.position().z()
        );
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(position),
                Direction.UP,
                position,
                false
        );
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    private void click(
            AbstractContainerMenu menu,
            int slot,
            int button,
            ContainerInput input
    ) {
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        LocalPlayer player = minecraft.player;
        if (gameMode == null || player == null) {
            return;
        }
        gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
    }

    private void closeCurrentContainer() {
        LocalPlayer player = minecraft.player;
        if (player != null) {
            player.closeContainer();
        }
        openedContainer = null;
        pendingOpen = null;
        pendingOpenedAt = null;
    }

    private AbstractContainerScreen<?> currentContainerScreen() {
        if (minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            return screen;
        }
        return null;
    }

    private AbstractContainerMenu currentContainerMenu() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            return null;
        }
        AbstractContainerMenu menu = screen.getMenu();
        return slotClassification(menu).containerSlots().isEmpty() ? null : menu;
    }

    private static MenuSlotClassification slotClassification(
            AbstractContainerMenu menu
    ) {
        List<Boolean> playerSlotFlags = new ArrayList<>();
        for (Slot slot : menu.slots) {
            playerSlotFlags.add(slot.container instanceof Inventory);
        }
        return MenuSlotClassification.classify(playerSlotFlags);
    }

    private static ContainerView view(AbstractContainerMenu menu, ContainerRef container) {
        MenuSlotClassification classification = slotClassification(menu);
        List<ContainerSlot> slots = new ArrayList<>();
        for (MenuSlotClassification.ContainerSlotMapping mapping
                : classification.logicalContainerSlots()) {
            ItemStack stack = menu.getSlot(mapping.menuSlot()).getItem();
            if (!stack.isEmpty()) {
                slots.add(new ContainerSlot(
                        mapping.logicalSlot(),
                        itemId(stack),
                        stack.getCount()
                ));
            }
        }
        return new ContainerView(
                container.id(),
                container.dimension(),
                revision(menu),
                classification.containerCapacity(),
                slots
        );
    }

    private static List<InventoryCapture.SlotCapture> captureSlots(
            AbstractContainerMenu menu
    ) {
        List<InventoryCapture.SlotCapture> captures = new ArrayList<>();
        for (int slot : slotClassification(menu).containerSlots()) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty()) {
                captures.add(captureSlot(slot, stack));
            }
        }
        return List.copyOf(captures);
    }

    private static InventoryCapture.SlotCapture captureSlot(int slot, ItemStack stack) {
        String itemId = itemId(stack);
        if (!isShulkerBox(stack)) {
            return new InventoryCapture.SlotCapture(
                    slot,
                    itemId,
                    stack.getCount(),
                    InventoryItemKind.LOOSE,
                    List.of()
            );
        }
        try {
            ItemContainerContents contents = stack.getOrDefault(
                    DataComponents.CONTAINER,
                    ItemContainerContents.EMPTY
            );
            List<InventoryCapture.ItemCount> nested = contents
                    .nonEmptyItemCopyStream()
                    .map(item -> new InventoryCapture.ItemCount(
                            itemId(item),
                            item.getCount()
                    ))
                    .toList();
            return new InventoryCapture.SlotCapture(
                    slot,
                    itemId,
                    stack.getCount(),
                    InventoryItemKind.BOXED,
                    nested
            );
        } catch (RuntimeException exception) {
            return new InventoryCapture.SlotCapture(
                    slot,
                    itemId,
                    stack.getCount(),
                    InventoryItemKind.UNKNOWN,
                    List.of()
            );
        }
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static long revision(AbstractContainerMenu menu) {
        long containerId = Integer.toUnsignedLong(menu.containerId);
        long stateId = Integer.toUnsignedLong(menu.getStateId());
        return (containerId << 32) | stateId;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static ContainerAttemptResult pending(
            ContainerAttempt attempt,
            long revision,
            String detail
    ) {
        return new ContainerAttemptResult(
                ContainerAttemptStatus.PENDING,
                Math.max(attempt.baselineRevision(), revision),
                Optional.empty(),
                Optional.empty(),
                detail
        );
    }

    private static long revisionOrBaseline(
            AbstractContainerMenu menu,
            Transaction transaction
    ) {
        return menu == null ? transaction.baselineRevision : revision(menu);
    }

    private ContainerAttemptResult rejected(
            ContainerAttempt attempt,
            AbstractContainerMenu menu,
            String detail
    ) {
        transactions.remove(attempt.attemptId());
        return new ContainerAttemptResult(
                ContainerAttemptStatus.REJECTED,
                revision(menu),
                Optional.empty(),
                Optional.empty(),
                detail
        );
    }

    private static ContainerAttemptResult unavailable(
            ContainerAttempt attempt,
            String detail
    ) {
        return new ContainerAttemptResult(
                ContainerAttemptStatus.UNAVAILABLE,
                attempt.baselineRevision(),
                Optional.empty(),
                Optional.empty(),
                detail
        );
    }

    private void requireClientThread() {
        if (!scheduler.isClientThread()) {
            throw new IllegalStateException("container interaction must run on client thread");
        }
    }

    private enum Phase {
        OPEN_SOURCE,
        EXTRACT,
        OPEN_DESTINATION,
        PLACE
    }

    private enum PendingClick {
        NONE,
        EXTRACT,
        PICK_UP,
        PLACE
    }

    private static final class Transaction {
        private final ContainerTransfer transfer;
        private final long baselineRevision;
        private final Map<String, Integer> remaining;
        private Phase phase;
        private ContainerView sourceView;
        private ContainerView destinationView;
        private long pendingRevision = -1;
        private PendingClick pendingClick = PendingClick.NONE;
        private int pendingSlot = -1;
        private String pendingItemId;
        private int pendingCount;

        private Transaction(
                ContainerTransfer transfer,
                boolean hasSource,
                long baselineRevision
        ) {
            this.transfer = transfer;
            this.baselineRevision = baselineRevision;
            this.remaining = remaining(transfer.items());
            this.phase = hasSource ? Phase.OPEN_SOURCE : Phase.OPEN_DESTINATION;
        }
    }
}
