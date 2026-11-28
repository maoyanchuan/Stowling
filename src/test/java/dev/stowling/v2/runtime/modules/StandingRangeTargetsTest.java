package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.TaskStep;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StandingRangeTargetsTest {
    @Test
    void resolutionScopesBaselineToIndependentRunsAndLeavesTheOriginalResultUntouched() {
        var area = new AreaBounds(220, -24, -51, 220, -24, -51);
        var target = new ContainerTarget(new BlockPosition(216, -23, -51), "minecraft:chest", area);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), area, area, "DIRECT_BLOCK_ID");
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Zone", List.of(binding));
        var resolved = CategoryZoneResult.success(Map.of("minecraft:stripped_spruce_log",
                List.of(target)));
        for (boolean independent : List.of(false, true)) {
            var workspace = new TaskRunWorkspace();
            workspace.configureClassificationTask(independent);
            var context = new TaskExecutionContext(unused(NavigationPort.class),
                    unused(DroppedItemObserverPort.class),
                    unused(dev.stowling.automation.ContainerRegionPort.class),
                    unused(dev.stowling.automation.ContainerTransferPort.class),
                    unused(dev.stowling.automation.ContainerWithdrawalPort.class),
                    (CategoryZonePort) Proxy.newProxyInstance(CategoryZonePort.class
                            .getClassLoader(), new Class<?>[] {CategoryZonePort.class},
                            (proxy, method, args) -> resolved),
                    Map.of(), Map.of(), Map.of(zone.id(), zone), workspace);
            var step = TaskStep.action(UUID.randomUUID(), "RESOLVE_CONTAINER_CATEGORIES",
                    Map.of("categoryZoneRef", "category-zone:" + zone.id(),
                            "allowEmptyContainerScan", "true"));
            assertEquals(TaskModuleState.SUCCEEDED,
                    new ResolveContainerCategoriesModule(context, step).start().state());
            assertEquals(independent, workspace.acceptedItemsByContainer()
                    .keySet().iterator().next().classificationStanding());
            assertFalse(target.classificationStanding());
        }
    }

    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new AssertionError("Unexpected call: " + method.getName());
                }));
    }
}
