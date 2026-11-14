package dev.stowling.client.interaction;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.ContainerAttempt;
import dev.stowling.automation.ContainerAttemptResult;
import dev.stowling.automation.ContainerAttemptStatus;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.ContainerSlot;
import dev.stowling.automation.ContainerTransfer;
import dev.stowling.automation.ContainerView;
import dev.stowling.automation.ItemAmount;
import dev.stowling.automation.ItemTransferPort;
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
import java.util.function.Function;
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
 * Confirms each container action from a stable predicted menu state before another click.
 */
public final class MinecraftContainerInteraction
        implements ContainerInteraction, ItemTransferPort {
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);
    private static final String PLAYER = "PLAYER";

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final Function<String, Optional<ContainerRef>> containerResolver;
    private final OperationDriver operationDriver;
    private final Map<String, Transaction> transactions = new HashMap<>();
    private final Map<String, ActiveOperation> operations = new HashMap<>();

    private ContainerRef openedContainer;
    private ContainerRef pendingOpen;
    private Instant pendingOpenedAt;

    public MinecraftContainerInteraction(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this(
                Objects.requireNonNull(minecraft, "minecraft"),
                scheduler,
                ignored -> Optional.empty()
        );
    }

    public MinecraftContainerInteraction(
            Minecraft minecraft,
            ClientActionScheduler scheduler,
            Function<String, Optional<ContainerRef>> containerResolver
    ) {
        this(
                Objects.requireNonNull(minecraft, "minecraft"),
                scheduler,
                containerResolver,
                null
        );
    }

    MinecraftContainerInteraction(
            ClientActionScheduler scheduler,
            Function<String, Optional<ContainerRef>> containerResolver,
            OperationDriver operationDriver
    ) {
        this(null, scheduler, containerResolver, operationDriver);
    }

    private MinecraftContainerInteraction(
            Minecraft minecraft,
            ClientActionScheduler scheduler,
            Function<String, Optional<ContainerRef>> containerResolver,
            OperationDriver operationDriver
    ) {
        this.minecraft = minecraft;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.containerResolver = Objects.requireNonNull(
                containerResolver,
                "containerResolver"
        );
        this.operationDriver = operationDriver == null
                ? new TransactionOperationDriver()
                : operationDriver;
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
    public AutomationOperationResult start(
            String operationKey,
            ItemTransferPort.Request request
    ) {
        requireClientThread();
        String checkedKey = requireText(operationKey, "operationKey");
        Objects.requireNonNull(request, "request");
        if (operations.containsKey(checkedKey)) {
            return AutomationOperationResult.running(
                    "operation is already active; poll the same operation key"
            );
        }
        Optional<MappedOperation> mapped = mapOperation(request);
        if (mapped.isEmpty()) {
            return AutomationOperationResult.failed(
                    "container id is not available for the requested transfer"
            );
        }
        AttemptToken token = new AttemptToken(checkedKey, 0);
        ActiveOperation operation = new ActiveOperation(
                token,
                mapped.orElseThrow()
        );
        operations.put(checkedKey, operation);
        try {
            ContainerAttemptResult result = operationDriver.start(
                    token,
                    operation.mapping().specification()
            );
            return mapPendingStart(operation, result);
        } catch (RuntimeException exception) {
            operations.remove(checkedKey);
            operationDriver.cancel(token);
            throw exception;
        }
    }

    @Override
    public AutomationOperationResult poll(String operationKey) {
        requireClientThread();
        String checkedKey = requireText(operationKey, "operationKey");
        ActiveOperation operation = operations.get(checkedKey);
        if (operation == null) {
            return AutomationOperationResult.failed(
                    "operation is not active; manual recovery is required"
            );
        }
        return pollOperation(operation);
    }

    @Override
    public AutomationOperationResult reconcile(String operationKey) {
        requireClientThread();
        String checkedKey = requireText(operationKey, "operationKey");
        ActiveOperation operation = operations.get(checkedKey);
        if (operation == null) {
            return AutomationOperationResult.paused(
                    "operation state is unavailable; manual recovery is required"
            );
        }
        return pollOperation(operation);
    }

    private AutomationOperationResult mapPendingStart(
            ActiveOperation operation,
            ContainerAttemptResult result
    ) {
        Objects.requireNonNull(result, "operation start result");
        if (result.status() == ContainerAttemptStatus.ACKNOWLEDGED
                || result.status() == ContainerAttemptStatus.REJECTED
                || result.status() == ContainerAttemptStatus.UNAVAILABLE) {
            return completeOperation(operation, result);
        }
        return AutomationOperationResult.running(result.detail());
    }

    private AutomationOperationResult pollOperation(ActiveOperation operation) {
        ContainerAttemptResult result = operationDriver.poll(operation.token());
        if (result.status() == ContainerAttemptStatus.PENDING) {
            return AutomationOperationResult.running(result.detail());
        }
        return completeOperation(operation, result);
    }

    private AutomationOperationResult completeOperation(
            ActiveOperation operation,
            ContainerAttemptResult result
    ) {
        operations.remove(operation.token().attemptId());
        return switch (result.status()) {
            case ACKNOWLEDGED -> AutomationOperationResult.succeeded(
                    operation.mapping().confirmedAction()
            );
            case REJECTED, UNAVAILABLE -> AutomationOperationResult.failed(
                    result.detail() + "; manual recovery is required"
            );
            case PENDING -> AutomationOperationResult.running(result.detail());
        };
    }

    private Optional<MappedOperation> mapOperation(ItemTransferPort.Request request) {
        Optional<ContainerRef> source;
        Optional<ContainerRef> destination;
        switch (request.mode()) {
            case PUT -> {
                if (!PLAYER.equals(request.source())) {
                    return Optional.empty();
                }
                source = Optional.empty();
                destination = resolveContainer(request.destination());
                if (destination.isEmpty()) {
                    return Optional.empty();
                }
            }
            case TAKE -> {
                if (!PLAYER.equals(request.destination())) {
                    return Optional.empty();
                }
                source = resolveContainer(request.source());
                destination = Optional.empty();
                if (source.isEmpty()) {
                    return Optional.empty();
                }
            }
            case TRANSFER -> {
                source = resolveContainer(request.source());
                destination = resolveContainer(request.destination());
                if (source.isEmpty() || destination.isEmpty()) {
                    return Optional.empty();
                }
                ContainerRef sourceRef = source.orElseThrow();
                ContainerRef destinationRef = destination.orElseThrow();
                if (sourceRef.equals(destinationRef)
                        || !sourceRef.dimension().equals(destinationRef.dimension())) {
                    return Optional.empty();
                }
            }
            default -> throw new IllegalStateException("unexpected transfer mode");
        }
        TransferSpec specification = new TransferSpec(
                source,
                destination,
                List.of(new ItemAmount(request.itemId(), request.count()))
        );
        String action = confirmedAction(request);
        return Optional.of(new MappedOperation(
                request,
                specification,
                action
        ));
    }

    private Optional<ContainerRef> resolveContainer(String containerId) {
        return containerResolver.apply(containerId);
    }

    private static String confirmedAction(ItemTransferPort.Request request) {
        return switch (request.mode()) {
            case TAKE -> "TAKE " + request.itemId()
                    + " from " + request.source()
                    + " to PLAYER confirmed by source quick move with empty cursor";
            case PUT -> "PUT " + request.itemId()
                    + " from PLAYER to " + request.destination()
                    + " confirmed by server menu state";
            case TRANSFER -> "TRANSFER " + request.itemId()
                    + " from " + request.source()
                    + " to " + request.destination()
                    + " confirmed by server menu state";
        };
    }

    @Override
    public ContainerAttempt begin(ContainerTransfer transfer, long baselineRevision) {
        requireClientThread();
        Objects.requireNonNull(transfer, "transfer");
        if (baselineRevision < 0) {
            throw new IllegalArgumentException("baselineRevision must not be negative");
        }
        String attemptId = UUID.randomUUID().toString();
        TransferSpec specification = new TransferSpec(
                transfer.source(),
                Optional.of(transfer.destination()),
                transfer.items()
        );
        Transaction transaction = new Transaction(specification, baselineRevision);
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
        AttemptToken token = new AttemptToken(
                attempt.attemptId(),
                attempt.baselineRevision()
        );
        Transaction transaction = transactions.get(token.attemptId());
        if (transaction == null) {
            return unavailable(token, "attempt is no longer active");
        }
        return pollTransaction(token, transaction);
    }

    private ContainerAttemptResult pollTransaction(
            AttemptToken token,
            Transaction transaction
    ) {
        requireClientThread();
        AbstractContainerMenu menu = currentContainerMenu();
        if (menu == null) {
            if (transaction.pendingClick != null) {
                removeTransaction(token);
                return unavailable(
                        token,
                        "container closed before click confirmation"
                );
            }
            Optional<ContainerRef> target = transaction.targetFor(transaction.phase);
            if (target.isEmpty()) {
                removeTransaction(token);
                return unavailable(token, "container target is unavailable");
            }
            prepare(target.orElseThrow());
            return pending(token, transaction.baselineRevision, "waiting for container");
        }
        if (pendingOpen != null) {
            openedContainer = pendingOpen;
            pendingOpen = null;
            pendingOpenedAt = null;
        }
        if (transaction.pendingClick != null) {
            ContainerClickConfirmation.Snapshot observed = clickSnapshot(
                    menu,
                    transaction.pendingSlot,
                    transaction.pendingClick.itemId()
            );
            ContainerClickConfirmation.Evaluation evaluation =
                    ContainerClickConfirmation.evaluate(
                    transaction.pendingClick,
                    observed
            );
            if (!evaluation.confirmed()) {
                return rejected(
                        token,
                        transaction,
                        menu,
                        "container click prediction was corrected or inconsistent"
                );
            }
            applyConfirmedClick(transaction, evaluation.moved());
            transaction.pendingClick = null;
        }
        return switch (transaction.phase) {
            case OPEN_SOURCE -> openSource(token, transaction);
            case EXTRACT -> extract(token, transaction, menu);
            case OPEN_DESTINATION -> openDestination(token, transaction);
            case PLACE -> place(token, transaction, menu);
            case RETURN_CURSOR -> returnCursor(token, transaction, menu);
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
                && transaction.references(activeTarget)) {
            closeCurrentContainer();
        }
    }

    private ContainerAttemptResult openSource(
            AttemptToken token,
            Transaction transaction
    ) {
        ContainerRef source = transaction.source.orElseThrow();
        prepare(source);
        AbstractContainerMenu menu = currentContainerMenu();
        if (menu == null || !source.equals(openedContainer)) {
            return pending(token, transaction.baselineRevision, "opening source container");
        }
        if (!transaction.sourceStability.observe(menuSnapshot(menu))) {
            return pending(
                    token,
                    revision(menu),
                    "waiting for source contents to stabilize"
            );
        }
        transaction.sourceView = view(menu, source);
        transaction.phase = Phase.EXTRACT;
        return pending(token, revision(menu), "source container is ready");
    }

    private ContainerAttemptResult extract(
            AttemptToken token,
            Transaction transaction,
            AbstractContainerMenu menu
    ) {
        ContainerRef source = transaction.source.orElseThrow();
        if (!source.equals(openedContainer)) {
            prepare(source);
            return pending(token, revision(menu), "source container is no longer open");
        }
        if (transaction.remaining.isEmpty()) {
            if (transaction.toPlayer()) {
                if (!menu.getCarried().isEmpty()) {
                    return rejected(
                            token,
                            transaction,
                            menu,
                            "source transfer completed with a non-empty cursor"
                    );
                }
                return acknowledged(token, transaction, menu);
            }
            closeCurrentContainer();
            transaction.phase = Phase.OPEN_DESTINATION;
            prepare(transaction.destination.orElseThrow());
            return pending(
                    token,
                    revision(menu),
                    "opening destination container"
            );
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
            if (!ContainerCursorRecovery.canQuickMoveWholeStack(
                    stack.getCount(),
                    wanted
            )) {
                return rejected(
                        token,
                        transaction,
                        menu,
                        "partial source stack extraction is not safely supported"
                );
            }
            ContainerClickConfirmation.Snapshot before = clickSnapshot(
                    menu,
                    slotIndex,
                    itemId
            );
            click(menu, slotIndex, 0, ContainerInput.QUICK_MOVE);
            ContainerClickConfirmation.Snapshot predicted = clickSnapshot(
                    menu,
                    slotIndex,
                    itemId
            );
            transaction.pendingClick = new ContainerClickConfirmation.Pending(
                    ContainerClickConfirmation.ClickType.EXTRACT,
                    itemId,
                    before,
                    predicted
            );
            transaction.pendingSlot = slotIndex;
            return pending(token, revision(menu), "source transfer sent");
        }
        return rejected(
                token,
                transaction,
                menu,
                "requested items are absent from the source"
        );
    }

    private ContainerAttemptResult openDestination(
            AttemptToken token,
            Transaction transaction
    ) {
        ContainerRef destination = transaction.destination.orElseThrow();
        prepare(destination);
        AbstractContainerMenu menu = currentContainerMenu();
        if (menu == null || !destination.equals(openedContainer)) {
            return pending(
                    token,
                    revisionOrBaseline(menu, transaction),
                    "opening destination"
            );
        }
        if (!transaction.destinationStability.observe(menuSnapshot(menu))) {
            return pending(
                    token,
                    revision(menu),
                    "waiting for destination contents to stabilize"
            );
        }
        transaction.destinationView = view(menu, destination);
        transaction.phase = Phase.PLACE;
        return pending(token, revision(menu), "destination container is ready");
    }

    private ContainerAttemptResult place(
            AttemptToken token,
            Transaction transaction,
            AbstractContainerMenu menu
    ) {
        ContainerRef destination = transaction.destination.orElseThrow();
        if (!destination.equals(openedContainer)) {
            prepare(destination);
            return pending(
                    token,
                    revision(menu),
                    "destination container is no longer open"
            );
        }
        MenuSlotClassification slots = slotClassification(menu);
        ItemStack carried = menu.getCarried();
        ContainerCursorRecovery.NextStep nextStep = ContainerCursorRecovery.nextStep(
                !transaction.remaining.isEmpty(),
                stackState(carried)
        );
        if (nextStep == ContainerCursorRecovery.NextStep.ACKNOWLEDGE) {
            return acknowledged(token, transaction, menu);
        }
        if (nextStep == ContainerCursorRecovery.NextStep.RETURN_CURSOR) {
            transaction.phase = Phase.RETURN_CURSOR;
            return pending(
                    token,
                    revision(menu),
                    "returning cursor stack to inventory"
            );
        }
        if (carried.isEmpty()) {
            int quickMoveSlot = findQuickMovePlayerSlot(
                    menu,
                    slots,
                    transaction.remaining
            );
            if (quickMoveSlot >= 0) {
                ItemStack quickMoveStack = menu.getSlot(quickMoveSlot).getItem();
                String quickMoveItem = itemId(quickMoveStack);
                ContainerClickConfirmation.Snapshot before = clickSnapshot(
                        menu,
                        quickMoveSlot,
                        quickMoveItem
                );
                click(menu, quickMoveSlot, 0, ContainerInput.QUICK_MOVE);
                ContainerClickConfirmation.Snapshot predicted = clickSnapshot(
                        menu,
                        quickMoveSlot,
                        quickMoveItem
                );
                transaction.pendingClick = new ContainerClickConfirmation.Pending(
                        ContainerClickConfirmation.ClickType.QUICK_MOVE,
                        quickMoveItem,
                        before,
                        predicted
                );
                transaction.pendingSlot = quickMoveSlot;
                return pending(token, revision(menu), "player quick move sent");
            }
            int picked = pickNextPlayerStack(menu, slots.playerSlots(), transaction.remaining);
            if (picked < 0) {
                return rejected(
                        token,
                        transaction,
                        menu,
                        "player inventory lacks the requested quantity"
                );
            }
            ItemStack pickedStack = menu.getSlot(picked).getItem();
            String pickedItemId = itemId(pickedStack);
            ContainerClickConfirmation.Snapshot before = clickSnapshot(
                    menu,
                    picked,
                    pickedItemId
            );
            click(menu, picked, 0, ContainerInput.PICKUP);
            ContainerClickConfirmation.Snapshot predicted = clickSnapshot(
                    menu,
                    picked,
                    pickedItemId
            );
            transaction.pendingClick = new ContainerClickConfirmation.Pending(
                    ContainerClickConfirmation.ClickType.PICK_UP,
                    pickedItemId,
                    before,
                    predicted
            );
            transaction.pendingSlot = picked;
            transaction.pickupOriginSlot = picked;
            return pending(token, revision(menu), "player stack pickup sent");
        }
        String carriedItem = itemId(carried);
        int wanted = transaction.remaining.getOrDefault(carriedItem, 0);
        if (wanted <= 0) {
            return rejected(
                    token,
                    transaction,
                    menu,
                    "cursor contains an unexpected item"
            );
        }
        int destinationSlot = findDestinationSlot(menu, slots.containerSlots(), carried);
        if (destinationSlot < 0) {
            return rejected(
                    token,
                    transaction,
                    menu,
                    "destination container has no compatible slot"
            );
        }
        int button = carried.getCount() <= wanted ? 0 : 1;
        ContainerClickConfirmation.Snapshot before = clickSnapshot(
                menu,
                destinationSlot,
                carriedItem
        );
        click(menu, destinationSlot, button, ContainerInput.PICKUP);
        ContainerClickConfirmation.Snapshot predicted = clickSnapshot(
                menu,
                destinationSlot,
                carriedItem
        );
        transaction.pendingClick = new ContainerClickConfirmation.Pending(
                ContainerClickConfirmation.ClickType.PLACE,
                carriedItem,
                before,
                predicted
        );
        transaction.pendingSlot = destinationSlot;
        return pending(token, revision(menu), "destination slot click sent");
    }

    private ContainerAttemptResult returnCursor(
            AttemptToken token,
            Transaction transaction,
            AbstractContainerMenu menu
    ) {
        ContainerRef destination = transaction.destination.orElseThrow();
        if (!destination.equals(openedContainer)) {
            return rejected(
                    token,
                    transaction,
                    menu,
                    "destination closed before cursor was returned"
            );
        }
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return acknowledged(token, transaction, menu);
        }
        String carriedItem = itemId(carried);
        int returnSlot = findReturnSlot(
                menu,
                slotClassification(menu).playerSlots(),
                transaction.pickupOriginSlot,
                carried
        );
        if (returnSlot < 0) {
            return rejected(
                    token,
                    transaction,
                    menu,
                    "player inventory has no slot for cursor stack"
            );
        }
        ContainerClickConfirmation.Snapshot before = clickSnapshot(
                menu,
                returnSlot,
                carriedItem
        );
        click(menu, returnSlot, 0, ContainerInput.PICKUP);
        ContainerClickConfirmation.Snapshot predicted = clickSnapshot(
                menu,
                returnSlot,
                carriedItem
        );
        transaction.pendingClick = new ContainerClickConfirmation.Pending(
                ContainerClickConfirmation.ClickType.RETURN_CURSOR,
                carriedItem,
                before,
                predicted
        );
        transaction.pendingSlot = returnSlot;
        return pending(token, revision(menu), "cursor return sent");
    }

    private ContainerAttemptResult acknowledged(
            AttemptToken token,
            Transaction transaction,
            AbstractContainerMenu menu
    ) {
        Optional<ContainerView> sourceAfter = transaction.toPlayer()
                ? transaction.source.map(source -> view(menu, source))
                : Optional.ofNullable(transaction.sourceView);
        Optional<ContainerView> destinationAfter = transaction.destination
                .map(destination -> view(menu, destination));
        removeTransaction(token);
        String detail = transaction.toPlayer()
                ? "source quick move and empty cursor were confirmed by the server"
                : "all requested slots and the cursor were confirmed by the server";
        return new ContainerAttemptResult(
                ContainerAttemptStatus.ACKNOWLEDGED,
                revision(menu),
                sourceAfter,
                destinationAfter,
                detail
        );
    }

    private static void applyConfirmedClick(Transaction transaction, int moved) {
        ContainerClickConfirmation.Pending pending = transaction.pendingClick;
        if (pending.type() == ContainerClickConfirmation.ClickType.EXTRACT
                || pending.type() == ContainerClickConfirmation.ClickType.QUICK_MOVE
                || pending.type() == ContainerClickConfirmation.ClickType.PLACE) {
            decrement(transaction.remaining, pending.itemId(), moved);
        }
    }

    private static ContainerClickConfirmation.Snapshot clickSnapshot(
            AbstractContainerMenu menu,
            int slot,
            String itemId
    ) {
        return new ContainerClickConfirmation.Snapshot(
                menu.containerId,
                stackState(menu.getSlot(slot).getItem()),
                stackState(menu.getCarried()),
                playerItemCount(menu, itemId),
                containerItemCount(menu, itemId)
        );
    }

    private static ContainerMenuStability.Snapshot menuSnapshot(
            AbstractContainerMenu menu
    ) {
        List<ContainerClickConfirmation.StackState> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            slots.add(stackState(slot.getItem()));
        }
        return new ContainerMenuStability.Snapshot(
                menu.containerId,
                slots,
                stackState(menu.getCarried())
        );
    }

    private static ContainerClickConfirmation.StackState stackState(ItemStack stack) {
        return stack.isEmpty()
                ? new ContainerClickConfirmation.StackState("", 0)
                : new ContainerClickConfirmation.StackState(itemId(stack), stack.getCount());
    }

    private static int playerItemCount(AbstractContainerMenu menu, String itemId) {
        int count = 0;
        for (int slot : slotClassification(menu).playerSlots()) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static int containerItemCount(AbstractContainerMenu menu, String itemId) {
        int count = 0;
        for (int slot : slotClassification(menu).containerSlots()) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
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

    private static int findQuickMovePlayerSlot(
            AbstractContainerMenu menu,
            MenuSlotClassification slots,
            Map<String, Integer> remaining
    ) {
        List<ContainerQuickMovePlan.Candidate> candidates = new ArrayList<>();
        for (int slot : slots.playerSlots()) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            candidates.add(new ContainerQuickMovePlan.Candidate(
                    slot,
                    itemId(stack),
                    stack.getCount(),
                    destinationCapacity(menu, slots.containerSlots(), stack)
            ));
        }
        return ContainerQuickMovePlan.selectPlayerSlot(candidates, remaining);
    }

    private static int destinationCapacity(
            AbstractContainerMenu menu,
            List<Integer> containerSlots,
            ItemStack stack
    ) {
        int capacity = 0;
        for (int slot : containerSlots) {
            Slot candidate = menu.getSlot(slot);
            if (!candidate.mayPlace(stack)) {
                continue;
            }
            ItemStack existing = candidate.getItem();
            int maximum = candidate.getMaxStackSize(stack);
            if (existing.isEmpty()) {
                capacity = Math.addExact(capacity, maximum);
            } else if (ItemStack.isSameItemSameComponents(stack, existing)) {
                capacity = Math.addExact(capacity, Math.max(0, maximum - existing.getCount()));
            }
        }
        return capacity;
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

    private static int findReturnSlot(
            AbstractContainerMenu menu,
            List<Integer> playerSlots,
            int originSlot,
            ItemStack carried
    ) {
        List<ContainerCursorRecovery.SlotState> slots = new ArrayList<>();
        for (int slot : playerSlots) {
            Slot candidate = menu.getSlot(slot);
            ItemStack existing = candidate.getItem();
            slots.add(new ContainerCursorRecovery.SlotState(
                    slot,
                    existing.isEmpty() ? "" : itemId(existing),
                    existing.getCount(),
                    candidate.getMaxStackSize(carried),
                    candidate.mayPlace(carried)
            ));
        }
        return ContainerCursorRecovery.selectReturnSlot(
                slots,
                originSlot,
                itemId(carried)
        );
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
            AttemptToken token,
            long revision,
            String detail
    ) {
        return new ContainerAttemptResult(
                ContainerAttemptStatus.PENDING,
                Math.max(token.baselineRevision(), revision),
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
            AttemptToken token,
            Transaction transaction,
            AbstractContainerMenu menu,
            String detail
    ) {
        removeTransaction(token);
        if (transaction.toPlayer()) {
            closeCurrentContainer();
        }
        return new ContainerAttemptResult(
                ContainerAttemptStatus.REJECTED,
                revision(menu),
                Optional.empty(),
                Optional.empty(),
                detail
        );
    }

    private static ContainerAttemptResult unavailable(
            AttemptToken token,
            String detail
    ) {
        return new ContainerAttemptResult(
                ContainerAttemptStatus.UNAVAILABLE,
                token.baselineRevision(),
                Optional.empty(),
                Optional.empty(),
                detail
        );
    }

    private void removeTransaction(AttemptToken token) {
        transactions.remove(token.attemptId());
        operations.remove(token.attemptId());
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
        PLACE,
        RETURN_CURSOR
    }

    record AttemptToken(String attemptId, long baselineRevision) {
        AttemptToken {
            attemptId = requireText(attemptId, "attemptId");
            if (baselineRevision < 0) {
                throw new IllegalArgumentException(
                        "baselineRevision must not be negative"
                );
            }
        }
    }

    record TransferSpec(
            Optional<ContainerRef> source,
            Optional<ContainerRef> destination,
            List<ItemAmount> items
    ) {
        TransferSpec {
            source = Objects.requireNonNull(source, "source");
            destination = Objects.requireNonNull(destination, "destination");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (source.isEmpty() && destination.isEmpty()) {
                throw new IllegalArgumentException(
                        "source or destination must be present"
                );
            }
        }
    }

    record MappedOperation(
            ItemTransferPort.Request request,
            TransferSpec specification,
            String confirmedAction
    ) {
        MappedOperation {
            request = Objects.requireNonNull(request, "request");
            specification = Objects.requireNonNull(
                    specification,
                    "specification"
            );
            confirmedAction = requireText(confirmedAction, "confirmedAction");
        }
    }

    private record ActiveOperation(
            AttemptToken token,
            MappedOperation mapping
    ) {
        private ActiveOperation {
            token = Objects.requireNonNull(token, "token");
            mapping = Objects.requireNonNull(mapping, "mapping");
        }
    }

    interface OperationDriver {
        ContainerAttemptResult start(AttemptToken token, TransferSpec transfer);

        ContainerAttemptResult poll(AttemptToken token);

        void cancel(AttemptToken token);
    }

    private final class TransactionOperationDriver implements OperationDriver {
        @Override
        public ContainerAttemptResult start(
                AttemptToken token,
                TransferSpec transfer
        ) {
            requireClientThread();
            Transaction transaction = new Transaction(
                    transfer,
                    token.baselineRevision()
            );
            transactions.put(token.attemptId(), transaction);
            ContainerRef initialTarget = transfer.source()
                    .or(() -> transfer.destination())
                    .orElseThrow();
            prepare(initialTarget);
            return pending(token, token.baselineRevision(), "operation started");
        }

        @Override
        public ContainerAttemptResult poll(AttemptToken token) {
            requireClientThread();
            Transaction transaction = transactions.get(token.attemptId());
            if (transaction == null) {
                return unavailable(token, "operation attempt is no longer active");
            }
            return pollTransaction(token, transaction);
        }

        @Override
        public void cancel(AttemptToken token) {
            requireClientThread();
            Transaction transaction = transactions.remove(token.attemptId());
            if (transaction == null) {
                return;
            }
            if (openedContainer != null && transaction.references(openedContainer)) {
                closeCurrentContainer();
            }
        }
    }

    private static final class Transaction {
        private final Optional<ContainerRef> source;
        private final Optional<ContainerRef> destination;
        private final long baselineRevision;
        private final Map<String, Integer> remaining;
        private final ContainerMenuStability sourceStability = new ContainerMenuStability();
        private final ContainerMenuStability destinationStability =
                new ContainerMenuStability();
        private Phase phase;
        private ContainerView sourceView;
        private ContainerView destinationView;
        private ContainerClickConfirmation.Pending pendingClick;
        private int pendingSlot = -1;
        private int pickupOriginSlot = -1;

        private Transaction(
                TransferSpec transfer,
                long baselineRevision
        ) {
            this.source = transfer.source();
            this.destination = transfer.destination();
            this.baselineRevision = baselineRevision;
            this.remaining = remaining(transfer.items());
            this.phase = source.isPresent()
                    ? Phase.OPEN_SOURCE
                    : Phase.OPEN_DESTINATION;
        }

        private boolean toPlayer() {
            return source.isPresent() && destination.isEmpty();
        }

        private boolean references(ContainerRef container) {
            return source.filter(container::equals).isPresent()
                    || destination.filter(container::equals).isPresent();
        }

        private Optional<ContainerRef> targetFor(Phase phase) {
            return switch (phase) {
                case OPEN_SOURCE, EXTRACT -> source;
                case OPEN_DESTINATION, PLACE, RETURN_CURSOR -> destination;
            };
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
