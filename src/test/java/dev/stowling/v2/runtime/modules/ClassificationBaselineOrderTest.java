package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ClassificationLayoutEntry;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationBaselineOrderTest {
    @Test
    void fixedOrderIgnoresCapacityAndDistancePreferencesOnlyWhenEnabled() {
        var workspace = new TaskRunWorkspace();
        var source = target(0);
        var far = target(100);
        var near = target(1);
        workspace.setResolvedCategories(List.of(far, near),
                Map.of(far, Set.of("minecraft:stone"), near, Set.of("minecraft:stone")),
                List.of(source), List.of(new ClassificationLayoutEntry("minecraft:stone",
                        List.of(far, near))));
        workspace.selectClassificationSource(source);
        workspace.recordWithdrawn("minecraft:stone", 64);
        workspace.configureClassificationBaseline(true);
        assertEquals(far, workspace.selectStorageTarget("carried", false,
                (first, second) -> Integer.compare(first.position().x(), second.position().x()))
                .orElseThrow());
        workspace.resetStorageSelection();
        workspace.configureClassificationBaseline(false);
        assertEquals(near, workspace.selectStorageTarget("carried", false,
                (first, second) -> Integer.compare(first.position().x(), second.position().x()))
                .orElseThrow());
    }

    @Test
    void sameCategoryDraftSurvivesTheEffectiveMixedBatchOverride() {
        var workspace = new TaskRunWorkspace();
        workspace.configureSameCategoryBoxBatching(true);
        workspace.configureClassificationBaseline(true);
        assertFalse(workspace.sameCategoryBoxBatching());
        workspace.configureClassificationBaseline(false);
        assertTrue(workspace.sameCategoryBoxBatching());
        workspace.configureSameCategoryBoxBatching(false);
        workspace.configureClassificationBaseline(true);
        workspace.configureClassificationBaseline(false);
        assertFalse(workspace.sameCategoryBoxBatching());
    }

    @Test
    void unreadBlockedSourceIsNotTreatedAsEmptyOrAppliedToTheEstablishedFlow() {
        var workspace = new TaskRunWorkspace();
        var source = target(0);
        workspace.configureClassificationBaseline(true);
        workspace.recordClassificationSourceIssue(source, "unread source");
        assertTrue(workspace.cacheContents(source).isEmpty());
        assertFalse(workspace.hasRecordedClassifiableStock(source, Set.of("minecraft:stone")));
        workspace.configureClassificationBaseline(false);
        assertTrue(workspace.hasRecordedClassifiableStock(source, Set.of("minecraft:stone")));
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }
}
