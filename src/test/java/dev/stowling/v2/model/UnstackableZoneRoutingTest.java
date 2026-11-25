package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.config.V2ValidationException;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnstackableZoneRoutingTest {
    @TempDir
    Path directory;

    @Test
    void persistsZoneDestinationAndTaskOptInWithoutChangingTheDefaultFlow() throws Exception {
        var overflow = new RegionDefinition(UUID.randomUUID(), "Overflow", 20, 64, 0,
                21, 64, 0);
        var source = new RegionDefinition(UUID.randomUUID(), "Source", 30, 64, 0,
                31, 64, 0);
        var options = new Gson().fromJson("{\"scopeMode\":\"selection\","
                + "\"recognitionMode\":\"unstackable\",\"unstackableOverflowRef\":\"region:"
                + overflow.id() + "\"}", CategoryZoneOptions.class);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(0, 64, 0, 0, 64, 0),
                new AreaBounds(0, 60, 0, 0, 60, 0), "UNSTACKABLE_ITEMS");
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Armor", List.of(binding),
                null, options);
        var baseline = definition(source, zone, null);
        var enabled = definition(source, zone, true);
        var baselineSteps = AutoClassifyTaskFactory.tasks(baseline, List.of()).getFirst().steps();
        var enabledTasks = AutoClassifyTaskFactory.tasks(enabled, List.of());
        assertFalse(baseline.unstackableZoneRoutingEnabled());
        assertFalse(baselineSteps.stream().anyMatch(step ->
                UnstackableZoneRouting.MODULE.equals(step.moduleId())));
        assertEquals(UnstackableZoneRouting.MODULE,
                enabledTasks.getFirst().steps().get(2).moduleId());
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), enabledTasks,
                List.of(source, overflow), List.of(zone), List.of(), List.of(), List.of(enabled));
        ConfigValidator.requireValid(config);
        Path file = directory.resolve("config.json");
        Files.writeString(file, new Gson().toJson(config));
        var store = new ConfigStore(file);
        var snapshot = store.read();
        store.save(snapshot.revision(), snapshot.config());
        var restored = new ConfigStore(file).read().config();
        assertEquals(options, restored.categoryZones().getFirst().categoryOptions());
        assertTrue(restored.autoClassifyTasks().getFirst().unstackableZoneRoutingEnabled());
        assertThrows(V2ValidationException.class, () -> ConfigValidator.requireValid(
                new AppConfig(config.schemaVersion(), List.of(), enabledTasks,
                        List.of(source), List.of(zone), List.of(), List.of(), List.of(enabled))));
    }

    @Test
    void onlyUnmatchedOrConfirmedFullTargetsAreEligible() {
        var first = new ContainerTarget(new BlockPosition(0, 64, 0), "minecraft:chest");
        var second = new ContainerTarget(new BlockPosition(1, 64, 0), "minecraft:chest");
        assertTrue(UnstackableZoneRouting.eligible(List.of(), target -> false));
        assertTrue(UnstackableZoneRouting.eligible(List.of(first, second), target -> true));
        assertFalse(UnstackableZoneRouting.eligible(List.of(first, second), first::equals));
        assertFalse(UnstackableZoneRouting.eligible(List.of(first), target -> false));
    }

    @Test
    void isolatesExecutionRulesOnlyWhenEnabledAndKeepsTheSavedDraft() {
        var global = new Gson().fromJson("{\"recognitionMode\":\"unstackable\"}",
                CategoryZoneOptions.class);
        var local = new Gson().fromJson("{\"recognitionMode\":\"container-item\","
                + "\"ignoreSupportBlock\":true,\"supportBlock\":\"minecraft:stone\","
                + "\"multiItemBindingsEnabled\":true}", CategoryZoneOptions.class);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(0, 64, 0, 0, 64, 0), new AreaBounds(0, 60, 0, 0, 60, 0),
                "MARKER_CONTAINER_CONTENTS", List.of("minecraft:stone"), List.of(),
                null, StoragePolicy.DEFAULT, false, "Armor", true, local);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Dedicated", List.of(binding),
                null, global);
        assertEquals(zone, UnstackableZoneRouting.forExecution(zone, false));
        var execution = UnstackableZoneRouting.forExecution(zone, true);
        assertEquals("UNSTACKABLE_ITEMS", execution.bindings().getFirst().ruleId());
        assertFalse(execution.bindings().getFirst().categoryOptions().multiItemBindingsEnabled());
        assertTrue(execution.bindings().getFirst().ignoredMarkerBlockIds().isEmpty());
        assertEquals(local, zone.bindings().getFirst().categoryOptions());
        var ordinary = new CategoryZoneDefinition(UUID.randomUUID(), "Ordinary", List.of(binding));
        assertEquals(ordinary, UnstackableZoneRouting.forExecution(ordinary, true));
    }

    @Test
    void zoneOwnershipAndCurrentTargetsStayLocalToOneRun() {
        String armor = "minecraft:diamond_chestplate";
        var target = new ContainerTarget(new BlockPosition(0, 64, 0), "minecraft:chest");
        var source = new ContainerTarget(new BlockPosition(10, 64, 0), "minecraft:chest");
        var workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(target),
                Map.of(target, java.util.Set.of("=" + armor)), List.of(source));
        assertEquals(java.util.Set.of("=" + armor), workspace.remainingCacheCategories(source));
        workspace.recordManagedAcquired(armor, 1);
        workspace.claimUnstackableRemainders(java.util.Set.of(armor));
        assertTrue(workspace.zoneOwnsUnstackable(armor));
        assertTrue(workspace.activeUnstackableRemainder(armor));
        assertEquals(List.of(target), workspace.currentZoneStorageTargets(armor));
        assertTrue(workspace.remainingCacheCategories(source).isEmpty());
        assertEquals(1, workspace.managedItems().get(armor));
        workspace.claimUnstackableRemainders(java.util.Set.of(armor));
        assertFalse(workspace.activeUnstackableRemainder(armor));
        assertFalse(new TaskRunWorkspace().zoneOwnsUnstackable(armor));
    }

    private static AutoClassifyDefinition definition(RegionDefinition source,
            CategoryZoneDefinition zone, Boolean enabled) {
        return new AutoClassifyDefinition(UUID.randomUUID(), "Sort", false, false, true,
                null, null, "region:" + source.id(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + zone.id())),
                TaskPriority.NORMAL, false, null, null, null, null,
                AutoClassifyDefinition.Kind.CLASSIFICATION, null, null, null, null,
                null, null, enabled);
    }

}
