package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationVisitPolicyTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final ContainerTarget SOURCE = target(0);
    private static final ContainerTarget FIRST = target(10);
    private static final ContainerTarget SECOND = target(11);
    private static final ContainerTarget OTHER = target(12);

    @Test
    void continuousAccessRequiresItsOwnSwitchAndNeverLeaksToOtherTasksOrRuns() {
        for (boolean classification : List.of(false, true)) {
            for (boolean enabled : List.of(false, true)) {
                var workspace = workspace(classification);
                assertFalse(workspace.continuousContainerAccessEnabled());
                workspace.configureContinuousContainerAccess(enabled);
                assertEquals(classification && enabled,
                        workspace.continuousContainerAccessEnabled());
                assertFalse(workspace(classification).continuousContainerAccessEnabled());
                workspace.configureContinuousContainerAccess(false);
                assertFalse(workspace.continuousContainerAccessEnabled());
            }
        }
    }

    @Test
    void fullCargoWaitsWhileOtherItemsAreDeliveredWithoutLosingOwnership() {
        var workspace = workspace(true);
        workspace.recordWithdrawn(STONE, 64);
        workspace.recordWithdrawn(DIRT, 32);
        workspace.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        workspace.recordConfirmedFullStorage(SECOND, Set.of(STONE));
        workspace.markTargetUnavailable(FIRST);
        workspace.markTargetUnavailable(SECOND);
        assertEquals(Map.of(DIRT, 32), workspace.carriedItems());
        assertEquals(OTHER, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.consumeDeposit("carried", Map.of(DIRT, 32), Map.of());
        assertFalse(workspace.hasCarriedItems());
        assertTrue(workspace.hasManagedItems());
        assertEquals(Map.of(STONE, 64), workspace.managedItems());
        assertFalse(workspace.cacheHasMore());
        workspace.endClassificationDelivery();
        assertEquals(Map.of(STONE, 64), workspace.carriedItems());
    }

    @Test
    void stillTriesAnotherSameItemTargetAndDoesNotTreatNavigationFailureAsFull() {
        var workspace = workspace(true);
        workspace.recordWithdrawn(STONE, 64);
        workspace.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        workspace.markTargetUnavailable(FIRST);
        assertEquals(SECOND, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.markTargetUnavailable(SECOND, "navigation failed");
        assertEquals(Map.of(STONE, 64), workspace.carriedItems());
        assertEquals(SOURCE, workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void laterZoneCanAcceptRetainedCargoAndASeparateRunCannotInheritFullResults() {
        var workspace = workspace(true);
        workspace.recordWithdrawn(STONE, 64);
        workspace.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        workspace.recordConfirmedFullStorage(SECOND, Set.of(STONE));
        assertFalse(workspace.hasCarriedItems());
        var later = target(20);
        workspace.setResolvedCategories(List.of(later), Map.of(later, Set.of(STONE)),
                List.of(SOURCE));
        assertEquals(Map.of(STONE, 64), workspace.carriedItems());
        assertEquals(later, workspace.selectStorageTarget("carried", false).orElseThrow());
        var nextRun = workspace(true);
        nextRun.recordWithdrawn(STONE, 64);
        assertTrue(nextRun.hasCarriedItems());
    }

    @Test
    void ordinaryTasksKeepTheirOriginalReturnToSourcePath() {
        var workspace = workspace(false);
        workspace.recordWithdrawn(STONE, 64);
        workspace.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        workspace.recordConfirmedFullStorage(SECOND, Set.of(STONE));
        workspace.markTargetUnavailable(FIRST);
        workspace.markTargetUnavailable(SECOND);
        assertTrue(workspace.hasCarriedItems());
        assertEquals(SOURCE, workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void auxiliaryStockCannotTriggerItsOwnDemandAndOnlyRequestedCategoriesRemain() {
        var workspace = new TaskRunWorkspace();
        workspace.configureClassificationTask(true);
        var auxiliary = target(2);
        workspace.recordCacheContents(SOURCE, Map.of());
        workspace.recordCacheContents(auxiliary, Map.of(STONE, 64, DIRT, 64));
        assertTrue(workspace.classificationDemand(List.of(SOURCE), Set.of(STONE, DIRT)).isEmpty());
        workspace.setClassificationDemand(Set.of());
        workspace.setResolvedCategories(List.of(FIRST), Map.of(FIRST, Set.of(STONE, DIRT)),
                List.of(auxiliary));
        assertFalse(workspace.hasRecordedClassifiableStock(auxiliary, Set.of(STONE, DIRT)));
        workspace.recordCacheContents(SOURCE, Map.of(STONE, 32));
        var demand = workspace.classificationDemand(List.of(SOURCE), Set.of(STONE, DIRT));
        assertEquals(Set.of(STONE), demand);
        workspace.setClassificationDemand(demand);
        assertEquals(Set.of(STONE), workspace.remainingCacheCategories(auxiliary));
    }

    @Test
    void mixedBoxContentsAndCarriedCargoTriggerTheirActualCategories() {
        var workspace = new TaskRunWorkspace();
        workspace.configureClassificationTask(true);
        String mixed = "minecraft:shulker_box#mixed@stowling:mixed_contents";
        workspace.recordClassificationBoxContents(mixed, Set.of(STONE, "minecraft:oak_log"));
        workspace.recordCacheContents(SOURCE, Map.of(mixed, 1, "minecraft:shulker_box", 9));
        workspace.recordWithdrawn(DIRT, 16);
        assertEquals(Set.of(STONE, DIRT),
                workspace.classificationDemand(List.of(SOURCE), Set.of(STONE, DIRT)));
    }

    @Test
    void onlyIndependentClassificationUsesTheMergedBehavior() {
        var classification = workspace(true);
        assertTrue(classification.classificationTask());
        var ordinary = workspace(false);
        assertFalse(ordinary.classificationTask());
        ordinary.recordWithdrawn(STONE, 64);
        ordinary.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        ordinary.recordConfirmedFullStorage(SECOND, Set.of(STONE));
        assertEquals(Map.of(STONE, 64), ordinary.carriedItems());
    }

    @Test
    void realDeliveryModulesFinishOtherCargoAndDoNotFailOnTheLastFullRemainder() {
        for (boolean otherCargo : List.of(false, true)) {
            var workspace = workspace(true);
            workspace.recordWithdrawn(STONE, 64);
            if (otherCargo) workspace.recordWithdrawn(DIRT, 32);
            var visits = new java.util.ArrayList<ContainerTarget>();
            var storage = new StorageAutomationPort() {
                @Override
                public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                    throw new AssertionError("Normal delivery must not enter cleanup");
                }

                @Override
                public TaskModuleExecutor deposit(TaskExecutionContext context,
                        ContainerTarget target, Map<String, Integer> items) {
                    visits.add(target);
                    TaskModuleResult result;
                    if (target.equals(FIRST) || target.equals(SECOND)) {
                        workspace.recordConfirmedFullStorage(target, Set.of(STONE));
                        result = TaskModuleResult.failed("confirmed full");
                    } else {
                        workspace.consumeDeposit("carried", items, Map.of());
                        result = TaskModuleResult.succeeded("confirmed delivery");
                    }
                    var completed = result;
                    return new TaskModuleExecutor() {
                        @Override
                        public TaskModuleResult start() {
                            return completed;
                        }

                        @Override
                        public TaskModuleResult tick() {
                            return completed;
                        }

                        @Override
                        public void cancel() {
                        }
                    };
                }
            };
            var inventory = new PlayerInventoryManager(() -> java.util.Optional.of(
                    new dev.stowling.automation.PlayerInventorySnapshot(workspace.managedItems())));
            inventory.initialize(new dev.stowling.automation.PlayerInventorySnapshot(Map.of()));
            var context = new TaskExecutionContext(
                    unused(dev.stowling.automation.NavigationPort.class),
                    unused(dev.stowling.automation.DroppedItemObserverPort.class),
                    unused(dev.stowling.automation.ContainerRegionPort.class),
                    unused(dev.stowling.automation.ContainerTransferPort.class),
                    unused(dev.stowling.automation.ContainerWithdrawalPort.class),
                    dev.stowling.automation.ContainerInspectionPort.UNAVAILABLE,
                    dev.stowling.automation.CategoryMarkerCachePort.UNAVAILABLE,
                    dev.stowling.automation.FoodConsumptionPort.UNAVAILABLE,
                    unused(dev.stowling.automation.CategoryZonePort.class),
                    Map.of(), Map.of(), Map.of(), workspace, inventory, storage);
            int limit = 4;
            while (workspace.hasCarriedItems() && limit-- > 0) {
                var select = dev.stowling.v2.model.TaskStep.action(java.util.UUID.randomUUID(),
                        "SELECT_STORAGE_TARGET", Map.of("allowCacheFallback", "true"));
                assertEquals(TaskModuleState.SUCCEEDED,
                        new SelectStorageTargetModule(context, select).start().state());
                var deposit = dev.stowling.v2.model.TaskStep.action(java.util.UUID.randomUUID(),
                        "DEPOSIT_ITEMS_TO_CONTAINERS", Map.of("targetRef", "output:storage",
                                "itemSource", "carried", "retryOnFailure", "true"));
                assertEquals(TaskModuleState.SUCCEEDED,
                        new DepositItemsActionModule(context, deposit).start().state());
            }
            assertEquals(otherCargo ? 3 : 2, visits.size());
            assertEquals(FIRST, visits.getFirst());
            assertTrue(visits.contains(SECOND));
            assertEquals(otherCargo, visits.contains(OTHER));
            assertFalse(visits.contains(SOURCE));
            assertFalse(workspace.hasCarriedItems());
            assertEquals(Map.of(STONE, 64), workspace.managedItems());
            workspace.endClassificationDelivery();
            assertEquals(Map.of(STONE, 64), workspace.carriedItems());
        }
    }

    private static <T> T unused(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[] {type}, (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected call: " + method.getName());
                }));
    }

    private static TaskRunWorkspace workspace(boolean handoff) {
        var workspace = new TaskRunWorkspace();
        workspace.configureClassificationTask(handoff);
        workspace.setResolvedCategories(List.of(FIRST, SECOND, OTHER),
                Map.of(FIRST, Set.of(STONE), SECOND, Set.of(STONE), OTHER, Set.of(DIRT)),
                List.of(SOURCE));
        workspace.setClassificationDemand(Set.of(STONE, DIRT));
        workspace.selectNextContainer().orElseThrow();
        workspace.setCacheHasMore(true);
        workspace.recordCacheContents(SOURCE, Map.of());
        return workspace;
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }
}
