package dev.stowling.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeControlService implements StowlingControlService {
    public static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");

    private final Map<UUID, TaskInfo> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean failStatus;

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
    public TaskInfo requestPickup(PickupRequest request) {
        calls.incrementAndGet();
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TaskInfo task = task(id, "PENDING");
        tasks.put(id, task);
        return task;
    }

    @Override
    public TaskInfo cancelTask(UUID taskId) {
        calls.incrementAndGet();
        TaskInfo existing = tasks.get(taskId);
        if (existing == null) {
            throw new NoSuchElementException();
        }
        TaskInfo cancelled = task(taskId, "CANCELLED");
        tasks.put(taskId, cancelled);
        return cancelled;
    }

    @Override
    public AuditResult requestAudit(AuditRequest request) {
        calls.incrementAndGet();
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
}
