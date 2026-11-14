package dev.stowling.client.automation;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.automation.MaterialAcquisitionPort;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventoryItem;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.StoredInventorySnapshot;
import dev.stowling.schedule.StopOutcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SqliteMaterialAcquisitionPort implements MaterialAcquisitionPort {
    private static final String MANUAL_RECOVERY =
            "未找到当前进程中的原料获取操作；请人工确认库存后重新创建任务。";
    private static final Comparator<StoredInventorySnapshot> LATEST_FIRST =
            Comparator.comparing(StoredInventorySnapshot::observedAt)
            .reversed()
            .thenComparing(snapshot -> snapshot.snapshot().containerId());

    private final SqliteInventoryRepository inventories;
    private final ItemTransferPort transfers;
    private final Map<String, String> transferKeys = new LinkedHashMap<>();
    private final Map<UUID, StopOutcome> stopOutcomes = new LinkedHashMap<>();

    public SqliteMaterialAcquisitionPort(
            SqliteInventoryRepository inventories,
            ItemTransferPort transfers
    ) {
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
    }

    @Override
    public synchronized AutomationOperationResult start(
            String operationKey,
            Request request
    ) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(request, "request");
        if (transferKeys.containsKey(operationKey)) {
            return poll(operationKey);
        }
        StoredInventorySnapshot selected = selectContainer(request);
        if (selected == null) {
            return AutomationOperationResult.failed(
                    "没有新鲜库存快照可确认所需原料: " + request.itemId()
            );
        }
        String transferKey = operationKey + ":material";
        ItemTransferPort.Request transfer = new ItemTransferPort.Request(
                ItemTransferPort.Mode.TAKE,
                selected.snapshot().containerId(),
                "PLAYER",
                request.itemId(),
                request.count()
        );
        AutomationOperationResult result = Objects.requireNonNull(
                transfers.start(transferKey, transfer),
                "material transfer result"
        );
        UUID operationTaskId = taskId(operationKey);
        if (operationTaskId != null) {
            stopOutcomes.remove(operationTaskId);
        }
        retain(operationKey, transferKey, result);
        return result;
    }

    @Override
    public synchronized AutomationOperationResult poll(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        String transferKey = transferKeys.get(operationKey);
        if (transferKey == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        AutomationOperationResult result = Objects.requireNonNull(
                transfers.poll(transferKey),
                "material transfer poll result"
        );
        retain(operationKey, transferKey, result);
        return result;
    }

    @Override
    public synchronized AutomationOperationResult reconcile(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        String transferKey = transferKeys.get(operationKey);
        if (transferKey == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        AutomationOperationResult result = Objects.requireNonNull(
                transfers.reconcile(transferKey),
                "material transfer reconcile result"
        );
        retain(operationKey, transferKey, result);
        return result;
    }

    @Override
    public synchronized StopOutcome requestStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        List<String> keys = matchingKeys(attemptId);
        if (keys.isEmpty()) {
            return stopOutcomes.getOrDefault(attemptId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = transfers.requestStop(attemptId);
        if (outcome == StopOutcome.CLEAN || outcome == StopOutcome.UNSAFE) {
            keys.forEach(transferKeys::remove);
        }
        return retainStopOutcome(attemptId, outcome);
    }

    @Override
    public synchronized StopOutcome pollStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        List<String> keys = matchingKeys(attemptId);
        if (keys.isEmpty()) {
            return stopOutcomes.getOrDefault(attemptId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = transfers.pollStop(attemptId);
        if (outcome == StopOutcome.CLEAN || outcome == StopOutcome.UNSAFE) {
            keys.forEach(transferKeys::remove);
        }
        return retainStopOutcome(attemptId, outcome);
    }

    private StoredInventorySnapshot selectContainer(Request request) {
        return inventories.findAllSnapshots().stream()
                .filter(snapshot ->
                        snapshot.snapshot().freshness() == InventoryFreshness.FRESH
                )
                .filter(snapshot ->
                        count(snapshot.snapshot().items(), request.itemId())
                                >= request.count()
                )
                .sorted(LATEST_FIRST)
                .findFirst()
                .orElse(null);
    }

    private static int count(List<InventoryItem> items, String itemId) {
        int count = 0;
        for (InventoryItem item : items) {
            if (item.itemId().equals(itemId)) {
                count = Math.addExact(count, item.count());
            }
        }
        return count;
    }

    private void retain(
            String operationKey,
            String transferKey,
            AutomationOperationResult result
    ) {
        switch (result.status()) {
            case RUNNING, PAUSED, FAILED ->
                    transferKeys.put(operationKey, transferKey);
            case SUCCEEDED -> transferKeys.remove(operationKey);
        }
    }

    private List<String> matchingKeys(UUID taskId) {
        List<String> result = new ArrayList<>();
        for (String key : transferKeys.keySet()) {
            if (taskId.equals(taskId(key))) {
                result.add(key);
            }
        }
        return result;
    }

    private static UUID taskId(String operationKey) {
        int separator = operationKey.indexOf(':');
        String candidate = separator < 0
                ? operationKey
                : operationKey.substring(0, separator);
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private StopOutcome retainStopOutcome(UUID taskId, StopOutcome outcome) {
        if (outcome != StopOutcome.PENDING) {
            stopOutcomes.put(taskId, outcome);
            if (stopOutcomes.size() > 128) {
                UUID oldest = stopOutcomes.keySet().iterator().next();
                stopOutcomes.remove(oldest);
            }
        }
        return outcome;
    }
}
