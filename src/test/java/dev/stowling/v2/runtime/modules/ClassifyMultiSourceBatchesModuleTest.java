package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.ClassificationLayoutEntry;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PlayerInventorySlot;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.domain.BlockPosition;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassifyMultiSourceBatchesModuleTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final ContainerTarget FIRST = target(0);
    private static final ContainerTarget SECOND = target(1);
    private static final ContainerTarget STONE_BOX = target(10);
    private static final ContainerTarget BACKUP = target(11);
    private static final ContainerTarget DIRT_BOX = target(12);

    @Test
    void fillsFromTwoSourcesBeforeDeliveringTheMixedBatchInLayoutOrder() {
        var fixture = new Fixture(0);
        assertEquals(TaskModuleState.SUCCEEDED, fixture.finish().state());
        assertEquals(List.of("take:0", "take:1", "store:10", "store:12"), fixture.events);
        assertEquals(Map.of(STONE, 64, DIRT, 64), fixture.firstDelivery);
        assertTrue(fixture.workspace.managedItems().isEmpty());
    }

    @Test
    void preservesPackingAndRecoverySpaceBeforeVisitingTheNextSource() {
        var fixture = new Fixture(33);
        assertEquals(TaskModuleState.SUCCEEDED, fixture.finish().state());
        assertEquals(List.of("take:0", "store:10", "take:1", "store:12"), fixture.events);
        assertEquals(33, fixture.physical.get("minecraft:wooden_pickaxe").intValue());
    }

    @Test
    void fullFirstBoxUsesTheNextSameItemBoxThenContinuesOtherItems() {
        var fixture = new Fixture(0);
        fixture.full.add(STONE_BOX);
        assertEquals(TaskModuleState.SUCCEEDED, fixture.finish().state());
        assertEquals(List.of("take:0", "take:1", "store:10", "store:11", "store:12"),
                fixture.events);
        assertTrue(fixture.workspace.classificationSummary().contains("未完成"));
    }

    @Test
    void failedOpeningDoesNotDiscardTheBatchOrPreventOtherDeliveries() {
        var fixture = new Fixture(0);
        fixture.failed.add(STONE_BOX);
        assertEquals(TaskModuleState.SUCCEEDED, fixture.finish().state());
        assertTrue(fixture.events.contains("store:11"));
        assertTrue(fixture.events.contains("store:12"));
        assertTrue(fixture.workspace.classificationSummary().contains("opening failed"));
        assertTrue(fixture.workspace.managedItems().isEmpty());
    }

    @Test
    void unreachableBoxIsSkippedWithoutReorderingTheRemainingTargets() {
        var fixture = new Fixture(0);
        fixture.unreachable.add(STONE_BOX.position());
        assertEquals(TaskModuleState.SUCCEEDED, fixture.finish().state());
        assertEquals(List.of("take:0", "take:1", "store:11", "store:12"), fixture.events);
        assertTrue(fixture.workspace.classificationSummary().contains("无法前往"));
    }

    @Test
    void returnsRejectedItemsOnceAndDoesNotWithdrawThemAgainInThisZone() {
        var fixture = new Fixture(0);
        fixture.failed.addAll(List.of(STONE_BOX, BACKUP));
        assertEquals(TaskModuleState.SUCCEEDED, fixture.finish().state());
        assertEquals(1, fixture.events.stream().filter("take:0"::equals).count());
        assertTrue(fixture.events.contains("store:0"));
        assertTrue(fixture.events.contains("store:12"));
        assertTrue(fixture.workspace.managedItems().isEmpty());
    }

    @Test
    void stopWaitsForConfirmedWithdrawalAndLeavesTheCargoForTaskCleanup() {
        var fixture = new Fixture(0);
        fixture.delayedWithdrawal = true;
        fixture.module.start();
        fixture.module.tick();
        assertFalse(fixture.module.safeToStop());
        assertTrue(fixture.workspace.carriedItems().isEmpty());
        fixture.module.requestStop();
        assertEquals(TaskModuleState.SUCCEEDED, fixture.module.tick().state());
        assertTrue(fixture.module.safeToStop());
        assertEquals(Map.of(STONE, 64), fixture.workspace.carriedItems());
        assertFalse(fixture.events.contains("take:1"));
        assertFalse(fixture.events.contains("store:10"));
    }

    @Test
    void disablingTheOptInDoesNotStartAnySourceOrStorageOperation() {
        var fixture = new Fixture(0);
        fixture.workspace.configureClassificationBaseline(false);
        assertEquals(TaskModuleState.FAILED, fixture.module.start().state());
        assertTrue(fixture.events.isEmpty());
        assertTrue(fixture.workspace.sameCategoryBoxBatching());
    }

    @Test
    void confirmedFullRemaindersStayCarriedWhileOtherDeliveriesFinish() {
        var fixture = new Fixture(0, true);
        fixture.full.addAll(List.of(STONE_BOX, BACKUP));
        assertEquals(TaskModuleState.SUCCEEDED, fixture.finish().state());
        assertEquals(List.of("take:0", "take:1", "store:10", "store:11", "store:12"),
                fixture.events);
        assertFalse(fixture.workspace.hasCarriedItems());
        assertEquals(Map.of(STONE, 64), fixture.workspace.managedItems());
        fixture.workspace.endClassificationDelivery();
        assertEquals(Map.of(STONE, 64), fixture.workspace.carriedItems());
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }

    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected call: " + method.getName());
                }));
    }

    private static final class Fixture implements ContainerWithdrawalPort, ContainerTransferPort,
            NavigationPort, StorageAutomationPort {
        private final TaskRunWorkspace workspace = new TaskRunWorkspace();
        private final List<String> events = new ArrayList<>();
        private final List<ContainerTarget> failed = new ArrayList<>();
        private final List<ContainerTarget> full = new ArrayList<>();
        private final List<BlockPosition> unreachable = new ArrayList<>();
        private final Map<String, Integer> physical = new LinkedHashMap<>();
        private final Map<ContainerTarget, Map<String, Integer>> stock = new LinkedHashMap<>();
        private final ClassifyMultiSourceBatchesModule module;
        private Map<String, Integer> firstDelivery;
        private boolean delayedWithdrawal;
        private ContainerTarget pendingTarget;

        private Fixture(int tools) {
            this(tools, false);
        }

        private Fixture(int tools, boolean handoff) {
            workspace.configureClassificationVisits(handoff, false, false);
            if (tools > 0) physical.put("minecraft:wooden_pickaxe", tools);
            stock.put(FIRST, new LinkedHashMap<>(Map.of(STONE, 64)));
            stock.put(SECOND, new LinkedHashMap<>(Map.of(DIRT, 64)));
            workspace.configureClassificationBaseline(true);
            workspace.setResolvedCategories(List.of(STONE_BOX, BACKUP, DIRT_BOX),
                    Map.of(STONE_BOX, Set.of(STONE), BACKUP, Set.of(STONE),
                            DIRT_BOX, Set.of(DIRT)), List.of(FIRST, SECOND),
                    List.of(new ClassificationLayoutEntry(STONE, List.of(STONE_BOX, BACKUP)),
                            new ClassificationLayoutEntry(DIRT, List.of(DIRT_BOX))));
            Map<BlockPosition, Map<String, Integer>> contents = new LinkedHashMap<>();
            stock.forEach((key, value) -> contents.put(key.position(), Map.copyOf(value)));
            workspace.recordCacheInventory(contents);
            var inventory = new PlayerInventoryManager(() -> Optional.of(snapshot()));
            inventory.initialize(snapshot());
            var context = new TaskExecutionContext(this, unused(DroppedItemObserverPort.class),
                    unused(ContainerRegionPort.class), this, this,
                    dev.stowling.automation.ContainerInspectionPort.UNAVAILABLE,
                    dev.stowling.automation.CategoryMarkerCachePort.UNAVAILABLE,
                    dev.stowling.automation.FoodConsumptionPort.UNAVAILABLE,
                    unused(CategoryZonePort.class), Map.of(), Map.of(), Map.of(), workspace,
                    inventory, this);
            module = new ClassifyMultiSourceBatchesModule(context);
        }

        private PlayerInventorySnapshot snapshot() {
            List<PlayerInventorySlot> slots = new ArrayList<>();
            physical.forEach((key, count) -> {
                int stacks = key.equals("minecraft:wooden_pickaxe") ? count : 1;
                for (int index = 0; index < stacks; index++) {
                    slots.add(new PlayerInventorySlot(slots.size(), key, "", count / stacks,
                            stacks > 1 ? 1 : 64, List.of(), 0, 0));
                }
            });
            while (slots.size() < 36) slots.add(PlayerInventorySlot.empty(slots.size()));
            return new PlayerInventorySnapshot(physical, Map.of(), Map.of(), slots);
        }

        private TaskModuleResult finish() {
            TaskModuleResult result = module.start();
            for (int tick = 0; tick < 200 && result.state() != TaskModuleState.SUCCEEDED
                    && result.state() != TaskModuleState.FAILED; tick++) result = module.tick();
            return result;
        }

        @Override
        public ContainerWithdrawalResult startWithdrawal(ContainerTarget target, Set<String> items) {
            events.add("take:" + target.position().x());
            pendingTarget = target;
            return delayedWithdrawal ? ContainerWithdrawalResult.pending("server pending")
                    : pollWithdrawal();
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            Map<String, Integer> moved = Map.copyOf(stock.get(pendingTarget));
            stock.get(pendingTarget).clear();
            moved.forEach((key, count) -> physical.merge(key, count, Math::addExact));
            return ContainerWithdrawalResult.succeeded("confirmed", moved, false);
        }

        @Override
        public void cancelWithdrawal() {
            throw new AssertionError("In-flight withdrawal must be confirmed before stopping");
        }

        @Override
        public ContainerTransferResult startDeposit(ContainerTarget target,
                Map<String, Integer> items) {
            events.add("store:" + target.position().x());
            if (firstDelivery == null) firstDelivery = workspace.carriedItems();
            if (failed.contains(target)) {
                return ContainerTransferResult.failed("opening failed", items);
            }
            if (full.contains(target)) return ContainerTransferResult.succeeded("full", items);
            items.forEach((key, count) -> {
                int remaining = physical.getOrDefault(key, 0) - count;
                if (remaining == 0) physical.remove(key);
                else physical.put(key, remaining);
            });
            return ContainerTransferResult.succeeded("confirmed", Map.of());
        }

        @Override
        public TaskModuleExecutor cleanup(TaskExecutionContext context) {
            throw new AssertionError("Unexpected cleanup");
        }

        @Override
        public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                Map<String, Integer> items) {
            return new TaskModuleExecutor() {
                @Override
                public TaskModuleResult start() {
                    var result = startDeposit(target, items);
                    if (full.contains(target)) {
                        workspace.recordConfirmedFullStorage(target,
                                workspace.acceptedItemsByContainer().get(target));
                    }
                    workspace.consumeDeposit("carried", items, result.remaining());
                    return TaskModuleResult.succeeded(result.message());
                }

                @Override
                public TaskModuleResult tick() {
                    return start();
                }

                @Override
                public void cancel() {
                }
            };
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            throw new AssertionError("Deposits are confirmed synchronously in this fixture");
        }

        @Override
        public void cancelDeposit() {
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            return unreachable.contains(target) ? NavigationStatus.FAILED : NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return start(target);
        }

        @Override
        public boolean isWithinInteractionRange(BlockPosition target) {
            return !unreachable.contains(target);
        }

        @Override
        public void cancel() {
        }
    }
}
