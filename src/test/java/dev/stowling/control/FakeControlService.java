package dev.stowling.control;

import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.config.ConfigRevisionConflictException;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeControlService implements StowlingControlService {
    public static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID AUTOMATION_TASK_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");

    private final Map<UUID, TaskInfo> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean failStatus;
    private volatile boolean activePickup;
    private volatile boolean activeAutomation;
    private volatile String automationRevision = "automation-revision-1";
    private volatile List<AutomationWorkflow> automationWorkflows =
            List.of(automationWorkflow());
    private volatile String coordinateRevision = "revision-1";
    private volatile ContainerPosition coordinatePosition = new ContainerPosition(1, -44, 4);
    private volatile List<ConfigCoordinatePointInfo> coordinatePoints = List.of(
            coordinatePoint("north-position", "North position", 1, -44, 4),
            coordinatePoint("north-cache-position", "North cache position", 2, -44, 4)
    );

    public FakeControlService() {
        tasks.put(TASK_ID, task(TASK_ID, "RUNNING"));
    }

    public int calls() {
        return calls.get();
    }

    public void setFailStatus(boolean failStatus) {
        this.failStatus = failStatus;
    }

    @Override
    public ControlStatus status() {
        calls.incrementAndGet();
        if (failStatus) {
            throw new IllegalStateException("sensitive-account-data");
        }
        return new ControlStatus(paused.get(), paused.get() ? "PAUSED" : "RUNNING", NOW);
    }

    @Override
    public List<TaskInfo> tasks() {
        calls.incrementAndGet();
        return List.copyOf(tasks.values());
    }

    @Override
    public List<PickupSiteInfo> pickupSites() {
        calls.incrementAndGet();
        return List.of(new PickupSiteInfo(
                "north",
                "minecraft:overworld",
                new ContainerPosition(1, -44, 4),
                16,
                60
        ));
    }

    @Override
    public List<PickupWorkflowInfo> pickupWorkflows() {
        calls.incrementAndGet();
        return List.of(new PickupWorkflowInfo(
                "north",
                "North",
                "minecraft:overworld",
                List.of(new PickupWorkflowRegionInfo("north", "North", 100, 60))
        ));
    }

    @Override
    public TaskInfo requestPickup(PickupRequest request) {
        calls.incrementAndGet();
        if (!"north".equals(request.workflowId())) {
            throw new PickupWorkflowNotFoundException();
        }
        if (request.regionId().isPresent()
                && !"north".equals(request.regionId().orElseThrow())) {
            throw new InvalidPickupRequestException("Region does not belong to workflow");
        }
        if (activePickup) {
            throw new ActiveTaskConflictException();
        }
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TaskInfo task = new TaskInfo(
                id,
                "PICKUP",
                "PENDING",
                "PICKING_UP",
                NOW,
                NOW,
                null,
                request.workflowId(),
                request.regionId().orElse(null),
                0L,
                "NAVIGATE_PICKUP"
        );
        tasks.put(id, task);
        activePickup = true;
        return task;
    }

    @Override
    public TaskInfo cancelTask(UUID taskId) {
        calls.incrementAndGet();
        TaskInfo existing = tasks.get(taskId);
        if (existing == null) {
            throw new TaskNotFoundException();
        }
        TaskInfo cancelled = task(taskId, "CANCELLED");
        tasks.put(taskId, cancelled);
        activePickup = false;
        activeAutomation = false;
        return cancelled;
    }

    @Override
    public AuditResult requestAudit(AuditRequest request) {
        calls.incrementAndGet();
        if (request.containerIds().stream().anyMatch(id -> !"overflow".equals(id))) {
            throw new ContainerNotFoundException();
        }
        return new AuditResult(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                NOW
        );
    }

    @Override
    public List<InventoryInfo> inventory() {
        calls.incrementAndGet();
        return List.of(new InventoryInfo(
                "live",
                "overflow",
                "minecraft:overworld",
                NOW,
                "FRESH",
                List.of(new InventoryItemInfo("minecraft:stone", 64, "LOOSE"))
        ));
    }

    @Override
    public List<ContainerInfo> containers() {
        calls.incrementAndGet();
        return List.of(new ContainerInfo(
                "overflow",
                "minecraft:overworld",
                new ContainerPosition(1, 64, 2),
                "CHEST"
        ));
    }

    @Override
    public List<AutomationModuleInfo> automationModules() {
        calls.incrementAndGet();
        return java.util.Arrays.stream(AutomationStepType.values())
                .map(type -> AutomationModuleInfo.from(
                        new AutomationModuleDescriptor(type, type.name())
                ))
                .toList();
    }

    @Override
    public TaskInfo requestAutomation(AutomationTaskRequest request) {
        calls.incrementAndGet();
        if (automationWorkflows.stream().noneMatch(
                workflow -> workflow.id().equals(request.workflowId())
        )) {
            throw new AutomationWorkflowNotFoundException();
        }
        TaskInfo task = new TaskInfo(
                AUTOMATION_TASK_ID,
                "AUTOMATION",
                "PENDING",
                "IDLE_WAITING",
                NOW,
                NOW,
                null,
                request.workflowId(),
                null,
                null,
                "READY",
                "wait",
                0,
                1,
                "{}",
                null
        );
        tasks.put(AUTOMATION_TASK_ID, task);
        activeAutomation = true;
        return task;
    }

    @Override
    public AutomationWorkflowsSnapshot automationWorkflows() {
        calls.incrementAndGet();
        return new AutomationWorkflowsSnapshot(automationRevision, automationWorkflows);
    }

    @Override
    public AutomationWorkflowsSnapshot updateAutomationWorkflows(
            AutomationWorkflowUpdateRequest request
    ) {
        calls.incrementAndGet();
        if (activePickup || activeAutomation) {
            throw new ActiveTaskConflictException();
        }
        if (!automationRevision.equals(request.revision())) {
            throw new ConfigRevisionConflictException();
        }
        automationWorkflows = request.automationWorkflows();
        automationRevision = "automation-revision-2";
        return new AutomationWorkflowsSnapshot(automationRevision, automationWorkflows);
    }

    @Override
    public ControlStatus pause() {
        calls.incrementAndGet();
        paused.set(true);
        return new ControlStatus(true, "PAUSED", NOW);
    }

    @Override
    public ControlStatus resume() {
        calls.incrementAndGet();
        paused.set(false);
        return new ControlStatus(false, "RUNNING", NOW);
    }

    @Override
    public ConfigReloadResult reloadConfig() {
        calls.incrementAndGet();
        return new ConfigReloadResult(true, NOW);
    }

    @Override
    public ConfigCoordinatePointsSnapshot coordinatePoints() {
        calls.incrementAndGet();
        return new ConfigCoordinatePointsSnapshot(coordinateRevision, coordinatePoints);
    }

    @Override
    public ConfigCoordinatePointsSnapshot updateCoordinatePoints(
            ConfigCoordinatePointsUpdateRequest request
    ) {
        calls.incrementAndGet();
        if (activePickup) {
            throw new ActiveTaskConflictException();
        }
        if (!coordinateRevision.equals(request.revision())) {
            throw new ConfigRevisionConflictException();
        }
        coordinatePoints = request.coordinatePoints();
        coordinateRevision = "revision-2";
        return coordinatePoints();
    }

    @Override
    public ConfigCoordinatesSnapshot configCoordinates() {
        calls.incrementAndGet();
        return coordinateSnapshot();
    }

    @Override
    public ConfigCoordinatesSnapshot updateConfigCoordinates(
            ConfigCoordinatesUpdateRequest request
    ) {
        calls.incrementAndGet();
        if (activePickup) {
            throw new ActiveTaskConflictException();
        }
        if (!coordinateRevision.equals(request.revision())) {
            throw new ConfigRevisionConflictException();
        }
        if (request.coordinates().isEmpty()) {
            throw new InvalidConfigCoordinatesException("Coordinate keys are missing");
        }
        if (request.coordinates().size() > 1) {
            throw new InvalidConfigCoordinatesException("Duplicate coordinate key");
        }
        ConfigCoordinateUpdate update = request.coordinates().getFirst();
        if (!"container/overflow".equals(update.key())) {
            throw new InvalidConfigCoordinatesException("Unknown coordinate key");
        }
        coordinatePosition = new ContainerPosition(update.x(), update.y(), update.z());
        coordinateRevision = "revision-2";
        return coordinateSnapshot();
    }

    @Override
    public PickupConfigSnapshot pickupConfig() {
        calls.incrementAndGet();
        return pickupSnapshot();
    }

    @Override
    public PickupConfigSnapshot updatePickupConfig(
            PickupConfigUpdateRequest request
    ) {
        calls.incrementAndGet();
        if (activePickup) {
            throw new ActiveTaskConflictException();
        }
        if (!coordinateRevision.equals(request.revision())) {
            throw new ConfigRevisionConflictException();
        }
        coordinateRevision = "revision-2";
        return new PickupConfigSnapshot(
                coordinateRevision,
                request.pickupRegions(),
                request.cacheContainers(),
                request.pickupWorkflows()
        );
    }

    private PickupConfigSnapshot pickupSnapshot() {
        PickupRegion region = new PickupRegion(
                "north",
                "North",
                "minecraft:overworld",
                "north-position",
                new BlockPosition(1, -44, 4),
                new SpherePickupShape("north-position", new BlockPosition(1, -44, 4), 16)
        );
        NamedCache cache = new NamedCache(
                "north-cache",
                "North cache",
                "north-cache-position",
                "minecraft:overworld",
                new BlockPosition(2, -44, 4),
                ContainerType.CHEST
        );
        PickupWorkflow workflow = new PickupWorkflow(
                "north",
                "North",
                "minecraft:overworld",
                List.of(new PickupRegionBinding("north", 100, Duration.ofSeconds(60))),
                List.of("north-cache"),
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.MANUAL,
                Duration.ofSeconds(300)
        );
        return new PickupConfigSnapshot(
                coordinateRevision,
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );
    }

    private ConfigCoordinatesSnapshot coordinateSnapshot() {
        return new ConfigCoordinatesSnapshot(
                coordinateRevision,
                List.of(new ConfigCoordinateInfo(
                        "container/overflow",
                        "CONTAINER",
                        "overflow",
                        "Overflow container",
                        "minecraft:overworld",
                        coordinatePosition
                ))
        );
    }

    private static TaskInfo task(UUID id, String status) {
        return new TaskInfo(
                id,
                "PICKUP",
                status,
                "PICKING_UP",
                NOW,
                NOW,
                null
        );
    }

    private static AutomationWorkflow automationWorkflow() {
        return new AutomationWorkflow(
                "daily",
                "Daily automation",
                List.of(new AutomationStep(
                        "wait",
                        AutomationStepType.WAIT,
                        Map.of("seconds", "1")
                ))
        );
    }

    private static ConfigCoordinatePointInfo coordinatePoint(
            String id,
            String name,
            int x,
            int y,
            int z
    ) {
        return new ConfigCoordinatePointInfo(
                id,
                name,
                "minecraft:overworld",
                new ContainerPosition(x, y, z)
        );
    }
}
