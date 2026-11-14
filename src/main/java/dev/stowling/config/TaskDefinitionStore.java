package dev.stowling.config;

import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.schedule.TaskDefinition;
import dev.stowling.schedule.TaskKey;
import dev.stowling.schedule.VersionedTaskDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TaskDefinitionStore {
    private final ConfigFileStore fileStore;

    public TaskDefinitionStore(ConfigFileStore fileStore) {
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
    }

    public List<TaskDefinition> list() {
        synchronized (fileStore) {
            return definitions(fileStore.read().config());
        }
    }

    public VersionedTaskDefinition get(TaskKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (fileStore) {
            return findVersioned(fileStore.read().config(), key);
        }
    }

    public VersionedTaskDefinition create(TaskDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        synchronized (fileStore) {
            ConfigFileSnapshot snapshot = fileStore.read();
            if (contains(snapshot.config(), definition.key())) {
                throw new IllegalArgumentException(
                        "Task definition already exists: " + definition.key()
                );
            }
            StowlingConfig updated = add(snapshot.config(), definition);
            ConfigFileSnapshot saved = fileStore.save(snapshot.revision(), updated);
            return findVersioned(saved.config(), definition.key());
        }
    }

    public VersionedTaskDefinition update(
            TaskKey key,
            String revision,
            TaskDefinition definition
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(definition, "definition");
        if (!key.equals(definition.key())) {
            throw new IllegalArgumentException("Updated task identity must match the key");
        }
        synchronized (fileStore) {
            ConfigFileSnapshot snapshot = fileStore.read();
            VersionedTaskDefinition current = findVersioned(snapshot.config(), key);
            if (!current.revision().equals(revision)) {
                throw new ConfigRevisionConflictException();
            }
            StowlingConfig updated = replace(snapshot.config(), definition);
            ConfigFileSnapshot saved = fileStore.save(snapshot.revision(), updated);
            return findVersioned(saved.config(), key);
        }
    }

    public void delete(TaskKey key, String revision) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(revision, "revision");
        synchronized (fileStore) {
            ConfigFileSnapshot snapshot = fileStore.read();
            VersionedTaskDefinition current = findVersioned(snapshot.config(), key);
            if (!current.revision().equals(revision)) {
                throw new ConfigRevisionConflictException();
            }
            StowlingConfig updated = remove(snapshot.config(), key);
            fileStore.save(snapshot.revision(), updated);
        }
    }

    private VersionedTaskDefinition findVersioned(
            StowlingConfig config,
            TaskKey key
    ) {
        for (TaskDefinition definition : definitions(config)) {
            if (key.equals(definition.key())) {
                return new VersionedTaskDefinition(
                        fileStore.taskRevision(definition),
                        definition
                );
            }
        }
        throw new TaskDefinitionNotFoundException(key);
    }

    private static boolean contains(StowlingConfig config, TaskKey key) {
        return definitions(config).stream().anyMatch(definition -> key.equals(definition.key()));
    }

    private static List<TaskDefinition> definitions(StowlingConfig config) {
        List<TaskDefinition> definitions = new ArrayList<>();
        config.pickupWorkflows().stream()
                .map(TaskDefinition::from)
                .forEach(definitions::add);
        config.automationWorkflows().stream()
                .map(TaskDefinition::from)
                .forEach(definitions::add);
        return List.copyOf(definitions);
    }

    private static StowlingConfig add(
            StowlingConfig config,
            TaskDefinition definition
    ) {
        List<PickupWorkflow> pickups = new ArrayList<>(config.pickupWorkflows());
        List<AutomationWorkflow> automations = new ArrayList<>(
                config.automationWorkflows()
        );
        if (TaskKey.PICKUP.equals(definition.kind())) {
            pickups.add(definition.toPickup());
        } else {
            automations.add(definition.toAutomation());
        }
        return copy(config, pickups, automations);
    }

    private static StowlingConfig replace(
            StowlingConfig config,
            TaskDefinition definition
    ) {
        List<PickupWorkflow> pickups = new ArrayList<>(config.pickupWorkflows());
        List<AutomationWorkflow> automations = new ArrayList<>(
                config.automationWorkflows()
        );
        if (TaskKey.PICKUP.equals(definition.kind())) {
            int index = pickupIndex(pickups, definition.id());
            pickups.set(index, definition.toPickup());
        } else {
            int index = automationIndex(automations, definition.id());
            automations.set(index, definition.toAutomation());
        }
        return copy(config, pickups, automations);
    }

    private static StowlingConfig remove(StowlingConfig config, TaskKey key) {
        List<PickupWorkflow> pickups = new ArrayList<>(config.pickupWorkflows());
        List<AutomationWorkflow> automations = new ArrayList<>(
                config.automationWorkflows()
        );
        if (TaskKey.PICKUP.equals(key.kind())) {
            pickups.remove(pickupIndex(pickups, key.id()));
        } else {
            automations.remove(automationIndex(automations, key.id()));
        }
        return copy(config, pickups, automations);
    }

    private static StowlingConfig copy(
            StowlingConfig config,
            List<PickupWorkflow> pickups,
            List<AutomationWorkflow> automations
    ) {
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                config.coordinatePoints(),
                config.pickupRegions(),
                config.cacheContainers(),
                pickups,
                automations,
                config.storageRoutes(),
                config.packingStations(),
                config.auditSchedules(),
                config.overflowContainer()
        );
    }

    private static int pickupIndex(List<PickupWorkflow> workflows, String id) {
        for (int index = 0; index < workflows.size(); index++) {
            if (workflows.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Pickup task disappeared during update: " + id);
    }

    private static int automationIndex(
            List<AutomationWorkflow> workflows,
            String id
    ) {
        for (int index = 0; index < workflows.size(); index++) {
            if (workflows.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Automation task disappeared during update: " + id);
    }
}
